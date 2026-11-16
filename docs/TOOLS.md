# Tools (design for review)

Tools are Lua files that run **on the CC computer**, using the computer's own Lua environment and rights. The model can only
*ask* for a tool call. The loader below decides whether the call runs, with what limits, and what the model sees back.

## 1. Where tools come from

| Location | Who provides it | Can players change it? | Exposed name |
|---|---|---|---|
| `/rom/tools/<name>.lua` | The mod and the server operator (datapack under `data/<namespace>/lua/rom/tools/`) | No, the ROM is read-only | `<name>` |
| `/tools/<name>.lua` | Whoever uses the computer | Yes, it's a normal folder on the computer | `user_<name>` |

**Operator ROM tools** are trusted defaults. Operators can add, replace or remove them with a datapack. When two datapacks
ship the same ROM file, the normal datapack override order decides; there's no merging.

**User tools** can do nothing a user couldn't already do by running a program on that computer. They are an
extension point, not a security boundary (see SECURITY.md §4).

## 2. Discovery (deterministic)

`require("cc.ai.tools").load(options)`:
1. Lists `/rom/tools`, then `/tools`. Only plain files ending in `.lua` count; folders and other files are ignored.
2. Within each folder, sorts by file name, by byte value. Tools are always given to the model in this order: all ROM tools,
   then all user tools.
3. The name is the file name without `.lua`. It must match `^[a-z][a-z0-9_]{0,47}$`. Any other file is skipped and
   reported. Requiring lowercase rules out two files that differ only in case.
4. **Namespacing:** user tools get the prefix `user_`. ROM tool names may not start with `user_`; one that does is skipped
   and reported. ROM and user names therefore can't collide, and the model can always tell which side a tool came from.
5. Each file is loaded with `loadfile(path, "t", env)` and called once with no arguments, inside `pcall`, with a 1-second
   limit. `env` is a fresh table that falls back to the program's environment. The file must return a table (the
   [tool definition](#3-tool-definition)). Load errors, invalid definitions, and files that take too long are skipped and
   reported.
6. Tools are then filtered, in this order:
   - Server `disabledRomTools` (operator) removes ROM tools by name.
   - Server `allowUserTools = false` removes every user tool.
   - Server `allowDestructiveTools = false` removes every tool that is destructive *by definition*
     (`destructive = true`).
   - The computer's setting `ai.disabled_tools` (a comma-separated list of exposed names, e.g.
     `run_shell,user_nuke`) removes those tools.
   - `options.only` / `options.except` (lists of exposed names) filter further for one program.
7. At most 64 tools are kept. Anything past that is reported and dropped, following the order from step 2.

The result:
```lua
local toolset = tools.load()
toolset.list     -- array of loaded tools, in order
toolset.byName   -- exposed name -> tool
toolset.errors   -- array of { path = "/tools/x.lua", message = "..." }; empty when everything loaded
toolset:definitions()                 -- array for the request's `tools` field
toolset:call(name, input, context)    -- runs one call; returns content, isError
```

`load` never raises because of a bad tool file; the problems end up in `errors`. The `ai` program prints them when it
starts.

## 3. Tool definition

```lua
-- /tools/weather.lua  ->  exposed to the model as "user_weather"
return {
  description = "Read the redstone weather sensor on the left side. Returns 'rain' or 'clear'.",
  input_schema = { type = "object", properties = {}, required = {} },   -- optional; this is the default
  destructive = false,        -- optional; boolean or function(input) -> boolean, see §5
  timeout = 2,                -- optional, in seconds; default 5, capped at the server's maxToolSeconds
  run = function(input, ctx)
    return redstone.getInput("left") and "rain" or "clear"
  end,
}
```

| Field | Required | Rules |
|---|---|---|
| `description` | yes | A string, 1 to 1024 bytes. It is what the model reads to decide when to use the tool. |
| `run` | yes | `function(input, ctx)` |
| `input_schema` | no | A JSON Schema with `type = "object"`. It is sent to the model as is. |
| `destructive` | no | Default `false`. |
| `timeout` | no | A number greater than 0. |
| `strict` | no | Sent through as the API's `strict` flag. |

## 4. Invocation

`toolset:call(name, input, ctx)`:
1. **Unknown name:** returns the error `unknown tool 'x'`. This happens if the model makes up a name or the tool was filtered
   out.
2. **Input check:** `input` must be a table. If the schema has `required` keys, they must be there, and properties that
   declare `type` of `string`, `number`, `integer`, `boolean`, `object` or `array` are checked against it. Anything
   deeper is up to the tool, which must treat `input` as untrusted.
3. **Destructive check** (§5). A denied call returns the error
   `denied: this action needs the user's approval and was not approved`, so the model can tell the user instead of
   retrying.
4. **Run:** `run(input, ctx)` runs in its own coroutine, inside `pcall`, raced with a timer using
   `parallel.waitForAny`. If the timer wins, the call returns the error `timed out after Ns`.
5. **Result:**
   - A string is sent as is.
   - A table is turned into JSON with `textutils.serialiseJSON`, or sent as an error if that fails.
   - `nil` or `true` becomes `"ok"`.
   - `nil, message` (the CC failure convention) and `error(message)` both become errors. For errors, the `file:line:`
     prefix is removed from messages that come from inside the tool file.
6. **Output cap:** the result is cut to `maxToolOutputBytes`, and `…[truncated N bytes]` is added.

The tool loop sends each result back as `{ type = "tool_result", tool_use_id = ..., content = ..., is_error = ... }`. It
runs the calls of one reply **in order, one at a time**, because CC computers are single-threaded. It runs at most
`maxToolCallsPerTurn` of them; the rest get the error `skipped: too many tool calls in one turn`.

**Timeout limits:** a call that never yields still trips CC's own "too long without yielding" error after about 7
seconds, which becomes the tool's error. A call that does yield, for example by sleeping or waiting for events, is
stopped by the timer. Stopping it means its coroutine is abandoned: CC can't kill a running coroutine any other way, so
side effects it already caused remain.

### The `ctx` argument
```lua
ctx = {
  computerId = 12,
  shell = shell,           -- the calling program's shell; nil if there is none
  resolve = function(path) -> absolutePath | nil, err,   -- see PATHS below
  limits = { ... },        -- ai.getLimits()
  toolName = "read_file",
}
```

## 5. Destructive actions and approval

A call is **destructive** if either of these is true:
- The tool's `destructive` is `true`, or its `destructive(input)` function returns `true`.
- The call writes, moves or deletes a **protected path**. Every file-changing ROM tool checks this itself, and user
  tools can use `ctx.resolve(path, { write = true })`, which returns a `protected = true` flag. Protected paths:
  - `startup`, `startup.lua`, and anything under `startup/`
  - `disk*/startup` and `disk*/startup.lua` (a disk's startup runs when a computer boots with it)
  - anything under `tools/`
  - `.settings`

  Changing these could make code run at boot, add new tools, or change `ai.auto_approve`. That would let a model give
  itself more power, so it always needs the user's approval, whatever the tool.

Destructive calls run only if the conversation's `approve(call)` callback returns `true`, or the computer's setting
`ai.auto_approve` is `true`. **There's no approval by default**: a program that doesn't pass `approve` can't run
destructive tools.

## 6. Paths

Every ROM file tool sends paths through `ctx.resolve`:
1. The path must be a string of 1 to 255 bytes, with no control characters. Paths are taken from the computer's root, not
   from the shell's current folder, and a leading `/` is optional.
2. It is normalised with `fs.combine`. If any `..` would end up above the root, the path is rejected.
3. With `{ write = true }`, paths under `rom/` are rejected straight away with a clear message, rather than waiting for
   CC's read-only error.

This is defence in depth. CC's `fs` API can't reach anything outside the computer's own file system and its mounts (disk
drives, ROM), so these tools give no host file access, with or without these rules.

## 7. Default ROM tools

| Name | Input | Destructive | Behaviour |
|---|---|---|---|
| `list_files` | `{ path?: string = "/" }` | no | Names in a folder, with a `/` after folder names, sorted. At most 500 entries. |
| `read_file` | `{ path, offset?: integer = 0, length?: integer = 16384 }` | no | Bytes `offset..offset+length` of a file, plus the total size, so long files can be read in pieces. Refuses folders. |
| `write_file` | `{ path, content, append?: boolean }` | **yes** | Creates parent folders, then writes or appends. Returns the bytes written. Each call writes at most 64 KiB. |
| `edit_file` | `{ path, old_text, new_text, replace_all?: boolean }` | **yes** | Replaces `old_text`, which must appear exactly once unless `replace_all` is set. Fails without changing anything if the text isn't found or isn't unique. Matches plain text, not Lua patterns. |
| `delete_file` | `{ path }` | **yes** | Deletes a file or an **empty** folder. A non-empty folder is refused. |
| `make_dir` | `{ path }` | protected paths only | Creates a folder and any missing parents. |
| `run_lua` | `{ code: string }` | **yes** | See below. |
| `run_shell` | `{ command: string }` | **yes** | See below. Only loaded when the program has a `shell`. |

**`run_lua`** runs Lua code **on this CC computer**:
- The code is compiled with `load(code, "=run_lua", "t", env)`, as text only.
- `env` is a fresh table that falls back to the program's globals, so the code has exactly the computer's normal APIs.
  It gets a `print`, `write` and `printError` that write into a capture buffer.
- If `code` doesn't compile as written, it is tried again as `return <code>`, so expressions such as `2 + 2` work, as they
  do in CC's `lua` program.
- The result is the captured output, then the returned values (each shown with `textutils.serialise`), cut to the output
  cap.
- It can't reach the host OS: CC's Lua has no `io.popen` or `os.execute`, and no file access outside the computer's own
  file system.

**`run_shell`** runs a command **in the CC shell of this computer**, through `shell.run(command)`:
- While it runs, the terminal is replaced by a capture object, a terminal that keeps text in memory and shows nothing.
  The real terminal is put back afterwards, even if the command fails.
- The result is the exit status (`shell.run`'s boolean) and the captured text.
- Programs that wait for typing (`read()`) wait until the timeout, because the user's typing isn't sent to them. Programs
  that draw full-screen interfaces give confusing output.
- "Shell" here means CC's shell, the one that runs CC programs such as `ls`, `cp`, `edit` and scripts on the computer.
  It is not the host's shell.
- It is marked destructive because a CC command can do anything a program can.

## 8. Writing your own tool

1. Create `/tools/<name>.lua` on the computer. Use lowercase letters, digits and `_`, start with a letter, and keep it
   to 48 characters at most.
2. Return a table with `description` and `run`. Add `input_schema` if the tool takes arguments.
3. Check the input. Return text or a table, or `nil, "what went wrong"` on failure.
4. Set `destructive = true` if the tool changes anything you'd want to approve first.
5. Run `ai` and check the startup list for load errors. The tool shows up to the model as `user_<name>`.

```lua
-- /tools/chest_count.lua  ->  user_chest_count
return {
  description = "Count the items of one kind in the chest above the computer.",
  input_schema = {
    type = "object",
    properties = { item = { type = "string", description = "Item id, e.g. minecraft:cobblestone" } },
    required = { "item" },
  },
  run = function(input)
    local chest = peripheral.wrap("top")
    if not chest or not chest.list then return nil, "no inventory on top" end
    local n = 0
    for _, stack in pairs(chest.list()) do
      if stack.name == input.item then n = n + stack.count end
    end
    return { item = input.item, count = n }
  end,
}
```
