#!/usr/bin/env bash
# Rebuilds the T4.3 montages in docs/parity/screens/statusline/ from the web reference PNGs
# (S0.4 parity-screens) and the Roborazzi goldens of :feature:shell. Phone crops compare 1:1
# (both 2.625 px/dp); tablet crops compare 1:1 (both 1 px/dp). Crops are x,y,w,h in each image's
# own pixels; the web crops were found with `CompareScreens.java align` (android crop searched in
# a window of the web shot).
#
#   docs/parity/screens/statusline/montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
set -euo pipefail
cd "$(dirname "$0")/../../../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/shell/src/test/screenshots
OUT=docs/parity/screens/statusline
TOOL=tools/compare-screens/CompareScreens.java

for skin in studio studio-dark; do
  gx=661
  # Context gauge with no reading (the seeded scenarios carry no metrics): rest in the streaming
  # header, open (telemetry sheet up) in session-details.
  java "$TOOL" montage "$OUT/context-gauge-$skin-phone.png" "Context gauge, no reading · $skin · phone (streaming rest · session-details open)" \
    "$WEB/streaming/$skin-phone.png@$gx,166,116,116" "$G/context-gauge/$skin-phone.png@63,557,116,116" \
    "$WEB/session-details/$skin-phone.png@$gx,166,116,116" "$G/context-gauge/$skin-phone.png@205,557,116,116"
done

# Tablet header rail (streaming, 1280×800): the dial "00:09:00" plate and the labelled gauge.
for skin in studio studio-dark; do
  rx=826; ry=86 # Studio's header is taller (studio.css:351 min-height 5rem)
  java "$TOOL" montage "$OUT/header-rail-$skin-tablet.png" "Header rail: session dial + labelled gauge · $skin · tablet (streaming)" \
    "$WEB/streaming/$skin-tablet.png@$rx,$ry,200,33" "$G/header-rail/$skin-tablet.png@23,49,200,33"
done
