#!/usr/bin/env bash
# Rebuilds the T3.3 primitive montages under docs/parity/screens/designsystem/ from the web
# reference PNGs (S0.4 parity-screens, phone 412×915 @2.625) and the Roborazzi goldens (412dp @420dpi,
# also 2.625 px/dp, so crops compare 1:1). Crops are x,y,w,h in each image's own pixels.
#
#   tools/compare-screens/designsystem-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh)
# (layout: <scenario>/<skin>-<size>.png).
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
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

# Studio footer keys: button-secondary (flat graphite, line-strong edge) and button-primary (flat accent).
for skin in studio studio-dark; do
  java "$TOOL" montage "$OUT/keys-footer-$skin-phone.png" "Keys · $skin · phone (settings-general footer)" \
    "$WEB/settings-general/$skin-phone.png@382,2179,258,136" "$G/keys-dialog-footer/$skin-phone.png@32,83,258,136" \
    "$WEB/settings-general/$skin-phone.png@612,2179,391,136" "$G/keys-dialog-footer/$skin-phone.png@257,83,391,136"
done

# Keys from their web class sets (keys-web board) vs the scenarios that show them: approval-pending
# (Approve = button-primary, Deny = button-secondary chat-approval-deny, jump = chat-jump) and streaming
# (Interrupt = chat-send chat-interrupt, paperclip = chat-attach-btn, header End session = end-session).
# Web crops were found with `CompareScreens.java align` (android crop searched in a window of the web shot).
java "$TOOL" montage "$OUT/keys-web-tactile-phone.png" "Keys by class set · tactile · phone (approve · deny · interrupt · paperclip · end-session · jump)" \
    "$WEB/approval-pending/tactile-phone.png@59,1612,327,148" "$G/keys-web/tactile-phone.png@32,83,327,148" \
    "$WEB/approval-pending/tactile-phone.png@394,1612,258,144" "$G/keys-web/tactile-phone.png@365,83,258,144" \
    "$WEB/streaming/tactile-phone.png@911,2231,136,144" "$G/keys-web/tactile-phone.png@32,287,136,144" \
    "$WEB/streaming/tactile-phone.png@35,2232,136,143" "$G/keys-web/tactile-phone.png@174,287,136,143" \
    "$WEB/streaming/tactile-phone.png@914,158,136,144" "$G/keys-web/tactile-phone.png@316,287,136,144" \
    "$WEB/approval-pending/tactile-phone.png@911,1854,142,150" "$G/keys-web/tactile-phone.png@455,287,142,150"
java "$TOOL" montage "$OUT/keys-web-night-phone.png" "Keys by class set · night · phone (approve · deny · interrupt · paperclip · end-session · jump)" \
    "$WEB/approval-pending/night-phone.png@59,1612,327,144" "$G/keys-web/night-phone.png@32,83,327,144" \
    "$WEB/approval-pending/night-phone.png@394,1612,258,141" "$G/keys-web/night-phone.png@365,83,258,141" \
    "$WEB/streaming/night-phone.png@911,2231,136,141" "$G/keys-web/night-phone.png@32,287,136,141" \
    "$WEB/streaming/night-phone.png@38,2235,130,130" "$G/keys-web/night-phone.png@177,290,130,130" \
    "$WEB/streaming/night-phone.png@914,158,136,141" "$G/keys-web/night-phone.png@316,287,136,141" \
    "$WEB/approval-pending/night-phone.png@916,1856,130,130" "$G/keys-web/night-phone.png@461,290,130,130"
java "$TOOL" montage "$OUT/keys-web-precision-phone.png" "Keys by class set · precision · phone (approve · deny · interrupt · paperclip · end-session · jump)" \
    "$WEB/approval-pending/precision-phone.png@59,1612,329,149" "$G/keys-web/precision-phone.png@32,83,329,149" \
    "$WEB/approval-pending/precision-phone.png@394,1612,260,145" "$G/keys-web/precision-phone.png@366,83,260,145" \
    "$WEB/streaming/precision-phone.png@910,2231,137,145" "$G/keys-web/precision-phone.png@31,287,137,145" \
    "$WEB/streaming/precision-phone.png@35,2232,136,139" "$G/keys-web/precision-phone.png@174,287,136,139" \
    "$WEB/streaming/precision-phone.png@913,158,137,145" "$G/keys-web/precision-phone.png@315,287,137,145" \
    "$WEB/approval-pending/precision-phone.png@912,1854,140,149" "$G/keys-web/precision-phone.png@456,287,140,149"
java "$TOOL" montage "$OUT/keys-web-machine-phone.png" "Keys by class set · machine · phone (approve · deny · interrupt · paperclip · end-session · jump)" \
    "$WEB/approval-pending/machine-phone.png@59,1612,329,144" "$G/keys-web/machine-phone.png@32,83,329,144" \
    "$WEB/approval-pending/machine-phone.png@395,1612,259,142" "$G/keys-web/machine-phone.png@367,83,259,142" \
    "$WEB/streaming/machine-phone.png@911,2231,136,142" "$G/keys-web/machine-phone.png@32,287,136,142" \
    "$WEB/streaming/machine-phone.png@39,2234,130,130" "$G/keys-web/machine-phone.png@177,290,130,130" \
    "$WEB/streaming/machine-phone.png@914,158,136,142" "$G/keys-web/machine-phone.png@316,287,136,142" \
    "$WEB/approval-pending/machine-phone.png@916,1856,130,131" "$G/keys-web/machine-phone.png@461,290,130,131"
java "$TOOL" montage "$OUT/keys-web-studio-phone.png" "Keys by class set · studio · phone (approve · deny · interrupt · paperclip · end-session · jump)" \
    "$WEB/approval-pending/studio-phone.png@90,1783,338,136" "$G/keys-web/studio-phone.png@32,83,338,136" \
    "$WEB/approval-pending/studio-phone.png@414,1783,231,136" "$G/keys-web/studio-phone.png@355,83,231,136" \
    "$WEB/streaming/studio-phone.png@900,2221,136,136" "$G/keys-web/studio-phone.png@32,287,136,136" \
    "$WEB/streaming/studio-phone.png@46,2221,136,136" "$G/keys-web/studio-phone.png@174,287,136,136" \
    "$WEB/streaming/studio-phone.png@923,158,137,146" "$G/keys-web/studio-phone.png@315,287,137,146" \
    "$WEB/approval-pending/studio-phone.png@913,1817,136,141" "$G/keys-web/studio-phone.png@458,287,136,141"
java "$TOOL" montage "$OUT/keys-web-studio-dark-phone.png" "Keys by class set · studio-dark · phone (approve · deny · interrupt · paperclip · end-session · jump)" \
    "$WEB/approval-pending/studio-dark-phone.png@90,1783,338,136" "$G/keys-web/studio-dark-phone.png@32,83,338,136" \
    "$WEB/approval-pending/studio-dark-phone.png@414,1783,231,136" "$G/keys-web/studio-dark-phone.png@355,83,231,136" \
    "$WEB/streaming/studio-dark-phone.png@900,2221,136,136" "$G/keys-web/studio-dark-phone.png@32,287,136,136" \
    "$WEB/streaming/studio-dark-phone.png@46,2221,136,136" "$G/keys-web/studio-dark-phone.png@174,287,136,136" \
    "$WEB/streaming/studio-dark-phone.png@924,158,136,142" "$G/keys-web/studio-dark-phone.png@316,287,136,142" \
    "$WEB/approval-pending/studio-dark-phone.png@915,1819,132,131" "$G/keys-web/studio-dark-phone.png@460,290,132,131"
