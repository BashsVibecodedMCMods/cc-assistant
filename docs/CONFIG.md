# Configuration and setup (design for review)

## Who needs what

| Backend | Server operator | Each player |
|---|---|---|
| `none` (default) | nothing | nothing. Every request fails with `not_configured`. |
| `player` (**recommended**) | nothing beyond setting the backend | The mod installed **on their client**, and at least one provider profile set up in the in-game UI. They pay their own provider. |
| `server` | One or more providers in `ccassistant-providers.json`, with keys in environment variables on the server machine. The operator pays. | Nothing extra; the client mod is optional. |
| `either` | as `server` | optional. Uses the owner's client when it is online and turned on, otherwise the server's default provider. |

Any provider with an OpenAI-compatible or Anthropic-compatible API works (PROVIDERS.md): Anthropic, OpenAI, OpenRouter,
NanoGPT, Z.AI's pay-as-you-go API, local Ollama or LM Studio, and others. **Subscription plans whose terms limit them to
particular apps can't be used**, for example Claude Pro/Max/Ultra and the Z.AI GLM Coding Plan.

## Server config: `world/serverconfig/ccassistant-server.toml`

```toml
# "none" | "player" | "server" | "either"
backend = "none"
# Every computer must be linked to a player. The player backend always needs this.
requireLink = true
# Command computers can run Minecraft commands through CC's commands API. Keep this off unless you trust every user of
# every command computer, and the model, with operator-level commands.
allowCommandComputers = false

[limits]
maxRequestBytes = 262144
maxResponseBytes = 1048576
maxTokens = 8192               # cap applied to every request, on every backend
requestTimeoutSeconds = 180
maxConcurrentPerComputer = 1
maxConcurrentPerPlayer = 2
maxConcurrentTotal = 16
requestsPerMinutePerPlayer = 10
requestsPerMinutePerComputer = 10

[tools]
# Defaults for the ROM tool loader. They limit the model; they are not a sandbox against the user (SECURITY.md §4).
allowUserTools = true
allowDestructiveTools = true
disabledRomTools = []          # e.g. ["run_shell", "run_lua"]
maxToolSeconds = 30
maxToolOutputBytes = 16384
maxToolCallsPerTurn = 16
```

## Server providers: `world/serverconfig/ccassistant-providers.json` (server and either backends only)

```json
{
  "default": "nano",
  "allowLuaChoice": false,
  "providers": [
    {
      "name": "nano",
      "preset": "nanogpt",
      "baseUrl": "https://api.nano-gpt.com/api/v1",
      "apiKeyEnvVar": "NANOGPT_API_KEY",
      "model": "z-ai/glm-5.3",
      "maxTokens": 4096
    },
    {
      "name": "claude",
      "preset": "anthropic",
      "apiKeyEnvVar": "ANTHROPIC_API_KEY",
      "model": "claude-opus-5-5",
      "effort": "medium",
      "refusalFallback": true
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `default` | The provider used when a request names none. |
| `allowLuaChoice` | Whether a request's `profile` may pick another provider by name. |
| `name` | Name used in `ai_response` info and by `profile`. |
| `preset` | One of the PROVIDERS.md §1 presets (`anthropic`, `openai`, `openrouter`, `nanogpt`, `zai`, `ollama`, `lmstudio`, `custom-openai`, `custom-anthropic`). It fills in `protocol` and `baseUrl`. |
| `protocol` | `"openai"` or `"anthropic"`. Required for custom presets. |
| `baseUrl` | Must be `https://`, or `http://` to a loopback address. |
| `apiKeyEnvVar` | The **name** of the environment variable that holds the key. It may be left out for local servers. The key itself never goes in this file. |
| `model`, `maxTokens` | Model id, and a token cap (also limited by `limits.maxTokens`). |
| `effort`, `refusalFallback` | Anthropic protocol only. |
| `useMaxCompletionTokens`, `headers` | OpenAI protocol options. The same header rules as the client apply. |

`/assistant models <provider> [search]` (permission level 2) lists the provider's models in chat, using the server's key,
so operators can find model ids. `/assistant reload` reloads both files. Requests already in progress keep the settings
they started with.

## Client settings (player backend)

Provider profiles and keys are set in the **in-game UI**: **Mods** -> CC: Terminal Assistant -> **Config**, a key binding,
or `/assistant-client config`. See CLIENT_UI.md for the screens and for where the files are stored (outside the game
instance).

The only things in the instance config, `config/ccassistant-client.toml`, are non-secret switches:

```toml
enabled = false          # nothing is sent until you turn this on (also the checkbox in the UI)
requestsPerMinute = 6
requestsPerHour = 60
maxRequestBytes = 262144
notify = true            # an action-bar notice for each request
storageDir = ""          # optional: where profiles.json and credentials.json live; empty = user config folder
```

## Setup

### Player, player backend (for example with NanoGPT)
1. Install the mod on your client. The server needs it too, with `backend = "player"` or `"either"`.
2. Open **Mods** -> CC: Terminal Assistant -> **Config**.
3. Click **Add**, choose **NanoGPT**, and paste your NanoGPT API key.
4. Click **Choose...**, type part of a model name, pick a model with a **tools ✓** badge, then click **Save**.
5. Optionally click **Test**.
6. Tick **Let my linked computers use these providers**.
7. On a computer, run `ai link` and type `/assistant link <code>` in chat within 5 minutes.
8. Run `ai`.

**Know what the server sees:** the server runs the computer, so its operator can read your conversations with it, and
the model id used. Your keys, provider URLs and other profiles never leave your machine.

### Operator, server backend
1. Put the mod and CC: Tweaked 1.120.2+ in `mods/`.
2. Write `ccassistant-providers.json` as shown above.
3. Set each key **in the server's environment**, for example in a systemd `Environment=` line or the start script. Don't
   put keys in any file inside the server folder.
4. Set `backend = "server"` and run `/assistant reload`.
5. Use `/assistant models nano glm` to find model ids.
6. Consider lowering `limits.maxTokens` and the rate limits to control cost.

## Commands

| Command | Who | Effect |
|---|---|---|
| `/assistant link <code>` | any player | Link the computer showing `<code>` to you. |
| `/assistant unlink <computerId>` | that computer's owner, or level 2 | Remove the link. |
| `/assistant list` | any player (level 2: everyone's) | Your linked computers. |
| `/assistant status` | any player | Backend, your links, your rate-limit state. |
| `/assistant models <provider> [search]` | level 2 | List a server provider's models. |
| `/assistant reload` | level 2 | Reload the server config and providers. |
| `/assistant-client config\|on\|off\|status` | any player (runs on your own client) | Open the UI, turn the client side on or off, show this session's counts. |
