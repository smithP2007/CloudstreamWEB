#!/usr/bin/env bash
# Launch a built package (build/csweb.tar.gz layout): no build tooling needed.
# Environment:
#   CS_WEB_DATA_DIR   data/plugins/SQLite root      (default ~/.cloudstream-web)
#   CS_WEB_STATIC_DIR SPA directory                 (default: packaged web-dist)
#   CS_WEB_LAN=1      bind 0.0.0.0 instead of 127.0.0.1
#   CS_WEB_LAN_TOKEN  require this shared token on /api (SPA prompts once)
#   PORT              listen port                   (default 8080)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

if [ -z "${CS_WEB_STATIC_DIR:-}" ]; then
    export CS_WEB_STATIC_DIR="$HERE/web-dist"
fi

exec "$HERE/server/bin/server"
