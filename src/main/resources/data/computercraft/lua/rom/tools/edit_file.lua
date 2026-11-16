-- Replace text in a file on this computer.
local MAX = 1024 * 1024
return {
    description = "Edit a file on this ComputerCraft computer by replacing old_text with new_text (plain text, not "
        .. "patterns). old_text must appear exactly once unless replace_all is true. Nothing changes if it isn't found. "
        .. "Asks the user first.",
    input_schema = {
        type = "object",
        properties = {
            path = { type = "string", description = "File to edit, from the computer's root." },
            old_text = { type = "string", description = "Exact text to find." },
            new_text = { type = "string", description = "Text to put in its place." },
            replace_all = { type = "boolean", description = "Replace every occurrence. Default false." },
        },
        required = { "path", "old_text", "new_text" },
    },
    destructive = true,
    run = function(input, ctx)
        local path, err = ctx.resolve(input.path, { write = true })
        if not path then return nil, err end
        if not fs.exists(path) or fs.isDir(path) then return nil, "no such file: " .. path end
        if input.old_text == "" then return nil, "old_text can't be empty" end
        if fs.getSize(path) > MAX then return nil, "file is larger than 1 MiB" end
        local h = fs.open(path, "rb")
        if not h then return nil, "can't open " .. path end
        local text = h.readAll() or ""
        h.close()

        local positions, start = {}, 1
        while true do
            local s, e = text:find(input.old_text, start, true)
            if not s then break end
            positions[#positions + 1] = { s, e }
            start = e + 1
        end
        if #positions == 0 then return nil, "old_text was not found; nothing changed" end
        if #positions > 1 and not input.replace_all then
            return nil, ("old_text appears %d times; add more context or set replace_all"):format(#positions)
        end
        local out, last = {}, 1
        for _, p in ipairs(positions) do
            out[#out + 1] = text:sub(last, p[1] - 1)
            out[#out + 1] = input.new_text
            last = p[2] + 1
        end
        out[#out + 1] = text:sub(last)
        local w = fs.open(path, "wb")
        if not w then return nil, "can't write " .. path end
        w.write(table.concat(out))
        w.close()
        return ("replaced %d occurrence(s) in %s"):format(#positions, path)
    end,
}
