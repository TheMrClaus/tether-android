# Vendored parity corpora

Generated in tether worktrees and copied by `tools/parity/sync-corpus.sh` — do not hand-edit the
table. The corpora may sit at different tether bases: each row's manifest `tetherSha` is the tree it
was generated from (TRACKER.md's PARITY_BASE line says which base each layer is at).

| Corpus | Source branch @ sha | Manifest tetherSha |
|---|---|---|
| reducer+helpers | `(detached)` @ `887c222` | `887c22214126fa662192e2a3adf6f2dd44e69cd6` |
| wire | `(detached)` @ `887c222` | `887c22214126fa662192e2a3adf6f2dd44e69cd6` |
| tokens | `(detached)` @ `887c222` | `887c222` |
| screens (PNGs gitignored) | `android-parity/ta-lx3` @ `97af028` | `97af028a88a99588af5db8f350e2a43aacf53935` |

T15.8 (re-baseline at tether `887c222`, v137): reducer+helpers and wire moved; screens did not. The
screens exporter fails at `887c222` (its seed clicks `button.mobile-menu`, which the redesigned top
bar no longer renders), so the screens stayed at `3f69e4f` until ta-lx3 fixed it tether-side (below).

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
