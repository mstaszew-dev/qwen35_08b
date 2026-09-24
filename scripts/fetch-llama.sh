#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin/llama-server"

if [ -x "$BIN" ]; then
  echo "llama-server already present: $BIN"
  exit 0
fi

if [ ! -d "$ROOT/llama.cpp" ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$ROOT/llama.cpp"
fi

if ! command -v cmake >/dev/null 2>&1; then
  brew install cmake
fi

mkdir -p "$ROOT/bin"
cmake -S "$ROOT/llama.cpp" -B "$ROOT/llama.cpp/build" -DGGML_METAL=ON -DGGML_CURL=OFF
cmake --build "$ROOT/llama.cpp/build" --target llama-server -j
cp "$ROOT/llama.cpp/build/bin/llama-server" "$BIN"
echo "built and installed $BIN"