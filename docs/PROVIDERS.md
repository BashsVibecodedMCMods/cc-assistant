# Model providers (design for review)

The mod works with any provider that offers one of two wire protocols. Between them they cover almost every hosted and
local LLM API:

| Protocol | Request | Model list | Used by |
|---|---|---|---|
| **OpenAI Chat Completions** | `POST {base}/chat/completions`, `Authorization: Bearer <key>` | `GET {base}/models` | OpenAI, OpenRouter, NanoGPT, Z.AI, Groq, Together, DeepSeek, Mistral, Ollama, LM Studio, vLLM, ... |
| **Anthropic Messages** | `POST {base}/v1/messages`, `x-api-key: <key>`, `anthropic-version` | `GET {base}/v1/models` | Anthropic, plus providers with an Anthropic-compatible endpoint (NanoGPT, Z.AI) |

The OpenAI **Responses** API isn't used. Chat Completions is the protocol nearly every other provider copies, and OpenAI
still serves it. A Responses adapter could be added later behind the same interface.

## 1. Built-in presets

A preset fills in the protocol, base URL and model-list handling. Every field stays editable, and *Custom* presets start
empty.

| Preset | Protocol | Base URL | Model list | Notes |
|---|---|---|---|---|
| Anthropic | Anthropic | `https://api.anthropic.com` | `/v1/models` | Console API key. Requests use `anthropic-version: 2026-10-21`. |
| OpenAI | OpenAI | `https://api.openai.com/v1` | `/models` (ids only) | Can send `max_completion_tokens` instead of `max_tokens` (profile option). |
| OpenRouter | OpenAI | `https://openrouter.ai/api/v1` | `/models`, with `supported_parameters`, `context_length` and `pricing` | Sends the optional `X-OpenRouter-Title: CC Terminal Assistant` header. |
| NanoGPT | OpenAI | `https://api.nano-gpt.com/api/v1` | `/models?detailed=true` (capabilities, pricing) | NanoGPT recommends the `api.` host for API keys, because it allows larger and longer requests. It has no IPv6 address, so IPv6-only players should switch to `https://nano-gpt.com/api/v1`. NanoGPT's own Anthropic-compatible endpoint (`/api/v1/messages`) can be used with *Custom Anthropic-compatible*. |
| Z.AI (pay-as-you-go API) | OpenAI | `https://api.z.ai/api/paas/v4` | none documented; type the model id | `tool_choice` supports `auto` only. |
| Ollama (local) | OpenAI | `http://localhost:11434/v1` | `/models` | No key. Plain `http` is allowed only for loopback addresses. |
| LM Studio (local) | OpenAI | `http://localhost:1234/v1` | `/models` | No key. |
| Custom (OpenAI-compatible) | OpenAI | (enter) | try `/models`, else type the id | |
| Custom (Anthropic-compatible) | Anthropic | (enter) | try `/v1/models`, else type the id | |

Sources, read 2026-11-16:
- NanoGPT: [introduction](https://docs.nano-gpt.com/introduction) and
  [models endpoint](https://docs.nano-gpt.com/api-reference/endpoint/models).
- OpenRouter: [API overview](https://openrouter.ai/docs/api/reference/overview) and
  [models](https://openrouter.ai/docs/guides/overview/models).
- Z.AI: [HTTP introduction](https://docs.z.ai/guides/develop/http/introduction),
  [function calling](https://docs.z.ai/guides/capabilities/function-calling) and
  [Coding Plan in other tools](https://docs.z.ai/devpack/tool/others).
- Anthropic `/v1/models`: Anthropic API docs.

**Not verified:** the OpenAI reference page returned 403 to my fetch, so the OpenAI `/models` shape (ids with no
capability fields) is from general knowledge. The first build should be tested against each preset with a real key.

### Plans that may not be used here

Some providers sell subscription plans whose terms restrict them to particular apps. Those plans can't be supported, for
the same reason Claude subscriptions can't (FEASIBILITY.md §2):
- **Claude Pro/Max/Ultra:** third-party apps may not use Claude.ai login or subscription credentials.
- **Z.AI GLM Coding Plan:** *"limited to use within the following officially supported tools and product environments"*,
  and users *"may not use their subscription benefits for tools or scenarios outside of this scope"*. Its endpoints
  (`/api/coding/paas/v4`, `/api/anthropic`) are **not** offered as presets.

The presets point only at pay-per-use API endpoints. A custom profile can point anywhere, so the provider-terms warning
in the UI (CLIENT_UI.md §3) applies: the user is responsible for following their provider's terms. NanoGPT's own docs
describe using its subscription through its API, so it is offered normally.

## 2. The mod's own message format

Lua always uses **one** format, whichever provider answers. It uses content blocks because they hold tool calls and tool
results without loss, and both protocols translate cleanly to and from them.

```lua
-- request (from Lua)
{
  system = "You are ...",                         -- optional string
  messages = {
    { role = "user", content = "List my files" },  -- a string, or an array of blocks
    { role = "assistant", content = {
        { type = "text", text = "Sure." },
        { type = "tool_use", id = "call_1", name = "list_files", input = { path = "/" } } } },
    { role = "user", content = {
        { type = "tool_result", tool_use_id = "call_1", content = "startup.lua\ntools/", is_error = false } } },
  },
  tools = { { name = "list_files", description = "...", input_schema = { type = "object", properties = {} } } },
  tool_choice = "auto",       -- "auto" | "none"
  max_tokens = 2048,          -- capped by the profile and the server
  stop_sequences = { },       -- up to 4
  profile = "cheap",          -- optional: choose one of the owner's profiles by name (player backend)
}

-- response (to Lua)
{
  model = "z-ai/glm-5.3",
  stop_reason = "tool_use",   -- "end_turn" | "tool_use" | "max_tokens" | "refusal" | "stop_sequence" | "other"
  content = {
    { type = "reasoning", text = "...", protocol = "openai" },   -- only if the provider returned reasoning
    { type = "text", text = "..." },
    { type = "tool_use", id = "call_2", name = "read_file", input = { path = "a.txt" } },
  },
  usage = { input_tokens = 812, output_tokens = 95 },           -- missing if the provider sent none
}
```

**Block types:** `text`, `tool_use`, `tool_result`, `reasoning`.
- A `reasoning` block has the model's visible reasoning, if any, as `text`, plus a `protocol` tag. Anthropic thinking
  blocks also carry an opaque `raw` field.
- Lua should copy reasoning blocks back unchanged. When the next request goes to the **same protocol** they are replayed:
  Anthropic needs its thinking blocks back. Otherwise they are dropped.

## 3. Translation to and from OpenAI Chat Completions

Mod format -> OpenAI:

| Mod format | OpenAI Chat Completions |
|---|---|
| `system` | first message `{ role = "system", content = ... }` |
| user `text` blocks | `{ role = "user", content = "<texts joined with blank lines>" }` |
| user `tool_result` block | its own `{ role = "tool", tool_call_id = ..., content = ... }`, in the original order. `is_error = true` adds `Error: ` to the content. Text blocks after the results become a separate user message after the tool messages. |
| assistant `text` blocks | `content` (joined) |
| assistant `tool_use` blocks | `tool_calls = { { id, type = "function", ["function"] = { name, arguments = <JSON string of input> } } }` |
| `reasoning` blocks | dropped |
| `tools` | `{ type = "function", ["function"] = { name, description, parameters = input_schema } }` |
| `tool_choice` | `"auto"` / `"none"`. Left out entirely when the request has no tools. |
| `max_tokens` | `max_tokens`, or `max_completion_tokens` when the profile says so |
| `stop_sequences` | `stop` |

OpenAI -> mod format, read from `choices[1]`, the first choice:
- `message.content` (a string or an array of text parts) becomes a `text` block.
- `message.reasoning_content` or `message.reasoning` becomes a `reasoning` block.
- Each item of `message.tool_calls` becomes a `tool_use` block:
  - A missing `id` is replaced with `call_<n>`.
  - `arguments` is decoded from JSON. If decoding fails, `input = {}` and `input_error = "<the decode error>"`; the tool
    loader then sends that error back as the tool's result, so the model can try again.
- `finish_reason` maps: `stop` -> `end_turn`, `tool_calls` -> `tool_use`, `length` -> `max_tokens`,
  `content_filter` -> `refusal`, anything else -> `other`.
- `usage.prompt_tokens` and `usage.completion_tokens` become `input_tokens` and `output_tokens`.

## 4. Translation to and from Anthropic Messages

This is nearly one-to-one:
- `reasoning` blocks with `protocol = "anthropic"` are replayed from `raw` as `thinking`/`redacted_thinking`.
- `tool_choice` becomes `{ type = "auto" }` or `{ type = "none" }`.
- Responses map `thinking`/`redacted_thinking` to `reasoning` with `raw`, and copy `stop_reason`.
- The profile can set `effort`, sent as `output_config.effort`, and the server-side refusal fallback.

## 5. Model listing and capabilities

The model picker (CLIENT_UI.md §2) asks the provider for its models, **from the player's own machine, with the player's
own key**. Each entry is normalised to:

```
{ id, name?, contextLength?, inputPrice?, outputPrice? (USD per million tokens), tools = true | false | nil }
```

`tools = nil` means unknown. Where each provider's details come from:
- **OpenRouter:** `supported_parameters` contains `"tools"`; `context_length`; `pricing.prompt` and `pricing.completion`
  are per-token values, so they are multiplied by 10^6.
- **NanoGPT:** `?detailed=true` gives capabilities and pricing. The exact field names will be confirmed against a live
  response before the parser is written.
- **Anthropic:** `/v1/models` gives `id`, `display_name` and context information. All current Claude models support tools.
- **Others:** ids only, so `tools` is unknown.

The list is cached per profile for 24 hours, with a *Refresh* button. When a provider has no list, or the request fails,
the picker falls back to typing the model id.

## 6. Differences that remain between providers

- **Tool calling varies by model.** Some models ignore `tools`, some return an error, and some write tool calls as
  plain text. The mod doesn't parse tool calls out of text; the picker's tool badge and `ai --no-tools` are the remedies.
  An error mentioning tools gets the hint "this model may not support tool calling".
- **Reasoning formats differ.** Some providers require earlier reasoning *not* to be sent back, and the OpenAI
  translation never sends it back. A provider that needs it back over the OpenAI protocol would need a profile option,
  not planned for v1.
- **No streaming in v1.** Every request is a single response, with long timeouts. Streaming would mean partial events to
  Lua, and could be added later.
- **Costs** come from the provider's pricing. The picker shows prices where the provider reports them, and the client's
  token counters count tokens, not money.
