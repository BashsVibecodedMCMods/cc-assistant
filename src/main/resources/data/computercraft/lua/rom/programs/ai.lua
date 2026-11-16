-- The `ai` program: chat with an AI model that can use tools on this computer (docs/LUA_API.md section 3).
local args = { ... }

local SAVE_PATH = "/.ai/last_chat"
local OWNER_CODES = { owner_offline = true, owner_no_client = true, owner_disabled = true }

if type(ai) ~= "table" then
    printError("The ai API isn't available on this computer.")
    return
end

local function colour(c)
    if term.isColour() then term.setTextColour(c) end
end

local function say(text, c)
    colour(c or colours.white)
    print(text)
    colour(colours.white)
end

-- CC's font can't show most non-ASCII characters; show them as '?' (the stored text is unchanged).
local function printable(s)
    return (tostring(s):gsub("[\xC2-\xF4][\x80-\xBF]*", "?"):gsub("[\x80-\xFF]", "?"))
end

local function usage()
    print("Usage:")
    print("  ai                  chat, with tools")
    print("  ai <prompt...>      answer one prompt and exit")
    print("  ai --no-tools       chat without tools")
    print("  ai --profile <name> use one of the owner's profiles")
    print("  ai --resume         continue the last saved chat")
    print("  ai link             link this computer to you")
    print("  ai status           show link, backend and limits")
end

-- ---- Subcommands ----

local function cmd_link()
    local code, seconds = ai.link()
    say("Type this in chat within " .. math.floor(seconds / 60) .. " minutes:", colours.lightGrey)
    say("  /assistant link " .. code, colours.yellow)
    say("Waiting... (Ctrl+T to stop)", colours.lightGrey)
    local timer = os.startTimer(seconds)
    while true do
        local event, a = os.pullEvent()
        if event == "ai_linked" then
            say("Linked to " .. printable(a) .. ".", colours.lime)
            return
        elseif event == "timer" and a == timer then
            say("The code expired. Run 'ai link' again.", colours.orange)
            return
        end
    end
end

local function cmd_status()
    local s = ai.status()
    print("Computer:  #" .. s.computerId)
    print("Backend:   " .. s.backend)
    print("Linked to: " .. (s.linked and printable(s.owner) or "nobody (run 'ai link')"))
    if s.linked then print("Owner:     " .. tostring(s.ownerState)) end
    local ok, code, msg = ai.isAvailable()
    if ok then say("Ready.", colours.lime) else say(printable(msg) .. " (" .. code .. ")", colours.orange) end
    local l = ai.getLimits()
    print(("Limits:    %d tokens, %ds timeout, %ds per tool"):format(l.maxTokens, l.requestTimeout, l.maxToolSeconds))
end

if args[1] == "link" then return cmd_link() end
if args[1] == "status" then return cmd_status() end
if args[1] == "help" or args[1] == "--help" or args[1] == "-h" then return usage() end

-- ---- Options ----

local useTools, profile, resume = true, nil, false
local promptWords = {}
local i = 1
while i <= #args do
    local a = args[i]
    if a == "--no-tools" then
        useTools = false
    elseif a == "--profile" then
        i = i + 1
        profile = args[i]
        if not profile then return usage() end
    elseif a == "--resume" then
        resume = true
    else
        promptWords[#promptWords + 1] = a
    end
    i = i + 1
end
local oneShot = #promptWords > 0
local prompt = table.concat(promptWords, " ")

-- ---- Conversation ----

local aiLib = require("cc.ai")
local tools
if useTools then
    tools = require("cc.ai.tools").load { shell = shell }
    for _, problem in ipairs(tools.errors) do
        say("Tool " .. problem.path .. ": " .. printable(problem.message), colours.orange)
    end
end

local function describe(call)
    local ok, json = pcall(textutils.serialiseJSON, call.input or {})
    json = ok and json or "?"
    if #json > 160 then json = json:sub(1, 157) .. "..." end
    return call.name .. " " .. printable(json)
end

local options = {
    tools = tools,
    profile = profile,
    approve = function(call)
        say("Allow " .. describe(call) .. "?", colours.yellow)
        write("[y/N] ")
        local answer = read()
        return answer ~= nil and answer:lower():sub(1, 1) == "y"
    end,
    onText = function(text) say(printable(text), colours.white) end,
    onToolCall = function(call)
        if not call.destructive then say("> " .. describe(call), colours.lightGrey) end
    end,
    onToolResult = function(call, content, isError)
        if isError then
            local first = printable(content):match("^[^\n]*")
            say("  " .. first, colours.red)
        end
    end,
}

local chat
if resume then
    local err
    chat, err = aiLib.Conversation.load(SAVE_PATH, options)
    if not chat then
        printError(err)
        return
    end
    say("Resumed (" .. #chat.messages .. " messages).", colours.lightGrey)
else
    chat = aiLib.Conversation.new(options)
end

local function saveChat()
    local ok, err = pcall(chat.save, chat, SAVE_PATH)
    if ok then say("Saved. Run 'ai --resume' to continue later.", colours.lightGrey) else printError(err) end
end

--- Wait until the owner is back, or the user quits. Returns true when ready.
local function waitForOwner()
    local status = ai.status()
    local name = status.owner and printable(status.owner) or "the owner"
    while true do
        write("[W]ait for " .. name .. " / [Q]uit: ")
        local answer = (read() or ""):lower()
        if answer:sub(1, 1) == "q" then return false end
        if answer:sub(1, 1) == "w" then break end
    end
    say("Waiting for " .. name .. "... (Ctrl+T to stop)", colours.lightGrey)
    while true do
        local ok, code = ai.isAvailable()
        if ok then
            say(name .. " is back.", colours.lime)
            return true
        end
        if not OWNER_CODES[code] then return true end -- a different problem: let the retry report it
        local event = os.pullEventRaw()
        if event == "terminate" then return false end
    end
end

--- Handle a failed say/retry. Returns true to keep chatting, false to stop.
local function handleFailure(code, msg)
    say(printable(msg) .. " (" .. tostring(code) .. ")", colours.red)
    if oneShot then return false end
    if OWNER_CODES[code] then
        if waitForOwner() then
            local answer, err, msg2 = chat:retry()
            if answer then return true end
            return handleFailure(err, msg2)
        end
        saveChat()
        return false
    end
    if chat:pending() then say("Type /retry to try again.", colours.lightGrey) end
    return true
end

-- ---- One-shot mode ----

if oneShot then
    local answer, code, msg = chat:say(prompt)
    if not answer then
        local ok, c, m = ai.isAvailable()
        error(printable(msg or m or "failed") .. " (" .. tostring(code or c) .. ")", 0)
    end
    return
end

-- ---- Interactive mode ----

do
    local ok, code, msg = ai.isAvailable()
    if not ok then
        say(printable(msg) .. " (" .. code .. ")", colours.orange)
        if OWNER_CODES[code] then
            if not waitForOwner() then return end
        elseif code == "not_linked" then
            say("Run 'ai link' first.", colours.lightGrey)
            return
        else
            return
        end
    end
end

say("Chat with AI. /exit to quit, /help for commands.", colours.lightGrey)
if tools then say(#tools.list .. " tools loaded.", colours.lightGrey) end
local history = {}
while true do
    colour(colours.yellow)
    write("> ")
    colour(colours.white)
    local line = read(nil, history)
    if line == nil then break end
    line = line:gsub("^%s+", ""):gsub("%s+$", "")
    if line ~= "" then
        history[#history + 1] = line
        if line == "/exit" or line == "/quit" then
            break
        elseif line == "/help" then
            print("/exit  quit        /reset  forget the chat")
            print("/save  save it     /retry  retry after an error")
        elseif line == "/reset" then
            chat:reset()
            say("Forgotten.", colours.lightGrey)
        elseif line == "/save" then
            saveChat()
        elseif line == "/retry" then
            local answer, code, msg = chat:retry()
            if not answer and not handleFailure(code, msg) then break end
        else
            local answer, code, msg = chat:say(line)
            if not answer and not handleFailure(code, msg) then break end
        end
    end
end
