--- Tool discovery and invocation for AI conversations (docs/TOOLS.md).
--
-- Tools are Lua files in /rom/tools (operator-provided, exposed under their own name) and /tools (user-provided,
-- exposed as "user_<name>"). They run on this computer with this computer's normal rights.
-- @module cc.ai.tools
local expect = require("cc.expect").expect
local capture = require("cc.ai.capture")

local M = {}
local native = _G.ai

local NAME_PATTERN = "^[a-z][a-z0-9_]*$"
local MAX_NAME = 48
local MAX_TOOLS = 64
local DEFAULT_TIMEOUT = 5

settings.define("ai.auto_approve", {
    description = "Run AI tool calls that change things without asking first.",
    default = false, type = "boolean",
})
settings.define("ai.disabled_tools", {
    description = "Comma-separated tool names the AI may not use on this computer, e.g. run_shell,user_nuke",
    default = "", type = "string",
})

local function limits()
    local ok, l = pcall(native.getLimits)
    if ok and type(l) == "table" then return l end
    return { maxToolSeconds = 30, maxToolOutputBytes = 16384, maxToolCallsPerTurn = 16, allowUserTools = true,
             allowDestructiveTools = true, disabledRomTools = {} }
end

--- Is a normalised path (no leading slash) one whose change could run code at boot, add tools, or change settings?
function M.isProtected(path)
    local p = path:gsub("^/+", "")
    return p == "startup" or p == "startup.lua" or p:find("^startup/") ~= nil
        or p:find("^disk%d*/startup$") ~= nil or p:find("^disk%d*/startup%.lua$") ~= nil or p:find("^disk%d*/startup/") ~= nil
        or p == "tools" or p:find("^tools/") ~= nil
        or p == ".settings"
end

--- Check and normalise a path from the model (docs/TOOLS.md section 6).
-- @treturn string|nil The absolute path, or nil and an error.
-- @treturn table|string Info `{ protected = boolean }`, or the error message.
function M.resolve(path, opts)
    opts = opts or {}
    if type(path) ~= "string" then return nil, "path must be a string" end
    if #path == 0 or #path > 255 then return nil, "path must be 1-255 characters" end
    if path:find("%c") then return nil, "path contains control characters" end
    local ok, p = pcall(fs.combine, "", path)
    if not ok then return nil, "invalid path" end
    if p == ".." or p:sub(1, 3) == "../" then return nil, "path goes outside the computer" end
    if opts.write and (p == "rom" or p:sub(1, 4) == "rom/") then return nil, "/rom is read-only" end
    return "/" .. p, { protected = M.isProtected(p) }
end

local function split_list(s)
    local out = {}
    for item in tostring(s or ""):gmatch("[^,%s]+") do out[item] = true end
    return out
end

local function sorted_lua_files(dir)
    if not fs.isDir(dir) then return {} end
    local files = {}
    for _, f in ipairs(fs.list(dir)) do
        if f:sub(-4) == ".lua" and not fs.isDir(fs.combine(dir, f)) then files[#files + 1] = f end
    end
    table.sort(files)
    return files
end

--- Run `fn` with a time limit. Returns pcall-style results, or false and "timed out after Ns".
-- Ctrl+T (terminate) is passed on rather than swallowed.
local function run_limited(fn, seconds)
    local result, finished
    local screen = term.current()
    parallel.waitForAny(function()
        result = table.pack(pcall(fn))
        finished = true
    end, function()
        sleep(seconds)
    end)
    -- An abandoned tool may have left the terminal redirected to a capture buffer.
    if term.current() ~= screen then term.redirect(screen) end
    if not finished then return false, ("timed out after %gs"):format(seconds) end
    if not result[1] and result[2] == "Terminated" then error("Terminated", 0) end
    return table.unpack(result, 1, result.n)
end
M.runLimited = run_limited

local function make_env(shell)
    local env = setmetatable({ shell = shell }, { __index = _G })
    env.require, env.package = require("cc.require").make(env, "/")
    return env
end

local function check_definition(def)
    if type(def) ~= "table" then return nil, "the file must return a table" end
    if def.available == false then return false end -- the tool chose not to load here (e.g. no shell)
    if type(def.description) ~= "string" or #def.description == 0 or #def.description > 1024 then
        return nil, "description must be a string of 1-1024 bytes"
    end
    if type(def.run) ~= "function" then return nil, "run must be a function" end
    local schema = def.input_schema or { type = "object", properties = {} }
    if type(schema) ~= "table" or schema.type ~= "object" then return nil, "input_schema must be a table with type = \"object\"" end
    if def.destructive ~= nil and type(def.destructive) ~= "boolean" and type(def.destructive) ~= "function" then
        return nil, "destructive must be a boolean or a function"
    end
    if def.timeout ~= nil and (type(def.timeout) ~= "number" or def.timeout <= 0) then return nil, "timeout must be a positive number" end
    return { description = def.description, input_schema = schema, run = def.run, destructive = def.destructive or false,
             timeout = def.timeout }
end

local Toolset = {}
Toolset.__index = Toolset

--- Load tools from /rom/tools then /tools (docs/TOOLS.md section 2). Never errors because of a bad tool file.
-- @tparam[opt] table options `{ shell = shell, only = {names}, except = {names} }`
function M.load(options)
    expect(1, options, "table", "nil")
    options = options or {}
    local shell = options.shell or rawget(_ENV, "shell")
    local lim = limits()
    local set = setmetatable({ list = {}, byName = {}, errors = {}, shell = shell, limits = lim }, Toolset)

    local rom_disabled = {}
    for _, n in ipairs(lim.disabledRomTools or {}) do rom_disabled[n] = true end
    local local_disabled = split_list(settings.get("ai.disabled_tools"))
    local only = options.only and {} or nil
    if only then for _, n in ipairs(options.only) do only[n] = true end end
    local except = {}
    for _, n in ipairs(options.except or {}) do except[n] = true end

    local sources = { { dir = "/rom/tools", prefix = "", rom = true } }
    if lim.allowUserTools ~= false then sources[2] = { dir = "/tools", prefix = "user_", rom = false } end

    for _, source in ipairs(sources) do
        for _, file in ipairs(sorted_lua_files(source.dir)) do
            local path = fs.combine(source.dir, file)
            local base = file:sub(1, -5)
            local function problem(msg) set.errors[#set.errors + 1] = { path = "/" .. path, message = msg } end
            if not base:find(NAME_PATTERN) or #base > MAX_NAME then
                problem("file name must be lowercase letters, digits and _, start with a letter, and be at most " .. MAX_NAME .. " characters")
            elseif source.rom and base:sub(1, 5) == "user_" then
                problem("ROM tool names may not start with user_")
            else
                local name = source.prefix .. base
                local fn, err = loadfile("/" .. path, "t", make_env(shell))
                if not fn then
                    problem(err)
                else
                    local ok, def = run_limited(fn, 1)
                    local tool, why
                    if ok then tool, why = check_definition(def) else why = tostring(def) end
                    if tool == false then
                        -- unavailable in this program; not an error
                    elseif not tool then
                        problem(why)
                    elseif (source.rom and rom_disabled[base]) or local_disabled[name] or except[name] or (only and not only[name]) then
                        -- filtered out on purpose; not an error
                    elseif lim.allowDestructiveTools == false and tool.destructive == true then
                        -- the operator turned destructive tools off
                    elseif #set.list >= MAX_TOOLS then
                        problem("more than " .. MAX_TOOLS .. " tools; this one was dropped")
                    else
                        tool.name, tool.path, tool.rom = name, "/" .. path, source.rom
                        set.list[#set.list + 1] = tool
                        set.byName[name] = tool
                    end
                end
            end
        end
    end
    return set
end

--- The tool definitions for a request's `tools` field.
function Toolset:definitions()
    local out = {}
    for i, t in ipairs(self.list) do
        out[i] = { name = t.name, description = t.description, input_schema = t.input_schema }
    end
    return out
end

local TYPE_CHECKS = {
    string = function(v) return type(v) == "string" end,
    number = function(v) return type(v) == "number" end,
    integer = function(v) return type(v) == "number" and v == math.floor(v) end,
    boolean = function(v) return type(v) == "boolean" end,
    object = function(v) return type(v) == "table" end,
    array = function(v) return type(v) == "table" end,
}

local function check_input(schema, input)
    for _, key in ipairs(schema.required or {}) do
        if input[key] == nil then return nil, ("missing required argument '%s'"):format(key) end
    end
    for key, prop in pairs(schema.properties or {}) do
        local v = input[key]
        local check = type(prop) == "table" and TYPE_CHECKS[prop.type]
        if v ~= nil and check and not check(v) then return nil, ("argument '%s' must be a %s"):format(key, prop.type) end
    end
    return true
end

local function to_text(...)
    local n = select("#", ...)
    local v, extra = ...
    if n == 0 or v == nil then
        if type(extra) == "string" then return extra, true end -- nil, "message"
        return "ok", false
    end
    if v == true then return "ok", false end
    if type(v) == "string" then return v, false end
    if type(v) == "table" then
        local ok, json = pcall(textutils.serialiseJSON, v)
        if ok then return json, false end
        return "the tool returned a table that can't be turned into JSON: " .. tostring(json), true
    end
    return tostring(v), false
end

local function clean_error(err, path)
    local msg = tostring(err)
    -- Remove "file:line: " prefixes from errors raised inside the tool file.
    local file = path and path:gsub("^/", "")
    if file then msg = msg:gsub("^/?" .. file:gsub("%p", "%%%0") .. ":%d+: ", "") end
    return msg
end

local function truncate(text, limit)
    if #text <= limit then return text end
    return text:sub(1, limit) .. ("\n...[truncated %d bytes]"):format(#text - limit)
end

--- Is this call destructive (docs/TOOLS.md section 5)?
function Toolset:isDestructive(name, input)
    local tool = self.byName[name]
    if not tool then return false end
    if tool.destructive == true then return true end
    if type(tool.destructive) == "function" then
        local ok, result = pcall(tool.destructive, input, self:context(tool))
        return not ok or result == true -- if the check fails, treat the call as destructive
    end
    return false
end

function Toolset:context(tool)
    return {
        computerId = os.getComputerID(),
        shell = self.shell,
        resolve = M.resolve,
        limits = self.limits,
        toolName = tool and tool.name,
        capture = capture,
        runLimited = run_limited,
    }
end

--- Run one tool call. `approve(call)` decides destructive calls (nil means deny, unless ai.auto_approve is set).
-- @treturn string content
-- @treturn boolean isError
function Toolset:call(name, input, approve, inputError)
    local tool = self.byName[name]
    if not tool then return ("unknown tool '%s'"):format(tostring(name)), true end
    if inputError then return "invalid arguments: " .. tostring(inputError), true end
    if type(input) ~= "table" then return "arguments must be an object", true end
    local ok, err = check_input(tool.input_schema, input)
    if not ok then return err, true end

    if self:isDestructive(name, input) and not settings.get("ai.auto_approve") then
        local allowed = false
        if approve then
            local okA, result = pcall(approve, { name = name, input = input, destructive = true })
            if not okA and result == "Terminated" then error("Terminated", 0) end
            allowed = okA and result == true
        end
        if not allowed then return "denied: this action needs the user's approval and was not approved", true end
    end

    local seconds = math.min(tool.timeout or DEFAULT_TIMEOUT, self.limits.maxToolSeconds or 30)
    local ctx = self:context(tool)
    local results = table.pack(run_limited(function() return tool.run(input, ctx) end, seconds))
    local limit = self.limits.maxToolOutputBytes or 16384
    if not results[1] then return truncate(clean_error(results[2], tool.path), limit), true end
    local text, isError = to_text(table.unpack(results, 2, results.n))
    if isError then text = clean_error(text, tool.path) end
    return truncate(text, limit), isError
end

return M
