#!/usr/bin/env bash
# Bootstrap the cloud-stream-web environment on this machine.
# Idempotent: safe to re-run.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "== bootstrap =="

# --- JDK ----------------------------------------------------------------------
if command -v java >/dev/null 2>&1; then
  echo "java: $(java -version 2>&1 | head -1)"
else
  echo "java missing — install: sudo apt-get install openjdk-21-jdk-headless"
  exit 1
fi

# --- Gradle -------------------------------------------------------------------
# Project ships a wrapper (scripts/gradlew falls back to a local install).
if [ ! -x "$PROJECT_DIR/gradlew" ]; then
  GRADLE_HOME="${GRADLE_HOME:-$HOME/.tools/gradle-8.14.3}"
  if [ -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "generating gradle wrapper from $GRADLE_HOME"
    (cd "$PROJECT_DIR" && "$GRADLE_HOME/bin/gradle" wrapper --gradle-version 8.14.3 >/dev/null)
  else
    echo "gradle missing at $GRADLE_HOME — install it or set GRADLE_HOME"
    exit 1
  fi
fi

# --- submodule ----------------------------------------------------------------
(cd "$PROJECT_DIR" && git submodule update --init --depth 1 upstream 2>/dev/null || true)

# --- enjarify (dex->jar, phase 2) ---------------------------------------------
mkdir -p "$PROJECT_DIR/plugin-runtime/tools"
if [ ! -d "$PROJECT_DIR/plugin-runtime/tools/enjarify" ]; then
  echo "DOWNLOADME enjarify" # downloaded in phase 2 once the loader needs it
fi

# --- web deps -----------------------------------------------------------------
if [ ! -d "$PROJECT_DIR/web/node_modules" ]; then
  (cd "$PROJECT_DIR/web" && npm install --no-audit --no-fund)
fi

echo "== bootstrap done =="
