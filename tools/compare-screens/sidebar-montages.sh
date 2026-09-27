#!/usr/bin/env bash
# Rebuilds the T5.1 session-list montages under docs/parity/screens/sidebar/ from the web reference
# PNGs (S0.4 parity-screens `session-drawer`) and the sidebar's Roborazzi goldens. Phone: 412x915
# @2.625 on both sides, so crops compare 1:1; tablet: 1280x800 @1 on both sides. Crops are
# x,y,w,h in each image's own pixels.
#
#   tools/compare-screens/sidebar-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/sidebar/src/test/screenshots
OUT=docs/parity/screens/sidebar
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in tactile night precision machine studio studio-dark; do
  # The drawer: min(20rem, 88vw) = 320dp = 840px (Studio min(21rem, 92vw) = 336dp = 882px).
  w=840
  case "$skin" in studio*) w=882 ;; esac
  # Header, New session, Scheduled actions, the Workspaces legend + filter bank, the two search wells.
  java "$TOOL" montage "$OUT/drawer-top-$skin-phone.png" "Session list · drawer (top) · $skin" \
    "$WEB/session-drawer/$skin-phone.png@0,0,$w,800" "$G/sidebar-drawer/$skin-phone.png@0,0,$w,800"
  # The workspace block header and the first rows, down to the footer.
  java "$TOOL" montage "$OUT/drawer-rows-$skin-phone.png" "Session list · drawer (block + rows + footer) · $skin" \
    "$WEB/session-drawer/$skin-phone.png@0,780,$w,1622" "$G/sidebar-drawer/$skin-phone.png@0,780,$w,1621"
  # Tablet: the expanded layout's 264dp rail, under the web's topbar (48px; Studio 4rem = 64px).
  top=48
  case "$skin" in studio*) top=64 ;; esac
  java "$TOOL" montage "$OUT/column-$skin-tablet.png" "Session list · expanded rail · $skin" \
    "$WEB/session-drawer/$skin-tablet.png@0,$top,264,$((800 - top))" "$G/sidebar-drawer/$skin-tablet.png@0,0,264,$((800 - top))"
done
