package dev.ccassistant.server;

import com.google.gson.JsonObject;
import dev.ccassistant.CCAssistant;
import dev.ccassistant.Config;
import dev.ccassistant.api.AiAPI;
import dev.ccassistant.core.AiException;
import dev.ccassistant.core.LuaJson;
import dev.ccassistant.core.Quotas;
import dev.ccassistant.core.ResponseValidator;
import dev.ccassistant.link.LinkCodes;
import dev.ccassistant.link.Links;
import dev.ccassistant.link.OwnerStates;
import dev.ccassistant.provider.Pending;
import dev.ccassistant.provider.ProviderClient;
import dev.ccassistant.provider.ProviderHttp;
import dev.ccassistant.provider.ProviderProfile;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything the server keeps while it runs: links, owner states, quotas and requests in progress. Methods are called
 * from computer threads, the server thread and HTTP threads, so all state is concurrent.
 */
public final class AssistantRuntime {
    private static volatile @Nullable AssistantRuntime instance;

    /** How requests reach a player's client. Replaced by game tests. */
    public interface PlayerTransport {
        boolean hasClient(UUID player);

        void send(UUID player, JsonObject message);
    }

    /** How server-backend requests are made. Replaced by game tests. */
    public interface ServerChat {
        Pending<JsonObject> chat(ProviderProfile provider, JsonObject request, int maxTokens, Duration timeout, int maxBytes) throws AiException;
    }

    public static final ServerChat REAL_SERVER_CHAT = (provider, request, maxTokens, timeout, maxBytes) ->
        ProviderClient.chat(provider, ServerProviders.key(provider), request, maxTokens, timeout, maxBytes);

    public static volatile @Nullable PlayerTransport transportOverride;
    public static volatile @Nullable ServerChat serverChatOverride;

    private enum Route {PLAYER, SERVER}

    private record Decision(Route route, @Nullable Links.Owner owner, @Nullable ProviderProfile provider) {
    }

    private final class InFlight {
        final int id;
        final AiAPI api;
        final Route route;
        final @Nullable Links.Owner owner;
        final String profile;
        final AtomicBoolean done = new AtomicBoolean();
        volatile @Nullable Pending<JsonObject> pending;
        volatile @Nullable ScheduledFuture<?> timeout;

        InFlight(int id, AiAPI api, Route route, @Nullable Links.Owner owner, String profile) {
            this.id = id;
            this.api = api;
            this.route = route;
            this.owner = owner;
            this.profile = profile;
        }

        @Nullable UUID ownerId() {
            return owner == null ? null : owner.uuid();
        }
    }

    private final MinecraftServer server;
    private final Links links;
    private final Quotas quotas = new Quotas();
    private final LinkCodes codes = new LinkCodes();
    private final OwnerStates owners = new OwnerStates(this::ownerChanged);
    private volatile ServerProviders providers;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "ccassistant-timeouts");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger nextId = new AtomicInteger();
    private final Map<Integer, InFlight> inFlight = new ConcurrentHashMap<>();
    private final Map<Integer, Set<AiAPI>> live = new ConcurrentHashMap<>();
    private final PlayerTransport netTransport;

    private AssistantRuntime(MinecraftServer server) {
        this.server = server;
        this.links = Links.get(server);
        this.providers = ServerProviders.load(server);
        this.netTransport = new NetTransport(server);
    }

    // ---- Lifecycle (server thread) ----

    public static void start(MinecraftServer server) {
        instance = new AssistantRuntime(server);
    }

    public static void stop() {
        var rt = instance;
        instance = null;
        if (rt == null) return;
        for (var f : new ArrayList<>(rt.inFlight.values())) rt.fail(f, AiException.CANCELLED, "The server is stopping", true);
        rt.timer.shutdownNow();
        rt.owners.clear();
    }

    public static @Nullable AssistantRuntime get() {
        return instance;
    }

    public MinecraftServer server() {
        return server;
    }

    public Links links() {
        return links;
    }

    public OwnerStates owners() {
        return owners;
    }

    public ServerProviders providers() {
        return providers;
    }

    public void reloadProviders() {
        providers = ServerProviders.load(server);
    }

    private PlayerTransport transport() {
        var t = transportOverride;
        return t != null ? t : netTransport;
    }

    // ---- Computers ----

    public void register(AiAPI api) {
        live.computeIfAbsent(api.computerId(), k -> ConcurrentHashMap.newKeySet()).add(api);
    }

    /** The computer shut down: cancel its requests without events. */
    public void unregister(AiAPI api) {
        var set = live.get(api.computerId());
        if (set != null) {
            set.remove(api);
            if (set.isEmpty()) live.remove(api.computerId(), set);
        }
        for (var f : new ArrayList<>(inFlight.values())) {
            if (f.api == api) fail(f, AiException.CANCELLED, "The computer shut down", true);
        }
    }

    private void queueOnComputer(int computer, String event, Object... args) {
        var set = live.get(computer);
        if (set != null) for (var api : set) api.queueEvent(event, args);
    }

    // ---- Linking ----

    public String createLinkCode(int computer) {
        return codes.create(computer);
    }

    /** A player typed {@code /assistant link <code>}. Returns the computer id. */
    public int link(String code, UUID player, String name) throws AiException {
        var computer = codes.consume(code, player);
        var old = links.link(computer, player, name);
        if (old != null && !old.uuid().equals(player)) {
            cancelComputer(computer, "The computer was linked to another player");
            var oldPlayer = server.getPlayerList().getPlayer(old.uuid());
            if (oldPlayer != null) {
                oldPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Computer #" + computer + " is now linked to " + name + " instead of you."));
            }
        }
        queueOnComputer(computer, "ai_linked", name);
        return computer;
    }

    public @Nullable Links.Owner unlink(int computer) {
        var old = links.unlink(computer);
        if (old != null) cancelComputer(computer, "The computer was unlinked");
        return old;
    }

    private void cancelComputer(int computer, String why) {
        for (var f : new ArrayList<>(inFlight.values())) {
            if (f.api.computerId() == computer) fail(f, AiException.CANCELLED, why, false);
        }
    }

    // ---- Owner states ----

    private void ownerChanged(UUID player, OwnerStates.State state) {
        if (state != OwnerStates.State.READY) {
            var code = codeFor(state);
            for (var f : new ArrayList<>(inFlight.values())) {
                if (f.route == Route.PLAYER && player.equals(f.ownerId())) fail(f, code, unavailableMessage(f.owner, state), false);
            }
        }
        for (var e : links.all().entrySet()) {
            if (e.getValue().uuid().equals(player)) queueOnComputer(e.getKey(), "ai_owner", state.id(), e.getValue().name());
        }
    }

    private static String codeFor(OwnerStates.State state) {
        return switch (state) {
            case OFFLINE -> AiException.OWNER_OFFLINE;
            case NO_CLIENT -> AiException.OWNER_NO_CLIENT;
            case DISABLED -> AiException.OWNER_DISABLED;
            case READY -> "";
        };
    }

    private static String unavailableMessage(@Nullable Links.Owner owner, OwnerStates.State state) {
        var name = owner == null ? "The owner" : owner.name();
        return switch (state) {
            case OFFLINE -> name + " is offline. This computer uses " + name + "'s AI provider, so it can't answer until they're back.";
            case NO_CLIENT -> name + " is online but doesn't have CC: Terminal Assistant on their client, which this computer needs.";
            case DISABLED -> name + " hasn't turned on AI for their computers (or has no provider set up).";
            case READY -> name + " is ready.";
        };
    }

    public String ownerStateId(int computer) {
        var owner = links.owner(computer);
        return owner == null ? "unlinked" : owners.state(owner.uuid()).id();
    }

    // ---- Requests ----

    private Decision decide(int computer, boolean admin, @Nullable String profile) throws AiException {
        var backend = Config.BACKEND.get();
        if (backend == Config.Backend.NONE) {
            throw new AiException(AiException.NOT_CONFIGURED, "AI isn't set up on this server (backend = none). Ask an operator.");
        }
        if (admin && !Config.ALLOW_COMMAND_COMPUTERS.get()) {
            throw new AiException(AiException.COMMAND_COMPUTER, "AI is disabled on command computers on this server.");
        }
        var owner = links.owner(computer);
        if (owner == null && (backend != Config.Backend.SERVER || Config.REQUIRE_LINK.get())) {
            throw new AiException(AiException.NOT_LINKED, "This computer isn't linked to a player. Run 'ai link' and type the code in chat.");
        }
        if (backend == Config.Backend.SERVER) return new Decision(Route.SERVER, owner, providers.pick(profile));
        var state = owner == null ? OwnerStates.State.OFFLINE : owners.state(owner.uuid());
        if (state == OwnerStates.State.READY && owner != null && transport().hasClient(owner.uuid())) {
            return new Decision(Route.PLAYER, owner, null);
        }
        if (state == OwnerStates.State.READY) state = OwnerStates.State.NO_CLIENT;
        if (backend == Config.Backend.EITHER) return new Decision(Route.SERVER, owner, providers.pick(profile));
        throw new AiException(codeFor(state), unavailableMessage(owner, state));
    }

    /** Null when a request would currently be accepted (ignoring rate limits), else the reason. */
    public @Nullable AiException availability(int computer, boolean admin) {
        try {
            var d = decide(computer, admin, null);
            if (d.route == Route.SERVER && d.provider != null) ServerProviders.key(d.provider);
            return null;
        } catch (AiException e) {
            return e;
        }
    }

    /** Starts a validated request. Returns its id, or throws for failures that happen before an id exists. */
    public int submit(AiAPI api, boolean admin, JsonObject request) throws AiException {
        var profile = request.has("profile") ? request.get("profile").getAsString() : null;
        var d = decide(api.computerId(), admin, profile);
        String key = null;
        ServerChat chat = null;
        if (d.route == Route.SERVER) {
            chat = serverChatOverride != null ? serverChatOverride : REAL_SERVER_CHAT;
            if (serverChatOverride == null) key = ServerProviders.key(d.provider); // fail before taking a slot
        }
        var cap = Config.MAX_TOKENS.get();
        if (d.provider != null) cap = Math.min(cap, d.provider.maxTokens());
        var maxTokens = request.has("max_tokens") ? (int) Math.min(cap, request.get("max_tokens").getAsLong()) : cap;
        var timeout = Duration.ofSeconds(Config.REQUEST_TIMEOUT_SECONDS.get());

        var ownerId = d.owner == null ? null : d.owner.uuid();
        quotas.acquire(api.computerId(), ownerId, Config.quotaLimits());
        var id = nextId.incrementAndGet();
        var f = new InFlight(id, api, d.route, d.owner, d.route == Route.SERVER ? d.provider.name() : profile == null ? "" : profile);
        inFlight.put(id, f);
        f.timeout = timer.schedule(() -> fail(f, AiException.TIMEOUT, "No answer within " + timeout.toSeconds() + "s", false),
            timeout.toSeconds() + 2, TimeUnit.SECONDS);
        try {
            if (d.route == Route.SERVER) {
                var body = request.deepCopy();
                body.remove("profile");
                var pending = chat.chat(d.provider, body, maxTokens, timeout, Config.MAX_RESPONSE_BYTES.get());
                f.pending = pending;
                final var secret = key;
                pending.result().whenComplete((response, error) -> {
                    if (error == null) finish(f, response, f.profile);
                    else {
                        var e = ProviderClient.unwrap(error);
                        fail(f, e.code(), ProviderHttp.scrub(e.getMessage(), secret), false);
                    }
                });
            } else {
                var msg = new JsonObject();
                msg.addProperty("t", "req");
                msg.addProperty("id", id);
                msg.addProperty("computer", api.computerId());
                msg.add("body", request);
                msg.addProperty("maxTokens", maxTokens);
                msg.addProperty("timeout", timeout.toSeconds());
                transport().send(d.owner.uuid(), msg);
            }
        } catch (AiException e) {
            fail(f, e.code(), e.getMessage(), true);
            throw e;
        } catch (RuntimeException e) {
            fail(f, AiException.BAD_RESPONSE, "Internal error", true);
            throw e;
        }
        return id;
    }

    public boolean cancel(AiAPI api, int id) {
        var f = inFlight.get(id);
        if (f == null || f.api != api) return false;
        fail(f, AiException.CANCELLED, "Cancelled", false);
        return true;
    }

    public int inFlightCount(AiAPI api) {
        var n = 0;
        for (var f : inFlight.values()) if (f.api == api) n++;
        return n;
    }

    // ---- Messages from clients (server thread) ----

    public void onClientMessage(UUID player, JsonObject msg) {
        var type = msg.has("t") && msg.get("t").isJsonPrimitive() ? msg.get("t").getAsString() : "";
        switch (type) {
            case "status" -> owners.clientStatus(player, msg.has("ready") && msg.get("ready").getAsBoolean());
            case "res" -> {
                if (!msg.has("id")) return;
                var f = inFlight.get(msg.get("id").getAsInt());
                // Only the owner's own client may answer, and only requests routed to it.
                if (f == null || f.route != Route.PLAYER || !player.equals(f.ownerId())) return;
                if (msg.has("ok") && msg.get("ok").getAsBoolean()) {
                    try {
                        var profile = msg.has("profile") && msg.get("profile").isJsonPrimitive() ? msg.get("profile").getAsString() : "";
                        finish(f, ResponseValidator.check(msg.get("body")), profile.length() > 32 ? profile.substring(0, 32) : profile);
                    } catch (AiException e) {
                        fail(f, e.code(), e.getMessage(), false);
                    }
                } else {
                    var code = msg.has("code") && msg.get("code").isJsonPrimitive() ? msg.get("code").getAsString() : "";
                    var text = msg.has("msg") && msg.get("msg").isJsonPrimitive() ? msg.get("msg").getAsString() : "";
                    if (!CLIENT_CODES.contains(code)) code = AiException.PROVIDER_ERROR;
                    var prefix = code.equals(AiException.OWNER_DISABLED) || code.equals(AiException.BAD_PROFILE)
                        ? (f.owner == null ? "The owner" : f.owner.name()) + "'s client refused the request: " : "";
                    fail(f, code, prefix + ProviderHttp.scrub(text, null), false);
                }
            }
            default -> {
            }
        }
    }

    private static final Set<String> CLIENT_CODES = Set.of(AiException.OWNER_DISABLED, AiException.BAD_PROFILE,
        AiException.PROVIDER_ERROR, AiException.NETWORK_ERROR, AiException.BAD_RESPONSE, AiException.TIMEOUT,
        AiException.TOO_LARGE, AiException.BAD_REQUEST);

    // ---- Completion: exactly once per request ----

    private void finish(InFlight f, JsonObject response, String profile) {
        if (!complete(f)) return;
        var info = new HashMap<String, Object>();
        info.put("backend", f.route == Route.PLAYER ? "player" : "server");
        if (f.owner != null) info.put("owner", f.owner.name());
        if (!profile.isEmpty()) info.put("profile", profile);
        f.api.queueEvent("ai_response", f.id, LuaJson.toLua(response), info);
    }

    private void fail(InFlight f, String code, String message, boolean silent) {
        if (!complete(f)) return;
        var pending = f.pending;
        if (pending != null) pending.cancel();
        if (f.route == Route.PLAYER && f.owner != null && !code.equals(AiException.OWNER_OFFLINE)) {
            var cancel = new JsonObject();
            cancel.addProperty("t", "cancel");
            cancel.addProperty("id", f.id);
            try {
                transport().send(f.owner.uuid(), cancel);
            } catch (RuntimeException e) {
                CCAssistant.LOG.debug("Couldn't send cancel for request {}", f.id, e);
            }
        }
        if (!silent) f.api.queueEvent("ai_error", f.id, code, message);
    }

    private boolean complete(InFlight f) {
        if (!f.done.compareAndSet(false, true)) return false;
        inFlight.remove(f.id, f);
        var t = f.timeout;
        if (t != null) t.cancel(false);
        quotas.release(f.api.computerId(), f.ownerId());
        return true;
    }
}
