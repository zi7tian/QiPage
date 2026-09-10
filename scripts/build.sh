#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Build the release APK, the instrumentation APK and run every JVM check.
#
#
# Usage:
#   scripts/build.sh                     # current phase (p4)
#   scripts/build.sh --offline           # no network (deps already cached)
#   scripts/build.sh --no-lint           # skip Android Lint
# ---------------------------------------------------------------------------
set -euo pipefail
. "$(dirname "$0")/lib.sh"

OFFLINE=0
LINT=1

usage() {
    cat <<'EOF'
Usage: scripts/build.sh [--offline] [--no-lint]

  --offline  run Gradle with --offline; requires a warm dependency cache
  --no-lint  skip :app:lintRelease
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --offline) OFFLINE=1; shift ;;
        --no-lint) LINT=0; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

setup_env
ensure_keystore

# Fixtures feed the instrumentation APK, so they must exist before packaging.
if [[ ! -f "$ROOT/fixtures/generated/utf8-small.txt" ]]; then
    info "Fixtures missing; generating them first"
    "$SCRIPT_DIR/new-fixtures.sh"
fi

TASKS=(
    ':core:test'
    ':engine-txt:test'
    ':engine-epub:test'
    ':feature:testReleaseUnitTest'
    ':app:assembleRelease'
    ':app:assembleReleaseAndroidTest'
)
ARTIFACT_DIR="$ROOT/artifacts"
APK_NAME="qipage-0.4.0-p4.apk"

GRADLE_ARGS=("${TASKS[@]}" --console=plain)
[[ "$OFFLINE" == "1" ]] && GRADLE_ARGS+=(--offline)

print_toolchain
info "Gradle tasks: ${TASKS[*]}"

cd "$ROOT"
gradle_run "${GRADLE_ARGS[@]}"

if [[ -n "$APK_NAME" ]]; then
    APK_SOURCE="$ROOT/app/build/outputs/apk/release/app-release.apk"
    [[ -f "$APK_SOURCE" ]] || die "Expected APK not found: $APK_SOURCE"
    mkdir -p "$ARTIFACT_DIR"

    TARGET="$ARTIFACT_DIR/$APK_NAME"
    # A published artifact is evidence: its sha256 is pinned in the phase's
    # verification report. Never clobber one silently - an Android build is not
    # bit-reproducible across toolchains, so overwriting it invalidates the
    # documented hash. Move the old file aside instead.
    if [[ -f "$TARGET" ]]; then
        OLD_SHA="$(sha256_of "$TARGET")"
        NEW_SHA="$(sha256_of "$APK_SOURCE")"
        if [[ "$OLD_SHA" != "$NEW_SHA" ]]; then
            BACKUP="$TARGET.$(date +%Y%m%d-%H%M%S).bak"
            mv "$TARGET" "$BACKUP"
            warn "Existing artifact archived before overwrite:"
            warn "  $(basename "$BACKUP")  (sha256 ${OLD_SHA:0:16}...)"
            warn "  a published release pins the previous hash; update it if you publish this build."
        fi
    fi

    cp -f "$APK_SOURCE" "$TARGET"
    info "Copied release APK -> artifacts/$APK_NAME"
    info "  sha256 $(sha256_of "$TARGET")"
fi

info "Build finished successfully."
