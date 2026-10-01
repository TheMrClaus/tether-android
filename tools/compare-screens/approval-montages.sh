#!/usr/bin/env bash
# Rebuilds the T6.3 approval montages under docs/parity/screens/approvals/ from the web reference PNGs
# (S0.4 parity-screens, scenario approval-pending) and the approval goldens in
# feature/chat/src/test/screenshots/approval-write/.
#
#   tools/compare-screens/approval-montages.sh [web-reference-dir]
#
# web-reference-dir defaults to $PARITY_WEB_SCREENS, then this checkout's synced corpus
# (parity-corpus/screens/web, tools/parity/sync-corpus.sh).
#
# Wells as in chat-montages.sh (phone 412dp × 678dp at 2.625 px/dp; tablet 950dp × 530dp at 1 px/dp).
# Phone: the web's content is shorter than its well (the composer's waiting row ends it at y 2022),
# so both are TOP-aligned. Tablet: both are scrolled to the newest content, so BOTTOM-aligned (the
# web's frame ends at y 615, above its waiting row).
set -euo pipefail
cd "$(dirname "$0")/../.."
WEB="${1:-${PARITY_WEB_SCREENS:-parity-corpus/screens/web}}"
G=feature/chat/src/test/screenshots
OUT=docs/parity/screens/approvals
TOOL=tools/compare-screens/CompareScreens.java
mkdir -p "$OUT"

for skin in studio studio-dark; do
  java "$TOOL" montage "$OUT/approval-pending-$skin-phone.png" "Approval · a Write waiting for the operator (Approve / Deny) · $skin (phone)" \
    "$WEB/approval-pending/$skin-phone.png@0,310,1080,1700" "$G/approval-write/$skin-phone.png@0,0,1080,1700"

  java "$TOOL" montage "$OUT/approval-pending-$skin-tablet.png" "Approval · a Write waiting for the operator · $skin (tablet, desktop layout)" \
    "$WEB/approval-pending/$skin-tablet.png@318,115,950,500" "$G/approval-write/$skin-tablet.png@0,30,950,500"
done
