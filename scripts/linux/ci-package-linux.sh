#!/usr/bin/env bash

# Tuvora fork: builds the Linux release packages (DEB + AppImage) on an Ubuntu CI runner and copies
# them to the repo root under their published names. Shared by release.yml (tag push) and build.yml
# (manual pre-release check) so both exercise exactly the same packaging path.
#
#   scripts/linux/ci-package-linux.sh <version> <version-code>
#
# Expects install-desktop-build-dependencies.sh to have run and local.properties to exist.
# Output: Tuvora-Linux-x86_64-<version>.deb and Tuvora-Linux-x86_64-<version>.AppImage

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <version> <version-code>" >&2
    exit 2
fi

version="$1"
version_code="$2"

# Pinned and checksummed: appimagetool runs with our build's contents, so never take "latest".
appimagetool_version="1.9.1"
appimagetool_sha256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
appimagetool_path="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/appimagetool-x86_64.AppImage"

curl -fsSL -o "$appimagetool_path" \
    "https://github.com/AppImage/appimagetool/releases/download/${appimagetool_version}/appimagetool-x86_64.AppImage"
actual_sha256="$(sha256sum "$appimagetool_path" | awk '{print $1}')"
if [[ "$actual_sha256" != "$appimagetool_sha256" ]]; then
    echo "appimagetool checksum mismatch: expected $appimagetool_sha256, got $actual_sha256" >&2
    exit 1
fi
chmod +x "$appimagetool_path"

export APPIMAGETOOL="$appimagetool_path"
export APPIMAGE_WEBSITE_URL="https://tuvora.co"

# The DEB hook in composeApp/build.gradle.kts runs patch-linux-deb.sh (runtime Depends: libmpv2,
# WebKitGTK, GStreamer) and verify-linux-deb.sh; the AppImage hook runs build-appimage.sh.
./gradlew \
    :composeApp:packageReleaseDeb \
    :composeApp:packageReleaseAppImage \
    "-Pnuvio.desktop.versionName=${version}" \
    "-Pnuvio.desktop.versionCode=${version_code}" \
    "-PversionNameOverride=${version}" \
    "-PversionCodeOverride=${version_code}" \
    -Pcompose.desktop.packaging.checkJdkVendor=false \
    --no-configuration-cache \
    --no-daemon \
    --stacktrace

deb_dir="composeApp/build/compose/binaries/main-release/deb"
mapfile -t debs < <(find "$deb_dir" -maxdepth 1 -type f -name '*.deb' | sort)
if [[ "${#debs[@]}" -ne 1 ]]; then
    echo "Expected exactly one .deb in $deb_dir, found ${#debs[@]}." >&2
    ls -la "$deb_dir" >&2 || true
    exit 1
fi
cp "${debs[0]}" "Tuvora-Linux-x86_64-${version}.deb"

appimage="composeApp/build/compose/release-appimages/Tuvora-Linux-x86_64-${version}.AppImage"
if [[ ! -f "$appimage" ]]; then
    echo "Expected AppImage was not produced: $appimage" >&2
    find composeApp/build/compose -maxdepth 6 -name '*.AppImage' >&2 || true
    exit 1
fi
cp "$appimage" "Tuvora-Linux-x86_64-${version}.AppImage"
chmod +x "Tuvora-Linux-x86_64-${version}.AppImage"

# The packages must carry the native player. Without libplayer_bridge.so the app launches, browses,
# and then fails the moment someone presses Play — the exact bug (B13) that kept Linux unreleased.
# The bridge is packed into the app jar at native/linux/ by the desktopJar task.
has_player_bridge() {
    local root="$1" jar
    while IFS= read -r jar; do
        if unzip -l "$jar" 2>/dev/null | grep -q 'native/linux/libplayer_bridge\.so$'; then
            return 0
        fi
    done < <(find "$root" -type f -name '*.jar')
    return 1
}

check_dir="$(mktemp -d)"
dpkg-deb -x "Tuvora-Linux-x86_64-${version}.deb" "$check_dir/deb"
has_player_bridge "$check_dir/deb" || {
    echo "The DEB does not contain native/linux/libplayer_bridge.so." >&2
    exit 1
}
(cd "$check_dir" && "$OLDPWD/Tuvora-Linux-x86_64-${version}.AppImage" --appimage-extract >/dev/null)
has_player_bridge "$check_dir/squashfs-root" || {
    echo "The AppImage does not contain native/linux/libplayer_bridge.so." >&2
    exit 1
}
rm -rf "$check_dir"

ls -lh "Tuvora-Linux-x86_64-${version}.deb" "Tuvora-Linux-x86_64-${version}.AppImage"
