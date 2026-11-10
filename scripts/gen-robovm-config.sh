#!/usr/bin/env bash
# Injects forceLinkClasses patterns produced by reflect-scan into mobivm/robovm.xml.
#
# Usage: scripts/gen-robovm-config.sh <force-link-classes.txt> [robovm.xml]
set -euo pipefail

FL="${1:-work/scan-payload/force-link-classes.txt}"
XML="${2:-mobivm/robovm.xml}"

if [ ! -f "$FL" ]; then echo "force-link file not found: $FL" >&2; exit 1; fi
if [ ! -f "$XML" ]; then echo "robovm.xml not found: $XML" >&2; exit 1; fi

# Defensive: strip a UTF-8 BOM if the file was ever written by Windows PowerShell.
# RoboVM (SimpleXML) fails to parse a BOM-prefixed config with a misleading error.
if [ "$(head -c 3 "$XML" | od -An -tx1 | tr -d ' \n')" = "efbbbf" ]; then
  tail -c +4 "$XML" > "$XML.nobom" && mv "$XML.nobom" "$XML"
  echo "[gen-robovm] stripped UTF-8 BOM from $XML"
fi

TMP="$(mktemp)"
awk -v fl="$FL" '
  /<!-- GENERATED:BEGIN -->/ {
    print;
    while ((getline line < fl) > 0) {
      sub(/^[ \t]+/, "", line);
      sub(/[ \t]+$/, "", line);
      sub(/\r$/, "", line);
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
' "$XML" | tr -d '\r' > "$TMP"

mv "$TMP" "$XML"
echo "[gen-robovm] injected $(grep -vc '^#' "$FL" || true) patterns into $XML"
