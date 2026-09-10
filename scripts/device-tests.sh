#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Run the P4 instrumented regression on the dedicated project emulator.
#
# Refuses to run against a physical device: it enables airplane mode, uses
# `adb root`, and rewrites files under /data/user/0/. Those are acceptable on a
# throwaway emulator and unacceptable on a real phone.
#
#
# Usage:
#   scripts/device-tests.sh                          # full 13-scenario suite
#   scripts/device-tests.sh --only txt,epub          # subset
#   scripts/device-tests.sh --serial emulator-5556
# ---------------------------------------------------------------------------
set -euo pipefail
. "$(dirname "$0")/lib.sh"

SERIAL="emulator-5554"
ONLY=""
RUN_TAG=""
APK="$ROOT/artifacts/p4/qipage-0.4.0-p4.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
EVIDENCE="$ROOT/build/evidence"

usage() {
    cat <<'EOF'
Usage: scripts/device-tests.sh [--serial emulator-5554] [--only a,b,c] [--run-tag TAG]
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) SERIAL="${2:?}"; shift 2 ;;
        --only) ONLY="${2:?}"; shift 2 ;;
        --run-tag) RUN_TAG="${2:?}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

setup_env
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"

[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || die \
    "Only the dedicated project emulator is supported (got '$SERIAL'). This script enables airplane mode and runs adb root."
[[ -f "$APK" ]] || die "App APK not found: $APK (run scripts/build.sh first)"
[[ -f "$TEST_APK" ]] || die "Test APK not found: $TEST_APK (run scripts/build.sh first)"

mkdir -p "$EVIDENCE"
cd "$ROOT"

info "Installing app APK"
"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null
info "Installing instrumentation APK"
"$ADB" -s "$SERIAL" install -r "$TEST_APK" >/dev/null

# The suite must prove the app works with no connectivity at all.
info "Enabling airplane mode"
"$ADB" -s "$SERIAL" shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell svc wifi disable >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell svc data disable >/dev/null 2>&1 || true

# Fixtures live in the test package's private storage; that needs root, which is
# only acceptable on this dedicated emulator.
"$ADB" -s "$SERIAL" root >/dev/null
sleep 2

TEST_UID="$("$ADB" -s "$SERIAL" shell stat -c %u /data/user/0/local.readapp.test | tr -d '\r')"
[[ "$TEST_UID" =~ ^[0-9]+$ ]] || die "Could not determine test package UID (got '$TEST_UID')"
info "Test package UID: $TEST_UID"

CACHE_DIR="/data/user/0/local.readapp.test/cache"
"$ADB" -s "$SERIAL" shell mkdir -p "$CACHE_DIR"

info "Pushing fixtures and user samples"
for ext in txt epub; do
    sample="$(find novel_test -maxdepth 1 -name "*.$ext" -print -quit 2>/dev/null || true)"
    [[ -n "$sample" ]] || die "No novel_test/*.$ext sample found. These are local-only and gitignored."
    "$ADB" -s "$SERIAL" push "$sample" "$CACHE_DIR/user-sample.$ext" >/dev/null
    "$ADB" -s "$SERIAL" push "fixtures/generated/p4-layout.$ext" "$CACHE_DIR/p4-layout.$ext" >/dev/null
    "$ADB" -s "$SERIAL" shell chown "$TEST_UID:$TEST_UID" \
        "$CACHE_DIR/user-sample.$ext" "$CACHE_DIR/p4-layout.$ext"
done

# Custom-font and background-image import are exercised with real local files.
BACKGROUND="$(mktemp -u /tmp/qipage-background-XXXX.png)"
python3 - "$BACKGROUND" <<'PY'
import struct, sys, zlib
path = sys.argv[1]
w = h = 360
raw = b"".join(b"\x00" + bytes((220, 231, 221, 255)) * w for _ in range(h))
def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
open(path, "wb").write(
    b"\x89PNG\r\n\x1a\n"
    + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    + chunk(b"IDAT", zlib.compress(raw, 9))
    + chunk(b"IEND", b"")
)
PY
"$ADB" -s "$SERIAL" shell cp /system/fonts/Roboto-Regular.ttf "$CACHE_DIR/QiPage-test.ttf"
"$ADB" -s "$SERIAL" push "$BACKGROUND" "$CACHE_DIR/QiPage-background.png" >/dev/null
"$ADB" -s "$SERIAL" shell chown "$TEST_UID:$TEST_UID" \
    "$CACHE_DIR/QiPage-test.ttf" "$CACHE_DIR/QiPage-background.png"
"$ADB" -s "$SERIAL" unroot >/dev/null

# mode, fixture, expected-error-substring
CASES=(
    "reset||"
    "migrate||"
    "txt||"
    "reset||"
    "epub||"
    "reset||"
    "sample|user-sample.txt|"
    "sample|user-sample.epub|"
    "reject|fixed-layout.epub|暂不支持固定版式"
    "reject|zip-traversal.epub|越界资源路径"
    "custom|p4-layout.epub|"
    "reset||"
    "links|p2-long.epub|"
    "performance||"
)

FAILED=0
for entry in "${CASES[@]}"; do
    IFS='|' read -r mode fixture expected <<< "$entry"
    name="$mode"
    [[ -n "$fixture" ]] && name="$name-$fixture"
    if [[ -n "$ONLY" && ",$ONLY," != *",$name,"* ]]; then
        continue
    fi
    [[ -n "$RUN_TAG" ]] && name="$name-$RUN_TAG"

    info "case: $name"
    raw="$("$ADB" -s "$SERIAL" shell am instrument -w \
        -e mode "$mode" ${fixture:+-e fixture "$fixture"} ${expected:+-e expected "$expected"} \
        local.readapp.test/local.readapp.test.P4Instrumentation)"
    printf '%s\n' "$raw" > "$EVIDENCE/$name.txt"

    json_line="$(printf '%s\n' "$raw" | grep -E '^\{' | tail -1 || true)"
    [[ -n "$json_line" ]] || die "No JSON result for case '$name'"

    printf '%s' "$json_line" | APK="$APK" NAME="$name" EVIDENCE="$EVIDENCE" python3 -c '
import json, os, pathlib, sys
result = json.load(sys.stdin)
apk = pathlib.Path(os.environ["APK"])
import hashlib
result["apkSha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
result["device"] = "Project API 30 x86_64 emulator"
out = pathlib.Path(os.environ["EVIDENCE"]) / (os.environ["NAME"] + ".json")
out.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
sys.exit(0 if result.get("pass") else 1)
' || { FAILED=1; warn "FAILED $name"; continue; }

    info "  PASS $name"
done

# Screenshots are written by the app into its own external files dir.
info "Pulling screenshots"
PULL="$(mktemp -d)"
"$ADB" -s "$SERIAL" root >/dev/null
if "$ADB" -s "$SERIAL" pull /sdcard/Android/data/local.readapp/files/p4-evidence "$PULL" >/dev/null 2>&1; then
    mkdir -p "$EVIDENCE/final-screenshots"
    find "$PULL" -type f -name '*.png' ! -name 'failure-*' -exec cp -f {} "$EVIDENCE/final-screenshots/" \;
    info "  screenshots -> build/evidence/final-screenshots/"
else
    warn "Screenshot pull unavailable; JSON assertions are preserved."
fi
"$ADB" -s "$SERIAL" unroot >/dev/null
rm -rf "$PULL" "$BACKGROUND"

if [[ "$FAILED" == "1" ]]; then
    die "One or more device scenarios failed."
fi
info "All device scenarios passed."
