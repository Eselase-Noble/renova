#!/usr/bin/env bash
# Builds the deployable web bundle: the API jar, the console as a self-contained Node server, a launcher,
# systemd units and a deployment guide.
#
#   web/package.sh        # web/target/renova-web-VERSION/ and web/target/renova-web-VERSION.tar.gz
set -euo pipefail
cd "$(dirname "$0")/.."

mvn -B -q -pl web/api -am package -DskipTests
VERSION=$(mvn -B -q -pl web/api help:evaluate -Dexpression=project.version -DforceStdout)
(cd web/console && { [ -d node_modules ] || npm ci; } && npm run build)

OUT="web/target/renova-web-$VERSION"
rm -rf "$OUT" "$OUT.tar.gz"
mkdir -p "$OUT/api" "$OUT/console"
cp web/api/target/renova-web-api-"$VERSION".jar "$OUT/api/renova-web-api.jar"
# Next's standalone output: server.js and only the node_modules it needs. Static files are copied beside it.
cp -r web/console/.next/standalone/. "$OUT/console/"
cp -r web/console/.next/static "$OUT/console/.next/static"
if [ -d web/console/public ]; then cp -r web/console/public "$OUT/console/public"; fi
cp -r web/bundle/bin web/bundle/systemd web/bundle/README.md "$OUT/"
echo "$VERSION" > "$OUT/VERSION"
tar -C web/target -czf "$OUT.tar.gz" "renova-web-$VERSION"
echo "Built $OUT.tar.gz"
