#!/usr/bin/env bash
# Rebuilds the T4.2 expanded-shell montages in docs/parity/screens/shell-expanded/ from the web
# reference PNGs (S0.4 parity-screens, tablet 1280×800 @1x) and the :feature:shell Roborazzi
# goldens (w1280dp-h800dp-mdpi, also 1 px/dp), so crops compare 1:1. Crops are x,y,w,h in each
# image's own pixels.
#
#   docs/parity/screens/shell-expanded/montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then the main checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# The goldens fill the hosted surfaces with stand-ins (the rail's session list is T5.1, the chat
# transcript and composer T6/T7, the inspector T9.1), so each montage pairs the CHROME the expanded
# shell owns: the topbar and workspace header band, the rail's edge and the stage's bay gutter,
# the floating telemetry sheet's frame and header, and the empty stage.
set -euo pipefail
cd "$(dirname "$0")/../../../.."
WEB="${1:-${PARITY_WEB_SCREENS:-$HOME/git/tether-android/parity-corpus/screens/web}}"
G=feature/shell/src/test/screenshots
OUT=docs/parity/screens/shell-expanded
TOOL=tools/compare-screens/CompareScreens.java

for skin in tactile night precision machine studio studio-dark; do
  case "$skin" in studio|studio-dark) band=146 ;; *) band=112 ;; esac
  # Topbar (brand on the rail's vertical, link readout, labelled tool keys, Lock) + the header
  # band (title, dial, labelled gauge, links key, Pin, End session): idle-session.
  java "$TOOL" montage "$OUT/idle-$skin-tablet.png" "Expanded shell · idle session · $skin (topbar + workspace header)" \
    "$WEB/idle-session/$skin-tablet.png@0,0,1280,$band" "$G/shell-expanded-idle/$skin-tablet.png@0,0,1280,$band"

  # The rail's right edge and the stage's bay (gutter + the screen's bezel corner), below the
  # rail's list and the timeline marker: idle-session.
  java "$TOOL" montage "$OUT/bay-$skin-tablet.png" "Expanded shell · rail edge + stage bay · $skin" \
    "$WEB/idle-session/$skin-tablet.png@244,440,120,160" "$G/shell-expanded-idle/$skin-tablet.png@244,440,120,160"

  # Telemetry open below 100rem: the sheet floats beside the conversation (frame, header, close);
  # the body is the inspector (T9.1).
  java "$TOOL" montage "$OUT/details-$skin-tablet.png" "Expanded shell · session details (floating sheet) · $skin" \
    "$WEB/session-details/$skin-tablet.png@0,0,1280,$band" "$G/shell-expanded-details/$skin-tablet.png@0,0,1280,$band" \
    "$WEB/session-details/$skin-tablet.png@880,100,400,100" "$G/shell-expanded-details/$skin-tablet.png@880,100,400,100"

  # No session: the whole workspace column (the bay-seated well and its content block).
  java "$TOOL" montage "$OUT/empty-$skin-tablet.png" "Expanded shell · empty state · $skin (workspace column)" \
    "$WEB/empty-state/$skin-tablet.png@264,0,1016,800" "$G/shell-expanded-empty/$skin-tablet.png@264,0,1016,800"
done
