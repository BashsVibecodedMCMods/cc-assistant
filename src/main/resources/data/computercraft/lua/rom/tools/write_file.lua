-- Create, overwrite or append to a file on this computer.
local MAX = 64 * 1024
return {
    description = "Write a file on this ComputerCraft computer, creating folders as needed. Overwrites the file "
        .. "unless 'append' is true. At most 64 KiB per call. Asks the user first.",
    input_schema = {
        type = "object",
        properties = {
            path = { type = "string", description = "File to write, from the computer's root." },
            content = { type = "string", description = "The text to write." },
            append = { type = "boolean", description = "Add to the end instead of replacing. Default false." },
        },
        required = { "path", "content" },
    },
    destructive = true,
    run = function(input, ctx)
        local path, err = ctx.resolve(input.path, { write = true })
        if not path then return nil, err end
        if #input.content > MAX then return nil, ("content is %d bytes; the limit is %d per call"):format(#input.content, MAX) end
        if fs.isDir(path) then return nil, path .. " is a folder" end
        local dir = fs.getDir(path)
        if dir ~= "" and not fs.exists(dir) then fs.makeDir(dir) end
        local handle, openErr = fs.open(path, input.append and "ab" or "wb")
        if not handle then return nil, openErr or ("can't open " .. path) end
        local ok, writeErr = pcall(handle.write, input.content)
        handle.close()
        if not ok then return nil, tostring(writeErr) end
        return ("%s %d bytes to %s"):format(input.append and "appended" or "wrote", #input.content, path)
    end,
}
