# Design: Qwen3.5 0.8B local gateway (GraalVM/Kotlin + llama.cpp)

Date: 2026-09-24. Status: approved 2026-09-24.

## Goal

Install `Qwen3.5-0.8B` (4-bit Q4_K_M, ~533 MB) locally, serve it on a fast,
memory-thrifty OpenAI-compatible gateway, autostart it at login, and replace
the tailnet "laptop qwen" slot in msrouter/opencode with this local model.

## Decisions (user-approved)

- Project folder: `~/qwen35_08b` (runtime home, outside the ZCodeProject hub).
- Gateway port: `127.0.0.1:8091` (none of 11434/1234/1235/8787).
- Wiring: replace the tailnet `laptop` provider slot (msrouter `LAPTOP_*` env +
  opencode.json `laptop` provider) with the local 0.8B gateway.
- Idle unload timeout: 10 minutes (configurable); model reloads in ~1-2 s on
  the next request.
- Model context: 32K tokens, 4-bit quant. Gateway deterministically prunes
  requests up to 128K down to 32K max.
- Push result to GitHub when complete.

## Architecture

```
opencode / msrouter / curl
        │  POST /v1/chat/completions, GET /v1/models, GET /healthz
        ▼
qwen35-gw  (Kotlin, GraalVM native-image, listens 127.0.0.1:8091)
   │ rewrite model -> qwen3.5-0.8b
   │ strip vision (image_url parts)
   │ strip thinking (thinking/reasoning fields; force thinking off)
   │ deterministic prune messages+tools -> <=30K est. tokens
   │ serialize on single slot
   ▼  (child process, port 127.0.0.1:8101)
llama-server  (llama.cpp arm64/Metal, Qwen3.5-0.8B-Q4_K_M.gguf, n_ctx 32768)
```

Auto-start: LaunchAgent `com.zcode.qwen35-gw` (RunAtLoad, KeepAlive) keeps the
gateway resident; llama-server is spawned by the gateway on demand and killed
after the idle timeout (frees ~1 GB RAM).

## Components in `~/qwen35_08b/`

- `Qwen3.5-0.8B-Q4_K_M.gguf` - model file (downloaded; gitignored, GitHub cap).
- `bin/llama-server` - llama.cpp server built for arm64/macOS with Metal.
- `qwen35-gw` - GraalVM native executable (Kotlin).
- `src/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle wrapper`.
- `scripts/download-model.sh`, `scripts/fetch-llama.sh`.
- `docs/superpowers/specs/` design doc; `README.md`; `AGENTS.md`.
- `com.zcode.qwen35-gw.plist` - LaunchAgent template.
- `logs/` - stdout/stderr.

## Gateway behavior

- `POST /v1/chat/completions` (SSE streaming passthrough), `GET /v1/models`
  (advertises `qwen3.5-0.8b`), `GET /healthz`.
- **Vision strip:** remove `content[]` parts with `type == "image_url"`;
  a message left with no text gets `"[image removed]"`. Pure function.
- **Thinking off by default:** drop `thinking` / `reasoning` fields from the
  body; force disable via chat-template mechanism validated in the spike
  (`chat_template_kwargs.enable_thinking=false`, reasoning budget 0, or a
  llama-server flag). First working mechanism wins; contract is: no think
  tokens in output by default.
- **Deterministic pruning (128K -> 32K):** estimate tokens as
  `floor(chars/4)` per text part + 10/message + tool-definition JSON (matches
  msrouter `estimatePromptTokens`). If the prompt exceeds 30,000, drop oldest
  non-system messages, then truncate the newest message to fit. Deterministic,
  no summarization. Tools count into the same budget.
- **Idle unload:** `lastRequestAt` timestamp; a 30 s ticker SIGTERMs
  llama-server when warm and idle for `IDLE_MINUTES` (default 10). On arrival
  with a cold model, spawn llama-server and poll `/health` until ready
  (timeout ~30 s) before forwarding. In-flight requests are never interrupted:
  SIGTERM only when the slot is free.
- **Single slot:** requests serialized through a queue (llama-server
  `--parallel 1`); upstream failures return gateway 503 JSON.
- **Model rewrite:** inbound `model` is rewritten to `qwen3.5-0.8b` before
  forwarding (llama-server `--alias qwen3.5-0.8b`).

## llama-server flags (speed + memory)

`-m <gguf> --host 127.0.0.1 --port 8101 --ctx-size 32768 -ngl 99
--flash-attn on --parallel 1 --threads 10 --metrics off --jinja
--alias qwen3.5-0.8b --cache-type-k q8_0 --cache-type-v q8_0`

KV cache in q8_0 halves RAM with negligible speed cost on Apple silicon
(validated in spike). GGUF is mmap'd (default).

## Autostart

`~/Library/LaunchAgents/com.zcode.qwen35-gw.plist`:
ProgramArguments = `[~/qwen35_08b/qwen35-gw]`, RunAtLoad true, KeepAlive true,
WorkingDirectory `~/qwen35_08b`, StandardOut/ErrPath -> `logs/`. Loaded via
`launchctl bootstrap gui/uid`.

## Wiring changes

- msrouter `.env`: `LAPTOP_ENABLED=true`, `LAPTOP_BASE_URL=http://127.0.0.1:8091/v1`,
  `LAPTOP_MODEL=qwen3.5-0.8b`. Env names kept (comment updated: "local 0.8B
  gateway, was tailnet laptop"). msrouter comments, `.env.example`, and tests
  updated to match.
- opencode.json: `laptop` provider baseURL -> `http://127.0.0.1:8091/v1`,
  model `qwen3.5-0.8b`, `limit.context=32768`, output 8192, tool_call true,
  attachment false.

## Memory/space conservation

- llama-server mmap + q8_0 KV + unload-on-idle bounds RAM.
- 32K ctx bounds KV cache.
- GGUF not committed to git (GitHub 100 MB limit); `scripts/download-model.sh`
  fetches it.

## Testing (TDD)

kotlin.test + JUnit under JVM; native-image smoke after. Units:
- Vision strip, thinking strip, token estimator, deterministic pruner
  (property: output fits budget; system + newest preserved; determinism).
- Config parsing (env/args).
- Process manager with a fake launcher: spawn/kill, health poll, start failure.
- Idle scheduler with injectable clock: unloads at N, reloads on request,
  never kills an in-flight request.
- HTTP integration against a stub upstream server: rewrite, SSE passthrough,
  prune, vision/thinking on the wire, /models, /healthz, error mapping.

## Risks / spike (phase 0)

Qwen3.5-0.8B uses a hybrid/DeltaNet-style arch; llama.cpp needs a recent build
(local 2B notes used b10333). Spike objective: obtain a working llama.cpp
build, load the 0.8B GGUF, confirm thinking-off path and `/v1/chat/completions`
contract, measure tokens/s and KV size, then freeze the flags.