# Vendored parity corpora

Generated in tether worktrees and copied by `tools/parity/sync-corpus.sh` — do not hand-edit.
PARITY_BASE (UI: tokens, screens) = tether `7d65611` (v128). The protocol corpora (reducer+helpers,
wire) may be newer: each row's manifest `tetherSha` is the tree it was generated from.

| Corpus | Source branch @ sha | Manifest tetherSha |
|---|---|---|
| reducer+helpers | `(detached)` @ `79c3d37` | `79c3d377d2f1e650286092b3387392c15512a843` |
| wire | `(detached)` @ `79c3d37` | `79c3d377d2f1e650286092b3387392c15512a843` |
| tokens | `android-parity/S0.5` @ `356b456` | `356b456` |
| screens (PNGs gitignored) | `android-parity/S0.4` @ `3f69e4f` | `3f69e4fa48eab33afdfc55769aace707d7026c13` |
