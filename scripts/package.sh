#!/usr/bin/env bash
# Build a distributable tarball: prebuilt server + SPA + launcher + unit file.
# Output: build/csweb.tar.gz — see README "Running from the package".
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> building SPA"
( cd "$ROOT/web" && npm run build --silent )

echo "==> building server"
if [ -x "$ROOT/gradlew" ]; then
    ( cd "$ROOT" && ./gradlew :server:installDist --quiet )
else
    ( cd "$ROOT" && gradle :server:installDist --quiet )
fi

DIST="$ROOT/build/dist"
STAGE="$DIST/csweb"
rm -rf "$DIST" && mkdir -p "$STAGE"

cp -r "$ROOT/server/build/install/server" "$STAGE/server"
cp -r "$ROOT/web/dist" "$STAGE/web-dist"
cp "$ROOT/scripts/run-packaged.sh" "$STAGE/run.sh"
cp "$ROOT/scripts/cloudstream-web.service" "$STAGE/"
cp "$ROOT/docs/api.md" "$STAGE/API.md"
chmod +x "$STAGE/run.sh"

echo "==> compressing"
tar -czf "$ROOT/build/csweb.tar.gz" -C "$DIST" csweb
du -h "$ROOT/build/csweb.tar.gz"
echo "package ready: build/csweb.tar.gz (unpack, then ./run.sh)"
