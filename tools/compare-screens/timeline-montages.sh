#!/usr/bin/env bash
# Rebuilds the T6.5 montages under docs/parity/screens/timeline/ from the web reference PNGs
# (S0.4 parity-screens, scenario `conversation-timeline`: five prompts, pinned to the newest) and
# the goldens in feature/chat/src/test/screenshots/timeline-rest/.
#
#   tools/compare-screens/timeline-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# Phone: the web's transcript well is y 310..2090 px (2.625 px/dp), the golden's 0..1780.
# Tablet: the web docks the rail in the stage's 3.4rem gutter, left of the chat frame, so the web
# crop starts at the gutter (x 264) instead of the frame (x 318); the golden's rail is the first
# 54dp of the well. Both 950 px wide from the header down (web y 115).
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/chat/src/test/screenshots
OUT=docs/parity/screens/timeline
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in tactile night precision machine studio studio-dark; do
  java "$TOOL" montage "$OUT/rest-$skin-phone.png" "Timeline · five prompts at rest (scale line, ticks, violet needle) · $skin (phone)" \
    "$WEB/conversation-timeline/$skin-phone.png@0,310,1080,1780" "$G/timeline-rest/$skin-phone.png@0,0,1080,1780"

  java "$TOOL" montage "$OUT/rest-$skin-tablet.png" "Timeline · five prompts at rest, rail docked left · $skin (tablet, desktop layout)" \
    "$WEB/conversation-timeline/$skin-tablet.png@264,115,950,530" "$G/timeline-rest/$skin-tablet.png@0,0,950,530"
done
