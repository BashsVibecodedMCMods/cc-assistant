# Security model (design for review)

## 1. Assets and who must be protected from whom

| Asset | Protected from |
|---|---|
| A player's API keys and provider settings (base URLs, headers) | The server, other players, Lua code, logs, world files, shared instance folders |
| The operator's API keys | Players, Lua code, logs, world files, config files |
| Money (API usage) | Abuse by other players, runaway loops, and a malicious server spending the player's key |
| A player's conversations | Other players' computers and clients |
| The server | The model or a tool reaching the host OS, or Minecraft commands |
| A computer's files and behaviour | The model acting without the user's consent; the model giving itself more power |

## 2. Trust boundaries

```
[provider API] <--HTTPS-- [server backend: operator providers, keys in env vars]
                                   |
                            Minecraft server ---- game connection ----> [player's client: profiles + keys]
                                   |                                          |
                            CC computer (Lua VM)                              +--HTTPS (or loopback http)--> [provider API]
                            ^ model output and user tools run here, under CC's sandbox
```

Treated as untrusted:
- **All model output**: text, tool names and tool inputs.
- **Responses from a player's client**, which could be modified.
- **Requests the server sends to a player's client**, since the server could be malicious toward the player.
- **User tools and Lua programs.**

## 3. Identity and authorization

- **Linking.** A computer is linked to exactly one player, through a code shown on the computer and typed in chat. Typing
  it proves the player agreed to *their* account paying for that computer.
  - Codes are 6 characters from a 32-symbol alphabet with no lookalike characters, single-use, and expire after 5
    minutes. Only one code is active per computer.
  - A player gets 10 failed `/assistant link` attempts per 10 minutes. A wrong code consumes nothing.
- **Re-linking.** Someone who can use a computer can link it to themselves, which replaces the old owner. The old owner,
  if online, is told in chat, and any of their requests still in progress are cancelled. This matches CC: anyone who can
  open a computer already controls it fully, including its files and history.
- **Who can unlink:**
  - the owner: `/assistant unlink <id>`
  - the computer itself: `ai.unlink()`
  - an operator (permission level 2): `/assistant unlink <id>`, which works on any computer.
- **The server backend** also requires a link by default (`requireLink = true`). This lets usage be counted and limited
  per player. An operator can turn that off, and then limits count per computer only.
- **Routing (player backend).** A request goes only to the client of the player linked to the computer, and only over
  that player's own connection. A client only ever gets requests from its owner's computers.
- **Per-player view.** `/assistant list` shows only your own links; operators can list everything. `ai.status()` only
  shows the computer's own link.

## 4. What tools can and cannot do

**The CC sandbox is the security boundary, and the mod doesn't widen it.** Tools are ordinary Lua running on the
computer:
- They have no host processes, no host files outside the computer's save folder, and no Java access.
- This mod adds no Lua functions that reach outside the computer, except `ai.*`, which only makes the validated API
  requests described in LUA_API.md.

So user tools and `run_lua`/`run_shell` give the model **at most the rights of a person typing at that computer**. On top
of that boundary, these controls limit what the model can do without the user's consent:
1. **Approval for destructive actions.** It is off unless the program passes an `approve` callback or the user sets
   `ai.auto_approve`. See TOOLS.md §5.
2. **Protected paths** (`startup*`, `disk*/startup*`, `tools/`, `.settings`) always count as destructive. Without
   approval, the model can't add boot code, new tools, or change `ai.auto_approve`.
3. **Command computers** are refused (`allowCommandComputers = false`). On them, `run_lua` could call CC's `commands` API,
   which runs Minecraft server commands at operator level. The check is done in Java, from the computer's
   `ADMIN_COMPUTER` component, so Lua can't get around it.
4. **CC's `http` API** stays governed by CC's own config: the operator's allow/deny rules, which by default block
   private and local addresses. The mod doesn't change it.
5. **Provider-hosted tools** (web search, code execution, and similar) can't be requested: the request format only has
   custom tools, so Lua can't spend extra money or reach the web through the provider.

**Operator settings are honest defaults, not hard walls.** `disabledRomTools`, `allowUserTools` and
`allowDestructiveTools` are applied by the ROM loader in Lua. A user who writes their own tool loader can get around them,
but only to run code they could already run on that computer. Java enforces the limits that protect the server and the
budget: request shape, size, rate, concurrency, timeouts, command computers, and linking.

## 5. Keys and credentials

- **Server keys:** each provider in `serverconfig/ccassistant-providers.json` names an environment variable, which is read
  each time a request is made. Keys are never stored in config, world data or the jar, never shown to Lua, and never sent
  to clients.
- **Player keys** (CLIENT_UI.md §3):
  - Stored in `credentials.json` in the player's user config folder, outside the game instance, with permissions `0600`
    on POSIX. Or, per profile, read from an environment variable whose *name* is stored.
  - Never sent to the server. Never shown in full in the UI after saving (only the last 4 characters). Never written to
    logs.
  - This is plain text protected only by file permissions, and the docs say so.
- **Where keys can be sent:**
  - A profile's base URL must be `https://`, or `http://` to a loopback address for local model servers.
  - HTTP redirects are **not followed**, so a key can't be passed on to another host.
  - Keys go only to the base URL of the profile they belong to.
  - Extra headers can't set `Authorization`, `x-api-key`, `Host` or `Cookie`.
  - The server has no way to set or read a client's base URLs, headers or keys.
- **No subscription logins.** No Claude.ai login, no OAuth tokens, no session cookies (FEASIBILITY.md §2). Presets point only at
  pay-per-use API endpoints. Plans limited to particular apps, such as the Z.AI GLM Coding Plan, aren't offered
  (PROVIDERS.md §1). The profile editor reminds players to follow their provider's terms.
- **Logging:** request ids, computer ids, player names, sizes, status codes and API error *types*. Never keys, headers,
  or message content, and no base URLs in anything sent to the server. Provider error bodies are reduced to the HTTP
  status plus a short error message, capped at 300 characters, with URLs removed.

## 6. Protecting the player from the server (player backend)

A malicious or misconfigured server could try to spend a player's key. The client enforces its own controls:
- **Opt-in:** `enabled = false` by default. Nothing is sent until the player turns it on.
- **Limits:** its own requests per minute (default 6) and per hour (default 60), its own token cap (default 8192), its own
  request size cap (default 256 KiB). Whichever of the client or server limits is lower wins.
- **Same validation as the server:** the client re-checks the body with the same rules and refuses unknown fields. The
  model, provider, effort and token cap always come from **its own** profile. The server can only pick among the
  profiles the player marked "computers may choose this profile".
- **Visibility:** an action-bar notice for each request ("AI request from computer #12 via nano, 3.1 KB"), and a running count
  of tokens for the session in `/assistant-client status`.
- **Kill switch:** a key binding (unbound by default) or `/assistant-client off` disables it immediately and fails any
  requests in progress.

## 7. Bounds

| Bound | Default | Enforced in |
|---|---|---|
| Request size | 256 KiB | Java (server), client |
| Messages per request / blocks per message | 500 / 100 | Java |
| Tools per request | 64 | Java and loader |
| Response size | 1 MiB | Java (both backends) |
| `max_tokens` | 8192 | Java (server), client |
| In-progress requests | 1 per computer, 2 per player, 16 per server | Java |
| Request rate | 10/min per player, 10/min per computer | Java |
| Request timeout | 180 s | Java |
| Tool time | 5 s default, 30 s cap | Lua loader |
| Tool output | 16 KiB | Lua loader |
| Tool calls per reply / requests per `say` | 16 / 25 | Lua loader |
| Link attempts | 10 per 10 min per player | Java |

## 8. Failure handling

- **Every request ends in exactly one event**, `ai_response` or `ai_error`, unless the computer shut down.
  Timeouts, cancellation, unlinking, the owner disconnecting, and the server stopping all produce `ai_error` with a
  stable code.
- **Owner disconnects** (player backend): every request routed to that player fails at once with `owner_offline`.
- **Server stops:** all requests are cancelled and nothing is retried.
- **No automatic retries in Java**, so one Lua call never turns into hidden repeated charges. Lua decides whether to try
  again.

## 9. Known limitations

- On the player backend, the server operator can read the conversation, because the computer runs on their server. The
  README tells players this plainly.
- Someone who can use a computer can read its conversation history files and re-link it. CC has no per-computer access
  control, and this mod doesn't add one.
- A tool that times out is abandoned, not undone (TOOLS.md §4).
- Operator tool settings can be bypassed by users running their own code (§4); they protect against the model, not
  against the user.
