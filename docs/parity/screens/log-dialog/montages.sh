#!/usr/bin/env bash
# Rebuilds the T4.5 montages in docs/parity/screens/log-dialog/ from the web reference PNGs
# (capture-web.mjs; <web-dir>/log-dialog/<skin>-<size>.png) and the `log-dialog-web` Roborazzi
# goldens of :feature:shell (the web reference's own state: its stats and connection records).
# Whole frames: phone 1:1 (both 2.625 px/dp), tablet 1:1 (both 1 px/dp). The web frames show the
# page behind the scrim; the goldens render the dialog over a plain scrim.
#
#   docs/parity/screens/log-dialog/montages.sh <web-dir>
set -euo pipefail
cd "$(dirname "$0")/../../../.."
WEB="${1:?usage: montages.sh <web-dir from capture-web.mjs>}/log-dialog"
G=feature/shell/src/test/screenshots/log-dialog-web
OUT=docs/parity/screens/log-dialog
TOOL=tools/compare-screens/CompareScreens.java
for skin in tactile night precision machine studio studio-dark; do
  for size in phone tablet; do
    java "$TOOL" montage "$OUT/log-dialog-$skin-$size.png" "Health & Event Log · $skin · $size (web fake-server state)" \
      "$WEB/$skin-$size.png" "$G/$skin-$size.png"
  done
done
