#!/usr/bin/env bash
# Rebuilds the T11.1 workspace-file-browser montages under docs/parity/screens/files/ from the web
# reference PNGs (S0.4 parity-screens, scenario `file-browser`: parity-app opened from the top bar,
# nothing selected) and the goldens in feature/files/src/test/screenshots/files-list/.
#
#   tools/compare-screens/files-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# Both sides are the full viewport: phone 412x915 @2.625 (the goldens render 412dp @420dpi, the
# same 2.625 px/dp), tablet 1280x800 @1. The web shot shows the console behind the dialog's scrim;
# the goldens draw the scrim over nothing. Crops are x,y,w,h in each image's own pixels.
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/files/src/test/screenshots
OUT=docs/parity/screens/files
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in studio studio-dark; do
  java "$TOOL" montage "$OUT/list-$skin-phone.png" "Workspace files · parity-app, nothing selected · $skin (phone)" \
    "$WEB/file-browser/$skin-phone.png@0,0,1080,2400" "$G/files-list/$skin-phone.png@0,0,1080,2400"
  java "$TOOL" montage "$OUT/list-$skin-tablet.png" "Workspace files · parity-app, nothing selected · $skin (tablet, desktop layout)" \
    "$WEB/file-browser/$skin-tablet.png@0,0,1280,800" "$G/files-list/$skin-tablet.png@0,0,1280,800"
done
