#!/usr/bin/env bash
# Rebuilds the T10.5 passkey montages under docs/parity/screens/passkeys/ from the web reference PNGs
# (S0.4 parity-screens, scenario `settings-devices`: no passkey registered, the add row ready) and
# the goldens in feature/settings/src/test/screenshots/settings-devices-passkey-empty/.
#
#   tools/compare-screens/passkeys-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then this checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# Both are cropped to the Passkeys section, from its heading to the password switch. Phone: the web
# dialog is inset (its section starts at y 1272), the golden full screen (y 480). Tablet: both are
# the centred 880-wide dialog; the web scrolls to Notifications first (Passkeys at y 425), the golden
# to Passkeys (y 215). The login screen's passkey key has no web reference scenario (the seed never
# registers a passkey); its goldens are feature/auth/src/test/screenshots/login-passkey*/.
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-parity-corpus/screens/web}}"
G=feature/settings/src/test/screenshots/settings-devices-passkey-empty
OUT=docs/parity/screens/passkeys
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in studio studio-dark; do
  java "$TOOL" montage "$OUT/add-passkey-$skin-phone.png" "Settings · Devices · Passkeys: none yet, Add a passkey · $skin (phone)" \
    "$WEB/settings-devices/$skin-phone.png@0,1272,1082,780" "$G/$skin-phone.png@0,480,1081,780"

  java "$TOOL" montage "$OUT/add-passkey-$skin-tablet.png" "Settings · Devices · Passkeys: none yet, Add a passkey · $skin (tablet, desktop layout)" \
    "$WEB/settings-devices/$skin-tablet.png@200,425,880,290" "$G/$skin-tablet.png@200,215,880,290"
done
