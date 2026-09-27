# Qwen3.5 0.8B Local Gateway

A resident OpenAI-compatible gateway (Kotlin, compiled to a GraalVM native-image
binary) that serves `Qwen3.5-0.8B-Q4_K_M.gguf` through a local llama.cpp
`llama-server` child process.

- Listens on `127.0.0.1:8091`.
- Owns a `llama-server` child on `127.0.0.1:8101`, spawned on demand and unloaded
  after 10 idle minutes.
- Replaces the tailnet "laptop" qwen3.5 slot in msrouter/opencode with a local,
  free, zero-latency model.

## Quick start

```bash
sh scripts/download-model.sh   # fetch the GGUF once (idempotent, ~503 MB)
sh scripts/fetch-llama.sh      # clone + build llama-server once (idempotent, Metal)
sh scripts/build.sh            # gradle test + native-image build to ./qwen35-gw
```

Then run `./qwen35-gw` (defaults below match the paths on this machine), or install
the launchd agent so it autostarts at login.

## Architecture

```
                    +--------------------+
                    |   qwen35-gw        |
  opencode / curl   |   native gateway   |  spawns on demand (~0.7 s cold start)
 -----------------> |   127.0.0.1:8091   | -------------------------------+
                    |                    |                                  v
                    |  strip vision      |                +-------------------------------+
                    |  thinking off      |  /v1/chat/     |  llama-server (Metal)          |
                    |  32K prompt prune  |  completions   |  127.0.0.1:8101               |
                    |  model rewrite     | ------------>  |  Qwen3.5-0.8B-Q4_K_M.gguf     |
                    |  SSE relay         | <------------  |  --ctx-size 32768, q8_0 KV     |
                    +--------------------+    relayed     +-------------------------------+
                              ^
                              | idle scheduler: no requests for 10 min -> SIGTERM llama-server
                              + (next request cold-starts it again)
```

## API surface (127.0.0.1:8091)

| Method | Path                    | Description |
|--------|-------------------------|-------------|
| GET    | `/healthz`              | `{"status":"up","model":"qwen3.5-0.8b","loaded":true,"active":0}` |
| GET    | `/v1/models`            | OpenAI model list advertising id `qwen3.5-0.8b` |
| POST   | `/v1/chat/completions`  | OpenAI-compatible chat completions; SSE streaming is relayed raw |

Every chat request is transformed before relay: image parts are stripped
(`[image removed]`), thinking is forced off via `chat_template_kwargs`
`enable_thinking=false`, prompts above the 32K token budget are pruned
deterministically (system + last message kept), and the `model` field is rewritten
to `qwen3.5-0.8b`.

## Configuration (environment variables)

All variables are optional; defaults match a default install via the two fetch
scripts. A leading `~/` in paths expands to the home directory.

| Variable            | Default                                        | Description |
|---------------------|------------------------------------------------|-------------|
| `PORT`              | `8091`                                         | Gateway listen port |
| `UPSTREAM_PORT`     | `8101`                                         | llama-server port |
| `UPSTREAM_HOST`     | `127.0.0.1`                                    | llama-server host |
| `MODEL_ID`          | `qwen3.5-0.8b`                                 | Advertised model id + llama alias |
| `MODEL_PATH`        | `~/qwen35_08b/Qwen3.5-0.8B-Q4_K_M.gguf`       | GGUF model file |
| `LLAMA_SERVER_PATH` | `~/qwen35_08b/bin/llama-server`                | llama-server binary |
| `CTX_SIZE`          | `32768`                                        | llama `--ctx-size` |
| `THREADS`           | `10`                                           | llama `--threads` |
| `IDLE_MINUTES`      | `10`                                           | Idle minutes before the model is unloaded |
| `PRUNE_BUDGET`      | `30000`                                        | Prompt token budget for deterministic pruning |
| `LOG_DIR`           | `~/qwen35_08b/logs`                            | `llama-server.log` output |

## launchd autostart

The plist runs the gateway at login and keeps it alive:

```bash
cp com.zcode.qwen35-gw.plist ~/Library/LaunchAgents/
launchctl bootout gui/$(id -u)/com.zcode.qwen35-gw 2>/dev/null || true
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.zcode.qwen35-gw.plist
```

`KeepAlive` restarts the gateway if it exits; `RunAtLoad` starts it at login, so the
service survives re-login. Manual restart: `launchctl kickstart -k gui/$(id -u)/com.zcode.qwen35-gw`.
Logs go to `logs/gateway.out.log`, `logs/gateway.err.log`, and `logs/llama-server.log`.

2026-09-27 update (this machine): the LaunchAgent was removed from
`~/Library/LaunchAgents` - the gateway no longer autostarts at login; it is
started manually. Use the helper that bootstraps/bootouts the plist above:

```bash
~/bin/qwen35gw start|stop|restart|status
```

or run `./qwen35-gw` directly from the project directory.

## Model and license

- The GGUF is `Qwen3.5-0.8B-Q4_K_M.gguf` from the `lmstudio-community/Qwen3.5-0.8B-GGUF`
  repository on Hugging Face. Qwen3.5 is released under the Apache-2.0 license.
- llama.cpp (the `llama-server` runtime) is MIT-licensed; it links the model through
  Metal on Apple Silicon.

## Wiring into msrouter/opencode

This gateway replaces the tailnet "laptop" qwen3.5 slot:

- msrouter: `LAPTOP_ENABLED=true`, `LAPTOP_BASE_URL=http://127.0.0.1:8091/v1`,
  `LAPTOP_MODEL=qwen3.5-0.8b` in `.env`; chain-routing treats it as the weakest,
  absolute-last hop.
- opencode: the `laptop` provider points at `http://127.0.0.1:8091/v1` with model
  `qwen3.5-0.8b` (`limit.context: 32768`, `limit.output: 8192`), so cheap quick turns
  stay local and never touch an API bill.

The gateway must be reachable for opencode/msrouter to fall back to it; if it is down,
requests simply skip the laptop hop.