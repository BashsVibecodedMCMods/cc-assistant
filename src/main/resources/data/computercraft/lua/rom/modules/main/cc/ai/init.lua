--- Conversations with an AI model, with tools (docs/LUA_API.md section 2).
--
-- @usage
--   local ai = require("cc.ai")
--   local chat = ai.Conversation.new { tools = require("cc.ai.tools").load() }
--   print(chat:say("What files are on this computer?"))
-- @module cc.ai
local expect = require("cc.expect").expect

local M = {}
local native = _G.ai
if type(native) ~= "table" then error("the ai API is not available on this computer", 0) end

local DEFAULT_SYSTEM = [[
You are an assistant running inside a ComputerCraft: Tweaked computer in Minecraft (computer #%d%s). You can only act
through the tools you are given, which work on this game computer's own files and Lua environment; you have no access to
any real computer, the internet, or the player's machine. The screen is about 51 characters wide, so keep replies short
and plain (no Markdown tables). If a tool call is denied, tell the user what you wanted to do instead of retrying.]]

--- Send one request and wait for its result. Ctrl+T cancels it and is passed on.
-- @tparam table body A request (docs/LUA_API.md, "Request body").
-- @treturn table|nil The response, or nil.
-- @treturn table|string The info table, or an error code.
-- @treturn[opt] string The error message.
function M.send(body)
    expect(1, body, "table")
    local id, code, msg = native.request(body)
    if not id then return nil, code, msg end
    while true do
        local event, rid, a, b = os.pullEventRaw()
        if event == "terminate" then
            native.cancel(id)
            error("Terminated", 0)
        elseif event == "ai_response" and rid == id then
            return a, b
        elseif event == "ai_error" and rid == id then
            return nil, a, b
        end
    end
end

local Conversation = {}
Conversation.__index = Conversation
M.Conversation = Conversation

--- Create a conversation (docs/LUA_API.md, "ai.Conversation.new").
function Conversation.new(options)
    expect(1, options, "table", "nil")
    options = options or {}
    local label = os.getComputerLabel()
    local self = setmetatable({
        messages = {},
        system = options.system or DEFAULT_SYSTEM:format(os.getComputerID(), label and (", labelled \"" .. label .. "\"") or ""),
        tools = options.tools,
        profile = options.profile,
        approve = options.approve,
        maxTurns = options.maxTurns or 25,
        onText = options.onText,
        onToolCall = options.onToolCall,
        onToolResult = options.onToolResult,
        _pending = false,
        _pendingText = nil,
    }, Conversation)
    return self
end

local function body_for(self)
    local body = { system = self.system, messages = self.messages, profile = self.profile }
    if self.tools and #self.tools.list > 0 then body.tools = self.tools:definitions() end
    return body
end

local function final_text(response)
    local parts = {}
    for _, block in ipairs(response.content or {}) do
        if block.type == "text" then parts[#parts + 1] = block.text end
    end
    return table.concat(parts, "\n")
end

local function run_tools(self, response)
    local results = {}
    local maxCalls = (self.tools and self.tools.limits and self.tools.limits.maxToolCallsPerTurn) or 16
    local n = 0
    for _, block in ipairs(response.content or {}) do
        if block.type == "tool_use" then
            n = n + 1
            local content, isError
            if n > maxCalls then
                content, isError = "skipped: too many tool calls in one turn", true
            elseif not self.tools then
                content, isError = "no tools are available", true
            else
                local call = { id = block.id, name = block.name, input = block.input,
                               destructive = self.tools:isDestructive(block.name, block.input or {}) }
                if self.onToolCall then self.onToolCall(call) end
                content, isError = self.tools:call(block.name, block.input, self.approve, block.input_error)
                if self.onToolResult then self.onToolResult(call, content, isError) end
            end
            results[#results + 1] = { type = "tool_result", tool_use_id = block.id, content = content, is_error = isError or nil }
        end
    end
    return results
end

-- Runs requests from the current history. `checkpoint` is the history length to go back to on failure.
local function run(self, checkpoint, text)
    for _ = 1, self.maxTurns do
        local response, info, msg = M.send(body_for(self))
        if not response then
            for i = #self.messages, checkpoint + 1, -1 do self.messages[i] = nil end
            self._pending = true
            self._pendingText = text
            return nil, info, msg
        end
        self.messages[#self.messages + 1] = { role = "assistant", content = response.content }
        text = nil -- the user's message is now answered; a later failure resumes from tool results
        if self.onText then
            for _, block in ipairs(response.content or {}) do
                if block.type == "text" and block.text ~= "" then self.onText(block.text) end
            end
        end
        local hasCalls = false
        for _, block in ipairs(response.content or {}) do if block.type == "tool_use" then hasCalls = true end end
        if not hasCalls then
            self._pending, self._pendingText = false, nil
            return final_text(response), response
        end
        self.messages[#self.messages + 1] = { role = "user", content = run_tools(self, response) }
        checkpoint = #self.messages
    end
    self._pending, self._pendingText = true, nil
    return nil, "too_many_turns", ("Stopped after %d requests; call retry() to continue"):format(self.maxTurns)
end

--- Send a message and run tool calls until the model is done.
-- @treturn string|nil The final reply text, or nil.
-- @treturn table|string The last response, or an error code.
-- @treturn[opt] string The error message.
function Conversation:say(text)
    expect(1, text, "string")
    local before = #self.messages
    self.messages[#self.messages + 1] = { role = "user", content = text }
    return run(self, before, text)
end

--- Is the conversation waiting to be retried after a failure?
function Conversation:pending()
    return self._pending
end

--- Continue after a failure (for example once the owner is back online).
function Conversation:retry()
    if not self._pending then return nil, "nothing_pending", "There is nothing to retry" end
    if self._pendingText then return self:say(self._pendingText) end
    return run(self, #self.messages, nil)
end

--- Forget the history.
function Conversation:reset()
    self.messages, self._pending, self._pendingText = {}, false, nil
end

--- Save the history (and a pending message) to a file on this computer.
function Conversation:save(path)
    expect(1, path, "string")
    local dir = fs.getDir(path)
    if dir ~= "" and not fs.exists(dir) then fs.makeDir(dir) end
    local data = textutils.serialise({ version = 1, messages = self.messages, pendingText = self._pendingText,
                                       pending = self._pending }, { compact = true })
    local h = assert(fs.open(path, "wb"))
    h.write(data)
    h.close()
end

--- Load a conversation saved with :save().
function Conversation.load(path, options)
    expect(1, path, "string")
    local h = fs.open(path, "rb")
    if not h then return nil, "no saved conversation at " .. path end
    local data = textutils.unserialise(h.readAll() or "")
    h.close()
    if type(data) ~= "table" or type(data.messages) ~= "table" then return nil, path .. " is not a saved conversation" end
    local self = Conversation.new(options)
    self.messages = data.messages
    self._pending = data.pending == true
    self._pendingText = data.pendingText
    return self
end

return M
