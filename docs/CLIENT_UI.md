# In-game configuration UI and credential storage (design for review)

Everything here is on the **player's own client**. The server never sees these screens, the base URLs, or the keys.

## 1. Opening it

- **Mods** menu -> CC: Terminal Assistant -> **Config**. This uses NeoForge's `IConfigScreenFactory`, so it also works from
  the title screen without joining a world.
- A key binding, **Open AI provider settings**, unbound by default, under the mod's category in Controls.
- The client command `/assistant-client config`.

All screens are built from vanilla widgets (`EditBox`, `Button`, `CycleButton`, `ObjectSelectionList`), so no extra
library is needed and they follow the game's GUI scale and narration.

## 2. Screens

### 2.1 Providers (main screen)
```
┌ AI Providers ─────────────────────────────────────────────────────────┐
│ [x] Let my linked computers use these providers                       │
│                                                                       │
│  ● nano      NanoGPT       z-ai/glm-5.3              tools ✓          │
│    claude    Anthropic     claude-opus-5-5           tools ✓          │
│    local     Ollama        qwen3:8b                  tools ?          │
│                                                                       │
│ [Add] [Edit] [Duplicate] [Delete] [Set default] [Test]                │
│ [Limits...]                                                [Done]     │
│ Keys are stored only on this computer (~/.config/ccassistant).       │
│ They are never sent to the Minecraft server.                         │
└───────────────────────────────────────────────────────────────────────┘
```
- The top checkbox is the client's `enabled` switch, the same as `/assistant-client on|off`. It is off by default.
- `●` marks the **default profile**, which is used when a request names no profile.
- **Test** sends one short request ("Reply with OK", `max_tokens` 16) after asking for confirmation, because it costs a
  tiny amount. It shows the reply or the error, and the time it took.

### 2.2 Edit profile
```
┌ Edit profile ─────────────────────────────────────────────────────────┐
│ Name         [nano            ]   (letters, digits, _ -; used by Lua) │
│ Provider     [< NanoGPT >]                                            │
│ Base URL     [https://api.nano-gpt.com/api/v1               ]         │
│ API key      [••••••••••••••••••••3f9a]  [Show] [Clear]               │
│              ( ) Store key   (•) Read from environment variable [NANO_KEY] │
│ Model        [z-ai/glm-5.3                 ]  [Choose...]             │
│ Max tokens   [8192 ]     Effort [< medium >] (Anthropic only)         │
│ [x] Computers may choose this profile by name                         │
│ Advanced: [x] use max_completion_tokens  Extra headers [Edit...]      │
│                                                                       │
│ ⚠ Follow your provider's terms. Some subscription plans may only be   │
│   used in specific apps.                                              │
│ [Test]                                   [Cancel]  [Save]             │
└───────────────────────────────────────────────────────────────────────┘
```
- Changing **Provider** fills in the preset's protocol and base URL (PROVIDERS.md §1). It overwrites them only if they
  still hold the previous preset's values, so custom edits survive.
- **API key:**
  - Editing it shows the characters. Once saved, only the last 4 are ever shown.
  - *Show* reveals it only while the screen is open.
  - Pasting works (Ctrl+V).
  - Local presets mark the key as optional.
- **Environment variable mode** stores only the variable's name. It is read when a request is made, which suits players
  who already keep keys in their shell environment.
- **Validation on Save:**
  - The name is unique and matches `^[A-Za-z0-9_-]{1,32}$`.
  - The base URL must be `https://`, or `http://` to a loopback address (`localhost`, `127.0.0.0/8`, `::1`).
  - Max tokens is between 1 and 128000.
- **Extra headers:** a list of name/value pairs sent to that provider only. Header names that carry credentials or decide
  routing are refused: `Authorization`, `x-api-key`, `Host`, `Cookie`, `Content-Length`. Values are stored with the
  non-secret profile data, so the screen says not to put secrets there.

### 2.3 Model picker
```
┌ Choose model: nano ───────────────────────────────────────────────────┐
│ Search [glm               ]   [x] Tool support only   [Refresh]      │
│                                                                       │
│  z-ai/glm-5.3            GLM 5.3          200k   $0.60/$2.20   tools ✓ │
│  z-ai/glm-5.3-air        GLM 5.3 Air      128k   $0.20/$1.10   tools ✓ │
│  thudm/glm-4-9b          GLM-4 9B          32k   $0.03/$0.03   tools ? │
│                                                                       │
│ 3 of 412 models. Prices per million input/output tokens.              │
│ Not listed? Type an id: [                        ] [Use]              │
│                                              [Cancel]  [Select]       │
└───────────────────────────────────────────────────────────────────────┘
```
- The list is fetched on a background thread, with a "Loading..." row, and cached for 24 hours (PROVIDERS.md §5). The
  game never freezes while it loads.
- **Search** filters as you type. A model matches when every space-separated word appears, ignoring case, in its id or
  name. Results are sorted with exact id matches first, then ids that start with the query, then the rest alphabetically.
- **Tool support only** hides models reported as not supporting tools. Unknown (`?`) models stay visible.
- Errors appear in the list area as a message ("401: check the API key", "No model list at this URL; type an id below").
  The full error goes to the client log, without the key.

### 2.4 Limits
The client limits from SECURITY.md §6 (requests per minute and per hour, request size cap, notices), plus this session's
request and token counts per profile.

## 3. Storage

| File | Contents | Where |
|---|---|---|
| `profiles.json` | Profiles **without secrets**: name, preset, protocol, base URL, model, max tokens, effort, flags, extra headers, key *mode* (stored or environment variable, and the variable name), default profile | user config folder (below) |
| `credentials.json` | `{ "<profile id>": "<key>" }` for stored keys only | user config folder, permissions `0600` on POSIX |
| `config/ccassistant-client.toml` | `enabled`, client limits, `notify`, and an optional `storageDir` override | the game instance's normal config |

**User config folder:**
- Windows: `%APPDATA%\ccassistant\`
- macOS: `~/Library/Application Support/ccassistant/`
- Linux/other: `$XDG_CONFIG_HOME/ccassistant/`, or `~/.config/ccassistant/`

Why it lives **outside the game instance**:
- Players share instance folders, modpack exports and `config/` folders, and paste config files into support chats. A
  key in there would leak.
- Keeping it outside also means every instance sees the same profiles.

`storageDir` can move both files, for example to a portable install. If it points inside `saves/`, it is refused.

**Honest limits of this storage:**
- The key is plain text on the player's disk, protected only by file permissions, as with `~/.aws/credentials` and most
  command-line tools.
- Java has no portable OS-keychain API, so keychain support isn't planned for v1.
- Anyone or anything that can read the player's files can read the key. Players who want more can use environment
  variable mode or a key limited by budget at their provider.

**Writes are atomic:** to a temporary file, then a rename, so a crash can't leave half a key file. A damaged file is
renamed to `*.broken-<time>` and the UI says so, rather than failing silently.

## 4. Data flow

1. A computer sends a request. The server finds the computer's owner and sends the request to that player's client,
   with the `profile` name if one was given.
2. The client checks, in order:
   - `enabled`
   - the profile exists (the default profile if none was named), and may be chosen by name if one was named
   - the client limits
   - the same request validation as the server
3. The client reads the key (stored, or from the environment variable), translates the request to the profile's
   protocol, sends it to the profile's base URL with redirects **off**, translates the reply back, and returns it.
4. The server gets only the reply in the mod's format: model id, content, stop reason, usage. It never gets the base URL,
   the key, the headers or the provider's name.

Errors that reach Lua are worded so they make sense on the server: "the owner's client refused the request: no default
profile set", "the provider returned 401". They never contain the URL or the key.

## 5. Not covered here

- **Server operators' providers** are configured in a file on the server, not in this UI (CONFIG.md). An in-game UI for
  them would have to send the operator's key over the network, which this design avoids.
- **Lua can't list or edit the owner's profiles.** It can only name one in `profile`, so a computer's user can't learn
  which providers the owner has.
