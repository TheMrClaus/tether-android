#!/usr/bin/env bash
# Rebuilds the T5.3 search montages under docs/parity/screens/search/. The S0.4 corpus has no web
# scenario with the global search modal or the in-chat find bar open, so the references are the
# nearest web shots of the same content: `long-markdown-top` for the find bar over the same reply
# (the diff is the marks and the bar), `session-drawer` for the sidebar filter with content hits
# (the row grid and block header; the rows differ). Crops are x,y,w,h in each image's pixels.
#
#   tools/compare-screens/search-montages.sh [web-reference-dir]
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
C=feature/chat/src/test/screenshots
S=feature/sidebar/src/test/screenshots
OUT=docs/parity/screens/search
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in tactile night precision machine studio studio-dark; do
  java "$TOOL" montage "$OUT/find-markdown-$skin-phone.png" "In-chat find · \"the\" over the long-markdown reply · $skin (phone) · web ref: long-markdown-top (no find bar)" \
    "$WEB/long-markdown-top/$skin-phone.png@0,310,1080,1780" "$C/find-markdown/$skin-phone.png@0,0,1080,1780"
  java "$TOOL" montage "$OUT/find-markdown-$skin-tablet.png" "In-chat find · \"the\" · $skin (tablet) · web ref: long-markdown-top (no find bar)" \
    "$WEB/long-markdown-top/$skin-tablet.png@318,115,950,530" "$C/find-markdown/$skin-tablet.png@0,0,950,530"
  w=840
  case "$skin" in studio*) w=882 ;; esac
  java "$TOOL" montage "$OUT/sidebar-content-search-$skin-phone.png" "Sidebar filter + content search · $skin · web ref: session-drawer (no query)" \
    "$WEB/session-drawer/$skin-phone.png@0,0,$w,2400" "$S/sidebar-content-search/$skin-phone.png@0,0,$w,2400"
done
