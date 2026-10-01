#!/usr/bin/env bash
# Rebuilds the T7.3 montages under docs/parity/screens/commands/ from the web reference
# reconstructions (tools/compare-screens/commands-web-reference.py: the web's own markup and CSS at
# PARITY_BASE, rendered by a headless Chromium at 412×915 @DPR 2.625; the S0.4 seeder reaches none
# of these states) and the goldens feature/chat/src/test/screenshots/commands-*/.
#
#   tools/compare-screens/commands-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to /tmp/tether-commands-web (rendered first when missing).
# Band: the deck is pinned to the foot of both images, so the band is the golden's full height taken
# from the bottom of each.
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-/tmp/tether-commands-web}"
[ -d "$WEB/command" ] || python3 tools/compare-screens/commands-web-reference.py "$WEB"
G=feature/chat/src/test/screenshots
OUT=docs/parity/screens/commands
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

height() { python3 -c "import struct,sys; f=open(sys.argv[1],'rb'); f.read(16); print(struct.unpack('>II', f.read(8))[1])" "$1"; }

declare -A TITLE=(
  [command]="the ! command mode (flag, red-edged mono well, Send to agent + Background)"
  [foreground]="a foreground command running (Background + Stop)"
  [slash]="the slash palette from the CLI inventory"
  [mention]="the @ Agents picker"
  [delegate]="the delegate chip and its selects"
  [panel]="the transcript's command panel (running, failed)"
)
for state in command foreground slash mention delegate panel; do
  for skin in studio studio-dark; do
    g="$G/commands-$state/$skin-phone.png"
    w="$WEB/$state/$skin-phone.png"
    gh=$(height "$g")
    wh=$(height "$w")
    band=$(( gh < wh ? gh : wh ))
    java "$TOOL" montage "$OUT/$state-$skin-phone.png" "Commands · ${TITLE[$state]} · $skin (phone)" \
      "$w@0,$((wh - band)),1081,$band" "$g@0,$((gh - band)),1081,$band"
  done
done
