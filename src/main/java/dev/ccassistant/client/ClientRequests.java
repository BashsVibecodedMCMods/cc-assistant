package dev.ccassistant.client;

import com.google.gson.JsonObject;
import dev.ccassistant.core.AiException;
import dev.ccassistant.core.RequestValidator;
import dev.ccassistant.provider.Pending;
import dev.ccassistant.provider.ProviderClient;
import dev.ccassistant.provider.ProviderHttp;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Serves requests the server forwards from the player's linked computers, using the player's own profiles
 * (docs/CLIENT_UI.md section 4). Treats the server as untrusted: opt-in, own limits, own validation, own model.
 */
public final class ClientRequests {
    public record Settings(BooleanSupplier enabled, IntSupplier perMinute, IntSupplier perHour, IntSupplier maxRequestBytes) {
    }

    /** Per-profile counts for this session, shown in the UI. */
    public static final class Counter {
        public int requests;
        public long inputTokens;
        public long outputTokens;
    }

    private final Supplier<ProfileStore> store;
    private final Settings settings;
    private final Consumer<JsonObject> toServer;
    private final Consumer<String> notifier;
    private final LongSupplier clock;
    private final Map<Integer, Pending<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> times = new ArrayDeque<>();
    private final Map<String, Counter> counters = new LinkedHashMap<>();
    private volatile @Nullable Boolean lastSentReady;

    public ClientRequests(Supplier<ProfileStore> store, Settings settings, Consumer<JsonObject> toServer, Consumer<String> notifier,
                          LongSupplier clock) {
        this.store = store;
        this.settings = settings;
        this.toServer = toServer;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Whether this client can serve requests now: switched on, with a default profile that has a usable key. */
    public boolean ready() {
        if (!settings.enabled().getAsBoolean()) return false;
        var s = store.get();
        var p = s.defaultProfile();
        return p != null && s.hasUsableKey(p);
    }

    /** Tells the server the one-bit readiness status if it changed (or always, when {@code force}). */
    public void sendStatus(boolean force) {
        var ready = ready();
        if (!force && Boolean.valueOf(ready).equals(lastSentReady)) return;
        lastSentReady = ready;
        var msg = new JsonObject();
        msg.addProperty("t", "status");
        msg.addProperty("ready", ready);
        toServer.accept(msg);
    }

    public void forgetStatus() {
        lastSentReady = null;
    }

    public void onMessage(JsonObject msg) {
        var type = msg.has("t") && msg.get("t").isJsonPrimitive() ? msg.get("t").getAsString() : "";
        if (!msg.has("id") || !msg.get("id").isJsonPrimitive()) return;
        var id = msg.get("id").getAsInt();
        if (type.equals("cancel")) {
            var p = pending.remove(id);
            if (p != null) p.cancel();
        } else if (type.equals("req")) {
            try {
                start(id, msg);
            } catch (AiException e) {
                reply(id, e);
            }
        }
    }

    private void start(int id, JsonObject msg) throws AiException {
        if (!settings.enabled().getAsBoolean()) throw new AiException(AiException.OWNER_DISABLED, "AI for computers is switched off");
        var body = msg.get("body");
        var max = settings.maxRequestBytes().getAsInt();
        if (body == null || body.toString().getBytes(StandardCharsets.UTF_8).length > max) {
            throw new AiException(AiException.TOO_LARGE, "The request is larger than the owner's limit of " + max + " bytes");
        }
        var request = RequestValidator.validate(body, max);
        var s = store.get();
        var named = request.has("profile") ? request.get("profile").getAsString() : null;
        var profile = named != null ? s.byName(named) : s.defaultProfile();
        if (profile == null) {
            throw named != null ? new AiException(AiException.BAD_PROFILE, "no profile named '" + named + "'")
                : new AiException(AiException.OWNER_DISABLED, "no default provider profile is set");
        }
        if (named != null && !profile.luaSelectable()) {
            throw new AiException(AiException.BAD_PROFILE, "profile '" + named + "' can't be chosen by computers");
        }
        var key = s.key(profile);
        if (!s.hasUsableKey(profile)) throw new AiException(AiException.OWNER_DISABLED, "profile '" + profile.name() + "' has no API key");
        takeRateSlot();

        var serverCap = msg.has("maxTokens") ? msg.get("maxTokens").getAsInt() : profile.maxTokens();
        var maxTokens = Math.max(1, Math.min(serverCap, request.has("max_tokens")
            ? (int) Math.min(Integer.MAX_VALUE, request.get("max_tokens").getAsLong()) : profile.maxTokens()));
        maxTokens = Math.min(maxTokens, profile.maxTokens());
        var timeoutSeconds = msg.has("timeout") ? Math.min(600, Math.max(5, msg.get("timeout").getAsLong())) : 180;
        request.remove("profile");
        request.remove("max_tokens");

        var computer = msg.has("computer") ? msg.get("computer").getAsInt() : -1;
        var size = body.toString().length();
        notifier.accept("AI request from computer #" + computer + " via " + profile.name() + ", " + formatBytes(size));

        var call = ProviderClient.chat(profile, key, request, maxTokens, Duration.ofSeconds(timeoutSeconds), 4 * 1024 * 1024);
        pending.put(id, call);
        final var profileName = profile.name();
        call.result().whenComplete((response, error) -> {
            if (pending.remove(id) == null && error != null) return; // cancelled by the server: nothing to send
            if (error != null) {
                var e = ProviderClient.unwrap(error);
                reply(id, new AiException(e.code(), ProviderHttp.scrub(e.getMessage(), key)));
                return;
            }
            count(profileName, response);
            var out = new JsonObject();
            out.addProperty("t", "res");
            out.addProperty("id", id);
            out.addProperty("ok", true);
            out.addProperty("profile", profileName);
            out.add("body", response);
            toServer.accept(out);
        });
    }

    private synchronized void takeRateSlot() throws AiException {
        var now = clock.getAsLong();
        while (!times.isEmpty() && times.peekFirst() <= now - 3_600_000) times.removeFirst();
        var lastMinute = times.stream().filter(t -> t > now - 60_000).count();
        if (lastMinute >= settings.perMinute().getAsInt() || times.size() >= settings.perHour().getAsInt()) {
            throw new AiException(AiException.OWNER_DISABLED, "the owner's own request limit was reached; try again later");
        }
        times.addLast(now);
    }

    private synchronized void count(String profile, JsonObject response) {
        var c = counters.computeIfAbsent(profile, k -> new Counter());
        c.requests++;
        var usage = response.getAsJsonObject("usage");
        if (usage != null) {
            if (usage.has("input_tokens")) c.inputTokens += usage.get("input_tokens").getAsLong();
            if (usage.has("output_tokens")) c.outputTokens += usage.get("output_tokens").getAsLong();
        }
    }

    public synchronized Map<String, Counter> counters() {
        return new LinkedHashMap<>(counters);
    }

    private void reply(int id, AiException e) {
        var out = new JsonObject();
        out.addProperty("t", "res");
        out.addProperty("id", id);
        out.addProperty("ok", false);
        out.addProperty("code", e.code());
        out.addProperty("msg", e.getMessage());
        toServer.accept(out);
    }

    /** Kill switch / disconnect: abort everything in progress. */
    public void cancelAll() {
        for (var id : new ArrayList<>(pending.keySet())) {
            var p = pending.remove(id);
            if (p != null) p.cancel();
        }
    }

    public int inProgress() {
        return pending.size();
    }

    static String formatBytes(long n) {
        return n < 1024 ? n + " B" : String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0);
    }
}
