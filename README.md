# CC: Terminal Assistant

**Status: design review. No mod code yet.**

A NeoForge add-on for CC: Tweaked (Minecraft 1.21.1, NeoForge 21.1.249, CC: Tweaked 1.120.2+). Programs on CC computers
can talk to an AI model, using tools that read and edit the computer's files, run Lua, and run CC shell commands. It works
with any provider that has an OpenAI-compatible or Anthropic-compatible API: Anthropic, OpenAI, OpenRouter, NanoGPT,
Z.AI, a local Ollama, and others. Unofficial; not affiliated with any model provider.

## Design documents
| Document | Contents |
|---|---|
| [docs/FEASIBILITY.md](docs/FEASIBILITY.md) | Why Claude subscriptions can't be used, multiplayer architectures, provider research, sources |
| [docs/PROVIDERS.md](docs/PROVIDERS.md) | Supported protocols, presets, the mod's message format, translation, model lists |
| [docs/CLIENT_UI.md](docs/CLIENT_UI.md) | In-game provider settings, searchable model picker, where keys are stored |
| [docs/LUA_API.md](docs/LUA_API.md) | `ai` global, `cc.ai` module, `ai` program, events, error codes |
| [docs/TOOLS.md](docs/TOOLS.md) | `/rom/tools` and `/tools`, discovery order, namespacing, tool format, default tools, writing your own |
| [docs/SECURITY.md](docs/SECURITY.md) | Trust boundaries, linking computers to players, keys, bounds, failure handling, limitations |
| [docs/CONFIG.md](docs/CONFIG.md) | Server config and providers file, client settings, setup, commands |
| [docs/PLAN.md](docs/PLAN.md) | Phased implementation plan, tests, open decisions |

## In short
- **Bring your own provider:**
  - Each player adds provider profiles (provider, key, model) in an in-game UI with a searchable model list.
  - Their own game client makes the requests.
  - Their keys never reach the server and are stored outside the game folder.
- **Or the operator provides one:** providers in a server file, keys in environment variables.
- **Subscription plans limited to particular apps can't be used**, for example Claude Pro/Max/Ultra and the Z.AI GLM
  Coding Plan. Pay-per-use API keys and local models can.
- **Every computer is linked to a player** by a one-time code (`ai link`, then `/assistant link <code>`).
- **Tools run inside the CC computer**, under CC's sandbox, never on the host. Destructive actions need the user's
  approval.
