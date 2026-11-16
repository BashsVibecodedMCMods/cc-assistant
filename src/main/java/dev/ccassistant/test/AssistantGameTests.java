package dev.ccassistant.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.computer.core.ServerContext;
import dev.ccassistant.CCAssistant;
import dev.ccassistant.Config;
import dev.ccassistant.core.AiException;
import dev.ccassistant.provider.Pending;
import dev.ccassistant.server.AssistantRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * End-to-end tests on real CC: Tweaked computers. Run with {@code ./gradlew runGameTestServer}. Each test sets every
 * config value it depends on and uses its own batch, because config is global and tests in one batch run together.
 */
@GameTestHolder(CCAssistant.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AssistantGameTests {
    private static final int BOOT = 72000; // the test server ticks as fast as it can while computers run in real time

    // ---- Helpers ----

    private static void configure(Config.Backend backend, boolean requireLink) {
        Config.BACKEND.set(backend);
        Config.REQUIRE_LINK.set(requireLink);
        Config.ALLOW_COMMAND_COMPUTERS.set(false);
        Config.REQUEST_TIMEOUT_SECONDS.set(180);
        Config.ALLOW_USER_TOOLS.set(true);
        Config.ALLOW_DESTRUCTIVE_TOOLS.set(true);
        Config.DISABLED_ROM_TOOLS.set(List.of());
        Config.MAX_REQUEST_BYTES.set(262_144);
        Config.MAX_TOKENS.set(8192);
        Config.MAX_CONCURRENT_PER_COMPUTER.set(1);
        Config.MAX_CONCURRENT_PER_PLAYER.set(2);
        Config.MAX_CONCURRENT_TOTAL.set(16);
        Config.REQUESTS_PER_MINUTE_PER_COMPUTER.set(10);
        Config.REQUESTS_PER_MINUTE_PER_PLAYER.set(10);
        AssistantRuntime.serverChatOverride = null;
        AssistantRuntime.transportOverride = null;
        rt().useProviders(dev.ccassistant.server.ServerProviders.parse(JsonParser.parseString(
            "{\"providers\":[{\"name\":\"fake\",\"preset\":\"custom-openai\",\"baseUrl\":\"https://example.invalid/v1\",\"model\":\"m\"}]}")
            .getAsJsonObject()));
    }

    private static AssistantRuntime rt() {
        var rt = AssistantRuntime.get();
        if (rt == null) throw new GameTestAssertException("runtime not started");
        return rt;
    }

    /** A completed fake reply in the mod's format. */
    private static JsonObject reply(String stop, JsonObject... blocks) {
        var o = new JsonObject();
        o.addProperty("model", "fake-model");
        o.addProperty("stop_reason", stop);
        var content = new JsonArray();
        for (var b : blocks) content.add(b);
        o.add("content", content);
        var usage = new JsonObject();
        usage.addProperty("input_tokens", 1);
        usage.addProperty("output_tokens", 1);
        o.add("usage", usage);
        return o;
    }

    private static JsonObject text(String t) {
        var b = new JsonObject();
        b.addProperty("type", "text");
        b.addProperty("text", t);
        return b;
    }

    private static JsonObject toolUse(String id, String name, String inputJson) {
        var b = new JsonObject();
        b.addProperty("type", "tool_use");
        b.addProperty("id", id);
        b.addProperty("name", name);
        b.add("input", JsonParser.parseString(inputJson));
        return b;
    }

    private record Computer(int id, Path dir, AbstractComputerBlockEntity entity) {
        String result() {
            try {
                return Files.readString(dir.resolve("ccai_result.txt"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "(no result file)";
            }
        }
    }

    /** Places a computer, writes {@code files} (startup.lua etc.) into it, and boots it. */
    private static Computer boot(GameTestHelper helper, Block block, Map<String, String> files) {
        var server = helper.getLevel().getServer();
        var id = ComputerCraftAPI.createUniqueNumberedSaveDir(server, "computer");
        var dir = ServerContext.get(server).storageDir().resolve("computer").resolve(Integer.toString(id));
        try {
            for (var e : files.entrySet()) {
                var p = dir.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, block);
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(rel)) instanceof AbstractComputerBlockEntity entity)) {
            throw new GameTestAssertException("No computer block entity");
        }
        entity.setComputerID(id);
        entity.createServerComputer().turnOn();
        return new Computer(id, dir, entity);
    }

    /** Succeeds when the computer labels itself "ccai_ok"; fails with its result file on "ccai_fail". */
    private static void expectOk(GameTestHelper helper, Computer c, Runnable cleanup) {
        helper.succeedWhen(() -> {
            var sc = c.entity().getServerComputer();
            var label = sc == null ? null : sc.getLabel();
            if ("ccai_fail".equals(label)) {
                try {
                    cleanup.run();
                } catch (RuntimeException ignored) {
                    // report the Lua failure, not a follow-on check
                }
                throw new GameTestAssertException("Lua failed: " + c.result());
            }
            helper.assertTrue("ccai_ok".equals(label), "Lua has not finished (label " + label + ")");
            cleanup.run();
        });
    }

    private static Map<String, String> startup(String body) {
        var files = new LinkedHashMap<String, String>();
        files.put("startup.lua", LUA_PRELUDE + body + LUA_EPILOGUE);
        return files;
    }

    private static final String LUA_PRELUDE = """
        local log = {}
        local function check(cond, msg) if not cond then error(msg, 2) end end
        local function eq(a, b, msg) if a ~= b then error((msg or "") .. ": expected " .. tostring(b) .. ", got " .. tostring(a), 2) end end
        local ok, err = pcall(function()
        """;
    private static final String LUA_EPILOGUE = """
        end)
        local f = fs.open("ccai_result.txt", "w")
        f.write(ok and "ok" or tostring(err))
        f.close()
        os.setComputerLabel(ok and "ccai_ok" or "ccai_fail")
        """;

    // ---- Tests ----

    @GameTest(template = "empty", timeoutTicks = BOOT, batch = "ccai_not_configured")
    public static void notConfiguredGivesClearError(GameTestHelper helper) {
        configure(Config.Backend.NONE, true);
        var c = boot(helper, ModRegistry.Blocks.COMPUTER_NORMAL.get(), startup("""
            local ok, code, msg = ai.isAvailable()
            eq(ok, false, "isAvailable")
            eq(code, "not_configured", "code")
            check(msg:find("backend"), "message mentions the backend: " .. msg)
            local id, code2 = ai.request { messages = { { role = "user", content = "hi" } } }
            eq(id, nil, "no id")
            eq(code2, "not_configured", "request code")
            local okReq, e = pcall(ai.request, "not a table")
            check(not okReq, "wrong argument type raises")
            """));
        expectOk(helper, c, () -> {
        });
    }

    @GameTest(template = "empty", timeoutTicks = BOOT, batch = "ccai_bad_request")
    public static void badRequestsAreRejectedBeforeSending(GameTestHelper helper) {
        configure(Config.Backend.SERVER, false);
        AssistantRuntime.serverChatOverride = (p, r, m, t, b) -> {
            throw new AiException(AiException.BAD_RESPONSE, "should not be called");
        };
        var c = boot(helper, ModRegistry.Blocks.COMPUTER_NORMAL.get(), startup("""
            local id, code, msg = ai.request { messages = { { role = "user", content = "x" } }, model = "gpt-4" }
            eq(code, "bad_request", "unknown field")
            check(msg:find("model"), msg)
            id, code = ai.request { messages = {} }
            eq(code, "bad_request", "empty messages")
            id, code = ai.request { messages = { { role = "user", content = "x" } }, tool_choice = "any" }
            eq(code, "bad_request", "tool_choice")
            id, code = ai.request { messages = { { role = "user", content = string.rep("x", 300000) } } }
            eq(code, "too_large", "size")
            """));
        expectOk(helper, c, () -> AssistantRuntime.serverChatOverride = null);
    }

    @GameTest(template = "empty", timeoutTicks = BOOT, batch = "ccai_link")
    public static void linkCodeFlow(GameTestHelper helper) {
        configure(Config.Backend.PLAYER, true);
        var player = UUID.randomUUID();
        var c = boot(helper, ModRegistry.Blocks.COMPUTER_NORMAL.get(), startup("""
            eq(select(2, ai.isAvailable()), "not_linked", "before linking")
            eq(ai.status().ownerState, "unlinked", "state")
            local code, seconds = ai.link()
            eq(#code, 6, "code length")
            eq(seconds, 300, "expiry")
            local f = fs.open("code.txt", "w") f.write(code) f.close()
            local event, name = os.pullEvent("ai_linked")
            eq(name, "Tester", "linked name")
            local s = ai.status()
            eq(s.owner, "Tester", "owner")
            eq(s.ownerState, "offline", "owner state")
            eq(select(2, ai.isAvailable()), "owner_offline", "after linking, owner offline")
            check(ai.unlink(), "unlink returns true")
            eq(ai.status().linked, false, "unlinked")
            """));
        var linked = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (linked.get()) return;
            var file = c.dir().resolve("code.txt");
            if (!Files.exists(file)) return;
            try {
                var code = Files.readString(file).trim();
                if (code.length() != 6) return;
                // a wrong code is refused and doesn't use up the real one
                try {
                    rt().link("WRONG1", player, "Tester");
                    throw new GameTestAssertException("wrong code accepted");
                } catch (AiException expected) {
                    helper.assertTrue(expected.code().equals(AiException.BAD_REQUEST), "wrong code error");
                }
                helper.assertTrue(rt().link(code.toLowerCase(), player, "Tester") == c.id(), "link returns the computer id");
                linked.set(true);
            } catch (IOException | AiException e) {
                throw new GameTestAssertException("link failed: " + e);
            }
        });
        expectOk(helper, c, () -> rt().unlink(c.id()));
    }

    private static final String TOOL_LOOP_LUA = """
        local tools = require("cc.ai.tools").load { shell = shell }
        -- discovery: ROM tools sorted, then user tools with the user_ prefix; bad files reported, not fatal
        local names = {}
        for i, d in ipairs(tools:definitions()) do names[i] = d.name end
        eq(table.concat(names, ","), "delete_file,edit_file,list_files,make_dir,read_file,run_lua,run_shell,write_file,user_echo,user_slow", "tool order")
        local errs = {}
        for _, e in ipairs(tools.errors) do errs[#errs + 1] = e.path end
        table.sort(errs)
        eq(table.concat(errs, ","), "/tools/Bad.lua,/tools/broken.lua", "tool errors")

        fs.makeDir("emptydir")
        local h = fs.open("notes.txt", "w") h.write("alpha beta gamma") h.close()
        local approvals = {}
        local ai2 = require("cc.ai")
        local chat = ai2.Conversation.new {
          tools = tools,
          approve = function(call)
            approvals[#approvals + 1] = call.name .. ":" .. tostring(call.input.path or call.input.command or "")
            return call.input.path ~= "startup.lua"   -- refuse the protected write
          end,
        }
        local answer, code, msg = chat:say("do the things \\xC3\\xA9")
        check(answer, "say failed: " .. tostring(code) .. " " .. tostring(msg))
        eq(answer, "h\\xC3\\xA9llo \\xE2\\x98\\x83", "unicode reply comes back as UTF-8")

        -- tool results, in order
        local results = chat.messages[3].content
        local function res(i) return results[i].content, results[i].is_error end
        check(res(1):find("wrote 2 bytes"), "write_file: " .. res(1))
        local listing = res(2)
        check(listing:find("hello.txt") and listing:find("emptydir/"), "list_files: " .. listing)
        check(res(3):find("bytes 0%-2 of 2") and res(3):find("hi"), "read_file: " .. res(3))
        check(res(4):find("returned: 42") and res(4):find("printed"), "run_lua: " .. res(4))
        check(res(5):find("succeeded") and res(5):find("notes.txt"), "run_shell: " .. res(5))
        eq(res(6), "echo: x", "user tool")
        local c7, e7 = res(7)
        check(e7 and (c7:find("outside") or c7:find("[Ii]nvalid")), "path traversal refused: " .. c7)
        local c8, e8 = res(8)
        check(e8 and c8:find("denied"), "protected write denied: " .. c8)
        check(not fs.exists("startup.lua.bak"), "nothing written")
        local c9, e9 = res(9)
        check(e9 and c9:find("read%-only"), "rom write refused: " .. c9)
        check(res(10):find("replaced 1"), "edit_file: " .. res(10))
        local nh = fs.open("notes.txt", "r") local notes = nh.readAll() nh.close()
        eq(notes, "alpha BETA gamma", "edit applied")
        local c11, e11 = res(11)
        check(e11 and c11:find("unknown tool"), "unknown tool: " .. c11)
        local c12, e12 = res(12)
        check(e12 and c12:find("invalid arguments"), "bad JSON arguments: " .. c12)
        local c13, e13 = res(13)
        check(e13 and c13:find("missing required"), "schema check: " .. c13)
        check(res(14):find("deleted /emptydir"), "delete_file: " .. res(14))
        check(not fs.exists("emptydir"), "deleted")
        local c15, e15 = res(15)
        check(e15 and c15:find("timed out"), "tool timeout: " .. c15)
        check(term.current() == term.native(), "terminal restored after timeouts")
        -- approval was asked for each destructive call (write, run_lua, run_shell, protected write, rom write, edit, delete)
        eq(#approvals, 7, "approvals: " .. table.concat(approvals, " "))
        """;

    @GameTest(template = "empty", timeoutTicks = BOOT, batch = "ccai_tool_loop")
    public static void fullToolLoopWithServerBackend(GameTestHelper helper) {
        configure(Config.Backend.SERVER, false);
        var seenUnicode = new AtomicReference<String>();
        AssistantRuntime.serverChatOverride = (provider, request, maxTokens, timeout, maxBytes) -> {
            var messages = request.getAsJsonArray("messages");
            if (messages.size() == 1) {
                seenUnicode.set(messages.get(0).getAsJsonObject().get("content").getAsString());
                return done(reply("tool_use",
                    toolUse("c1", "write_file", "{\"path\":\"hello.txt\",\"content\":\"hi\"}"),
                    toolUse("c2", "list_files", "{}"),
                    toolUse("c3", "read_file", "{\"path\":\"/hello.txt\"}"),
                    toolUse("c4", "run_lua", "{\"code\":\"print('printed') return 6 * 7\"}"),
                    toolUse("c5", "run_shell", "{\"command\":\"ls\"}"),
                    toolUse("c6", "user_echo", "{\"text\":\"x\"}"),
                    toolUse("c7", "read_file", "{\"path\":\"../../../etc/passwd\"}"),
                    toolUse("c8", "write_file", "{\"path\":\"startup.lua\",\"content\":\"evil\"}"),
                    toolUse("c9", "write_file", "{\"path\":\"rom/x.lua\",\"content\":\"x\"}"),
                    toolUse("c10", "edit_file", "{\"path\":\"notes.txt\",\"old_text\":\"beta\",\"new_text\":\"BETA\"}"),
                    toolUse("c11", "made_up_tool", "{}"),
                    badArgs("c12", "list_files"),
                    toolUse("c13", "read_file", "{}"),
                    toolUse("c14", "delete_file", "{\"path\":\"emptydir\"}"),
                    toolUse("c15", "user_slow", "{}")));
            }
            return done(reply("end_turn", text("héllo ☃")));
        };
        var files = startup(TOOL_LOOP_LUA);
        files.put("tools/echo.lua", """
            return { description = "Echo text back.",
                     input_schema = { type = "object", properties = { text = { type = "string" } }, required = { "text" } },
                     run = function(input) return "echo: " .. input.text end }
            """);
        files.put("tools/slow.lua", """
            return { description = "Sleeps too long.", timeout = 1, run = function() sleep(30) return "late" end }
            """);
        files.put("tools/Bad.lua", "return {}");
        files.put("tools/broken.lua", "return {");
        var c = boot(helper, ModRegistry.Blocks.COMPUTER_NORMAL.get(), files);
        expectOk(helper, c, () -> {
            AssistantRuntime.serverChatOverride = null;
            helper.assertTrue("do the things é".equals(seenUnicode.get()), "Lua UTF-8 reached Java as text: " + seenUnicode.get());
        });
    }

    private static JsonObject badArgs(String id, String name) {
        var b = toolUse(id, name, "{}");
        b.addProperty("input_error", "tool arguments are not valid JSON");
        return b;
    }

    private static Pending<JsonObject> done(JsonObject response) {
        return new Pending<>(CompletableFuture.completedFuture(response), () -> {
        });
    }

    private static Pending<JsonObject> never(Consumer<Boolean> onCancel) {
        var f = new CompletableFuture<JsonObject>();
        return new Pending<>(f, () -> {
            onCancel.accept(true);
            f.completeExceptionally(new AiException(AiException.CANCELLED, "Cancelled"));
        });
    }

    @GameTest(template = "empty", timeoutTicks = BOOT, batch = "ccai_cancel_timeout")
    public static void cancelAndTimeout(GameTestHelper helper) {
        configure(Config.Backend.SERVER, false);
        Config.REQUEST_TIMEOUT_SECONDS.set(5);
        var cancelled = new AtomicBoolean();
        AssistantRuntime.serverChatOverride = (p, r, m, t, b) -> never(cancelled::set);
        var c = boot(helper, ModRegistry.Blocks.COMPUTER_NORMAL.get(), startup("""
            local body = { messages = { { role = "user", content = "hi" } } }
            local id = assert(ai.request(body))
            eq(ai.status().inFlight, 1, "in flight")
            local id2, busy = ai.request(body)
            eq(busy, "busy", "one request per computer")
            check(ai.cancel(id), "cancel returns true")
            local event, rid, code = os.pullEvent("ai_error")
            eq(rid, id, "cancel event id") eq(code, "cancelled", "cancel code")
            check(not ai.cancel(id), "second cancel returns false")
            local id3 = assert(ai.request(body))
            local event3, rid3, code3, msg3 = os.pullEvent("ai_error")
            eq(rid3, id3, "timeout id") eq(code3, "timeout", "timeout code: " .. tostring(msg3))
            """));
        expectOk(helper, c, () -> {
            AssistantRuntime.serverChatOverride = null;
            Config.REQUEST_TIMEOUT_SECONDS.set(180);
            helper.assertTrue(cancelled.get(), "backend call was cancelled");
        });
    }

    @GameTest(template = "empty", timeoutTicks = BOOT, batch = "ccai_owner")
    public static void ownerOfflineIsHandledGracefully(GameTestHelper helper) {
        configure(Config.Backend.PLAYER, true);
        var player = UUID.randomUUID();
        AssistantRuntime.transportOverride = new AssistantRuntime.PlayerTransport() {
            @Override
            public boolean hasClient(UUID p) {
                return true;
            }

            @Override
            public void send(UUID p, JsonObject msg) {
                if (!"req".equals(msg.get("t").getAsString())) return;
                var content = msg.getAsJsonObject("body").getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                if (content.equals("hang")) return; // a slow provider that never answers
                var res = new JsonObject();
                res.addProperty("t", "res");
                res.addProperty("id", msg.get("id").getAsInt());
                res.addProperty("ok", true);
                res.addProperty("profile", "nano");
                res.add("body", reply("end_turn", text("pong")));
                rt().server().execute(() -> rt().onClientMessage(p, res));
            }
        };
        var c = boot(helper, ModRegistry.Blocks.COMPUTER_NORMAL.get(), startup("""
            local function signal(name) local f = fs.open(name, "w") f.write("1") f.close() end
            local function waitReady()
              while true do
                local _, state, who = os.pullEvent("ai_owner")
                if state == "ready" then return who end
              end
            end
            while not ai.status().linked do sleep(0.1) end
            local body = { messages = { { role = "user", content = "ping" } } }
            -- 1. owner offline: refused at once, with a message naming them
            local id, code, msg = ai.request(body)
            eq(id, nil, "no id") eq(code, "owner_offline", "code")
            check(msg:find("Owner") and msg:find("offline"), "message: " .. msg)
            eq(ai.status().ownerState, "offline", "state")
            -- 2. wait for them without polling
            signal("waiting")
            eq(waitReady(), "Owner", "owner name in ai_owner")
            local reply, info = require("cc.ai").send(body)
            check(reply, "request after owner returned: " .. tostring(info))
            eq(reply.content[1].text, "pong", "reply")
            eq(info.backend, "player", "backend") eq(info.profile, "nano", "profile") eq(info.owner, "Owner", "owner")
            -- 3. owner leaves mid-request: ends at once with owner_offline (not a timeout)
            local id2 = assert(ai.request { messages = { { role = "user", content = "hang" } } })
            signal("sent")
            local event2, rid, code2 = os.pullEvent("ai_error")
            eq(rid, id2, "id") eq(code2, "owner_offline", "mid-request logout")
            -- 4. a conversation keeps its state and can retry later
            local chat = require("cc.ai").Conversation.new()
            local a, c3 = chat:say("hello")
            eq(a, nil, "say fails") eq(c3, "owner_offline", "say code")
            check(chat:pending(), "pending") eq(#chat.messages, 0, "history back to before say")
            signal("retry")
            waitReady()
            local a2 = chat:retry()
            eq(a2, "pong", "retry works") eq(#chat.messages, 2, "history after retry")
            """));
        rt().links().link(c.id(), player, "Owner");
        var step = new java.util.concurrent.atomic.AtomicInteger();
        helper.onEachTick(() -> {
            if (step.get() == 0 && Files.exists(c.dir().resolve("waiting"))) {
                step.set(1);
                rt().owners().login(player, true);
                rt().owners().clientStatus(player, true);
            } else if (step.get() == 1 && Files.exists(c.dir().resolve("sent"))) {
                step.set(2);
                rt().owners().logout(player);
            } else if (step.get() == 2 && Files.exists(c.dir().resolve("retry"))) {
                step.set(3);
                rt().owners().login(player, true);
                rt().owners().clientStatus(player, true);
            }
        });
        expectOk(helper, c, () -> {
            rt().owners().logout(player);
            rt().unlink(c.id());
            AssistantRuntime.transportOverride = null;
        });
    }

    @GameTest(template = "empty", timeoutTicks = BOOT, batch = "ccai_owner_states")
    public static void ownerWithoutClientOrDisabled(GameTestHelper helper) {
        configure(Config.Backend.PLAYER, true);
        var player = UUID.randomUUID();
        var c = boot(helper, ModRegistry.Blocks.COMPUTER_NORMAL.get(), startup("""
            while not ai.status().linked do sleep(0.1) end
            while ai.status().ownerState ~= "no_client" do os.pullEvent("ai_owner") end
            eq(select(2, ai.isAvailable()), "owner_no_client", "no client")
            local f = fs.open("next", "w") f.write("1") f.close()
            while ai.status().ownerState ~= "disabled" do os.pullEvent("ai_owner") end
            local id, code = ai.request { messages = { { role = "user", content = "x" } } }
            eq(code, "owner_disabled", "disabled")
            """));
        rt().links().link(c.id(), player, "Owner");
        var step = new java.util.concurrent.atomic.AtomicInteger();
        helper.onEachTick(() -> {
            if (step.get() == 0 && c.entity().getServerComputer() != null) {
                step.set(1);
                helper.runAfterDelay(40, () -> rt().owners().login(player, false));
            } else if (step.get() == 1 && Files.exists(c.dir().resolve("next"))) {
                step.set(2);
                rt().owners().logout(player);
                rt().owners().login(player, true); // has the mod, but its switch is off
            }
        });
        expectOk(helper, c, () -> {
            rt().owners().logout(player);
            rt().unlink(c.id());
        });
    }

    @GameTest(template = "empty", timeoutTicks = BOOT, batch = "ccai_command")
    public static void commandComputersAreRefused(GameTestHelper helper) {
        configure(Config.Backend.SERVER, false);
        AssistantRuntime.serverChatOverride = (p, r, m, t, b) -> done(reply("end_turn", text("x")));
        var c = boot(helper, ModRegistry.Blocks.COMPUTER_COMMAND.get(), startup("""
            check(commands, "this is a command computer")
            local id, code = ai.request { messages = { { role = "user", content = "x" } } }
            eq(code, "command_computer", "refused")
            """));
        expectOk(helper, c, () -> AssistantRuntime.serverChatOverride = null);
    }

    @GameTest(template = "empty", timeoutTicks = BOOT, batch = "ccai_disabled_tools")
    public static void operatorAndUserToolFilters(GameTestHelper helper) {
        configure(Config.Backend.SERVER, false);
        Config.DISABLED_ROM_TOOLS.set(List.of("run_shell"));
        Config.ALLOW_USER_TOOLS.set(false);
        var files = startup("""
            settings.set("ai.disabled_tools", "run_lua")
            local tools = require("cc.ai.tools").load { shell = shell }
            check(not tools.byName.run_shell, "operator disabled run_shell")
            check(not tools.byName.run_lua, "computer setting disabled run_lua")
            check(not tools.byName.user_echo, "user tools off")
            check(tools.byName.read_file, "others still there")
            settings.set("ai.disabled_tools", "")
            settings.set("ai.auto_approve", true)
            local t2 = require("cc.ai.tools").load { shell = shell }
            local content, isError = t2:call("write_file", { path = "a.txt", content = "x" }, nil)
            check(not isError, "auto_approve allows without a callback: " .. content)
            settings.set("ai.auto_approve", false)
            local c2, e2 = t2:call("write_file", { path = "b.txt", content = "x" }, nil)
            check(e2 and c2:find("denied"), "no approver means denied")
            """);
        files.put("tools/echo.lua", "return { description = 'e', run = function() return 'e' end }");
        var c = boot(helper, ModRegistry.Blocks.COMPUTER_NORMAL.get(), files);
        expectOk(helper, c, () -> {
            Config.DISABLED_ROM_TOOLS.set(List.of());
            Config.ALLOW_USER_TOOLS.set(true);
        });
    }

    @GameTest(template = "empty", timeoutTicks = BOOT, batch = "ccai_real_http")
    public static void serverBackendOverRealHttp(GameTestHelper helper) {
        configure(Config.Backend.SERVER, false);
        com.sun.net.httpserver.HttpServer server;
        var seenBody = new AtomicReference<String>();
        try {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        server.createContext("/v1/chat/completions", ex -> {
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var out = """
                {"model":"local-model","choices":[{"finish_reason":"tool_calls","message":{"content":"Let me look.",
                 "tool_calls":[{"id":"call_9","type":"function","function":{"name":"list_files","arguments":"{\\"path\\":\\"/\\"}"}}]}}],
                 "usage":{"prompt_tokens":12,"completion_tokens":7}}
                """.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        rt().useProviders(dev.ccassistant.server.ServerProviders.parse(JsonParser.parseString(
            "{\"providers\":[{\"name\":\"local\",\"preset\":\"custom-openai\",\"baseUrl\":\"http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1\",\"model\":\"local-model\",\"maxTokens\":321}]}").getAsJsonObject()));
        var c = boot(helper, ModRegistry.Blocks.COMPUTER_NORMAL.get(), startup("""
            local reply, info = require("cc.ai").send {
              system = "be brief",
              messages = { { role = "user", content = "list files" } },
              tools = { { name = "list_files", description = "list", input_schema = { type = "object", properties = { path = { type = "string" } } } } },
            }
            check(reply, "request failed: " .. tostring(info))
            eq(info.backend, "server", "backend") eq(info.profile, "local", "provider name")
            eq(reply.model, "local-model", "model") eq(reply.stop_reason, "tool_use", "stop reason")
            eq(reply.content[1].type, "text", "text block") eq(reply.content[1].text, "Let me look.", "text")
            eq(reply.content[2].type, "tool_use", "tool block") eq(reply.content[2].id, "call_9", "call id")
            eq(reply.content[2].input.path, "/", "decoded arguments")
            eq(reply.usage.input_tokens, 12, "usage")
            """));
        expectOk(helper, c, () -> {
            server.stop(0);
            var body = JsonParser.parseString(seenBody.get()).getAsJsonObject();
            helper.assertTrue(body.get("model").getAsString().equals("local-model"), "model from the provider config");
            helper.assertTrue(body.get("max_tokens").getAsInt() == 321, "provider token cap applied: " + body.get("max_tokens"));
            helper.assertTrue(body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString().equals("system"), "system first");
            helper.assertTrue(body.getAsJsonArray("tools").get(0).getAsJsonObject().get("type").getAsString().equals("function"), "OpenAI tool shape");
        });
    }
}
