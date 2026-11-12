#!/usr/bin/env bash
# The body ends at the next `## [` heading or at the first link reference definition, which Keep a
# Changelog puts at the bottom of the file, inside the oldest section.
set -euo pipefail

[ $# -ge 1 ] || {
  echo "usage: scripts/changelog-section.sh X.Y.Z [CHANGELOG.md]" >&2
  exit 2
}

awk -v v="$1" '
  /^## \[/ {
    if (in_section) exit
    if (index($0, "## [" v "]") == 1) { in_section = 1; next }
  }
  /^\[[^]]+\]:/ { if (in_section) exit }
  in_section { print }
' "${2:-CHANGELOG.md}"
