# Vendored parity corpora

Generated in tether worktrees and copied by `tools/parity/sync-corpus.sh` — do not hand-edit the
table. The corpora may sit at different tether bases: each row's manifest `tetherSha` is the tree it
was generated from (TRACKER.md's PARITY_BASE line says which base each layer is at).

| Corpus | Source branch @ sha | Manifest tetherSha |
|---|---|---|
| reducer+helpers | `(detached)` @ `887c222` | `887c22214126fa662192e2a3adf6f2dd44e69cd6` |
| wire | `(detached)` @ `887c222` | `887c22214126fa662192e2a3adf6f2dd44e69cd6` |
| tokens | `(detached)` @ `887c222` | `887c222` |
| screens (PNGs gitignored) | `android-parity/S0.4` @ `3f69e4f` | `3f69e4fa48eab33afdfc55769aace707d7026c13` |

T15.8 (re-baseline at tether `887c222`, v137): reducer+helpers and wire moved; screens did not. The
screens exporter fails at `887c222` (its seed clicks `button.mobile-menu`, which the redesigned top
bar no longer renders), so the screens stay at `3f69e4f` until ta-lx3 fixes it tether-side.

ta-ccu: tokens are the unmodified `887c222` export (run twice, byte-identical). Against the old
Studio-reduced `356b456` file it retires 21 Machine-era material tokens (every one transparent, 0 or
none in both skins, except `--rocker-ms` 110ms, which the web inlined) and adds `--brand-blue`,
`--brand-claude`, `--brand-ink` and `--brand-paper`; no other Studio value changed. The top-level
browser `chrome` block is new and the generator ignores it.
