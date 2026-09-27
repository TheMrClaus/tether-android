#!/usr/bin/env bash
# Vendor the generated parity corpora (PLAN §5) from tether worktrees into parity-corpus/.
# Copies GENERATED OUTPUT only — it never runs exporters and never touches ~/git/tether
# (the production checkout). Regenerate the corpora in their worktrees first.
#
#   tools/parity/sync-corpus.sh [reducer-wt] [wire-wt] [tokens-wt]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WT=${WT:-$HOME/git/tether-wt}
REDUCER_WT=${1:-$WT/android-parity-S0.2}
WIRE_WT=${2:-$WT/android-parity-S0.3}
TOKENS_WT=${3:-$WT/android-parity-S0.5}
for d in "$REDUCER_WT" "$WIRE_WT" "$TOKENS_WT"; do
  case "$(cd "$d" && pwd)" in "$HOME/git/tether") echo "refusing: $d is the production checkout" >&2; exit 1;; esac
done
DEST="$ROOT/parity-corpus"
rm -rf "$DEST/reducer" "$DEST/helpers" "$DEST/wire" "$DEST/tokens"
mkdir -p "$DEST"
cp -r "$REDUCER_WT/parity-corpus/reducer" "$REDUCER_WT/parity-corpus/helpers" "$DEST/"
cp "$REDUCER_WT/parity-corpus/corpus-manifest.json" "$DEST/corpus-manifest.json"
cp -r "$WIRE_WT/parity-corpus/wire" "$DEST/"
cp -r "$TOKENS_WT/parity-corpus/tokens" "$DEST/"
{
  echo "# Vendored parity corpora"
  echo
  echo "Generated in tether worktrees and copied by \`tools/parity/sync-corpus.sh\` — do not hand-edit."
  echo "PARITY_BASE = tether \`7d65611\` (v128); each exporter branch is a scripts-only diff on top of it."
  echo
  echo "| Corpus | Source branch @ sha | Manifest tetherSha |"
  echo "|---|---|---|"
  for pair in "reducer+helpers:$REDUCER_WT:corpus-manifest.json" "wire:$WIRE_WT:wire/manifest.json" "tokens:$TOKENS_WT:tokens/design-tokens.json"; do
    IFS=: read -r name wt mf <<<"$pair"
    br=$(git -C "$wt" branch --show-current); sha=$(git -C "$wt" rev-parse --short HEAD)
    msha=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('tetherSha'))" "$DEST/$mf")
    echo "| $name | \`$br\` @ \`$sha\` | \`$msha\` |"
  done
} > "$DEST/VENDORED.md"
du -sh "$DEST"; cat "$DEST/VENDORED.md"
