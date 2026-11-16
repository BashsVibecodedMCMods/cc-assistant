# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is
A NeoForge add-on for CC: Tweaked (Minecraft 1.21.1, NeoForge 21.1.249, CC: Tweaked 1.120.2+, Java 21) that lets CC
computers talk to AI models through any OpenAI-compatible or Anthropic-compatible API. Mod id `ccassistant`, package
`dev.ccassistant`, Lua global `ai`. The design docs in `docs/` are the spec; keep them in sync with behaviour.

## Commands
- Build the jar and run the unit tests: `./gradlew build`. JUnit tests in `src/test` run through ModDevGradle's
  `unitTest`, which boots the mod, so CC: Tweaked is on the test runtime classpath.
- Game tests (real CC computers): `./gradlew runGameTestServer`. The exit code reflects pass/fail. Test names are
  lowercased method names. `dev/ccassistant/test/**` and `data/ccassistant/structure/**` are excluded from the jar.
- CI is `.github/workflows/release.yml`, copied from the sibling mods. Pushing `MC<mc>-<mod version>[-suffix]` publishes a
  release with `.github/release-notes.md` as its notes. A suffix makes it a prerelease, and the mod version in the tag
  must equal `mod_version`. A manual run of the workflow with a `tag` input does the same without pushing a tag: GitHub
  creates the tag on the run's commit when it publishes. Use that when tag pushes aren't possible.

## Architecture
- **Provider layer** (`provider/`, plain Java, shared by server and client):
  - `Protocol` with `OpenAiProtocol` (Chat Completions) and `AnthropicProtocol` (Messages, `anthropic-version: 2026-10-21`).
    They translate the mod's message format (docs/PROVIDERS.md section 2) to and from the wire.
  - `ProviderHttp` never follows redirects, allows only https or loopback http (`UrlRules`), caps response sizes, and
    scrubs keys and URLs from errors.
  - Raw `java.net.http` plus Gson is deliberate: no SDKs in the mod jar.
- **Server**:
  - `api/AiAPI` is the per-computer `ILuaAPI`.
  - `server/AssistantRuntime` holds links, owner states, quotas and requests in progress. Every request ends in exactly
    one `ai_response` or `ai_error`, unless the computer shut down.
  - Routing is in `decide()`. `link/OwnerStates` builds ready/offline/no_client/disabled from login, logout and the
    client's one-bit status, and fires `ai_owner` and fails player-routed requests on change.
  - `net/Transfers` chunks JSON messages, because serverbound custom payloads are capped at 32 KiB.
- **Client** (`client/`, client-only):
  - `ProfileStore` keeps `profiles.json` (no secrets) and `credentials.json` (0600) in the user config folder.
  - `ClientRequests` re-validates everything the server sends and applies its own limits and model.
  - The screens are in `client/screen`. Common code must reach client classes only behind `FMLEnvironment.dist.isClient()`.
- **Lua ROM** (`data/computercraft/lua/rom`):
  - `cc.ai` (Conversation and tool loop), `cc.ai.tools` (discovery, approval, limits, `resolve`), `cc.ai.capture`.
  - Default tools are in `rom/tools`, plus the `ai` program.

## Rules
- Never put keys in config, world data, logs or Lua; never send a client key to the server.
- Lua strings are byte strings: decode as UTF-8 going in (`LuaJson.decode`) and return `ByteBuffer`s going out.
- Game tests change global config, so each one sets every value it relies on in `configure()` and uses its own `batch`.
  The test world in `run/` persists between runs.
- Test hooks: `AssistantRuntime.serverChatOverride`, `transportOverride` and `useProviders()`.
