# Vendored parity corpora

Generated in tether worktrees and copied by `tools/parity/sync-corpus.sh` — do not hand-edit the
table. The corpora may sit at different tether bases: each row's manifest `tetherSha` is the tree it
was generated from (TRACKER.md's PARITY_BASE line says which base each layer is at).

| Corpus | Source branch @ sha | Manifest tetherSha |
|---|---|---|
| reducer+helpers | `(detached)` @ `887c222` | `887c22214126fa662192e2a3adf6f2dd44e69cd6` |
| wire | `(detached)` @ `887c222` | `887c22214126fa662192e2a3adf6f2dd44e69cd6` |
| tokens | `android-parity/S0.5` @ `356b456` | `356b456` |
| screens (PNGs gitignored) | `android-parity/S0.4` @ `3f69e4f` | `3f69e4fa48eab33afdfc55769aace707d7026c13` |

T15.5 (Studio-only appearance): `tokens/design-tokens.json` is the `356b456` export reduced to the
two Studio skins by `tools/parity/studio-only-tokens.py` (drops the four retired skins, `families`,
each skin's `family` and the top-level browser `chrome`; `skinMap` becomes mode -> skin, the shape
tether's exporter now writes). No Studio value changed.

T15.8 (re-baseline at tether `887c222`, v137): reducer+helpers and wire moved; tokens and screens did
not. The fresh token export is deterministic and has the shape the generator expects, but it
retires 21 material-layer tokens the app still references, so it is not vendored until ta-ccu
removes those usages. The screens exporter fails at `887c222` (its seed clicks `button.mobile-menu`,
which the redesigned top bar no longer renders), so the screens stay at `3f69e4f` until ta-lx3
fixes it tether-side.
