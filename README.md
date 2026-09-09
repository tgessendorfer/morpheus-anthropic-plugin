# Morpheus Anthropic Claude LLM Plugin

An `LlmProvider` plugin for HPE Morpheus Enterprise 9.0.0+ that adds **Anthropic Claude** as a
native AI integration type under *Tools > AI Services > Integrations*.

Unlike pointing the shipped `github-copilot` integration at an OpenAI-compatible proxy, this plugin
speaks the **native Anthropic Messages API** (`POST /v1/messages`). That matters for Agents backed by
the Morpheus MCP server, because the OpenAI compatibility layer drops prompt caching and does not
guarantee tool-schema conformance.

## What it does

| Capability | Status |
|---|---|
| Chat completions | yes |
| Streaming chat (SSE) | yes |
| Tool use / function calling | yes — full bidirectional translation |
| Prompt caching | yes — system prompt + tool catalog, on by default |
| Extended thinking | yes — optional, with budget control |
| 1M token context (beta) | yes — optional, Sonnet 4.5+ |
| Usage / rate limit sync | yes — from the `anthropic-ratelimit-*` headers |
| Model catalog sync (`GET /v1/models`) | yes |
| Embeddings | no (Anthropic offers no embedding endpoint) |

## Prompt caching — why this plugin exists

The Morpheus MCP server advertises 67 tool definitions on first contact and grows from there.
Those definitions are **identical on every turn** of an Agent conversation, but without caching they
are re-billed as fresh input tokens each time — and a tool-heavy agent run is many turns.

With caching enabled (the default) the provider sets a `cache_control` breakpoint on the system
prompt and on the last tool definition, which makes Anthropic cache the whole stable prefix. Cache
reads are billed at roughly a tenth of normal input tokens, and time-to-first-token drops noticeably.
Cache hit/miss counts are written into the response metadata (`cache_read_input_tokens`,
`cache_creation_input_tokens`) so you can prove the effect during a demo.

This is exactly what the OpenAI compatibility layer cannot do — it drops prompt caching entirely.

### The interesting part: the tool bridge

Morpheus passes tool definitions and tool call results around in the **OpenAI shape**
(`tool_calls`, `tool_call_id` inside `LlmChatMessage.metadata`). Anthropic uses `tool_use` and
`tool_result` **content blocks**. `AnthropicProvider` translates both directions:

| Morpheus / OpenAI | Anthropic Messages API |
|---|---|
| `role: system` message | top-level `system` field (all system turns concatenated) |
| `tools: [{type:function, function:{name, description, parameters}}]` | `tools: [{name, description, input_schema}]` |
| `tool_choice: "required"` / `"none"` / `{function:{name}}` | `{type:"any"}` / `{type:"none"}` / `{type:"tool", name}` |
| assistant `tool_calls[]` with JSON-string `arguments` | assistant `tool_use` blocks with parsed object `input` |
| `role: tool` + `tool_call_id` | `tool_result` block inside a **user** message (consecutive results merged) |
| `finish_reason: tool_calls` | `stop_reason: tool_use` |
| `max_tokens` optional | `max_tokens` **mandatory** — defaulted per integration |

## Build

Requires JDK 11–17 (not 21 — Groovy 3.0.9) and the bundled Gradle wrapper 7.5.1.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew clean test shadowJar
# -> build/libs/morpheus-anthropic-plugin-1.0.0-all.jar
```

## Install

1. Morpheus UI: **Administration > Integrations > Plugins > + Add**, upload the `-all.jar`.
2. **Tools > AI Services > Integrations > + New Integration**, choose **Anthropic Claude**.
3. Fill in:
   - **API Endpoint**: `https://api.anthropic.com` (a full `/v1/messages` URL is tolerated and trimmed)
   - **Credentials**: Local Credentials, or a Cypher-stored `api-key` credential — the value is your `sk-ant-...` key
   - **Anthropic API Version**: leave at `2023-06-01`
   - **Default Max Output Tokens**: `8192` is a sane start; raise it for long agent answers
   - **Enable Prompt Caching**: leave on — see above
   - **Enable Extended Thinking** / **Thinking Budget**: off by default. When on, `temperature` and
     `top_p` are dropped (the API rejects them) and `max_tokens` is raised above the budget
     automatically. Thinking output is returned in the response metadata, not in the message body.
   - **Enable 1M Token Context**: off by default. Sends `anthropic-beta: context-1m-2025-08-07` and
     reports a 1M context window for Sonnet 4.5+ models only. Priced at a premium above 200k input
     tokens.
4. Save — the integration verifies by calling `GET /v1/models`, which also populates the model list.
5. **Tools > AI Services > Agents > Create Agent** → pick this integration, attach the built-in
   Morpheus MCP server, and add your system prompt.

## Requirements & caveats

- The **appliance** makes the outbound call. `api.anthropic.com:443` must be reachable from the
  appliance, not just from your workstation.
- Behind a TLS-inspecting proxy, put the proxy CA into `/etc/pki/ca-trust/source/anchors/`, run
  `update-ca-trust`, then `morpheus-ctl restart`.
- Authentication uses the `x-api-key` header. `apiToken` is deliberately left unset on the
  `HttpApiClient` request options, because a `Authorization: Bearer` header makes
  `api.anthropic.com` reject the request.
- Context window is reported as 200k unless the 1M beta is enabled (Sonnet 4.5+ only).
- The plugin is unsigned. Depending on appliance policy you may need to allow unsigned plugins.
- Usage metrics come from a tiny probe request (`max_tokens: 1`) issued during refresh, using the
  cheapest enabled model. Anthropic reports per-minute buckets for requests, input tokens and output
  tokens; Morpheus models a single token bucket, so the **input token** bucket is surfaced.

## Relationship to the Local LLM plugin

Morpheus also ships `local-llm-plugin` (`com.morpheusdata.localllm`) on the Exchange, which registers
two providers — `ollama` and `openai-compatible`. The two plugins are complementary, not competing:

| Use case | Plugin |
|---|---|
| Ollama on your own VM | `local-llm-plugin` → `ollama` |
| vLLM / LiteLLM / any OpenAI-shaped endpoint | `local-llm-plugin` → `openai-compatible` |
| Claude with caching, thinking and native tool use | this plugin |

Install both and you can switch an Agent between a local model and Claude by changing its LLM
integration, with no other configuration changes.

## Attribution

Derived from the Apache 2.0 licensed
[HewlettPackard/morpheus-copilot-plugin](https://github.com/HewlettPackard/morpheus-copilot-plugin)
(structure, Gradle setup, session-scoped HTTP client pattern). Licensed under Apache 2.0.
