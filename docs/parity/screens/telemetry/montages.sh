#!/usr/bin/env bash
# Rebuilds the ta-coik.10 montages in docs/parity/screens/telemetry/ from the web's own reference
# captures (tether 90fbb9f design/telemetry-panel/, rendered from tests/telemetry-panel.spec.ts) and
# the `telemetry-panel-*` Roborazzi goldens of :feature:shell (the same fixture, InspectorBoards.Reference).
#
#   mkdir -p /tmp/telemetry-ref && for f in $(git -C ~/git/tether ls-tree --name-only 90fbb9f design/telemetry-panel/ | xargs -n1 basename); do
#     git -C ~/git/tether show 90fbb9f:design/telemetry-panel/$f > /tmp/telemetry-ref/$f; done
#   docs/parity/screens/telemetry/montages.sh /tmp/telemetry-ref
#
# Crops: the web phone frames are 390 CSS px at 1x, the phone goldens 2.625 px/dp; the web desktop
# column is 288 px at 1x, the tablet goldens (the 48-100rem floating card) 1 px/dp and wider (368dp).
# Each pair crops the panel BODY (below the sheet's "Session details" header) at the same aspect
# ratio, so the tool's scale-to-web step is uniform. Web viewport frames (phone-390, phone-attention,
# phone-empty) start below the sheet header (y=179).
set -euo pipefail
cd "$(dirname "$0")/../../../.."
WEB="${1:?usage: montages.sh <dir holding the design/telemetry-panel PNGs>}"
G=feature/shell/src/test/screenshots
OUT=docs/parity/screens/telemetry
TOOL=tools/compare-screens/CompareScreens.java

# pair <out> <title> <web.png> <webY> <app.png> <appX> <appY> <appW>
pair() {
  local spec
  spec=$(python3 - "$3" "$4" "$5" "$6" "$7" "$8" <<'PY'
import struct, sys
def size(p):
    d = open(p, 'rb').read(24); return struct.unpack('>II', d[16:24])
web, wy, app, ax, ay, aw = sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6])
ww, wh = size(web); _, ah = size(app)
web_h = wh - wy
app_h = round(web_h * aw / ww)
if ay + app_h > ah:                      # the golden is shorter: shorten the web crop instead
    app_h = ah - ay
    web_h = round(app_h * ww / aw)
print(f"{web}@0,{wy},{ww},{web_h} {app}@{ax},{ay},{aw},{app_h}")
PY
)
  # shellcheck disable=SC2086
  java "$TOOL" montage "$OUT/$1" "$2" $spec
}

for skin in studio studio-dark; do
  pair "panel-full-$skin-phone.png" "Telemetry panel · full sheet · $skin · phone" "$WEB/phone-sheet-full-$skin.png" 0 "$G/telemetry-panel-full/$skin-phone.png" 42 205 1024
  pair "panel-top-$skin-phone.png" "Telemetry panel · top of the sheet · $skin · phone" "$WEB/phone-390-$skin.png" 179 "$G/telemetry-panel-full/$skin-phone.png" 42 205 1024
  pair "panel-full-$skin-tablet.png" "Telemetry panel · full · $skin · tablet (web: desktop column)" "$WEB/desktop-column-full-$skin.png" 0 "$G/telemetry-panel-full/$skin-tablet.png" 17 72 366
done
pair "panel-attention-studio-phone.png" "Telemetry panel · attention · studio · phone" "$WEB/phone-attention-studio.png" 179 "$G/telemetry-panel-attention/studio-phone.png" 42 205 1024
pair "panel-empty-studio-dark-phone.png" "Telemetry panel · empty · studio-dark · phone" "$WEB/phone-empty-studio-dark.png" 179 "$G/telemetry-panel-empty/studio-dark-phone.png" 42 205 1024
pair "panel-attention-studio-dark-tablet.png" "Telemetry panel · attention · studio-dark · tablet (web: desktop column)" "$WEB/desktop-attention-studio-dark.png" 0 "$G/telemetry-panel-attention/studio-dark-tablet.png" 17 72 366
pair "panel-empty-studio-dark-tablet.png" "Telemetry panel · empty · studio-dark · tablet (web: desktop column)" "$WEB/desktop-empty-studio-dark.png" 0 "$G/telemetry-panel-empty/studio-dark-tablet.png" 17 72 366
pair "panel-selected-studio-dark-tablet.png" "Telemetry panel · subagent selected · studio-dark · tablet (web: desktop column)" "$WEB/desktop-subagent-selected-studio-dark.png" 0 "$G/telemetry-panel-selected/studio-dark-tablet.png" 17 72 366
