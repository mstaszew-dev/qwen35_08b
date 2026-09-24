#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/qwen35-gw" & GW_PID=$!
trap 'kill $GW_PID 2>/dev/null || true' EXIT
for i in $(seq 1 40); do curl -sf http://127.0.0.1:8091/healthz >/dev/null 2>&1 && break; sleep 0.25; done
curl -s http://127.0.0.1:8091/healthz; echo
curl -s http://127.0.0.1:8091/v1/models; echo
curl -s -X POST http://127.0.0.1:8091/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"mst/free","messages":[{"role":"user","content":"Say hello in exactly 5 words."}],"max_tokens":32}'; echo
curl -sN -X POST http://127.0.0.1:8091/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"qwen3.5-0.8b","messages":[{"role":"user","content":"Sing."}],"max_tokens":16,"stream":true}' | head -c 400; echo