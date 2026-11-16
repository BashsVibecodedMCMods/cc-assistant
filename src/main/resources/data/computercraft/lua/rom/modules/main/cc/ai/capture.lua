--- Terminal capture for the run_lua and run_shell tools: a terminal object that records text instead of drawing it.
-- @module cc.ai.capture
local M = {}

local WIDTH, HEIGHT = 51, 999

--- Create a capture terminal. `limit` is the most bytes kept.
-- @treturn table term A term-like object to pass to term.redirect.
-- @treturn function text Returns the captured text, and the number of bytes dropped.
function M.create(limit)
    local parent = term.current()
    local parts, size, dropped = {}, 0, 0
    local x, y = 1, 1
    local fg, bg = colours.white, colours.black
    local blink = false

    local function add(s)
        if s == "" then return end
        if size + #s > limit then
            local room = math.max(0, limit - size)
            dropped = dropped + (#s - room)
            s = s:sub(1, room)
        end
        if s ~= "" then
            parts[#parts + 1] = s
            size = size + #s
        end
    end

    local t = {}
    function t.write(text)
        text = tostring(text)
        add(text)
        x = x + #text
    end
    function t.blit(text, _, _) t.write(text) end
    function t.clear() end
    function t.clearLine() end
    function t.getCursorPos() return x, y end
    function t.setCursorPos(nx, ny)
        nx, ny = math.floor(nx), math.floor(ny)
        if ny ~= y then add(string.rep("\n", math.max(1, math.min(ny - y, 3)))) end
        x, y = nx, ny
    end
    function t.getSize() return WIDTH, HEIGHT end
    function t.scroll(n)
        if n > 0 then add(string.rep("\n", math.min(n, 3))) end
    end
    function t.setCursorBlink(b) blink = b end
    function t.getCursorBlink() return blink end
    function t.isColour() return parent.isColour() end
    t.isColor = t.isColour
    function t.setTextColour(c) fg = c end
    t.setTextColor = t.setTextColour
    function t.getTextColour() return fg end
    t.getTextColor = t.getTextColour
    function t.setBackgroundColour(c) bg = c end
    t.setBackgroundColor = t.setBackgroundColour
    function t.getBackgroundColour() return bg end
    t.getBackgroundColor = t.getBackgroundColour
    function t.setPaletteColour(...) end
    t.setPaletteColor = t.setPaletteColour
    function t.getPaletteColour(c) return parent.getPaletteColour(c) end
    t.getPaletteColor = t.getPaletteColour

    local function text()
        return table.concat(parts), dropped
    end
    return t, text
end

return M
