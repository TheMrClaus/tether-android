#!/usr/bin/env bash
# Rebuilds the T4.1 phone-shell montages under docs/parity/screens/shell/ from the web reference
# PNGs (S0.4 parity-screens, phone 412×915 @2.625) and the shell's Roborazzi goldens (412dp @420dpi,
# also 2.625 px/dp, so crops compare 1:1). Crops are x,y,w,h in each image's own pixels.
#
#   tools/compare-screens/shell-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# The goldens fill the hosted surfaces (chat, drawer list, inspector) with empty slots, so each
# montage pairs the CHROME: the topbar + workspace header band, the telemetry panel's header, the
# drawer's container edge over the scrim, and the whole empty stage.
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/shell/src/test/screenshots
OUT=docs/parity/screens/shell
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in studio studio-dark; do
  # Topbar (0-147px) + workspace header (147-310px): idle-session.
  java "$TOOL" montage "$OUT/idle-$skin-phone.png" "Phone shell · idle session · $skin (topbar + workspace header)" \
    "$WEB/idle-session/$skin-phone.png@0,0,1080,330" "$G/shell-idle/$skin-phone.png@0,0,1080,330"

  # Telemetry panel open: header band + the panel's own header ("Session details" + close).
  java "$TOOL" montage "$OUT/details-$skin-phone.png" "Phone shell · session details open · $skin" \
    "$WEB/session-details/$skin-phone.png@0,0,1080,480" "$G/shell-details/$skin-phone.png@0,0,1080,480"

  # Drawer open: the container's right edge over the scrimmed shell (the list itself is T5.1).
  java "$TOOL" montage "$OUT/drawer-$skin-phone.png" "Phone shell · drawer open · $skin (container edge + scrim)" \
    "$WEB/session-drawer/$skin-phone.png@760,0,320,330" "$G/shell-drawer/$skin-phone.png@760,0,320,330"

  # No session: the well's top-left corner, then the stage's content block (orbit → title →
  # key → providers). Studio's empty stage is StudioWelcome (T8.1) — see README.
  java "$TOOL" montage "$OUT/empty-$skin-phone.png" "Phone shell · empty state · $skin" \
    "$WEB/empty-state/$skin-phone.png@0,110,360,200" "$G/shell-empty/$skin-phone.png@0,110,360,200" \
    "$WEB/empty-state/$skin-phone.png@0,560,1080,1260" "$G/shell-empty/$skin-phone.png@0,560,1080,1260"
done
