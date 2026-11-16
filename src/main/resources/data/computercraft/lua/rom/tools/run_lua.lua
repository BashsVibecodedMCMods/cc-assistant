-- Run Lua code on this computer, capturing what it prints.
return {
    description = "Run Lua code on this ComputerCraft computer (CC: Tweaked's Lua, with the computer's normal APIs such "
        .. "as fs, peripheral, redstone, turtle). Returns what it printed and the values it returned. Expressions like "
        .. "'2 + 2' work. This runs inside the game computer, not on any real machine. Asks the user first.",
    input_schema = {
        type = "object",
        properties = { code = { type = "string", description = "Lua source code." } },
        required = { "code" },
    },
    destructive = true,
    timeout = 10,
    run = function(input, ctx)
        local env = setmetatable({ shell = ctx.shell }, { __index = _G })
        env.require, env.package = require("cc.require").make(env, "/")
        local fn, err = load(input.code, "=run_lua", "t", env)
        if not fn then
            local expr = load("return " .. input.code, "=run_lua", "t", env)
            if not expr then return nil, "syntax error: " .. tostring(err) end
            fn = expr
        end
        local limit = ctx.limits.maxToolOutputBytes or 16384
        local captureTerm, captured = ctx.capture.create(limit)
        local old = term.redirect(captureTerm)
        local results = table.pack(pcall(fn))
        term.redirect(old)
        local output, dropped = captured()
        local parts = {}
        if output ~= "" then parts[#parts + 1] = output end
        if dropped > 0 then parts[#parts + 1] = ("[output truncated: %d bytes dropped]"):format(dropped) end
        if not results[1] then
            parts[#parts + 1] = "error: " .. tostring(results[2])
            return nil, table.concat(parts, "\n")
        end
        if results.n > 1 then
            local values = {}
            for i = 2, results.n do
                local ok, s = pcall(textutils.serialise, results[i], { compact = true })
                values[#values + 1] = ok and s or tostring(results[i])
            end
            parts[#parts + 1] = "returned: " .. table.concat(values, ", ")
        end
        if #parts == 0 then return "ok (no output)" end
        return table.concat(parts, "\n")
    end,
}
