package dev.ccassistant.api;

import dev.ccassistant.Config;
import dev.ccassistant.core.AiException;
import dev.ccassistant.core.LuaJson;
import dev.ccassistant.core.RequestValidator;
import dev.ccassistant.link.LinkCodes;
import dev.ccassistant.server.AssistantRuntime;
import dan200.computercraft.api.component.ComputerComponents;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;

import java.util.HashMap;
import java.util.Map;

/** The {@code ai} global on every computer (docs/LUA_API.md section 1). */
public final class AiAPI implements ILuaAPI {
    private final IComputerSystem system;

    public AiAPI(IComputerSystem system) {
        this.system = system;
    }

    @Override
    public String[] getNames() {
        return new String[]{"ai"};
    }

    @Override
    public void startup() {
        var rt = AssistantRuntime.get();
        if (rt != null) rt.register(this);
    }

    @Override
    public void shutdown() {
        var rt = AssistantRuntime.get();
        if (rt != null) rt.unregister(this);
    }

    public int computerId() {
        return system.getID();
    }

    public void queueEvent(String event, Object... args) {
        system.queueEvent(event, args);
    }

    private boolean isCommandComputer() {
        return system.getComponent(ComputerComponents.ADMIN_COMPUTER) != null;
    }

    private static AssistantRuntime runtime() throws AiException {
        var rt = AssistantRuntime.get();
        if (rt == null) throw new AiException(AiException.NOT_CONFIGURED, "The server isn't ready yet");
        return rt;
    }

    @LuaFunction
    public final MethodResult isAvailable() {
        try {
            var problem = runtime().availability(computerId(), isCommandComputer());
            return problem == null ? MethodResult.of(true) : MethodResult.of(false, problem.code(), problem.getMessage());
        } catch (AiException e) {
            return MethodResult.of(false, e.code(), e.getMessage());
        }
    }

    @LuaFunction
    public final Map<String, Object> status() {
        var out = new HashMap<String, Object>();
        out.put("backend", Config.BACKEND.get().name().toLowerCase(java.util.Locale.ROOT));
        out.put("computerId", computerId());
        var rt = AssistantRuntime.get();
        if (rt == null) {
            out.put("linked", false);
            out.put("inFlight", 0);
            return out;
        }
        var owner = rt.links().owner(computerId());
        out.put("linked", owner != null);
        if (owner != null) {
            out.put("owner", owner.name());
            var state = rt.owners().state(owner.uuid());
            out.put("ownerOnline", state != dev.ccassistant.link.OwnerStates.State.OFFLINE);
        }
        out.put("ownerState", rt.ownerStateId(computerId()));
        out.put("inFlight", rt.inFlightCount(this));
        return out;
    }

    @LuaFunction
    public final MethodResult link() throws LuaException {
        try {
            return MethodResult.of(runtime().createLinkCode(computerId()), (int) (LinkCodes.EXPIRY_MS / 1000));
        } catch (AiException e) {
            throw new LuaException(e.getMessage());
        }
    }

    @LuaFunction
    public final boolean unlink() {
        var rt = AssistantRuntime.get();
        return rt != null && rt.unlink(computerId()) != null;
    }

    @LuaFunction
    public final MethodResult request(IArguments args) throws LuaException {
        var table = args.getTable(0);
        try {
            var json = LuaJson.toJson(table);
            var request = RequestValidator.validate(json, Config.MAX_REQUEST_BYTES.get());
            return MethodResult.of(runtime().submit(this, isCommandComputer(), request));
        } catch (AiException e) {
            return MethodResult.of(null, e.code(), e.getMessage());
        }
    }

    @LuaFunction
    public final boolean cancel(int id) {
        var rt = AssistantRuntime.get();
        return rt != null && rt.cancel(this, id);
    }

    @LuaFunction
    public final Map<String, Object> getLimits() {
        var out = new HashMap<String, Object>();
        out.put("maxRequestBytes", Config.MAX_REQUEST_BYTES.get());
        out.put("maxTokens", Config.MAX_TOKENS.get());
        out.put("requestTimeout", Config.REQUEST_TIMEOUT_SECONDS.get());
        out.put("maxToolSeconds", Config.MAX_TOOL_SECONDS.get());
        out.put("maxToolOutputBytes", Config.MAX_TOOL_OUTPUT_BYTES.get());
        out.put("maxToolCallsPerTurn", Config.MAX_TOOL_CALLS_PER_TURN.get());
        out.put("allowUserTools", Config.ALLOW_USER_TOOLS.get());
        out.put("allowDestructiveTools", Config.ALLOW_DESTRUCTIVE_TOOLS.get());
        var disabled = new HashMap<Integer, Object>();
        var i = 1;
        for (var name : Config.DISABLED_ROM_TOOLS.get()) disabled.put(i++, name);
        out.put("disabledRomTools", disabled);
        return out;
    }
}
