#!/usr/bin/env bash
# Rebuilds the T6.1 transcript montages under docs/parity/screens/chat/ from the web reference PNGs
# (S0.4 parity-screens) and the chat goldens in feature/chat/src/test/screenshots/.
#
#   tools/compare-screens/chat-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# The goldens render the transcript WELL only (ChatTranscript, no topbar/header/composer), sized
# like the web's: phone 412dp × 678dp at 2.625 px/dp = the web's y 310..2090 px band; tablet
# 950dp × 530dp at 1 px/dp = the web desktop chat frame's x 318..1268, y 115..645. Crops are
# x,y,w,h in each image's own pixels.
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/chat/src/test/screenshots
OUT=docs/parity/screens/chat
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in tactile night precision machine studio studio-dark; do
  java "$TOOL" montage "$OUT/idle-$skin-phone.png" "Transcript · idle session · $skin (phone)" \
    "$WEB/idle-session/$skin-phone.png@0,310,1080,700" "$G/chat-idle/$skin-phone.png@0,0,1080,700"

  java "$TOOL" montage "$OUT/markdown-top-$skin-phone.png" "Transcript · long markdown, top (headings, lists, literal task list, table) · $skin (phone)" \
    "$WEB/long-markdown-top/$skin-phone.png@0,310,1080,1780" "$G/chat-markdown-top/$skin-phone.png@0,0,1080,1780"

  java "$TOOL" montage "$OUT/markdown-$skin-phone.png" "Transcript · long markdown, bottom (table, code + copy, quote, rule) · $skin (phone)" \
    "$WEB/long-markdown/$skin-phone.png@0,310,1080,1780" "$G/chat-markdown/$skin-phone.png@0,0,1080,1780"

  # The web cannot freeze a streaming message (parity-seed.mjs:701); its streaming scene is a turn
  # frozen on an open tool call. Only the operator's bubble is the same content in both.
  java "$TOOL" montage "$OUT/streaming-$skin-phone.png" "Transcript · streaming turn, user bubble · $skin (phone)" \
    "$WEB/streaming/$skin-phone.png@0,310,1080,210" "$G/chat-streaming/$skin-phone.png@0,0,1080,210"

  for shot in markdown-top:long-markdown-top markdown:long-markdown idle:idle-session; do
    a="${shot%%:*}"; w="${shot##*:}"
    java "$TOOL" montage "$OUT/$a-$skin-tablet.png" "Transcript · $w · $skin (tablet, desktop layout)" \
      "$WEB/$w/$skin-tablet.png@318,115,950,530" "$G/chat-$a/$skin-tablet.png@0,0,950,530"
  done
done
