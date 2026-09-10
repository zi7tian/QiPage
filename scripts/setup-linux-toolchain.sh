#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Bootstrap a self-contained Linux toolchain inside .tools/ (no root needed).
#
# Installs:
#   .tools/jdk-17/                  Temurin JDK 17 (portable tarball)
#   .tools/android-sdk/             Linux Android SDK (cmdline-tools)
#       platform-tools              adb, fastboot
#       platforms;android-36        compileSdk 36
#       build-tools;35.0.0          aapt2, apksigner, d8
#
# .tools/ is gitignored, so nothing here is committed. Re-running is safe and
# skips anything already present.
#
# Usage: scripts/setup-linux-toolchain.sh [--force]
# ---------------------------------------------------------------------------
set -euo pipefail
. "$(dirname "$0")/lib.sh"

FORCE=0
[[ "${1:-}" == "--force" ]] && FORCE=1

TOOLS="$ROOT/.tools"
DOWNLOADS="$TOOLS/downloads"
JDK_DIR="$TOOLS/jdk-17"
SDK_DIR="$TOOLS/android-sdk"

# Pinned versions. Bump deliberately: the JDK major must stay 17 for AGP 8.10.
JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
BUILD_TOOLS="35.0.0"
PLATFORM="android-36"

case "$(uname -m)" in
    x86_64) ;;
    *) die "This bootstrap only handles x86_64. Download the matching JDK/SDK manually for $(uname -m)." ;;
esac

mkdir -p "$DOWNLOADS"

# --- JDK -------------------------------------------------------------------
if [[ -x "$JDK_DIR/bin/java" && "$FORCE" != "1" ]]; then
    info "JDK already present: $("$JDK_DIR/bin/java" -version 2>&1 | head -1)"
else
    info "Downloading Temurin JDK 17"
    curl -fL --retry 3 -o "$DOWNLOADS/jdk17.tar.gz" "$JDK_URL"
    rm -rf "$JDK_DIR"; mkdir -p "$JDK_DIR"
    tar -xzf "$DOWNLOADS/jdk17.tar.gz" -C "$JDK_DIR" --strip-components=1
    info "Installed $("$JDK_DIR/bin/java" -version 2>&1 | head -1)"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$PATH"

# --- cmdline-tools ---------------------------------------------------------
if [[ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" && "$FORCE" != "1" ]]; then
    info "cmdline-tools already present"
else
    info "Downloading Android commandline-tools"
    curl -fL --retry 3 -o "$DOWNLOADS/cmdline-tools.zip" "$CMDLINE_URL"
    rm -rf "$SDK_DIR"; mkdir -p "$SDK_DIR/cmdline-tools"
    rm -rf /tmp/readapp-cmdline; mkdir -p /tmp/readapp-cmdline
    unzip -q "$DOWNLOADS/cmdline-tools.zip" -d /tmp/readapp-cmdline
    mv /tmp/readapp-cmdline/cmdline-tools "$SDK_DIR/cmdline-tools/latest"
    rm -rf /tmp/readapp-cmdline
    info "Installed cmdline-tools"
fi

# --- SDK packages ----------------------------------------------------------
export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_USER_HOME="$TOOLS/android-user"
mkdir -p "$ANDROID_USER_HOME"

SM="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"

info "Accepting SDK licences"
yes 2>/dev/null | "$SM" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true

info "Installing platform-tools, platforms;$PLATFORM, build-tools;$BUILD_TOOLS"
"$SM" --sdk_root="$SDK_DIR" "platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS"

info "Installed components:"
"$SM" --sdk_root="$SDK_DIR" --list_installed | sed 's/^/    /'

# --- local.properties ------------------------------------------------------
if [[ "$FORCE" == "1" || ! -f "$ROOT/local.properties" ]]; then
    info "Writing local.properties"
    printf 'sdk.dir=%s\n' "$SDK_DIR" > "$ROOT/local.properties"
fi

# --- emulator (optional) ---------------------------------------------------
if [[ "${INSTALL_EMULATOR:-0}" == "1" ]]; then
    SYSTEM_IMAGE="system-images;android-30;default;x86_64"
    info "Installing emulator and $SYSTEM_IMAGE (large download)"
    "$SM" --sdk_root="$SDK_DIR" "emulator" "$SYSTEM_IMAGE"
    info "Create an AVD with avdmanager; see scripts/README.md \"Emulator note\"."
fi

info "Toolchain ready."
print_toolchain
