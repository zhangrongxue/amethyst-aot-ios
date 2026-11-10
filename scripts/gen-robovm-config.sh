#!/usr/bin/env bash
# Injects forceLinkClasses patterns produced by reflect-scan into mobivm/robovm.xml.
#
# Usage: scripts/gen-robovm-config.sh <force-link-classes.txt> [robovm.xml]
set -euo pipefail

FL="${1:-work/scan-payload/force-link-classes.txt}"
XML="${2:-mobivm/robovm.xml}"

if [ ! -f "$FL" ]; then echo "force-link file not found: $FL" >&2; exit 1; fi
if [ ! -f "$XML" ]; then echo "robovm.xml not found: $XML" >&2; exit 1; fi

TMP="$(mktemp)"
awk -v fl="$FL" '
  /<!-- GENERATED:BEGIN -->/ {
    print;
    while ((getline line < fl) > 0) {
      sub(/^[ \t]+/, "", line);
      sub(/[ \t]+$/, "", line);
      if (line == "" || line ~ /^#/) continue;
      print "        <pattern>" line "</pattern>";
    }
    close(fl);
    skip = 1;
    next;
  }
  /<!-- GENERATED:END -->/ { skip = 0; print; next }
  skip == 1 { next }
  { print }
' "$XML" > "$TMP"

mv "$TMP" "$XML"
echo "[gen-robovm] injected $(grep -vc '^#' "$FL" || true) patterns into $XML"
