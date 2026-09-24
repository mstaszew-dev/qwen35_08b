#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL="$ROOT/Qwen3.5-0.8B-Q4_K_M.gguf"
URL="https://huggingface.co/lmstudio-community/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
MIN_BYTES=$((400 * 1024 * 1024))

if [ -f "$MODEL" ]; then
  SIZE=$(stat -f%z "$MODEL" 2>/dev/null || echo 0)
  if [ "$SIZE" -gt "$MIN_BYTES" ]; then
    echo "model already present: $(ls -lh "$MODEL" | awk '{print $5}') at $MODEL"
    exit 0
  fi
  echo "model file exists but is only $SIZE bytes, re-downloading"
fi

curl -L -C - -o "$MODEL" "$URL"
echo "download complete: $(ls -lh "$MODEL")"