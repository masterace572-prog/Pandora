#!/usr/bin/env bash
# Sets up the Android SDK/NDK inside a GitHub Codespace and builds Pandora.
# Safe to re-run: every step is skipped or resumed when already done.
set -euo pipefail

SDK_ROOT="$HOME/android-sdk"
CMDLINE_DIR="$SDK_ROOT/cmdline-tools/latest"

echo "== Pandora codespace setup =="

if [ ! -x "$CMDLINE_DIR/bin/sdkmanager" ]; then
    echo "-- Installing Android command line tools"
    mkdir -p "$SDK_ROOT/cmdline-tools"
    TMPZIP="$(mktemp -u /tmp/cmdline-tools-XXXX.zip)"
    # Newest first: older sdkmanager builds may not parse the newest repo XML,
    # so --update runs right after install.
    for NAME in \
        commandlinetools-linux-13114758_latest.zip \
        commandlinetools-linux-12266719_latest.zip \
        commandlinetools-linux-11076708_latest.zip; do
        echo "   trying $NAME"
        if curl -fsSL "https://dl.google.com/android/repository/$NAME" -o "$TMPZIP"; then
            break
        fi
    done
    test -s "$TMPZIP" || { echo "Could not download Android cmdline tools"; exit 1; }
    unzip -q -o "$TMPZIP" -d "$SDK_ROOT/cmdline-tools"
    rm -f "$TMPZIP"
    mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$CMDLINE_DIR"
fi

export ANDROID_HOME="$SDK_ROOT"
export PATH="$CMDLINE_DIR/bin:$PATH"

echo "-- Refreshing package metadata"
"$CMDLINE_DIR/bin/sdkmanager" --sdk_root="$SDK_ROOT" --update >/dev/null 2>&1 || true

echo "-- Installing platform, build-tools, NDK and CMake (large download)"
yes | "$CMDLINE_DIR/bin/sdkmanager" --sdk_root="$SDK_ROOT" \
    "platforms;android-37.0" "build-tools;37.0.0" \
    "ndk;30.0.16248370" "cmake;3.22.1" >/dev/null || true
yes | "$CMDLINE_DIR/bin/sdkmanager" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true

echo "sdk.dir=$SDK_ROOT" > local.properties

echo "-- Building Pandora (debug + release)"
./gradlew :pandora:assembleDebug :pandora:assembleRelease --no-daemon

mkdir -p dist
cp pandora/build/outputs/apk/debug/pandora-debug.apk dist/
cp pandora/build/outputs/apk/release/pandora-release-unsigned.apk dist/
"$SDK_ROOT/build-tools/37.0.0/zipalign" -c 4 dist/pandora-release-unsigned.apk

echo
echo "== BUILD OK =="
sha256sum dist/*.apk
echo
echo "Artifacts are in the dist/ folder (right-click -> Download in the VS Code explorer):"
echo "  dist/pandora-debug.apk              installable on your phone today"
echo "  dist/pandora-release-unsigned.apk   sign with your keystore for releases"
