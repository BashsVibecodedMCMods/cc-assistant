# Lua API (design for review)

The API isn't tied to any provider: the same Lua code works whether the answer comes from Claude, a NanoGPT model,
OpenRouter, Z.AI, or a local Ollama model. The provider is picked by configuration, never by Lua (PROVIDERS.md).

Two layers:
1. **`ai`**: a global on every computer, provided by the mod in Java. It's low level and doesn't block: it sends one
   request and later delivers the result as an event, the same way CC's `http.request` works.
2. **`cc.ai`**: a ROM Lua module (`require("cc.ai")`). It blocks, runs the tool loop, and keeps conversation history.
   Most programs only need this one.

The rest of this file uses CC conventions:
- Functions return `nil, code, message` for expected failures.
- Functions `error()` only on programmer mistakes, such as wrong argument types.
- Asynchronous results arrive as events.

## Text encoding

Lua strings are byte strings. Text sent to a model is read as **UTF-8**, and invalid sequences become U+FFFD. Text coming
back is UTF-8. The `ai` program swaps non-ASCII characters for `?` when printing, because CC's font can't show them. The
stored strings are left unchanged.

## 1. The `ai` global (native)

### `ai.isAvailable()` -> `boolean, code?, message?`
Says whether `ai.request` would currently be accepted from this computer, not counting rate limits.
Example: `false, "not_linked", "Run 'ai link' and type the code in chat."`

### `ai.status()` -> `table`
```lua
{
  backend = "player",      -- "none" | "server" | "player" | "either" (server config)
  linked = true,
  owner = "Steve",         -- nil when not linked
  ownerOnline = true,      -- player and either backends only
  ownerState = "ready",    -- player and either backends: see "When the owner is offline" below
  inFlight = 0,            -- this computer's requests in progress
  computerId = 12,
}
```
It never shows provider names, URLs or which profiles exist.

### `ai.link()` -> `code, expiresIn`
Creates a one-time code (6 characters, valid 300 seconds). The player who should own the computer types
`/assistant link <code>` in chat, and the computer then gets an `ai_linked, ownerName` event.
- Calling `link()` again replaces the earlier code.
- Linking a computer that already has an owner moves it to the new player (SECURITY.md §3).

### `ai.unlink()` -> `boolean`
Removes the link and fails any requests in progress with `not_linked`. Returns whether a link existed.

### `ai.request(body)` -> `id` | `nil, code, message`
Checks `body` (see [Request body](#request-body)), sends it in the background, and returns a request id (an integer).
Exactly one event for that id follows:
- `ai_response, id, response, info`
  - `response`: see [Response](#response).
  - `info = { backend = "server"|"player", owner = "Steve", profile = "nano" }`. `profile` is the profile name on the
    player backend; on the server backend it is the operator's provider name.
- `ai_error, id, code, message`

The checks done before an id is returned (bad body, size limits, not linked, rate limited, busy, command computer) come
back as `nil, code, message`, with no event.

### `ai.cancel(id)` -> `boolean`
Cancels a request in progress. The HTTP call is aborted on whichever machine is making it, then
`ai_error, id, "cancelled", ...` follows. Returns false if the id isn't in progress on this computer.

### `ai.getLimits()` -> `table`
```lua
{
  maxRequestBytes = 262144, maxTokens = 8192, requestTimeout = 180,
  maxToolSeconds = 30, maxToolOutputBytes = 16384, maxToolCallsPerTurn = 16,
  allowUserTools = true, allowDestructiveTools = true,
  disabledRomTools = { },
}
```
`maxTokens` is the server's cap. On the player backend, the owner's profile may set a lower one.

### Lifecycle
- When a computer shuts down or reboots, all of its requests are cancelled and no further events arrive.
- The link belongs to the computer id, so it survives the computer being moved or broken, like its files do.

### When the owner is offline

On the `player` backend, a computer can only use AI while its owner is **online, with the mod on their client, and the
feature turned on**. The server never queues requests for later, and never falls back to anyone else's provider: that
would spend money without the owner present. A computer whose owner is away fails fast and clearly, and nothing is lost.

**Owner states** (`ai.status().ownerState`):

| State | Meaning | Error code a request gets |
|---|---|---|
| `ready` | Online, client mod present, feature on, and a usable default profile | (accepted) |
| `offline` | Not logged in to this server | `owner_offline` |
| `no_client` | Online, but their client doesn't have the mod | `owner_no_client` |
| `disabled` | Online with the mod, but the feature is off or no usable profile is set | `owner_disabled` |
| `unlinked` | The computer has no owner | `not_linked` |

The server knows the state without asking the client each time:
- Logging in and out are server events.
- The client sends a one-bit "ready / not ready" status when it joins, and whenever the player changes the switch or
  their profiles. This tells the server nothing about the providers, models or keys.

**What Lua sees in each situation:**
1. **Owner not ready before the request:** `ai.request` returns `nil, code, message` at once, with no event. Example:
   `nil, "owner_offline", "Steve is offline. This computer uses Steve's AI provider, so it can't answer until they're back."`
   `ai.isAvailable()` returns the same code and message, for checking ahead of time.
2. **Owner logs out, or turns the feature off, while a request is in progress:** that request ends at once with
   `ai_error, id, "owner_offline"` (or `"owner_disabled"`). It doesn't wait for the timeout. On the client the HTTP call
   is aborted.
3. **Waiting for the owner:** when a linked owner's state changes, every computer linked to them gets
   `ai_owner, state, ownerName`. A program can sleep on that event instead of polling:
   ```lua
   while select(2, ai.isAvailable()) == "owner_offline" do
     print("Waiting for " .. ai.status().owner .. " to come online (Ctrl+T to stop)...")
     os.pullEvent("ai_owner")
   end
   ```
   The event is only sent when the state actually changes, so frequent rejoining can't flood computers.
4. **`either` backend:** when the owner isn't `ready`, requests go to the server's default provider, and
   `info.backend == "server"` says so. Operators who don't want that set `backend = "player"`.

The error message names the owner, as `ai.status()` already does. It never says why they are unavailable beyond the
state above, and never mentions their providers.

### Request body

The mod's own format, the same for every provider (PROVIDERS.md §2). **Only these fields are accepted.** Any other field
gives `bad_request`:

| Field | Rules |
|---|---|
| `messages` | Required. An array of `{ role = "user"|"assistant", content = string or array of blocks }`. Blocks: `text`, `tool_use`, `tool_result`, and `reasoning` blocks copied back unchanged from earlier responses. |
| `system` | Optional string. |
| `tools` | Optional. An array of `{ name, description, input_schema }`. Names must match `^[a-zA-Z0-9_-]{1,64}$`. |
| `tool_choice` | Optional. `"auto"` (the default) or `"none"`. |
| `max_tokens` | Optional. Capped by the server and by the profile, which also gives the default. |
| `stop_sequences` | Optional. Up to 4 strings. |
| `profile` | Optional string. Picks one of the owner's profiles by name, on the player backend only. The owner's settings decide whether that is allowed. On the server backend it picks one of the operator's providers, if the operator allows it. An unknown or disallowed name gives `bad_profile`. |

**The model, effort and provider are never set by Lua.** They come from the profile or the operator's provider, so
whoever pays controls the cost.

How tables become JSON:
- A table whose keys are exactly `1..n` becomes an array; any other table becomes an object.
- An **empty** table becomes `[]` under `messages`, `content`, `tools`, `required`, `enum` and `stop_sequences`, and `{}`
  elsewhere. So `input_schema = { type = "object", properties = {} }` works.
- Integers stay integers. NaN and infinity are rejected. Nesting deeper than 64 levels is rejected.

### Response
```lua
{
  model = "z-ai/glm-5.3",
  stop_reason = "tool_use",   -- "end_turn" | "tool_use" | "max_tokens" | "refusal" | "stop_sequence" | "other"
  content = {
    { type = "reasoning", text = "...", protocol = "openai" },  -- only some providers send this
    { type = "text", text = "I'll read it." },
    { type = "tool_use", id = "call_1", name = "read_file", input = { path = "notes.txt" } },
  },
  usage = { input_tokens = 812, output_tokens = 95 },          -- missing if the provider sent none
}
```
- A `tool_use` whose arguments the provider sent as invalid JSON has `input = {}` and an `input_error` string. The tool
  loader sends that error back to the model instead of running the tool.
- **Copy `content` back into the next request unchanged.** Reasoning blocks are replayed only to the same protocol,
  where some models need them, and dropped otherwise (PROVIDERS.md §2).

### Error codes

| Code | When |
|---|---|
| `not_configured` | Backend is `none`, or the server provider's key variable is empty. |
| `not_linked` | The computer has no owner, and the backend needs one. |
| `owner_offline` | Player backend: the owner isn't online, or logged out while a request was in progress. See "When the owner is offline". |
| `owner_no_client` | Player backend: the owner's game client doesn't have the mod. |
| `owner_disabled` | Player backend: the owner's client has the feature off, has no usable profile, or refused because of the owner's own limits. The message says which. |
| `bad_profile` | `profile` names a profile that doesn't exist or may not be chosen. |
| `command_computer` | Command computers are blocked (`allowCommandComputers = false`). |
| `bad_request` | The body broke a rule above. The message names the field. |
| `too_large` | The request is over `maxRequestBytes` or a count limit. |
| `rate_limited` | A per-player or per-computer rate limit was hit. The message gives the retry delay. |
| `busy` | A concurrency limit was hit. |
| `timeout` | No answer within `requestTimeout`. |
| `cancelled` | `ai.cancel`, `ai.unlink`, or the owner unlinked the computer. |
| `provider_error` | The provider answered with an error. The message has the HTTP status and the provider's error text, shortened, without URLs. |
| `network_error` | The provider couldn't be reached. |
| `bad_response` | The provider's reply couldn't be understood, or was over `maxResponseBytes`. |

## 2. The `cc.ai` module (ROM Lua)

```lua
local ai = require("cc.ai")
```

### `ai.send(body)` -> `response, info` | `nil, code, message`
A blocking `ai.request` that waits for its event. If the program is terminated (Ctrl+T) while it waits, the request is
cancelled and the terminate is passed on.

### `ai.Conversation.new(options)` -> `Conversation`
| Option | Default | Meaning |
|---|---|---|
| `system` | a short built-in prompt describing the CC computer | system prompt |
| `tools` | `nil` (no tools) | a toolset from `require("cc.ai.tools").load{...}` |
| `profile` | `nil` (the owner's default) | sent as the request's `profile` |
| `approve` | `nil` | `function(call) -> boolean`, called before destructive tool calls. Without it, destructive calls are **denied**. |
| `maxTurns` | 25 | the most requests one `say` may make |
| `onText`, `onToolCall`, `onToolResult` | `nil` | callbacks for showing progress |

### `conversation:say(text)` -> `finalText, response` | `nil, code, message`
1. Adds a user message and sends the conversation.
2. While `stop_reason == "tool_use"`, it runs every `tool_use` block of the reply in order, sends all the results back in
   **one** user message, and asks again.
3. It stops at `end_turn`, `max_tokens`, `refusal`, `stop_sequence` or `other`, or after `maxTurns` requests, which gives
   the error `too_many_turns`.

If a request fails partway through, for example because the owner went offline in the middle of a tool loop, the history
is left at the **last consistent point**: either before `say`, or right after the latest complete round of tool results.
Tool rounds that already ran are kept, because their side effects (files written, and so on) really happened.
`conversation:retry()` resends from that point once the problem is fixed, and `conversation:pending()` says whether a
retry is needed.

Other members: `conversation:retry()`, `conversation:pending()`, `conversation.messages`, `conversation:save(path)`, `ai.Conversation.load(path, options)`,
`conversation:reset()`.

### Example
```lua
local ai = require("cc.ai")
local tools = require("cc.ai.tools").load()       -- /rom/tools, then /tools
for _, problem in ipairs(tools.errors) do printError(problem.path .. ": " .. problem.message) end

local chat = ai.Conversation.new {
  tools = tools,
  approve = function(call)
    write(("Allow %s %s? [y/N] "):format(call.name, textutils.serialiseJSON(call.input)))
    return read():lower() == "y"
  end,
  onToolCall = function(call) print("> " .. call.name) end,
}

local answer, err, msg = chat:say("Make hello.txt that says hi, then list the files.")
if answer then print(answer) else printError(err .. ": " .. msg) end
```

### Low-level example
```lua
local id, code, msg = ai.request {
  max_tokens = 512,
  messages = { { role = "user", content = "Say hi in five words." } },
}
if not id then error(code .. ": " .. msg, 0) end
while true do
  local event, rid, a, b = os.pullEvent()
  if event == "ai_response" and rid == id then
    for _, block in ipairs(a.content) do if block.type == "text" then print(block.text) end end
    break
  elseif event == "ai_error" and rid == id then
    printError(a .. ": " .. b) break
  end
end
```

## 3. The `ai` program

`rom/programs/ai.lua`:
- `ai`: start an interactive chat with all tools loaded.
- `ai <prompt...>`: answer one prompt and exit.
- `ai --no-tools`: chat without tools, for models that can't use tools.
- `ai --profile <name>`: use one of the owner's profiles.
- `ai link`: show a link code.
- `ai status`: show the link, the backend, the owner's state and the limits.
- `ai --resume`: continue the last saved chat.

**When the owner isn't available**, the program never crashes or prints a stack trace:
- **One-shot mode** (`ai <prompt>`) prints the message in the error colour and exits with failure, so scripts calling
  `shell.run("ai ...")` can check the result.
- **Chat mode** prints the message and offers `[W]ait for <owner> / [Q]uit`.
  - *Wait* listens for `ai_owner` and retries the unanswered message as soon as the owner is `ready`.
  - The conversation so far is kept either way.
  - *Quit* first saves it to `/.ai/last_chat.json` and prints `Saved. Run 'ai --resume' to continue later.`
- `ai --resume` reloads that file.
- On startup, chat mode checks `ai.isAvailable()` first and shows the same message before you type anything.

Destructive tool calls show what will happen and ask `[y/N]`. The setting `ai.auto_approve` (default `false`) skips that
prompt on this computer. Ctrl+T cancels the request in progress.
