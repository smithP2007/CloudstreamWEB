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

# --- dex2jar (dex->jar WITH annotations, needed for .cs3 loading) ---------------
# enjarify alone drops annotations which breaks @JsonProperty-based providers.
mkdir -p "$PROJECT_DIR/plugin-runtime/tools"
if [ ! -f "$PROJECT_DIR/plugin-runtime/tools/dex2jar/d2j-dex2jar.sh" ]; then
  echo "downloading pxb1988/dex2jar v2.4 (Apache-2.0)"
  curl -sL -o /tmp/dex-tools.zip "https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip"
  unzip -oq /tmp/dex-tools.zip -d /tmp/dex-tools-x
  rm -rf "$PROJECT_DIR/plugin-runtime/tools/dex2jar"
  mkdir -p "$PROJECT_DIR/plugin-runtime/tools/dex2jar"
  cp -r /tmp/dex-tools-x/dex-tools-v2.4/. "$PROJECT_DIR/plugin-runtime/tools/dex2jar/"
  chmod +x "$PROJECT_DIR/plugin-runtime/tools/dex2jar/"*.sh
  rm -rf /tmp/dex-tools.zip /tmp/dex-tools-x
fi

# --- ffmpeg/ffprobe (v2: audio remux, live transcode, source probing) ----------
# Static builds land in the gitignored tools dir; PlaybackEngine resolves
# tools/ffmpeg/bin first, then CS_WEB_FFMPEG, then PATH.
FFMPEG_BIN="$PROJECT_DIR/tools/ffmpeg/bin"
if [ ! -x "$FFMPEG_BIN/ffmpeg" ] || [ ! -x "$FFMPEG_BIN/ffprobe" ]; then
  case "$(uname -m)" in
    x86_64)  FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz" ;;
    aarch64) FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz" ;;
    *)       FFMPEG_URL="" ;;
  esac
  if [ -z "$FFMPEG_URL" ]; then
    echo "ffmpeg: no static build for $(uname -m) — install ffmpeg via your package manager"
  else
    echo "downloading johnvansickle static ffmpeg (GPL-3.0, see THIRD_PARTY_NOTICE.md)"
    curl -sL -o /tmp/ffmpeg-static.tar.xz "$FFMPEG_URL"
    rm -rf /tmp/ffmpeg-x
    tar -xJf /tmp/ffmpeg-static.tar.xz -C /tmp
    mv /tmp/ffmpeg-*-static /tmp/ffmpeg-x
    mkdir -p "$FFMPEG_BIN"
    cp /tmp/ffmpeg-x/ffmpeg /tmp/ffmpeg-x/ffprobe "$FFMPEG_BIN/"
    chmod +x "$FFMPEG_BIN"/*
    rm -rf /tmp/ffmpeg-static.tar.xz /tmp/ffmpeg-x
  fi
fi

# --- web deps -----------------------------------------------------------------
if [ ! -d "$PROJECT_DIR/web/node_modules" ]; then
  (cd "$PROJECT_DIR/web" && npm install --no-audit --no-fund)
fi

echo "== bootstrap done =="
