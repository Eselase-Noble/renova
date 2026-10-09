#!/usr/bin/env bash
# Builds a self-contained Renova desktop app with jpackage: the app, its libraries and a Java runtime, so users
# need nothing installed. Run on each target OS (jpackage builds for the OS it runs on), and on macOS on each
# kind of Mac: an installer built on Apple Silicon does not run on an Intel Mac, nor the other way round.
#
#   desktop/package.sh                 # app image in desktop/target/dist/Renova
#   desktop/package.sh --type deb      # or rpm, dmg, pkg, msi, exe (needs that OS's packaging tools)
#   desktop/package.sh --type deb,rpm,tar.gz,pkg.tar.zst    # several at once; on Linux, every format below
#
# Linux formats: deb (Debian, Ubuntu, Mint; needs dpkg), rpm (Fedora, RHEL, openSUSE; needs rpmbuild),
# tar.gz (any distribution: unpack and run bin/Renova), pkg.tar.zst (Arch, Manjaro; needs zstd and fakeroot).
set -euo pipefail
cd "$(dirname "$0")/.."

TYPE="app-image"
if [[ "${1:-}" == "--type" ]]; then TYPE="$2"; fi

mvn -B -q -pl desktop -am package -DskipTests
VERSION=$(mvn -B -q -pl desktop help:evaluate -Dexpression=project.version -DforceStdout | sed 's/-SNAPSHOT//')
# macOS installers may not have a version that starts with 0: 0.3.1 becomes 1.3.1 there, and only there.
if [[ "$(uname)" == "Darwin" && "$VERSION" == 0.* ]]; then VERSION="1.${VERSION#0.}"; fi
INPUT=desktop/target/jpackage-input
rm -rf "$INPUT" desktop/target/dist desktop/target/app-image
mkdir -p "$INPUT"
cp desktop/target/renova-desktop-*.jar "$INPUT/renova-desktop.jar"
cp -r desktop/target/lib "$INPUT/lib"

# Signing, where the machine has what it needs. On macOS, with MAC_SIGNING_IDENTITY set to the name on a
# "Developer ID Application" certificate in the keychain, the application is signed as it is packaged; an
# unsigned one is refused by Gatekeeper. See docs/deployment.md, "Signing the installers".
SIGNING=()
if [[ "$(uname)" == "Darwin" && -n "${MAC_SIGNING_IDENTITY:-}" ]]; then
  SIGNING=(--mac-sign --mac-signing-key-user-name "$MAC_SIGNING_IDENTITY" --mac-package-identifier io.renova.desktop)
fi

DESCRIPTION="Assess and modernise legacy software systems, safely and repeatably."
DIST=desktop/target/dist

# jpackage makes one thing per run and wants an empty folder for an application image, so each is made in a
# folder of its own and the results are gathered in DIST.
# The system libraries JavaFX loads when the application starts: GTK 3 for its windows, X11 extensions, OpenGL and sound.
DEB_LIBRARIES="libgtk-3-0t64 | libgtk-3-0, libxtst6, libxxf86vm1, libgl1, libasound2t64 | libasound2, libfreetype6, libfontconfig1"
RPM_LIBRARIES="libgtk-3.so.0()(64bit),libgdk-3.so.0()(64bit),libXtst.so.6()(64bit),libXxf86vm.so.1()(64bit),libGL.so.1()(64bit),libasound.so.2()(64bit),libfreetype.so.6()(64bit),libfontconfig.so.1()(64bit)"

# What makes the installed application findable (an entry in the Start menu or the applications menu: without
# one an installer puts the files in place and leaves nothing to click), and on Linux the system libraries it
# needs. JavaFX unpacks its native libraries when it starts, so no packaging tool sees what they load, and a
# package without these installs on a system that lacks them and then does not start.
EXTRA=()
extras() { # type
  EXTRA=()
  case "$1" in
    # The upgrade id is the same for every version, so installing a newer one replaces the older.
    msi|exe) EXTRA=(--win-menu --win-menu-group Renova --win-shortcut --win-dir-chooser --win-upgrade-uuid 94fd60d3-6fd1-4bf8-8b02-f11d73b139f8) ;;
    # Debian and Ubuntu name some of these differently from release to release (the "t64" names).
    deb) EXTRA=(--linux-shortcut --linux-menu-group "Development;" --linux-package-deps "$DEB_LIBRARIES") ;;
    # Named as the libraries themselves, which every rpm distribution provides under its own package names.
    rpm) EXTRA=(--linux-shortcut --linux-menu-group "Development;" --linux-package-deps "$RPM_LIBRARIES") ;;
  esac
}

package() { # type, destination
  extras "$1"
  jpackage --type "$1" \
    ${EXTRA[@]+"${EXTRA[@]}"} \
    ${SIGNING[@]+"${SIGNING[@]}"} \
    --name Renova \
    --app-version "$VERSION" \
    --vendor "Renova" \
    --description "$DESCRIPTION" \
    --input "$INPUT" \
    --main-jar renova-desktop.jar \
    --main-class io.renova.desktop.Main \
    --java-options "-Xmx2g" \
    --dest "$2"
}

# The application as a folder, made once for the formats that are that folder wrapped up.
IMAGE=""
image() {
  if [[ -z "$IMAGE" ]]; then
    IMAGE=desktop/target/app-image
    rm -rf "$IMAGE"
    package app-image "$IMAGE"
  fi
}

ARCH=$(uname -m)
mkdir -p "$DIST"
IFS=',' read -ra TYPES <<< "$TYPE"
for type in "${TYPES[@]}"; do
  case "$type" in
    app-image)
      image
      cp -r "$IMAGE/Renova" "$DIST/Renova"
      ;;
    tar.gz)
      # For any distribution: unpack anywhere and run Renova/bin/Renova.
      image
      tar -C "$IMAGE" --owner=0 --group=0 -czf "$DIST/renova-$VERSION-linux-$ARCH.tar.gz" Renova
      ;;
    pkg.tar.zst)
      # An Arch Linux package: the application under /opt/renova, a command and a menu entry.
      image
      PKG=desktop/target/arch-pkg
      rm -rf "$PKG"
      mkdir -p "$PKG/opt" "$PKG/usr/bin" "$PKG/usr/share/applications"
      cp -r "$IMAGE/Renova" "$PKG/opt/renova"
      ln -s /opt/renova/bin/Renova "$PKG/usr/bin/renova-desktop"
      cat > "$PKG/usr/share/applications/renova.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Renova
Comment=$DESCRIPTION
Exec=/opt/renova/bin/Renova
Icon=/opt/renova/lib/Renova.png
Terminal=false
Categories=Development;
DESKTOP
      cat > "$PKG/.PKGINFO" <<PKGINFO
pkgname = renova
pkgbase = renova
pkgver = $VERSION-1
pkgdesc = $DESCRIPTION
url = https://github.com/Eselase-Noble/renova
builddate = $(date +%s)
packager = Renova
size = $(du -sb "$PKG" | cut -f1)
arch = $ARCH
license = custom
depend = gtk3
depend = libxtst
depend = libxxf86vm
depend = libglvnd
depend = alsa-lib
depend = freetype2
depend = fontconfig
PKGINFO
      # Files owned by root in the package, whoever builds it; .PKGINFO first, as pacman reads it.
      (cd "$PKG" && fakeroot tar --zstd -cf "../dist/renova-$VERSION-1-$ARCH.pkg.tar.zst" .PKGINFO opt usr)
      ;;
    *)
      OUT=desktop/target/jpackage-$type
      rm -rf "$OUT"
      package "$type" "$OUT"
      if [[ "$(uname)" == "Darwin" ]]; then
        # An installer runs only on the kind of Mac it was built on, so its name says which:
        # arm64 for Apple Silicon (M1 and later), x64 for Intel.
        for file in "$OUT"/*; do
          name=$(basename "$file")
          mv "$file" "$DIST/${name%.*}-$([[ "$ARCH" == "arm64" ]] && echo arm64 || echo x64).${name##*.}"
        done
      else
        mv "$OUT"/* "$DIST/"
      fi
      ;;
  esac
done

echo "Built desktop/target/dist ($TYPE)"
