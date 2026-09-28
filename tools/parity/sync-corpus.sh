#!/usr/bin/env bash
# Vendor the generated parity corpora (PLAN §5) from tether worktrees into parity-corpus/.
# Copies GENERATED OUTPUT only — it never runs exporters and never touches ~/git/tether
# (the production checkout). Regenerate the corpora in their worktrees first.
#
#   tools/parity/sync-corpus.sh [reducer-wt] [wire-wt] [tokens-wt] [screens-wt]
#
# A worktree argument of `-` leaves that corpus (and its VENDORED.md row) as vendored — e.g. a
# protocol/reducer catch-up that moves reducer+helpers and wire but keeps the UI parity base:
#   tools/parity/sync-corpus.sh <scratch> <scratch> - -
# A source tree need not be a branch checkout: a `git archive` extract with a `.git` whose HEAD is
# the archived sha works (its row then reads `(detached)`).
#
# Web reference screenshots (S0.4, 55 MB) are copied into parity-corpus/screens/web/ but the PNGs
# are gitignored; manifest.json + SHA256SUMS are committed and pin them. Check a local copy with
#   (cd parity-corpus/screens/web && sha256sum -c --quiet SHA256SUMS)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WT=${WT:-$HOME/git/tether-wt}
REDUCER_WT=${1:-$WT/android-parity-S0.2}
WIRE_WT=${2:-$WT/android-parity-S0.3}
TOKENS_WT=${3:-$WT/android-parity-S0.5}
SCREENS_WT=${4:-$WT/android-parity-S0.4}
for d in "$REDUCER_WT" "$WIRE_WT" "$TOKENS_WT" "$SCREENS_WT"; do
  [ "$d" = "-" ] && continue
  case "$(cd "$d" && pwd)" in "$HOME/git/tether") echo "refusing: $d is the production checkout" >&2; exit 1;; esac
done
DEST="$ROOT/parity-corpus"
OLD_VENDORED=$(cat "$DEST/VENDORED.md" 2>/dev/null || true)
mkdir -p "$DEST"
if [ "$REDUCER_WT" != "-" ]; then
  rm -rf "$DEST/reducer" "$DEST/helpers"
  cp -r "$REDUCER_WT/parity-corpus/reducer" "$REDUCER_WT/parity-corpus/helpers" "$DEST/"
  cp "$REDUCER_WT/parity-corpus/corpus-manifest.json" "$DEST/corpus-manifest.json"
fi
if [ "$WIRE_WT" != "-" ]; then
  rm -rf "$DEST/wire"
  cp -r "$WIRE_WT/parity-corpus/wire" "$DEST/"
fi
if [ "$TOKENS_WT" != "-" ]; then
  rm -rf "$DEST/tokens"
  cp -r "$TOKENS_WT/parity-corpus/tokens" "$DEST/"
fi
if [ "$SCREENS_WT" != "-" ]; then
  rm -rf "$DEST/screens/web"
  mkdir -p "$DEST/screens"
  cp -r "$SCREENS_WT/parity-corpus/screens/web" "$DEST/screens/web"
  (cd "$DEST/screens/web" && find . -name '*.png' | LC_ALL=C sort | xargs sha256sum > SHA256SUMS)
fi
{
  echo "# Vendored parity corpora"
  echo
  echo "Generated in tether worktrees and copied by \`tools/parity/sync-corpus.sh\` — do not hand-edit."
  echo "PARITY_BASE (UI: tokens, screens) = tether \`7d65611\` (v128). The protocol corpora (reducer+helpers,"
  echo "wire) may be newer: each row's manifest \`tetherSha\` is the tree it was generated from."
  echo
  echo "| Corpus | Source branch @ sha | Manifest tetherSha |"
  echo "|---|---|---|"
  for pair in "reducer+helpers:$REDUCER_WT:corpus-manifest.json" "wire:$WIRE_WT:wire/manifest.json" "tokens:$TOKENS_WT:tokens/design-tokens.json" "screens (PNGs gitignored):$SCREENS_WT:screens/web/manifest.json"; do
    IFS=: read -r name wt mf <<<"$pair"
    if [ "$wt" = "-" ]; then
      # Keep the row as it was vendored.
      grep -F "| $name |" <<<"$OLD_VENDORED" || echo "| $name | (not vendored) | |"
      continue
    fi
    br=$(git -C "$wt" branch --show-current); br=${br:-(detached)}; sha=$(git -C "$wt" rev-parse --short HEAD)
    msha=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('tetherSha'))" "$DEST/$mf")
    echo "| $name | \`$br\` @ \`$sha\` | \`$msha\` |"
  done
} > "$DEST/VENDORED.md"
du -sh "$DEST"; cat "$DEST/VENDORED.md"
