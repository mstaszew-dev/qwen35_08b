#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$ROOT/toolchain/graalvm"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$ROOT/toolchain/gradle-home"
"$ROOT/toolchain/gradle-8.14/bin/gradle" -p "$ROOT" test nativeCompile
cp "$ROOT/build/native/nativeCompile/qwen35-gw" "$ROOT/qwen35-gw"
echo "Built $ROOT/qwen35-gw"