#!/usr/bin/env bash
# Build the web bundle and start the backend (serving it).
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

cd "$PROJECT_DIR/web"
npm run build

cd "$PROJECT_DIR"
if [ -x ./gradlew ]; then ./gradlew :server:installDist --quiet; else gradle :server:installDist --quiet; fi

exec "$PROJECT_DIR/server/build/install/server/bin/server"
