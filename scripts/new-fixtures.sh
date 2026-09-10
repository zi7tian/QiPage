#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Regenerate every self-made test fixture into fixtures/generated/.
#
# The output directory is gitignored: these files are derived artefacts and the
# ~105 MiB stress sample must never be committed.
#
# ---------------------------------------------------------------------------
set -euo pipefail
. "$(dirname "$0")/lib.sh"

INCLUDE_LARGE=0
OUTPUT=""

usage() {
    cat <<'EOF'
Usage: scripts/new-fixtures.sh [--include-large-txt] [--output-dir DIR]

  --include-large-txt   also generate the ~5 MiB and ~100 MiB UTF-8 stress
                        samples (needed by the large-file regression cases)
  --output-dir DIR      write somewhere other than fixtures/generated
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --include-large-txt) INCLUDE_LARGE=1; shift ;;
        --output-dir) OUTPUT="${2:?--output-dir needs a value}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

command -v python3 >/dev/null || die "python3 is required to generate fixtures."

ARGS=()
[[ -n "$OUTPUT" ]] && ARGS+=(--output-dir "$OUTPUT")
[[ "$INCLUDE_LARGE" == "1" ]] && ARGS+=(--include-large-txt)

info "Generating fixtures${OUTPUT:+ into $OUTPUT}"
python3 "$SCRIPT_DIR/gen_fixtures.py" "${ARGS[@]}"
info "Done."
