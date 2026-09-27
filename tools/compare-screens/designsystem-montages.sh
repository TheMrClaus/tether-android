#!/usr/bin/env bash
# Rebuilds the T3.3 primitive montages under docs/parity/screens/designsystem/ from the web
# reference PNGs (S0.4 parity-screens, phone 412×915 @2.625) and the Roborazzi goldens (412dp @420dpi,
# also 2.625 px/dp, so crops compare 1:1). Crops are x,y,w,h in each image's own pixels.
#
#   tools/compare-screens/designsystem-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then /tmp/parity-s04-1
# (layout: <scenario>/<skin>-<size>.png).
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-/tmp/parity-s04-1}}"
G=core/designsystem/src/test/screenshots
OUT=docs/parity/screens/designsystem
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

# Rockers (settings-general: "Confirm before ending" ON, "Show agent thinking" OFF).
for skin in tactile night precision machine; do
  java "$TOOL" montage "$OUT/rocker-$skin-phone.png" "Rocker · $skin · phone (settings-general)" \
    "$WEB/settings-general/$skin-phone.png@802,1789,198,103" "$G/rocker/$skin-phone.png@30,101,198,103" \
    "$WEB/settings-general/$skin-phone.png@802,1411,198,103" "$G/rocker/$skin-phone.png@228,101,198,103"
done
for skin in studio studio-dark; do
  java "$TOOL" montage "$OUT/rocker-$skin-phone.png" "Rocker · $skin · phone (settings-general)" \
    "$WEB/settings-general/$skin-phone.png@880,1909,130,88" "$G/rocker/$skin-phone.png@36,108,130,88" \
    "$WEB/settings-general/$skin-phone.png@880,1463,130,88" "$G/rocker/$skin-phone.png@178,108,130,88"
done

# Status pill READY (idle-session chat header) — instrument skins print the etched mono badge.
for skin in tactile night precision machine; do
  java "$TOOL" montage "$OUT/status-pill-$skin-phone.png" "Status pill READY · $skin · phone (idle-session header)" \
    "$WEB/idle-session/$skin-phone.png@433,186,184,79" "$G/status-pills/$skin-phone.png@475,85,184,79"
done

# Dialog footer keys (settings-general: CANCEL secondary, SAVE SETTINGS primary with a check glyph).
for skin in tactile night precision machine; do
  java "$TOOL" montage "$OUT/keys-footer-$skin-phone.png" "Keys · $skin · phone (settings-general footer)" \
    "$WEB/settings-general/$skin-phone.png@251,2016,250,140" "$G/keys-dialog-footer/$skin-phone.png@34,85,250,140" \
    "$WEB/settings-general/$skin-phone.png@527,2016,450,140" "$G/keys-dialog-footer/$skin-phone.png@286,85,450,140"
done
