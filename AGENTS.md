# AGENTS.md - qwen35_08b

Local Qwen3.5 0.8B gateway: a GraalVM native-image Kotlin binary (`qwen35-gw`)
that serves a llama.cpp `llama-server` child process and exposes an
OpenAI-compatible endpoint on `127.0.0.1:8091`.

## Build

The frozen toolchain lives inside this repo under `toolchain/`. Always build with
these exact env vars and the bundled Gradle:

```bash
export JAVA_HOME=/Users/mst/qwen35_08b/toolchain/graalvm
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME=/Users/mst/qwen35_08b/toolchain/gradle-home
/Users/mst/qwen35_08b/toolchain/gradle-8.14/bin/gradle -p /Users/mst/qwen35_08b test nativeCompile
cp build/native/nativeCompile/qwen35-gw ./qwen35-gw
```

`scripts/build.sh` wraps exactly this (env is frozen inside the script). The
ready-to-run binary is `/Users/mst/qwen35_08b/qwen35-gw`.

## Test and native commands

- Run all tests: same env as above, then `gradle -p /Users/mst/qwen35_08b test`.
  JUnit 5 (`useJUnitPlatform`); tests live under `src/test/kotlin/com/zcode/qwen35gw/`.
- Native-only compile (no tests): `gradle -p /Users/mst/qwen35_08b nativeCompile`.
- Full build (tests + native): `sh scripts/build.sh`.
- Manual smoke against the real model: `sh scripts/smoke.sh` (requires the GGUF
  and Metal).

## Frozen llama-server flags

The gateway spawns llama-server with exactly these flags (see
`src/main/kotlin/com/zcode/qwen35gw/runtime/LlamaServerProcess.kt`):
do not change them without re-validating the spike facts (perf, KV cache size,
reasoning behavior):

```
llama-server -m <model> --host 127.0.0.1 --port 8101 --ctx-size 32768 -ngl 99
  --flash-attn on --parallel 1 --threads 10 --jinja --alias qwen3.5-0.8b
  --cache-type-k q8_0 --cache-type-v q8_0 --reasoning off
```

Per-request thinking off is ALSO injected by the gateway via
`"chat_template_kwargs":{"enable_thinking":false}`; `--reasoning off` is only the
hard server-side default.

## Idle-unload design

The gateway tracks request activity (`RequestTracker`) and an
`IdleUnloadScheduler` ticks every 30 s: if llama-server is RUNNING and no request
has arrived for `IDLE_MINUTES` (default 10), it SIGTERMs llama-server
(`destroy()` then escalate to `destroyForcibly()` only if the stop timeout of 5 s
elapses). The next request cold-starts llama-server in about 0.7 s and waits on
health `GET /health` on `127.0.0.1:8101` before serving.

## launchd

The launchd agent is installed at `~/Library/LaunchAgents/com.zcode.qwen35-gw.plist`
(Label `com.zcode.qwen35-gw`). RunAtLoad + KeepAlive keep the gateway up across
login and crashes. Reinstall:

```bash
cp com.zcode.qwen35-gw.plist ~/Library/LaunchAgents/
launchctl bootout gui/$(id -u)/com.zcode.qwen35-gw 2>/dev/null || true
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.zcode.qwen35-gw.plist
```

Logs: `logs/gateway.out.log`, `logs/gateway.err.log`, `logs/llama-server.log`.

## Do not commit

Never stage or commit: `*.gguf`, `toolchain/`, `llama.cpp/`, `logs/`, `bin/`
(all gitignored), plus `build/` artifacts and the copied `./qwen35-gw` binary
(regenerate via `scripts/build.sh`; the `toolchain/` tar.gz archives must not be
pushed to a remote).

## Conventions

- Conventional commits (`feat:`, `fix:`, `build:`, `refactor:`, `test:`).
- No em dashes (U+2014) in docs, commits, or chat.
- TDD when changing behavior: write a failing test first, keep production code
  pure and deterministic; no comments in scripts unless the script text itself
  carries them.