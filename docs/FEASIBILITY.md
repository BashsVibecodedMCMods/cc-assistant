# Feasibility report: Claude from CC: Tweaked computers

Research date: **2026-11-16**. All links were read on that date. Terms change, so re-check them before a release.

## 1. Target environment

| Component | Target | Found in the repositories | Status |
|---|---|---|---|
| Minecraft | 1.21.1 | `minecraft_version=1.21.1` (both repos) | match |
| NeoForge | 21.1.249 | `neo_version=21.1.249` | match |
| CC: Tweaked | 1.120.2 | `cct_version=1.120.2`, artifact `cc.tweaked:cc-tweaked-1.21.1-forge:1.120.2` from `maven.squiddev.cc` | match |
| Java | (implied 21) | toolchain 21; container has OpenJDK 21.0.12 | match |
| Build | — | Gradle 8.14.3 wrapper, ModDevGradle `net.neoforged.moddev` 2.0.148, Parchment 2024.11.17 | reused as-is |

No mismatches. The new project copies these versions and the conventions of `cc-wireless-peripherals` (single `build.gradle`,
`src/main/templates` metadata expansion, game tests in `dev/<mod>/test` excluded from the jar, CI in
`.github/workflows/release.yml`).

CC: Tweaked APIs this mod relies on, all checked with `javap` against the 1.120.2 jar:
- `ComputerCraftAPI.registerAPIFactory(ILuaAPIFactory)`; `ILuaAPIFactory.create(IComputerSystem)` returns an `ILuaAPI`
  (`getNames`, `startup`, `update`, `shutdown`). This is the public, supported way to add a global Lua API to every
  computer.
- `IComputerSystem` gives `getID()`, `queueEvent(...)`, `getLevel()`, `getPosition()`, `getLabel()` and
  `getComponent(ComputerComponents.ADMIN_COMPUTER)` (present on command computers).
- `@LuaFunction` methods; `IArguments.getTable` returns Lua tables as `Map<?, ?>`.
- ROM files are added by datapack under `data/computercraft/lua/rom/...` (the wireless-peripherals repo already does this
  for `rom/autorun`). `bios.lua` puts `rom/modules/main` on `package.path`, and `rom/programs` is on the shell path.

## 2. The feasibility gate: can players use their own Claude subscription?

### Findings

1. **A Claude subscription is not API access, and the two are billed separately.** Free/Pro/Max/Ultra plans are governed by
   the Consumer Terms; API keys by the Commercial Terms.
   - Consumer Terms (effective 2025-10-08), <https://www.anthropic.com/legal/consumer-terms>: *"Our Commercial Terms of
     Service govern your use of any Anthropic API key"*, and automated access is prohibited *"Except when you are
     accessing our Services via an Anthropic API Key or where we otherwise explicitly permit it"*.
   - Claude Code legal page, <https://code.claude.com/docs/en/legal-and-compliance>: Commercial Terms for Team, Enterprise
     and API users; Consumer Terms for Free, Pro, Max and Ultra.

2. **Third-party developers may not offer Claude.ai login or route requests through subscription credentials.**
   <https://code.claude.com/docs/en/legal-and-compliance>, "Authentication and credential use":
   - *"Developers building products or services that interact with Claude's capabilities, including those using the
     Agent SDK, should use API key authentication through Claude Console or a supported cloud provider. Anthropic does
     not permit third-party developers to offer Claude.ai login into their own applications, or to route requests
     through Free, Pro, Max, or Ultra plan credentials on behalf of their users. Moreover, developers may not collect,
     store, or intermediate Claude.ai credentials or session tokens."*
   - Agent SDK overview, <https://code.claude.com/docs/en/agent-sdk/overview>: *"Unless previously approved, Anthropic
     does not allow third party developers to offer claude.ai login or rate limits for their products, including agents
     built on the Claude Agent SDK. Use the API key authentication methods described in the Quickstart instead."*

3. **Officially supported ways to authenticate**, from the same pages: Claude Console API keys; cloud providers (Amazon
   Bedrock, Google Cloud, Microsoft Foundry); OAuth sign-in to Claude subscriptions *only* in Claude Code and Anthropic's
   own apps. Claude Code's headless mode (`claude -p`, <https://code.claude.com/docs/en/headless>) and the Agent SDK are
   official, but they are covered by the restriction in finding 2 when a third-party product drives them.

4. **Each end user must use their own credentials; nobody may resell or intermediate usage.** The legal page, on
   products that run Claude Code: *"Customers may not pay for, resell, or intermediate Claude usage on their end users'
   behalf. Each end user must authenticate with their own Anthropic API key, Claude subscription plan credentials, or 3P
   inference provider credential."* It also allows *"how customers provision and manage their own API keys … for use by
   the customer's own authorized users — provided the resulting usage is billed to the key owner … and is not resold or
   intermediated"*.

5. **Credentials must not be shared.** Consumer Terms §2: *"You may not share your Account login information, Anthropic
   API key, or Account credentials with anyone else."*

6. **Secondary reporting on subscription credits for programmatic use is contradictory** (May–June 2026 articles on a
   separate "Agent SDK credit" for `claude -p`, some saying it was withdrawn). I could not confirm it on an Anthropic
   page, and it doesn't change findings 2–5, so the design doesn't depend on it.

### Conclusion

| Option | Compliant? | Why |
|---|---|---|
| Mod or companion uses a player's **Claude subscription** (OAuth token, Claude.ai login, cookies, driving `claude -p` on their plan) | **No** (not without Anthropic's prior approval) | Finding 2: third parties may not offer Claude.ai login or route requests through plan credentials. Not implemented. |
| Player gives their key or login to the server | **No** | Finding 5 (no sharing) and finding 2 (no intermediating credentials). |
| **Player's own Anthropic API key, kept on the player's own machine**, used by software the player runs | **Yes** | Findings 1 and 4: an API key is the supported way to call Claude programmatically, and usage is billed to the key owner. The key never leaves the player's computer. |
| **Server operator's own API key**, operator pays, for players on their server | **Yes**, with care | Finding 4 allows a key owner to provision a key for their own authorized users if usage is not resold. Operators must not charge players for Claude access. |
| Scraping claude.ai, undocumented endpoints, browser cookies | **No** | Ruled out by the task and by findings 2 and 5. |

**A player-owned *subscription* path does not exist for a third-party mod.** The closest supported alternative that still
keeps cost and control with the player is a **player-owned API key used by the player's own Minecraft client**
(the "player" backend below). The mod is built so another supported backend (for example Bedrock, Vertex, or an
Anthropic-approved subscription integration if one ever exists) can be added behind the same interface.

## 3. Multiplayer architectures

The CC computer (and therefore every tool call) always runs **on the Minecraft server**. Only the model request can move.

### A. Player companion inside the Minecraft client (recommended for "bring your own key")

```
CC computer (server) --ai.request--> mod (server) --custom payload over the existing game connection--> player's
client mod --HTTPS, player's key for the chosen profile--> any provider (Anthropic, OpenAI, OpenRouter, NanoGPT, ...)
                       <---------------- response (validated, size-limited) <----------------
```

- **Reachability/NAT:** none needed. The player's game client already holds an outbound, authenticated connection to the
  server; the client makes an outbound HTTPS request. No extra ports, no relay, no inbound connections. A separate
  standalone companion process would need either a new listening port on the server or a third-party relay; the game
  client avoids both.
- **Identity binding:** a computer is linked to a player with a one-time code. A program calls `ai.link()`, which shows
  a short code with a 5-minute expiry; the player types `/assistant link <code>` in chat. The server then knows *which
  game connection* serves that computer. Codes are single-use and can't be guessed in practice (32^6 space, rate-limited
  attempts, short expiry). The player must type the code, so nobody can attach their computer to someone else's account.
- **Disconnects:** when the player logs out, their pending requests fail with `owner_offline`, and new requests fail
  until they reconnect. Nothing is queued.
- **What the server sees:** the request and response content (it has to: the computer and tools run there) and token
  usage. It **never** sees the API key. A modified client can only feed fabricated responses to its owner's own
  computers; the server treats all responses as untrusted input either way.
- **What the player's client sees:** only requests from computers linked to that player.
- **Cost/trust:** the player pays their own provider directly and can revoke their key at any time. The player must trust the
  server operator with the conversation content (as with any server-side computer).
- **Limits:** works only while the player is online with the client mod installed and enabled; turtles running
  unattended stop getting answers when the owner leaves.

### B. Server-operated API integration

```
CC computer --ai.request--> mod (server) --HTTPS, operator's key from an environment variable--> operator's provider(s)
```

- Simple and works while players are offline. The operator pays, sets model and token caps, and is responsible for use
  under their provider's terms (for Anthropic, the Commercial Terms). Per-player quotas (requests/minute, concurrency) stop one player from spending
  the budget. Players must not be charged for access (finding 4).
- The key comes from an environment variable whose *name* is configured; the key itself is never written to config,
  world files, Lua or logs.

### C. Standalone companion process / external relay

Rejected: needs either an inbound port on the server or a hosted relay, adds a second identity system, and offers nothing
over A for an API-key path. A subscription-backed companion would be non-compliant (finding 2).

### Recommendation

Ship **both A ("player") and B ("server")** behind a `Backend` interface, selected by server config, default **`none`**.
With `none`, every call returns an actionable error. Both backends share request validation, quotas, timeouts and
cancellation. Operators choose: "players bring their own key" (A), "I provide the model" (B), or `either` (try the
player's client first, fall back to the server's provider).

**Update after review: any provider, not only Claude.** Since Claude subscriptions can't be used, the player companion
(A) works with **any** provider that offers an OpenAI-compatible or Anthropic-compatible API, set up per player in an
in-game UI with a searchable model list. A is now the recommended mode. See §6, PROVIDERS.md and CLIENT_UI.md.

## 4. What "run Lua" and "run shell commands" mean here

Both tools run **inside the CC computer**, using CC: Tweaked's own Lua VM (Cobalt), with exactly the rights any program on
that computer already has. They cannot reach the host operating system: CC computers have no access to the host's
processes or files outside the computer's own save directory, and this mod adds no such access.
- **run_lua** compiles text with `load(code, "=run_lua", "t", env)` (text only) in an environment whose `print`/`write`
  are captured, and runs it in a coroutine with a time limit.
- **run_shell** runs a command line through the computer's CC shell (`shell.run`) with the terminal redirected to a
  capture buffer and a time limit. It runs CC programs such as `ls`, `edit`, or the computer's own scripts, not host
  commands.
- **Command computers** can run Minecraft server commands through CC's `commands` API. Letting a model drive that would
  be privilege escalation, so command computers are **blocked** unless the operator sets `allowCommandComputers`.
- CC's `http` API, if the operator enabled it in CC's config, is reachable from both tools, under CC's own rules.

## 5. Not verified here

- Live calls to any provider: no API keys were available in this environment. The HTTP clients and both protocol
  translators will be tested against a local fake server. To check for real: add a profile in the UI, click **Test**,
  then run `ai` on a linked computer.
- Model-list response shapes for NanoGPT `?detailed=true` and OpenAI `/models` (PROVIDERS.md §1, §5).
- The player backend in a real two-player session (client config, action-bar notices). The game tests drive the same
  server-side code with a fake client transport.

## 6. Other providers (added after review)

Read 2026-11-16. The design needs, per provider: a pay-per-use API key, tool calling, and ideally a model-list endpoint.

| Provider | Protocol | Tool calling | Model list | Terms note | Source |
|---|---|---|---|---|---|
| Anthropic API | Anthropic Messages | yes | `GET /v1/models` | Console API key (§2) | §2 above |
| OpenAI | Chat Completions (de facto standard) | yes | `GET /v1/models`, ids only (unverified: the docs page returned 403 to my fetch) | API key | general knowledge |
| OpenRouter | OpenAI-compatible, `https://openrouter.ai/api/v1` | yes, per model | `GET /api/v1/models` with `supported_parameters`, `context_length`, `pricing` | API key | [overview](https://openrouter.ai/docs/api/reference/overview), [models](https://openrouter.ai/docs/guides/overview/models) |
| NanoGPT | OpenAI-compatible `/api/v1/chat/completions`; also Anthropic-compatible `/api/v1/messages` | "generally matches the OpenAI standards" | `GET /api/v1/models`, with `?detailed=true` for capabilities and pricing | API key; NanoGPT documents subscription use through its API | [intro](https://docs.nano-gpt.com/introduction), [models](https://docs.nano-gpt.com/api-reference/endpoint/models) |
| Z.AI | OpenAI-style, `https://api.z.ai/api/paas/v4` | yes, `tool_choice` only `auto` | none documented | **The GLM Coding Plan is limited to listed tools**, so only the pay-as-you-go API is supported | [HTTP intro](https://docs.z.ai/guides/develop/http/introduction), [function calling](https://docs.z.ai/guides/capabilities/function-calling), [Coding Plan tools](https://docs.z.ai/devpack/tool/others) |
| Ollama / LM Studio | OpenAI-compatible, local | depends on the model | `GET /v1/models` | none (local) | general knowledge, unverified |

**Conclusion:** two protocol adapters (OpenAI Chat Completions and Anthropic Messages) cover all of these. Terms
differ by provider: some subscription plans restrict which apps may use them, so the mod offers only pay-per-use endpoint
presets, and the UI reminds players that their provider's terms apply.
