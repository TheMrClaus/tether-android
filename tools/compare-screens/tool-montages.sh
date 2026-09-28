#!/usr/bin/env bash
# Rebuilds the T6.2 tool-card montages under docs/parity/screens/tool-cards/ from the web reference
# PNGs (S0.4 parity-screens) and the tool goldens in feature/chat/src/test/screenshots/.
#
#   tools/compare-screens/tool-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# Wells as in chat-montages.sh (phone 412dp × 678dp at 2.625 px/dp; tablet 950dp × 530dp at 1 px/dp).
# The web's tool-cards session shows a todo bar above the composer, so its transcript ends higher
# (phone y 1960, tablet y 595): those crops are BOTTOM-aligned (the golden is in follow mode too).
# codex-tool-cards-top is TOP-aligned (the harness scrolled to the top; the golden scrolls to row 0).
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/chat/src/test/screenshots
OUT=docs/parity/screens/tool-cards
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in tactile night precision machine studio studio-dark; do
  java "$TOOL" montage "$OUT/tools-$skin-phone.png" "Tool cards · finished run collapsed (errors), MCP card with its picture · $skin (phone)" \
    "$WEB/tool-cards/$skin-phone.png@0,320,1080,1640" "$G/tool-tools/$skin-phone.png@0,140,1080,1640"

  java "$TOOL" montage "$OUT/codex-top-$skin-phone.png" "Codex rich cards · run held open by its file change, top · $skin (phone)" \
    "$WEB/codex-tool-cards-top/$skin-phone.png@0,310,1080,1780" "$G/tool-codex/$skin-phone.png@0,0,1080,1780"

  java "$TOOL" montage "$OUT/tools-$skin-tablet.png" "Tool cards · finished run collapsed, MCP card · $skin (tablet, desktop layout)" \
    "$WEB/tool-cards/$skin-tablet.png@318,115,950,480" "$G/tool-tools/$skin-tablet.png@0,50,950,480"

  java "$TOOL" montage "$OUT/codex-top-$skin-tablet.png" "Codex rich cards, top · $skin (tablet, desktop layout)" \
    "$WEB/codex-tool-cards-top/$skin-tablet.png@318,115,950,530" "$G/tool-codex/$skin-tablet.png@0,0,950,530"
done
