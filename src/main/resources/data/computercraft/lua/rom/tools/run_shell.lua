-- Run a command in this computer's CC shell, capturing its output.
if not shell then
    -- Loaded by a program that has no CC shell: this tool can't work here, so it isn't offered.
    return { available = false }
end
return {
    description = "Run a command line in this ComputerCraft computer's shell (CC programs such as ls, cp, mkdir, "
        .. "edit, or the computer's own scripts) and return its output and whether it succeeded. This is the game "
        .. "computer's shell, not a real operating system shell. Programs that wait for typing will time out. "
        .. "Asks the user first.",
    input_schema = {
        type = "object",
        properties = { command = { type = "string", description = "Command line, e.g. 'ls rom/programs'." } },
        required = { "command" },
    },
    destructive = true,
    timeout = 20,
    run = function(input, ctx)
        if not ctx.shell then return nil, "no CC shell is available to this program" end
        if input.command:find("^%s*$") then return nil, "command is empty" end
        local limit = ctx.limits.maxToolOutputBytes or 16384
        local captureTerm, captured = ctx.capture.create(limit)
        local old = term.redirect(captureTerm)
        local ok, result = pcall(ctx.shell.run, input.command)
        term.redirect(old)
        local output, dropped = captured()
        if dropped > 0 then output = output .. ("\n[output truncated: %d bytes dropped]"):format(dropped) end
        if not ok then return nil, "error: " .. tostring(result) .. (output ~= "" and "\n" .. output or "") end
        return (result and "succeeded" or "failed") .. (output ~= "" and "\n" .. output or " (no output)")
    end,
}
