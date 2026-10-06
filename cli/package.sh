#!/usr/bin/env bash
# Builds the CLI as a zip to unpack anywhere: renova.jar with launchers for Unix and Windows.
#
#   cli/package.sh        # cli/target/renova-cli-VERSION.zip
set -euo pipefail
cd "$(dirname "$0")/.."

mvn -B -q -pl cli -am package -DskipTests
VERSION=$(mvn -B -q -pl cli help:evaluate -Dexpression=project.version -DforceStdout)
OUT="cli/target/renova-cli-$VERSION"
rm -rf "$OUT" "$OUT.zip"
mkdir -p "$OUT"
cp cli/target/renova.jar cli/bundle/renova cli/bundle/renova.bat "$OUT/"
(cd cli/target && zip -q -r "renova-cli-$VERSION.zip" "renova-cli-$VERSION")
echo "Built $OUT.zip"
