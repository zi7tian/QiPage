#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Generate the local test signing key used by release builds.
#
# This key is NOT a production key and is deliberately kept out of version
# control (.tools/ is gitignored). Every developer and CI run generates their
# own, so builds are reproducible in behaviour but not byte-identical.
#
# If you ship a real release you must provision your own production key and
# keep an offline backup: Android refuses to update an app whose signing key
# changed, so losing it strands every existing install.
# ---------------------------------------------------------------------------
set -euo pipefail
. "$(dirname "$0")/lib.sh"

KEYSTORE="$ROOT/.tools/p0.keystore"

JAVA_HOME="$(find_java_home)" || die "No JDK found. Run scripts/setup-linux-toolchain.sh first."

if [[ -f "$KEYSTORE" ]]; then
    info "Keystore already exists: $KEYSTORE"
    exit 0
fi

mkdir -p "$(dirname "$KEYSTORE")"

# Known local-only credentials. These are not secrets: they exist purely so a
# debug/test build can be installed over a previous one and keep its data.
info "Generating local test keystore at $KEYSTORE"
"$JAVA_HOME/bin/keytool" -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias p0 \
    -dname "CN=ReadApp Local Test, OU=Development, O=QiPage, L=-, ST=-, C=CN" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 3650

info "Done. This is a local test key, NOT a production signing identity."
