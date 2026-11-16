-- Delete a file or an empty folder on this computer.
return {
    description = "Delete a file, or an empty folder, on this ComputerCraft computer. Folders that aren't empty are "
        .. "refused. Asks the user first.",
    input_schema = {
        type = "object",
        properties = { path = { type = "string", description = "File or empty folder to delete." } },
        required = { "path" },
    },
    destructive = true,
    run = function(input, ctx)
        local path, err = ctx.resolve(input.path, { write = true })
        if not path then return nil, err end
        if path == "/" then return nil, "can't delete the root folder" end
        if not fs.exists(path) then return nil, "no such file: " .. path end
        if fs.isDir(path) and #fs.list(path) > 0 then return nil, path .. " is a folder that isn't empty" end
        fs.delete(path)
        return "deleted " .. path
    end,
}
