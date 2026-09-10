#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Shared helpers for the ReadApp / QiPage build scripts.
# Source this file, do not execute it:  . "$(dirname "$0")/lib.sh"
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
export ROOT

# ---------------------------------------------------------------------------
# Locate a JDK 17. Priority:
#   1. JAVA_HOME if it already points at a usable JDK
#   2. the project-local portable JDK in .tools/jdk-17
#   3. a system JDK
# ---------------------------------------------------------------------------
find_java_home() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        echo "$JAVA_HOME"; return 0
    fi
    if [[ -x "$ROOT/.tools/jdk-17/bin/java" ]]; then
        echo "$ROOT/.tools/jdk-17"; return 0
    fi
    local candidate
    for candidate in /usr/lib/jvm/*/; do
        if [[ -x "${candidate}bin/javac" ]]; then
            echo "${candidate%/}"; return 0
        fi
    done
    return 1
}

# ---------------------------------------------------------------------------
# Locate the Android SDK. Priority:
#   1. ANDROID_SDK_ROOT / ANDROID_HOME if already exported
#   2. the project-local SDK in .tools/android-sdk
# ---------------------------------------------------------------------------
find_android_sdk() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT/platform-tools" ]]; then
        echo "$ANDROID_SDK_ROOT"; return 0
    fi
    if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/platform-tools" ]]; then
        echo "$ANDROID_HOME"; return 0
    fi
    if [[ -d "$ROOT/.tools/android-sdk/platform-tools" ]]; then
        echo "$ROOT/.tools/android-sdk"; return 0
    fi
    return 1
}

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# Export a consistent build environment. Call once at the top of a script.
# ---------------------------------------------------------------------------
setup_env() {
    JAVA_HOME="$(find_java_home)" || die "No JDK found. Run scripts/setup-linux-toolchain.sh, or export JAVA_HOME."
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"

    ANDROID_SDK_ROOT="$(find_android_sdk)" || die "No Android SDK found. Run scripts/setup-linux-toolchain.sh, or export ANDROID_SDK_ROOT."
    export ANDROID_SDK_ROOT
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
    export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"

    # Keep every cache inside the project so the repository stays self-contained.
    export GRADLE_USER_HOME="$ROOT/.tools/gradle-home"
    export ANDROID_USER_HOME="$ROOT/.tools/android-user"
    mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"
}

# ---------------------------------------------------------------------------
# Print the resolved toolchain versions (useful in logs and bug reports).
# ---------------------------------------------------------------------------
print_toolchain() {
    info "JAVA_HOME        = $JAVA_HOME"
    "$JAVA_HOME/bin/java" -version 2>&1 | sed 's/^/    /'
    info "ANDROID_SDK_ROOT = $ANDROID_SDK_ROOT"
    info "GRADLE_USER_HOME = $GRADLE_USER_HOME"
}

# ---------------------------------------------------------------------------
# Run Gradle. Prefers the project-local distribution, then the wrapper.
# ---------------------------------------------------------------------------
gradle_run() {
    if [[ -x "$ROOT/.tools/gradle-8.14.1/bin/gradle" ]]; then
        "$ROOT/.tools/gradle-8.14.1/bin/gradle" "$@"
    elif [[ -x "$ROOT/gradlew" ]]; then
        "$ROOT/gradlew" "$@"
    else
        die "No Gradle found (neither .tools/gradle-8.14.1/bin/gradle nor ./gradlew)."
    fi
}

# ---------------------------------------------------------------------------
# Create the local test signing key if it is missing.
# The key is intentionally NOT committed; see scripts/gen-test-keystore.sh.
# ---------------------------------------------------------------------------
ensure_keystore() {
    if [[ -f "$ROOT/.tools/p0.keystore" ]]; then
        return 0
    fi
    info "No test keystore found; generating .tools/p0.keystore"
    "$SCRIPT_DIR/gen-test-keystore.sh"
}

# ---------------------------------------------------------------------------
# sha256 of a file, lowercase hex only.
# ---------------------------------------------------------------------------
sha256_of() {
    sha256sum "$1" | cut -d' ' -f1
}
