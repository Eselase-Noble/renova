#!/usr/bin/env bash
# Builds a self-contained Renova desktop app with jpackage: the app, its libraries and a Java runtime, so users
# need nothing installed. Run on each target OS (jpackage builds for the OS it runs on).
#
#   desktop/package.sh                 # app image in desktop/target/dist/Renova
#   desktop/package.sh --type deb      # or rpm, dmg, pkg, msi, exe (needs that OS's packaging tools)
set -euo pipefail
cd "$(dirname "$0")/.."

TYPE="app-image"
if [[ "${1:-}" == "--type" ]]; then TYPE="$2"; fi

mvn -B -q -pl desktop -am package -DskipTests
VERSION=$(mvn -B -q -pl desktop help:evaluate -Dexpression=project.version -DforceStdout | sed 's/-SNAPSHOT//')
# macOS installers may not have a version that starts with 0: 0.3.1 becomes 1.3.1 there, and only there.
if [[ "$(uname)" == "Darwin" && "$VERSION" == 0.* ]]; then VERSION="1.${VERSION#0.}"; fi
INPUT=desktop/target/jpackage-input
rm -rf "$INPUT" desktop/target/dist
mkdir -p "$INPUT"
cp desktop/target/renova-desktop-*.jar "$INPUT/renova-desktop.jar"
cp -r desktop/target/lib "$INPUT/lib"

jpackage --type "$TYPE" \
  --name Renova \
  --app-version "$VERSION" \
  --vendor "Renova" \
  --description "Assess and modernise legacy software systems, safely and repeatably." \
  --input "$INPUT" \
  --main-jar renova-desktop.jar \
  --main-class io.renova.desktop.Main \
  --java-options "-Xmx2g" \
  --dest desktop/target/dist

echo "Built desktop/target/dist ($TYPE)"
