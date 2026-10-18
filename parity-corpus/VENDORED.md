# Vendored parity corpora

Generated in tether worktrees and copied by `tools/parity/sync-corpus.sh` — do not hand-edit the
table. The corpora may sit at different tether bases: each row's manifest `tetherSha` is the tree it
was generated from (TRACKER.md's PARITY_BASE line says which base each layer is at).

| Corpus | Source branch @ sha | Manifest tetherSha |
|---|---|---|
| reducer+helpers | `(detached)` @ `19c56e25` | `19c56e25eab4fdf75fb34bdf3e6962b9fc0ab074` |
| wire | `(detached)` @ `19c56e25` | `19c56e25eab4fdf75fb34bdf3e6962b9fc0ab074` |
| tokens | `(detached)` @ `887c222` | `887c222` |
| screens (PNGs gitignored) | `android-parity/ta-lx3` @ `97af028` | `97af028a88a99588af5db8f350e2a43aacf53935` |

T15.8 (re-baseline at tether `887c222`, v137): reducer+helpers and wire moved; screens did not. The
screens exporter fails at `887c222` (its seed clicks `button.mobile-menu`, which the redesigned top
bar no longer renders), so the screens stayed at `3f69e4f` until ta-lx3 fixed it tether-side (below).

W48 (ta-d3tj.1): the WIRE corpus alone moved to tether `19c56e25` (v152; `sync-corpus.sh - <src> - -`), leaving reducer+helpers at `887c222`
/ v137 until R1.

R1 (ta-d3tj.10): reducer+helpers and `corpus-manifest.json` re-vendored at tether `19c56e25` (v152; `sync-corpus.sh <src> - - -`, the output
byte-identical to the one W47 generated and saved). Wire and reducer+helpers are now at the same base; tokens and screens stay at `887c222`.
`reference/events.mjs` is replaced by `reference/events.ts` (hand-copied, `sync-corpus.sh` does not touch `reference/`): `engines/events.ts` at
`19c56e25`, sha256 `64fd00e52ecd1f9f3ec983ae956c9631a56238ae25040a672350ca8c21655ccd` (recorded in `reference/SHA256`, pinned by CorpusIntegrityTest).
Upstream renamed events.mjs to events.ts (9ee02e9b); the FoldSourceChecksTest label set (74) is unchanged.

ta-ccu: tokens are the unmodified `887c222` export (run twice, byte-identical). Against the old
Studio-reduced `356b456` file it retires 21 Machine-era material tokens (every one transparent, 0 or
none in both skins, except `--rocker-ms` 110ms, which the web inlined) and adds `--brand-blue`,
`--brand-claude`, `--brand-ink` and `--brand-paper`; no other Studio value changed. The top-level
browser `chrome` block is new and the generator ignores it.

ta-lx3 (screens at `887c222`): the seed fix tether#232 (`scripts/parity-seed.mjs` only) made the
exporter run again. The screens were captured on the PR head `97af028` (parent `887c222`, so the app
code is `887c222`'s), and that head is tree-identical to its squash merge on tether main `4b05be7`
(`git diff 97af028 4b05be7` is empty). Two full runs gave 120/120 byte-identical PNGs: 27 scenarios x
the two Studio skins x phone/tablet. This replaces the 3f69e4f corpus, which had six skins.
