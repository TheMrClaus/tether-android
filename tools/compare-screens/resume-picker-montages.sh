#!/usr/bin/env bash
# Rebuilds the T5.2 resume-picker montages under docs/parity/screens/resume-picker/. The web has no
# separate picker (history rows live in the session sidebar) and the S0.4 corpus seeds no
# history-only row, so the reference is the web `session-drawer` shot: the montages compare the
# row grid, block header and rail, not the row content. Crops are x,y,w,h in each image's pixels.
#
#   tools/compare-screens/resume-picker-montages.sh [web-reference-dir]
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/sidebar/src/test/screenshots
OUT=docs/parity/screens/resume-picker
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in studio studio-dark; do
  w=840
  case "$skin" in studio*) w=882 ;; esac
  for shot in list opening; do
    java "$TOOL" montage "$OUT/rows-$shot-$skin-phone.png" "Resume picker · $shot (block + rows) · $skin · web ref: session-drawer" \
      "$WEB/session-drawer/$skin-phone.png@0,780,$w,1622" "$G/resume-$shot/$skin-phone.png@0,780,$w,1621"
  done
  top=48
  case "$skin" in studio*) top=64 ;; esac
  java "$TOOL" montage "$OUT/column-list-$skin-tablet.png" "Resume picker · expanded rail · $skin · web ref: session-drawer" \
    "$WEB/session-drawer/$skin-tablet.png@0,$top,264,$((800 - top))" "$G/resume-list/$skin-tablet.png@0,0,264,$((800 - top))"
done
