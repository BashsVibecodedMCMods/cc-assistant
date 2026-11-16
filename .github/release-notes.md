First prerelease of CC: Terminal Assistant. Unofficial; not affiliated with any model provider.

**Requires:** Minecraft 1.21.1, NeoForge 21.1.x (built against 21.1.249), CC: Tweaked 1.120.2+. Install on the server, and on each client that should serve its owner's computers.

- `ai` Lua API on every computer, the `cc.ai` module (conversations with a tool loop), and an `ai` program for chatting.
- Works with any OpenAI-compatible or Anthropic-compatible provider: presets for Anthropic, OpenAI, OpenRouter, NanoGPT, Z.AI (pay-as-you-go API), Ollama and LM Studio, plus custom endpoints.
- Backends: `player` (each owner's own client and keys), `server` (operator's providers, keys in environment variables), `either`, or `none` (default).
- In-game provider settings (Mods > CC: Terminal Assistant > Config): profiles, masked keys, searchable model list. Keys are stored outside the game folder and never sent to the server.
- Computers are linked to players with a one-time code (`ai link`, then `/assistant link <code>`). An offline owner gives a clear error, and the `ai` program can wait for them.
- Tools from `/rom/tools` (files, `run_lua`, `run_shell`) and your own in `/tools` (exposed as `user_<name>`). Changes need approval; startup files, `/tools` and `.settings` are always protected. Command computers are blocked by default.
- Limits on request size, tokens, rate, concurrency, time and tool output, on both server and client.

Automated tests pass: 52 unit tests (including the HTTP client against a local server) and 10 game tests on real CC computers. **Not yet checked:** the in-game screens, live calls to real providers, and a real two-player session. Subscription plans that are limited to specific apps (Claude Pro/Max/Ultra, Z.AI GLM Coding Plan) can't be used.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
