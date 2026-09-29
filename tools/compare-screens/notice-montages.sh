#!/usr/bin/env bash
# Rebuilds the T6.6 notice montages under docs/parity/screens/notices/ from the web reference PNGs
# (S0.4 parity-screens, scenarios `notices` and `handoff-source-locked`) and the notice goldens in
# feature/chat/src/test/screenshots/notice-{outcome-unknown,handoff}/.
#
#   tools/compare-screens/notice-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then this checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# Phone: the web's transcript well starts at y 310 (2.625 px/dp), the goldens at 0; both top-aligned.
# The handoff lock is the web frame's last 140 px; the golden is the composer deck alone.
# Tablet: the web's transcript column is x 318..1268 (950 px at 1 px/dp) under the header (y 122).
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-parity-corpus/screens/web}}"
G=feature/chat/src/test/screenshots
OUT=docs/parity/screens/notices
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in tactile night precision machine studio studio-dark; do
  java "$TOOL" montage "$OUT/outcome-unknown-$skin-phone.png" "Notices · a turn recovered as outcome_unknown · $skin (phone)" \
    "$WEB/notices/$skin-phone.png@0,310,1080,560" "$G/notice-outcome-unknown/$skin-phone.png@0,0,1080,560"

  java "$TOOL" montage "$OUT/outcome-unknown-$skin-tablet.png" "Notices · outcome_unknown · $skin (tablet, desktop layout)" \
    "$WEB/notices/$skin-tablet.png@318,122,950,200" "$G/notice-outcome-unknown/$skin-tablet.png@0,0,950,200"

  java "$TOOL" montage "$OUT/handoff-lock-$skin-phone.png" "Handoff · the source's composer replaced by \"Continued in →\" · $skin (phone)" \
    "$WEB/handoff-source-locked/$skin-phone.png@0,2262,1080,140" "$G/notice-handoff/$skin-phone.png@0,32,1080,140"

  java "$TOOL" montage "$OUT/handoff-lock-$skin-tablet.png" "Handoff · \"Continued in →\" · $skin (tablet, desktop layout)" \
    "$WEB/handoff-source-locked/$skin-tablet.png@318,738,950,52" "$G/notice-handoff/$skin-tablet.png@0,13,950,52"
done
