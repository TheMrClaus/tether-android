#!/usr/bin/env bash
# Rebuilds the T7.2 session-controls montages under docs/parity/screens/controls/ from the web
# reference PNGs (S0.4 parity-screens, scenario idle-session) and the composer goldens in
# feature/chat/src/test/screenshots/composer-idle (which T7.2 renders with the web scenario's
# controls: "Opus (1M context)", Manual).
#
#   tools/compare-screens/controls-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus.
#
# - phone: the web below 64rem folds Model / Effort / Mode into ONE settings key in the well's
#   toolbar; the band is the well (bottom 288px of both images), as composer-montages.sh.
# - tablet: the web desktop row (Model / Mode pills, the hint) above the toolbar footer; the band
#   is the bottom 100px of the golden against the web deck band ending at the well's foot
#   (Studio x 301, y 700: the deck is centred).
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/chat/src/test/screenshots
OUT=docs/parity/screens/controls
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

height() { python3 -c "import struct,sys; f=open(sys.argv[1],'rb'); f.read(16); print(struct.unpack('>II', f.read(8))[1])" "$1"; }

PHONE_BAND=288
TABLET_BAND=100
for skin in studio studio-dark; do
  g="$G/composer-idle/$skin-phone.png"
  gh=$(height "$g")
  java "$TOOL" montage "$OUT/key-$skin-phone.png" "Session controls · idle-session (the settings key in the well) · $skin (phone)" \
    "$WEB/idle-session/$skin-phone.png@0,$((2402 - PHONE_BAND)),1081,$PHONE_BAND" "$g@0,$((gh - PHONE_BAND)),1081,$PHONE_BAND"
  g="$G/composer-idle/$skin-tablet.png"
  gh=$(height "$g")
  crop="301,700"
  java "$TOOL" montage "$OUT/row-$skin-tablet.png" "Session controls · idle-session (Model / Mode row) · $skin (tablet, desktop layout)" \
    "$WEB/idle-session/$skin-tablet.png@$crop,950,$TABLET_BAND" "$g@0,$((gh - TABLET_BAND)),950,$TABLET_BAND"
done
