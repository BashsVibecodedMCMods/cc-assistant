package dev.ccassistant.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.ccassistant.core.AiException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Calls a provider. Redirects are never followed (a key must not be passed on to another host), only https or loopback
 * http is allowed, responses are size-capped, and nothing that can carry the key or URL ends up in an error message.
 */
public final class ProviderHttp {
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|wss?)://\\S+");
    private static final int MAX_ERROR_CHARS = 300;
    private static final ExecutorService READERS = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "ccassistant-http");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .proxy(java.net.ProxySelector.getDefault()) // honours the JVM's proxy settings, if the player set any
        .executor(READERS)
        .build();

    private ProviderHttp() {
    }

    /** A request in progress. Cancelling aborts the HTTP exchange. */
    public static final class Call {
        private final CompletableFuture<JsonElement> result = new CompletableFuture<>();
        private volatile CompletableFuture<?> exchange;
        private final AtomicReference<InputStream> body = new AtomicReference<>();

        public CompletableFuture<JsonElement> result() {
            return result;
        }

        public void cancel() {
            result.completeExceptionally(new AiException(AiException.CANCELLED, "Cancelled"));
            var ex = exchange;
            if (ex != null) ex.cancel(true);
            var in = body.getAndSet(null);
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Sends {@code spec}. The future completes with the parsed JSON body of a 2xx response, or exceptionally with an
     * {@link AiException} whose message is safe to show anywhere. {@code secret} is scrubbed from messages too.
     */
    public static Call send(HttpSpec spec, Duration timeout, int maxBytes, String secret, Protocol protocol) {
        var call = new Call();
        HttpRequest request;
        try {
            UrlRules.checkBaseUrl(spec.url().replaceFirst("\\?.*$", ""));
            var builder = HttpRequest.newBuilder(URI.create(spec.url())).timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "CCTerminalAssistant");
            spec.headers().forEach(builder::header);
            if (spec.body() != null) {
                builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(spec.body().toString(), StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            request = builder.build();
        } catch (IllegalArgumentException e) {
            call.result.completeExceptionally(new AiException(AiException.NOT_CONFIGURED, "Provider settings are invalid: " + scrub(e.getMessage(), secret)));
            return call;
        }

        var exchange = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        call.exchange = exchange;
        exchange.whenComplete((response, error) -> {
            if (error != null) {
                call.result.completeExceptionally(mapError(error, secret));
                return;
            }
            try (var in = response.body()) {
                call.body.set(in);
                if (call.result.isDone()) return;
                var bytes = readCapped(in, maxBytes);
                var text = new String(bytes, StandardCharsets.UTF_8);
                var status = response.statusCode();
                JsonElement json = null;
                try {
                    json = JsonParser.parseString(text);
                } catch (RuntimeException ignored) {
                }
                if (status >= 200 && status < 300) {
                    if (json == null) throw new AiException(AiException.BAD_RESPONSE, "The provider's reply wasn't JSON");
                    call.result.complete(json);
                } else if (status >= 300 && status < 400) {
                    throw new AiException(AiException.PROVIDER_ERROR, "The provider answered with a redirect (" + status
                        + "), which isn't followed. Check the base URL.");
                } else {
                    var detail = json != null ? protocol.errorMessage(json) : null;
                    if (detail == null) detail = text.isBlank() ? null : text;
                    throw new AiException(AiException.PROVIDER_ERROR, "The provider returned " + status + statusHint(status)
                        + (detail == null ? "" : ": " + scrub(detail, secret)));
                }
            } catch (AiException e) {
                call.result.completeExceptionally(e);
            } catch (IOException e) {
                call.result.completeExceptionally(call.result.isDone() ? e
                    : new AiException(AiException.NETWORK_ERROR, "The connection to the provider broke off"));
            } catch (RuntimeException e) {
                call.result.completeExceptionally(new AiException(AiException.BAD_RESPONSE, "The provider's reply couldn't be read"));
            } finally {
                call.body.set(null);
            }
        });
        return call;
    }

    private static String statusHint(int status) {
        return switch (status) {
            case 401, 403 -> " (check the API key)";
            case 404 -> " (check the base URL and model)";
            case 429 -> " (rate limited or out of credit)";
            default -> "";
        };
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws IOException, AiException {
        var bytes = in.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new AiException(AiException.BAD_RESPONSE, "The provider's reply was larger than " + maxBytes + " bytes");
        }
        return bytes;
    }

    private static Throwable mapError(Throwable error, String secret) {
        var e = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (e instanceof AiException) return e;
        if (e instanceof CancellationException) return new AiException(AiException.CANCELLED, "Cancelled");
        if (e instanceof HttpConnectTimeoutException) {
            return new AiException(AiException.NETWORK_ERROR, "Timed out connecting to the provider");
        }
        if (e instanceof HttpTimeoutException) return new AiException(AiException.TIMEOUT, "The provider didn't answer in time");
        if (e instanceof java.net.ConnectException || e instanceof java.nio.channels.UnresolvedAddressException
            || e instanceof java.net.UnknownHostException) {
            return new AiException(AiException.NETWORK_ERROR, "Couldn't connect to the provider");
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return new AiException(AiException.NETWORK_ERROR, "Secure connection to the provider failed");
        }
        return new AiException(AiException.NETWORK_ERROR, "Couldn't reach the provider (" + e.getClass().getSimpleName() + ")");
    }

    /** Removes URLs and the secret, flattens whitespace, and shortens. */
    public static String scrub(String text, String secret) {
        if (text == null) return "";
        var s = text;
        if (secret != null && secret.length() >= 4) s = s.replace(secret, "***");
        s = URL.matcher(s).replaceAll("<url>");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > MAX_ERROR_CHARS) s = s.substring(0, MAX_ERROR_CHARS) + "...";
        return s;
    }
}
