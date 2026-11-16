package dev.ccassistant.provider;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.ccassistant.core.AiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProviderHttpTest {
    private static final String KEY = "sk-secret-123456";
    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/ok/chat/completions", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "{\"model\":\"m\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"hello\"}}]}");
        });
        server.createContext("/denied/chat/completions", ex -> respond(ex, 401,
            "{\"error\":{\"message\":\"Invalid key " + KEY + " see https://provider.example/docs\"}}"));
        server.createContext("/redirect/chat/completions", ex -> {
            ex.getResponseHeaders().add("Location", "https://evil.example/steal");
            respond(ex, 307, "");
        });
        server.createContext("/huge/chat/completions", ex -> respond(ex, 200, "\"" + "x".repeat(5000) + "\""));
        server.createContext("/slow/chat/completions", ex -> {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            respond(ex, 200, "{}");
        });
        server.createContext("/models", ex -> respond(ex, 200, "{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private ProviderProfile profile(String path) {
        return new ProviderProfile("i", "local", Preset.CUSTOM_OPENAI, ProtocolKind.OPENAI, base + path, "m", 100, null,
            false, false, Map.of(), ProviderProfile.KeyMode.STORED, "", true).validated();
    }

    private static final com.google.gson.JsonObject REQ =
        JsonParser.parseString("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").getAsJsonObject();

    private static AiException failure(Pending<?> p) {
        var e = assertThrows(ExecutionException.class, () -> p.result().get(30, TimeUnit.SECONDS));
        return ProviderClient.unwrap(e);
    }

    @Test
    void successfulChat() throws Exception {
        var out = ProviderClient.chat(profile("/ok"), KEY, REQ, 50, Duration.ofSeconds(10), 1 << 20).result().get(30, TimeUnit.SECONDS);
        assertEquals("hello", out.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("Bearer " + KEY, lastAuth.get());
        assertTrue(lastBody.get().contains("\"max_tokens\":50"));
    }

    @Test
    void errorsAreScrubbed() {
        var e = failure(ProviderClient.chat(profile("/denied"), KEY, REQ, 50, Duration.ofSeconds(10), 1 << 20));
        assertEquals(AiException.PROVIDER_ERROR, e.code());
        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertTrue(e.getMessage().contains("check the API key"), e.getMessage());
        assertFalse(e.getMessage().contains(KEY), e.getMessage());
        assertFalse(e.getMessage().contains("provider.example"), e.getMessage());
        assertFalse(e.getMessage().contains("127.0.0.1"), e.getMessage());
    }

    @Test
    void redirectsAreNotFollowed() {
        var e = failure(ProviderClient.chat(profile("/redirect"), KEY, REQ, 50, Duration.ofSeconds(10), 1 << 20));
        assertEquals(AiException.PROVIDER_ERROR, e.code());
        assertTrue(e.getMessage().contains("redirect"), e.getMessage());
        assertFalse(e.getMessage().contains("evil"), e.getMessage());
    }

    @Test
    void oversizedResponsesAreRejected() {
        var e = failure(ProviderClient.chat(profile("/huge"), KEY, REQ, 50, Duration.ofSeconds(10), 1000));
        assertEquals(AiException.BAD_RESPONSE, e.code());
    }

    @Test
    void timeout() {
        var e = failure(ProviderClient.chat(profile("/slow"), KEY, REQ, 50, Duration.ofSeconds(1), 1 << 20));
        assertEquals(AiException.TIMEOUT, e.code());
    }

    @Test
    void cancelEndsTheCallAtOnce() {
        var p = ProviderClient.chat(profile("/slow"), KEY, REQ, 50, Duration.ofSeconds(20), 1 << 20);
        p.cancel();
        assertEquals(AiException.CANCELLED, failure(p).code());
    }

    @Test
    void plainHttpToRemoteHostsIsRefused() {
        var bad = new ProviderProfile("i", "x", Preset.CUSTOM_OPENAI, ProtocolKind.OPENAI, "https://example.invalid", "m", 10, null,
            false, false, Map.of(), ProviderProfile.KeyMode.NONE, "", true);
        var spec = new HttpSpec("http://example.invalid/chat/completions", Map.of(), REQ);
        var call = ProviderHttp.send(spec, Duration.ofSeconds(5), 1000, KEY, OpenAiProtocol.INSTANCE);
        var e = ProviderClient.unwrap(assertThrows(ExecutionException.class, () -> call.result().get(10, TimeUnit.SECONDS)));
        assertEquals(AiException.NOT_CONFIGURED, e.code());
        assertNotNull(bad);
    }

    @Test
    void modelList() throws Exception {
        var models = ProviderClient.models(profile(""), KEY, Duration.ofSeconds(10)).result().get(30, TimeUnit.SECONDS);
        assertEquals(2, models.size());
    }
}
