-- List a folder on this computer.
return {
    description = "List the files and folders in a folder on this ComputerCraft computer. Folder names end with '/'. "
        .. "Paths are from the computer's root (e.g. '/', 'programs', 'disk').",
    input_schema = {
        type = "object",
        properties = { path = { type = "string", description = "Folder to list. Default '/'." } },
    },
    run = function(input, ctx)
        local path, err = ctx.resolve(input.path or "/")
        if not path then return nil, err end
        if not fs.exists(path) then return nil, "no such folder: " .. path end
        if not fs.isDir(path) then return nil, path .. " is a file, not a folder" end
        local names = fs.list(path)
        table.sort(names)
        local out = {}
        for i, name in ipairs(names) do
            if i > 500 then
                out[#out + 1] = ("...and %d more"):format(#names - 500)
                break
            end
            out[#out + 1] = fs.isDir(fs.combine(path, name)) and name .. "/" or name
        end
        if #out == 0 then return path .. " is empty" end
        return table.concat(out, "\n")
    end,
}
