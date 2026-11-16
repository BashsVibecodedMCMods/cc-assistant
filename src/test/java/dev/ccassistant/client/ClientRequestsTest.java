package dev.ccassistant.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.ccassistant.provider.Preset;
import dev.ccassistant.provider.ProtocolKind;
import dev.ccassistant.provider.ProviderProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ClientRequestsTest {
    private final List<JsonObject> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    private ClientRequests requests(ProfileStore store, int perMinute) {
        return new ClientRequests(() -> store, new ClientRequests.Settings(enabled::get, () -> perMinute, () -> 100, () -> 262144),
            sent::add, text -> {
        }, System::currentTimeMillis);
    }

    private static JsonObject req(int id, String extra) {
        return JsonParser.parseString("{\"t\":\"req\",\"id\":" + id + ",\"computer\":3,\"maxTokens\":50,\"timeout\":30,"
            + "\"body\":{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]" + extra + "}}").getAsJsonObject();
    }

    private JsonObject lastReply() throws InterruptedException {
        for (var i = 0; i < 200 && sent.stream().noneMatch(m -> m.get("t").getAsString().equals("res")); i++) Thread.sleep(25);
        return sent.stream().filter(m -> m.get("t").getAsString().equals("res")).reduce((a, b) -> b).orElseThrow();
    }

    private static ProviderProfile local(String name, String url, boolean selectable) {
        return new ProviderProfile(name + "-id", name, Preset.CUSTOM_OPENAI, ProtocolKind.OPENAI, url, "m", 40, null, false, false,
            Map.of(), ProviderProfile.KeyMode.STORED, "", selectable);
    }

    @Test
    void refusesWhenSwitchedOffOrUnconfigured(@TempDir Path dir) throws Exception {
        var store = new ProfileStore(dir, k -> null);
        var r = requests(store, 10);
        assertFalse(r.ready());
        r.onMessage(req(1, ""));
        assertEquals("owner_disabled", lastReply().get("code").getAsString());
        assertTrue(lastReply().get("msg").getAsString().contains("no default"));
        enabled.set(false);
        sent.clear();
        r.onMessage(req(2, ""));
        assertEquals("owner_disabled", lastReply().get("code").getAsString());
    }

    @Test
    void profileChoiceIsLimitedToSelectableProfiles(@TempDir Path dir) throws Exception {
        var store = new ProfileStore(dir, k -> null);
        store.save(local("main", "http://127.0.0.1:9/v1", true), "k-1111");
        store.save(local("secret", "http://127.0.0.1:9/v1", false), "k-2222");
        var r = requests(store, 10);
        r.onMessage(req(1, ",\"profile\":\"nope\""));
        assertEquals("bad_profile", lastReply().get("code").getAsString());
        sent.clear();
        r.onMessage(req(2, ",\"profile\":\"secret\""));
        assertEquals("bad_profile", lastReply().get("code").getAsString());
        sent.clear();
        r.onMessage(req(3, ",\"model\":\"x\""));
        assertEquals("bad_request", lastReply().get("code").getAsString());
    }

    @Test
    void servesARequestWithItsOwnCapsAndRateLimit(@TempDir Path dir) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        server.createContext("/v1/chat/completions", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            var out = "{\"model\":\"m\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"pong\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}"
                .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            var store = new ProfileStore(dir, k -> null);
            store.save(local("main", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", true), "sk-local-9999");
            var r = requests(store, 1);
            assertTrue(r.ready());
            r.onMessage(req(7, ",\"max_tokens\":1000"));
            var reply = lastReply();
            assertTrue(reply.get("ok").getAsBoolean(), reply.toString());
            assertEquals("main", reply.get("profile").getAsString());
            assertEquals("pong", reply.getAsJsonObject("body").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
            // the profile's cap (40) wins over the server's (50) and the request's (1000)
            assertTrue(body.get().contains("\"max_tokens\":40"), body.get());
            assertEquals("Bearer sk-local-9999", auth.get());
            assertEquals(1, r.counters().get("main").requests);
            assertEquals(2, r.counters().get("main").outputTokens);
            // second request within the minute: the client's own limit
            sent.clear();
            r.onMessage(req(8, ""));
            assertEquals("owner_disabled", lastReply().get("code").getAsString());
            assertTrue(lastReply().get("msg").getAsString().contains("limit"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void statusIsSentOnlyOnChange(@TempDir Path dir) throws Exception {
        var store = new ProfileStore(dir, k -> null);
        var r = requests(store, 10);
        r.sendStatus(false);
        r.sendStatus(false);
        assertEquals(1, sent.size());
        assertFalse(sent.get(0).get("ready").getAsBoolean());
        store.save(local("main", "http://127.0.0.1:9/v1", true), "k-1234");
        r.sendStatus(false);
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).get("ready").getAsBoolean());
        TimeUnit.MILLISECONDS.sleep(1);
    }
}
