#!/usr/bin/env bash
# Rebuilds the T6.7 montages under docs/parity/screens/interrupt-errors/ from the web reference PNGs
# (S0.4 parity-screens, scenario `streaming`: a turn frozen mid-flight, Queue + Interrupt in the
# deck) and the goldens feature/chat/src/test/screenshots/{composer-busy,interrupt-busy}/.
#
#   tools/compare-screens/interrupt-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# Bands as composer-montages.sh: phone = the well and the deck padding under it (the bottom 288px of
# both images); tablet = the toolbar's footer row (attach, SESSION readout, Queue, Interrupt), the
# bottom 60px of the golden against the same band of the web desktop frame's deck.
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/chat/src/test/screenshots
OUT=docs/parity/screens/interrupt-errors
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

height() { python3 -c "import struct,sys; f=open(sys.argv[1],'rb'); f.read(16); print(struct.unpack('>II', f.read(8))[1])" "$1"; }

PHONE_BAND=288
TABLET_BAND=60
for skin in studio studio-dark; do
  g="$G/composer-busy/$skin-phone.png"
  gh=$(height "$g")
  java "$TOOL" montage "$OUT/interrupt-$skin-phone.png" "Interrupt · streaming (the busy well: Interrupt, icon-only) · $skin (phone)" \
    "$WEB/streaming/$skin-phone.png@0,$((2402 - PHONE_BAND)),1081,$PHONE_BAND" "$g@0,$((gh - PHONE_BAND)),1081,$PHONE_BAND"
  g="$G/interrupt-busy/$skin-tablet.png"
  gh=$(height "$g")
  case "$skin" in studio*) crop="301,740" ;; *) crop="318,726" ;; esac
  java "$TOOL" montage "$OUT/interrupt-$skin-tablet.png" "Interrupt · streaming (toolbar footer: Queue + Interrupt) · $skin (tablet, desktop layout)" \
    "$WEB/streaming/$skin-tablet.png@$crop,950,$TABLET_BAND" "$g@0,$((gh - TABLET_BAND)),950,$TABLET_BAND"
done
