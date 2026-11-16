# CC: Terminal Assistant

A NeoForge add-on for CC: Tweaked (Minecraft 1.21.1, NeoForge 21.1.249, CC: Tweaked 1.120.2+). Programs on CC computers
can talk to an AI model, which can use tools to read and edit the computer's files, run Lua, and run CC shell commands.
It works with any provider that has an OpenAI-compatible or Anthropic-compatible API: Anthropic, OpenAI, OpenRouter,
NanoGPT, Z.AI, a local Ollama, and others. Unofficial; not affiliated with any model provider.

**Subscription plans that are limited to specific apps can't be used**, for example Claude Pro/Max/Ultra and the Z.AI GLM
Coding Plan ([why](docs/FEASIBILITY.md)). Pay-per-use API keys and local models can.

## Setup
**Server operator:** install the mod and CC: Tweaked, then set `backend` in `world/serverconfig/ccassistant-server.toml`:
- `player`: each player uses their own provider. This is the recommended mode.
- `server`: you provide the model. List providers in `ccassistant-providers.json`, with keys in environment variables.
- `either`: the owner's client when they're ready, otherwise the server's provider.

See [docs/CONFIG.md](docs/CONFIG.md).

**Player, `player` backend:**
1. Install the mod on your client.
2. Open **Mods > CC: Terminal Assistant > Config**.
3. **Add** a profile: choose a provider, paste your API key, **Choose...** a model, then **Save**.
4. Tick **Let my linked computers use these providers**.

Your keys stay in your user config folder, outside the game folder, and are never sent to the server. The server
operator can read conversations with computers on their server.

**On a computer:**
1. Run `ai link` and type `/assistant link <code>` in chat.
2. Run `ai`.

## Lua
```lua
local ai = require("cc.ai")
local chat = ai.Conversation.new {
  tools = require("cc.ai.tools").load(),
  approve = function(call) write("Allow " .. call.name .. "? ") return read() == "y" end,
}
print(chat:say("Make hello.txt that says hi"))
```
Lower level: `ai.request(body)` returns an id, followed by an `ai_response` or `ai_error` event. Also available:
`ai.isAvailable()`, `ai.status()`, `ai.link()`, `ai.cancel(id)` and `ai.getLimits()`. If the owner is offline, requests
fail at once with `owner_offline`, and an `ai_owner` event says when they're back. See
[docs/LUA_API.md](docs/LUA_API.md).

## Tools
- Built-in tools in `/rom/tools`: `list_files`, `read_file`, `write_file`, `edit_file`, `delete_file`, `make_dir`,
  `run_lua` and `run_shell`.
- Your own tools go in `/tools/<name>.lua` and appear as `user_<name>`.
- Anything that changes the computer asks first. Startup files, `/tools` and `.settings` are always protected.
- Tools run inside the CC computer only, never on the host.

See [docs/TOOLS.md](docs/TOOLS.md) and `help ai_tools` in game.

## Commands
- `/assistant link <code>`, `unlink <id>`, `list` and `status`.
- `models <provider> [search]` and `reload` (operators only).
- `/assistant-client config|on|off|status`, which runs on your own client.

## Development
- `./gradlew build`: jar and unit tests.
- `./gradlew runGameTestServer`: game tests on real CC computers.

CI (`.github/workflows/release.yml`) runs both. Pushing a tag like `MC1.21.1-0.1.0` publishes a release; a suffix such as
`-beta.1` makes it a prerelease.

## Design documents
[Feasibility](docs/FEASIBILITY.md) · [Providers](docs/PROVIDERS.md) · [Client UI](docs/CLIENT_UI.md) ·
[Lua API](docs/LUA_API.md) · [Tools](docs/TOOLS.md) · [Security](docs/SECURITY.md) · [Config](docs/CONFIG.md) ·
[Plan](docs/PLAN.md)

## Not yet verified
- The in-game screens. They compile against the game's widget classes but haven't been opened.
- Live calls to real providers. The HTTP client is tested against local fake servers.
- A real two-player session. The player backend is tested with a fake client transport.
- The model-list fields from NanoGPT's `detailed=true` and OpenAI's `/models`.
