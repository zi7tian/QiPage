#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Verify a release APK and record its measurements as evidence.
#
# Checks, and fails the script on any violation:
#   * no INTERNET / network-state / broad-storage permission is present
#   * minSdk is 30
#   * the APK signature verifies
# and then records byte size, MiB, sha256 and a per-entry size breakdown.
#
#
# Usage:
#   scripts/measure-apk.sh --apk artifacts/qipage-0.4.0-p4.apk --evidence build/evidence
# ---------------------------------------------------------------------------
set -euo pipefail
. "$(dirname "$0")/lib.sh"

APK=""
EVIDENCE=""

usage() {
    cat <<'EOF'
Usage: scripts/measure-apk.sh [--apk PATH] [--evidence DIR]
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk) APK="${2:?}"; shift 2 ;;
        --evidence) EVIDENCE="${2:?}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

setup_env

AAPT2="$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2"
APKSIGNER="$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner"
[[ -x "$AAPT2" ]] || die "aapt2 not found at $AAPT2"
[[ -x "$APKSIGNER" ]] || die "apksigner not found at $APKSIGNER"

APK="${APK:-$ROOT/artifacts/qipage-0.4.0-p4.apk}"
EVIDENCE="${EVIDENCE:-$ROOT/build/evidence}"

# --- permissions -----------------------------------------------------------
info "Inspecting permissions"
PERMISSIONS="$("$AAPT2" dump permissions "$APK")"
printf '%s\n' "$PERMISSIONS" > "$EVIDENCE/permissions.txt"

FORBIDDEN='android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|MANAGE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|READ_MEDIA_IMAGES|READ_MEDIA_VIDEO)'
if printf '%s' "$PERMISSIONS" | grep -Eq "$FORBIDDEN"; then
    printf '%s\n' "$PERMISSIONS" >&2
    die "Forbidden permission present in $APK"
fi
info "  no network or broad-storage permission present"

# --- badging ---------------------------------------------------------------
info "Inspecting badging"
BADGING="$("$AAPT2" dump badging "$APK")"
printf '%s\n' "$BADGING" > "$EVIDENCE/badging.txt"
# aapt2 prints "minSdkVersion:'30'"; the older aapt printed "sdkVersion:'30'".
printf '%s' "$BADGING" | grep -Eqi "(min)?sdkVersion:'30'" || die "Unexpected minSdk (expected 30)"
info "  minSdk 30 confirmed"

# --- signature -------------------------------------------------------------
info "Verifying signature"
if ! "$APKSIGNER" verify --verbose --print-certs "$APK" > "$EVIDENCE/signature.txt" 2>&1; then
    cat "$EVIDENCE/signature.txt" >&2
    die "Signature verification failed"
fi
info "  signature verifies"

# --- measurements ----------------------------------------------------------
BYTES="$(stat -c%s "$APK")"
SHA="$(sha256_of "$APK")"

info "Recording metrics"
APK="$APK" BYTES="$BYTES" SHA="$SHA" EVIDENCE="$EVIDENCE" python3 - <<'PY'
import json, os, zipfile, pathlib

apk = pathlib.Path(os.environ["APK"])
evidence = pathlib.Path(os.environ["EVIDENCE"])
payload = apk.read_bytes()

# Signing scheme detection: the v2/v3 block lives outside the ZIP entries.
has_v2 = b"APK Sig Block 42" in payload

with zipfile.ZipFile(apk) as zf:
    entries = {i.filename: i for i in zf.infolist()}
    dex = sum(i.file_size for n, i in entries.items() if n.startswith("classes") and n.endswith(".dex"))
    native = [
        {"path": n, "bytes": i.file_size, "compressedBytes": i.compress_size}
        for n, i in sorted(entries.items()) if n.startswith("lib/")
    ]
    has_v1 = any(n.endswith(".SF") for n in entries)

metrics = {
    "bytes": int(os.environ["BYTES"]),
    "MiB": round(int(os.environ["BYTES"]) / (1024 * 1024), 4),
    "sha256": os.environ["SHA"],
    "minSdk": 30,
    "signing": "Local test key, not production",
    "signatureSchemes": {"v1": has_v1, "v2plus": has_v2},
    "dexUncompressedBytes": dex,
    "nativeLibraries": native,
    "nativeAbis": sorted({n.split("/")[1] for n in entries if n.startswith("lib/")}),
}
(evidence / "apk-metrics.json").write_text(
    json.dumps(metrics, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
)
print(json.dumps(metrics, indent=2, ensure_ascii=False))
PY

info "Evidence written to ${EVIDENCE#$ROOT/}"
info "APK: $APK"
info "  $(numfmt --to=iec-i --suffix=B "$BYTES")  ($(python3 -c "print(f'{$BYTES/1048576:.4f}')") MiB)"
info "  sha256 $SHA"
