#!/usr/bin/env bash
# Rebuilds the T6.4 sub-agent run montages under docs/parity/screens/subagent-runs/ from the web
# reference PNGs (S0.4 parity-screens, scenario subagent-runs-top: the transcript scrolled to the top)
# and the goldens in feature/chat/src/test/screenshots/subrun-session/.
#
#   tools/compare-screens/subagent-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then this checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# Wells as in chat-montages.sh (phone 412dp × 678dp at 2.625 px/dp, from just below the workspace
# header; tablet 950dp × 530dp at 1 px/dp, the chat frame between the sidebar and the right edge).
# Both are TOP-aligned: the tab strip opens the well on both.
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-parity-corpus/screens/web}}"
G=feature/chat/src/test/screenshots
OUT=docs/parity/screens/subagent-runs
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in studio studio-dark; do
  java "$TOOL" montage "$OUT/subagent-runs-$skin-phone.png" "Sub-agent runs · tab strip, closed roster, the Session tab · $skin (phone)" \
    "$WEB/subagent-runs-top/$skin-phone.png@0,310,1080,1300" "$G/subrun-session/$skin-phone.png@0,0,1080,1300"

  # Studio's desktop frame is inset differently (a narrower sidebar, a taller header band).
  case "$skin" in studio|studio-dark) at="280,146" ;; *) at="318,115" ;; esac
  java "$TOOL" montage "$OUT/subagent-runs-$skin-tablet.png" "Sub-agent runs · the Session tab · $skin (tablet, desktop layout)" \
    "$WEB/subagent-runs-top/$skin-tablet.png@$at,950,420" "$G/subrun-session/$skin-tablet.png@0,0,950,420"
done
