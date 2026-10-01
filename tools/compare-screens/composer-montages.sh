#!/usr/bin/env bash
# Rebuilds the T7.1 composer montages under docs/parity/screens/composer/ from the web reference PNGs
# (S0.4 parity-screens) and the composer goldens in feature/chat/src/test/screenshots/composer-*.
#
#   tools/compare-screens/composer-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# The goldens render the composer deck alone (Composer at 412dp / 420dpi on a phone, 950dp / mdpi
# on a tablet). The pairs are BOTTOM-aligned bands (since T7.2 the well carries the web's Model /
# settings key on a phone and the Model / Mode row above the footer on a tablet):
# - phone: the well and the deck padding under it, the bottom 288px of both images;
# - tablet: the toolbar's footer row (attach, SESSION readout, Send) and the padding under it, the
#   bottom 60px of the golden against the same band of the web desktop frame's deck.
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/chat/src/test/screenshots
OUT=docs/parity/screens/composer
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

height() { python3 -c "import struct,sys; f=open(sys.argv[1],'rb'); f.read(16); print(struct.unpack('>II', f.read(8))[1])" "$1"; }

PHONE_BAND=288
TABLET_BAND=60
for skin in studio studio-dark; do
  for shot in idle:idle-session busy:streaming; do
    a="${shot%%:*}"; w="${shot##*:}"
    g="$G/composer-$a/$skin-phone.png"
    gh=$(height "$g")
    java "$TOOL" montage "$OUT/$a-$skin-phone.png" "Composer · $w (the well) · $skin (phone)" \
      "$WEB/$w/$skin-phone.png@0,$((2402 - PHONE_BAND)),1081,$PHONE_BAND" "$g@0,$((gh - PHONE_BAND)),1081,$PHONE_BAND"
  done
  g="$G/composer-idle/$skin-tablet.png"
  gh=$(height "$g")
  # Studio's deck (1rem / 1.25rem padding) centres its well at <= 53rem: the footer band sits at
  # x 301, y 740 (found with `CompareScreens align`).
  crop="301,740"
  java "$TOOL" montage "$OUT/idle-$skin-tablet.png" "Composer · idle-session (toolbar footer) · $skin (tablet, desktop layout)" \
    "$WEB/idle-session/$skin-tablet.png@$crop,950,$TABLET_BAND" "$g@0,$((gh - TABLET_BAND)),950,$TABLET_BAND"
done
