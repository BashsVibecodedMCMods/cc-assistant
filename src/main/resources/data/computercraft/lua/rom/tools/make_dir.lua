-- Create a folder on this computer.
return {
    description = "Create a folder (and any missing parent folders) on this ComputerCraft computer.",
    input_schema = {
        type = "object",
        properties = { path = { type = "string", description = "Folder to create." } },
        required = { "path" },
    },
    -- Only protected places (startup, tools, ...) need the user's approval.
    destructive = function(input, ctx)
        local _, info = ctx.resolve(input.path, { write = true })
        return type(info) ~= "table" or info.protected
    end,
    run = function(input, ctx)
        local path, err = ctx.resolve(input.path, { write = true })
        if not path then return nil, err end
        if fs.exists(path) and not fs.isDir(path) then return nil, path .. " is a file" end
        fs.makeDir(path)
        return "created " .. path
    end,
}
