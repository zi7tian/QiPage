#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Build the offline dependency licence inventory from the Gradle cache.
#
# The app ships licence texts in app/src/main/assets/licenses/ and shows them
# offline under "关于与许可". This script re-derives the inventory from the
# resolved POMs so a dependency change can be reviewed before a release.
#
# Output: build/evidence/license-inventory.csv
#
# Usage: scripts/inspect-licenses.sh
# ---------------------------------------------------------------------------
set -euo pipefail
. "$(dirname "$0")/lib.sh"

setup_env

CACHE="$GRADLE_USER_HOME/caches/modules-2/files-2.1"
[[ -d "$CACHE" ]] || die "Gradle module cache not found at $CACHE. Run scripts/build.sh once first."

EVIDENCE="$ROOT/build/evidence"
mkdir -p "$EVIDENCE"
REPORT="$EVIDENCE/dependencies.txt"

info "Resolving the release runtime classpath"
cd "$ROOT"
gradle_run :app:dependencies --configuration releaseRuntimeClasspath --console=plain > "$REPORT"

info "Resolving licences from $CACHE"
CACHE="$CACHE" EVIDENCE="$EVIDENCE" REPORT="$REPORT" python3 - <<'PY'
import csv, os, pathlib, re, xml.etree.ElementTree as ET

cache = pathlib.Path(os.environ["CACHE"])
evidence = pathlib.Path(os.environ["EVIDENCE"])
report = pathlib.Path(os.environ["REPORT"])

# Gradle's dependency report lists coordinates as group:artifact:version.
text = report.read_text(encoding="utf-8", errors="ignore")
coords = sorted({
    m for m in re.findall(r"[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+:[0-9][A-Za-z0-9_.\-]*", text)
})


def licences(coordinate, depth=0):
    """Licence names/urls for a coordinate, following <parent> POMs."""
    if depth > 8:
        return None
    parts = coordinate.split(":")
    if len(parts) != 3:
        return None
    directory = cache.joinpath(*parts)
    if not directory.is_dir():
        return None
    poms = sorted(directory.rglob("*.pom"))
    if not poms:
        return None
    try:
        root = ET.parse(poms[-1]).getroot()
    except ET.ParseError:
        return None
    ns = {"m": root.tag.split("}")[0].lstrip("{")} if "}" in root.tag else {}

    def find_all(node, name):
        return node.findall(f"m:{name}", ns) if ns else node.findall(name)

    for lic in find_all(root, "licenses"):
        for item in find_all(lic, "license"):
            names = [e.text for e in find_all(item, "name") if e.text]
            urls = [e.text for e in find_all(item, "url") if e.text]
            if names:
                return names, urls, coordinate
    for parent in find_all(root, "parent"):
        g = next((e.text for e in find_all(parent, "groupId") if e.text), None)
        a = next((e.text for e in find_all(parent, "artifactId") if e.text), None)
        v = next((e.text for e in find_all(parent, "version") if e.text), None)
        if g and a and v:
            return licences(f"{g}:{a}:{v}", depth + 1)
    return None


rows = []
for coordinate in coords:
    found = licences(coordinate)
    if found:
        name, url, source = " | ".join(found[0]), " | ".join(found[1]), found[2]
    else:
        name, url, source = "REVIEW_REQUIRED", "", ""
    rows.append({
        "coordinate": coordinate,
        "license": name,
        "licenseUrl": url,
        "licenseSource": source,
    })

out = evidence / "license-inventory.csv"
with out.open("w", newline="", encoding="utf-8") as fh:
    writer = csv.DictWriter(fh, fieldnames=["coordinate", "license", "licenseUrl", "licenseSource"])
    writer.writeheader()
    writer.writerows(rows)
print(f"Wrote {out} ({len(rows)} coordinates)")

needs_review = [r for r in rows if r["license"] == "REVIEW_REQUIRED"]
if needs_review:
    print(f"\n{len(needs_review)} coordinate(s) need manual review:")
    for row in needs_review:
        print("  " + row["coordinate"])
PY

info "Done."
