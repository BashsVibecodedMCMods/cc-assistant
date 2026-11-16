# Implementation plan (for review; no code is written until the docs are approved)

Mod id `ccassistant`, package `dev.ccassistant`, display name "CC: Terminal Assistant", Lua global `ai`. The mod is
unofficial and not affiliated with any model provider.

Design documents, which are the spec for this plan:
- [FEASIBILITY.md](FEASIBILITY.md): the provider-access conclusion, architecture choice, provider research, citations.
- [PROVIDERS.md](PROVIDERS.md): the two protocols, presets, the mod's message format and translations, model listing.
- [CLIENT_UI.md](CLIENT_UI.md): in-game provider screens, model picker, credential storage, client data flow.
- [LUA_API.md](LUA_API.md): the `ai` global, the `cc.ai` module, the `ai` program, error codes.
- [TOOLS.md](TOOLS.md): tool discovery, namespacing, format, invocation, default tools, authoring.
- [SECURITY.md](SECURITY.md): threat model, authorization, keys, bounds, failure handling, limitations.
- [CONFIG.md](CONFIG.md): server config and providers file, client switches, setup, commands.

## Phase 0: scaffold (already written, not yet built)
- `build.gradle`, `gradle.properties`, `settings.gradle`, Gradle wrapper. These copy `cc-wireless-peripherals`: MC 1.21.1,
  NeoForge 21.1.249, CC: Tweaked 1.120.2, ModDevGradle 2.0.148, Gradle 8.14.3, Java 21.
- JUnit 5 through ModDevGradle's `unitTest` block. This is **unverified** and is checked by the first build.
- Mod metadata template, CI workflow (build with JUnit, then game tests; a release on `MC<mc>-<version>` tags), and the
  game-test structure.
- Still to add: `.github/release-notes.md`, `CLAUDE.md`.

## Phase 1: provider layer (pure Java, shared by server and client)
| File | Role |
|---|---|
| `provider/ProviderProfile` | Immutable settings: name, preset, protocol, base URL, model, caps, headers, key source. |
| `provider/Presets` | The table in PROVIDERS.md §1. |
| `provider/ChatFormat` | The mod's message format as a Gson tree, with checks shared by Lua input and translators. |
| `provider/Protocol` | Interface: `toWire(request, profile)`, `fromWire(json)`, `modelsPath`, `parseModels(json)`, `authHeaders(key)`. |
| `provider/OpenAiProtocol` | Chat Completions translation (PROVIDERS.md §3) and model-list parsing, plus OpenRouter and NanoGPT extras. |
| `provider/AnthropicProtocol` | Messages translation (PROVIDERS.md §4), `anthropic-version: 2026-10-21`, effort and fallback. |
| `provider/ProviderHttp` | `java.net.http` with redirects **never** followed, the https/loopback rule, response size cap, cancellation, error bodies trimmed to status + short message with URLs removed. Keys never appear in exceptions. |
| `provider/ModelInfo`, `ModelSearch` | The normalised model entry, and the search/sort rules in CLIENT_UI.md §2.3. |

Why raw HTTP and not each provider's SDK:
- Two protocols with one small HTTP client cover every provider.
- Several SDKs (Anthropic's and OpenAI's Java SDKs bring Kotlin, OkHttp and Jackson) would bloat the mod jar and can
  clash with other mods.
- Minecraft already ships Gson.

## Phase 2: server core
| File | Role |
|---|---|
| `CCAssistant` | Entry point. Registers configs, the API factory, payloads, commands, events (logout, server stop). |
| `Config` | `ccassistant-server.toml` and `ccassistant-client.toml`. |
| `server/ServerProviders` | Loads `ccassistant-providers.json` and checks it; keys are read from environment variables when used. |
| `api/AiAPI` | The per-computer `ILuaAPI` named `ai`. `shutdown()` cancels the computer's requests. |
| `core/LuaJson` | Lua table <-> Gson tree (UTF-8 bytes, array rules, depth/NaN checks). |
| `core/RequestValidator` | The field whitelist and caps (LUA_API.md). Shared with the client. |
| `core/Quotas` | Rate windows and concurrency counters per player, computer and server. |
| `core/RequestManager` | Requests in progress, timeouts, cancellation, exactly-once `ai_response`/`ai_error`. |
| `backend/Backend`, `ServerBackend`, `PlayerBackend`, `EitherBackend` | Routing as in CONFIG.md. |
| `link/Links`, `link/LinkCodes` | Computer -> owner `SavedData`; one-time codes. |
| `command/AssistantCommand` | `/assistant link|unlink|list|status|models|reload`. |
| `link/OwnerStates` | Per-player state (`ready`/`offline`/`no_client`/`disabled`) from login, logout and the client's readiness bit. Fails in-progress requests on change and sends `ai_owner` events to linked computers, only when the state changes. |
| `net/TransferPayload`, `net/Reassembler` | Chunked bidirectional payload (serverbound custom payloads are capped at 32 KiB); registered `optional()`. |

## Phase 3: client
| File | Role |
|---|---|
| `client/ProfileStore` | `profiles.json` and `credentials.json` in the user config folder. Atomic writes, `0600` permissions, damaged-file recovery, `storageDir` override. |
| `client/ClientRequests` | Handles requests: enabled, profile lookup, client limits, validation, key lookup, protocol call, reply, kill switch. |
| `client/ModelCache` | Model lists per profile, 24-hour cache, fetched on a background thread. |
| `client/screen/ProvidersScreen` | The main screen (CLIENT_UI.md §2.1). |
| `client/screen/EditProfileScreen` | The profile editor: masked key box, env-var mode, preset handling, validation, Test. |
| `client/screen/ModelPickerScreen` | Search box, tool filter, `ObjectSelectionList` rows, manual id entry. |
| `client/screen/LimitsScreen` | Client limits and session counters. |
| `client/ClientHooks` | `IConfigScreenFactory`, key binding, `/assistant-client`, action-bar notices. Loaded only on the client. |

## Phase 4: Lua ROM (`src/main/resources/data/computercraft/lua/rom/`)
- `modules/main/cc/ai/init.lua`: `send`, `Conversation`.
- `modules/main/cc/ai/tools.lua`: discovery, filters, checks, approval, limits, `ctx.resolve`.
- `modules/main/cc/ai/capture.lua`: the capture terminal and the print capture.
- `tools/`: `list_files`, `read_file`, `write_file`, `edit_file`, `delete_file`, `make_dir`, `run_lua`, `run_shell`.
- `programs/ai.lua`, `help/ai.txt`, `help/ai_tools.txt`.

## Phase 5: tests
**JUnit** (no game):
- `LuaJson`; `RequestValidator`; `Quotas`; `LinkCodes`; `ModelSearch`.
- `OpenAiProtocol` and `AnthropicProtocol`, using recorded example responses: text, tool calls, several calls, invalid
  argument JSON, missing ids, reasoning fields, every `finish_reason`/`stop_reason`, usage, and a full round trip of a
  tool conversation.
- Model-list parsing for each preset's shape: OpenRouter, NanoGPT detailed, Anthropic, ids only.
- `ProviderHttp` against a local `com.sun.net.httpserver` fake:
  - success, 4xx/5xx, timeout, cancel, oversized response
  - **a redirect is not followed**
  - **http to a non-loopback host is refused**
  - the key never appears in exceptions or messages.
- `ProfileStore` in a temporary folder: round trip, atomic write, `0600` permissions on POSIX, damaged-file recovery,
  keys never in `profiles.json`.

**Game tests** (real CC computers, the pattern from `cc-wireless-peripherals`), with a fake backend and a fake player
transport installed for tests:
- `not_configured`; `not_linked`; link-code flow; re-link.
- Full tool loop: discovery order, `user_` namespacing, bad tools reported, read/write/edit/delete, `run_lua`,
  `run_shell` capture.
- Path traversal and `rom/` writes; protected paths denied without approval.
- Timeout, cancel, shutdown; `bad_profile`; command computer refused.
- **Owner availability:**
  - Owner offline gives `nil, "owner_offline", <message naming the owner>` straight from `ai.request` and `ai.isAvailable`.
  - A fake player joining without the mod channel gives `owner_no_client`; readiness bit off gives `owner_disabled`.
  - The owner logging out mid-request gives `ai_error, id, "owner_offline"` well before the timeout.
  - `ai_owner` is sent on each state change, and only on a change.
  - `either` falls back to the server provider with `info.backend == "server"`.
  - `Conversation` keeps completed tool rounds and `retry()` continues after the owner returns.
- The server backend against a local fake OpenAI-compatible server, end to end through `ai.request`.
- Tests that change config run in their own `batch`.

**Manual, not testable here:** live providers (no keys in this environment) and the UI screens, which can't be driven
headless. The README will give a manual checklist: each preset's Test button, model picker search, key masking,
env-var mode, two-player routing.

## Phase 6: docs and release
README, release notes, CLAUDE.md.

## Open decisions for you
1. **Where the project lives.** It is only in this container, and this session can't push it. Create a GitHub repository
   and add it to the session.
2. **Names:** the Lua global, module and program become `ai` / `cc.ai` / `ai`, with events `ai_response`/`ai_error`,
   now that the mod isn't tied to Claude. The mod is "CC: Terminal Assistant" (`ccassistant`).
3. **Key storage:** plain text with `0600` permissions in the user config folder, outside the instance, with
   environment-variable mode as an option. There's no OS keychain in v1.
4. **Lua choosing a profile:** allowed by name for profiles the owner marked as choosable (on by default). Say if it
   should be off by default.
5. **Owner offline:** fail fast with a clear message and let programs wait for `ai_owner`. No queueing, no fallback to
   anyone else's provider, except the operator's on `either`.
6. **No streaming in v1**: replies arrive whole.
7. **Default limits:** 8192 max tokens, 6 requests a minute and 60 an hour on the client, 10 a minute on the server.
