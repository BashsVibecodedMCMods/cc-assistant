-- Read part of a file on this computer.
return {
    description = "Read a file on this ComputerCraft computer. Returns up to 'length' bytes starting at 'offset', "
        .. "with a header giving the byte range and the file's total size, so long files can be read in pieces.",
    input_schema = {
        type = "object",
        properties = {
            path = { type = "string", description = "File to read, from the computer's root." },
            offset = { type = "integer", description = "Byte to start at. Default 0." },
            length = { type = "integer", description = "How many bytes to read. Default 16384." },
        },
        required = { "path" },
    },
    run = function(input, ctx)
        local path, err = ctx.resolve(input.path)
        if not path then return nil, err end
        if not fs.exists(path) then return nil, "no such file: " .. path end
        if fs.isDir(path) then return nil, path .. " is a folder; use list_files" end
        local size = fs.getSize(path)
        local offset = math.max(0, math.floor(input.offset or 0))
        local cap = math.max(256, (ctx.limits.maxToolOutputBytes or 16384) - 100)
        local length = math.min(math.max(1, math.floor(input.length or 16384)), cap)
        local handle, openErr = fs.open(path, "rb")
        if not handle then return nil, openErr or ("can't open " .. path) end
        local data
        local ok, readErr = pcall(function()
            if offset > 0 then handle.seek("set", offset) end
            data = handle.read(length) or ""
        end)
        handle.close()
        if not ok then return nil, tostring(readErr) end
        local last = offset + #data
        return ("[%s: bytes %d-%d of %d]\n%s"):format(path, offset, last, size, data)
    end,
}
