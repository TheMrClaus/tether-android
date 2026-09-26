# BEADS — the machine layer for the parity program

> Companion to [`PLAN.md`](./PLAN.md) §3 (Tracker Protocol) and [`TRACKER.md`](./TRACKER.md).
> **Row state, claims, dependencies and evidence live in the beads store.** `TRACKER.md` stays the
> human digest. Never edit the same row in both.

## What is where

| Thing | Where | Notes |
|---|---|---|
| Live task state (status, assignee, deps, evidence) | `.beads/` (Dolt) — issue prefix `ta` | Local DB, gitignored. `bd` is the only writer. |
| Issue history / audit | Dolt commits | `bd history <id>`, `bd history <id> --events` |
| Human digest | `TRACKER.md` | Narrative sections (RESUME HERE, Blockers, Decision log, Session log) stay hand-written. Task-board **rows** are refreshed from beads. |
| Durable backup + cross-machine sync | `refs/dolt/data` on `origin` | `bd dolt push` / `bd dolt pull`. Fresh clone: `bd bootstrap`. |
| The plan itself | `PLAN.md` | Unchanged, stable. |

**IDs are the program's own** (`PROG`, `P0`…`P14`, `T0.1`…`T14.5`, `S0.1`…`S13.1`), so every brief,
commit message, PR title and handover prompt that names `T2.3` still resolves. Seeded from
`TRACKER.md`; new/discovered work gets ordinary `ta-<hash>` IDs with a `discovered-from` edge.

The store was seeded with `~/bin/beads-seed.py --profile parity --repo ~/git/tether-android`
(reads `docs/parity/TRACKER.md`, emits JSONL, `bd import` upserts — safe to re-run after the tracker
moves). Re-run it if you ever need to reconcile the file back into the store.

## §3 rule → command

| PLAN §3 rule | beads |
|---|---|
| 1. Start of session: read state | `bd ready` (claimable frontier) · `bd list --all` · `bd show <id>` · `bd blocked` |
| 2. **Claim before work** | `bd update <id> --claim` — atomic: sets assignee=you + `in_progress`, idempotent if already yours. Then commit the tracker per §3. |
| 2. Stale claim (>2h) / takeover | `bd stale` to list; `bd update <id> --assignee <you> --force` ("abandoned claims only — prefer reclaim"), log the takeover in the session log |
| 3. **Checkpoint often** | `bd update <id> --append-notes "done: … | half: … | files: … | next: <exact command>"` |
| 4. **Finish with evidence** | `bd update <id> -s done --append-notes "EVIDENCE: sha …; ./gradlew test 41/41; docs/parity/screens/…"` |
| 4. Verifier promotes | `bd update <id> -s verified` (separate actor — never the maker) |
| 5. **Blocked** | `bd update <id> -s blocked --append-notes "needs owner: FCM secrets"` → shows in `bd blocked` |
| 6. Session log | `bd comment <id> "…"` (append-only, per-issue) — plus the TRACKER session-log line |
| 7. Decisions | `bd comment` on the affected task; material changes also edit `PLAN.md` + the TRACKER Decision log |
| 8. Status vocabulary | `open`(TODO) · `in_progress` · `blocked` · `review` · `done` · `verified` · `dropped` · `deferred` (owner-gated) — configured in this store, categories make `done/verified/dropped/deferred` leave `bd ready` |
| 9. Branching | unchanged — Android `parity/<task>-<slug>` → `main`; tether `android-parity/<task>` → PR. beads never touches git branches. |
| 10. Parity matrix | rows are beads too: `bd create "…" --parent P0 -l matrix` once T0.5 builds it, so "are we done" is `bd list -l matrix --json` |

**Concurrency is safe by construction.** `bd update --if-assignee X` / `--if-status X` are
compare-and-swap guards: a lost race writes nothing and exits **13** (treat exactly like the
markdown-era "reread and decide"). Two agents cannot both hold a task.

## Worktrees

Verified: **worktrees share the same store** via git common-dir discovery — a lane in
`~/git/tether-android-wt/<lane>` reads *and writes* the main checkout's beads DB with no redirect
config (`bd worktree info` shows the main repo). Lanes therefore claim centrally; no per-worktree
copies of the truth.

## Operating rules

- **beads ≠ git.** `bd dolt push` publishes `refs/dolt/data` (issue history) to `origin`; it does
  not touch `main` and creates no git commit. Do it at the same points §3 says to push the tracker.
- **Never** `bd import` another machine's whole store, and never hand-edit `.beads/`.
- `bd` is installed at `~/bin/bd`, **pinned v1.3.0**. The store carries a Dolt schema version with a
  forward-skew guard: **all writers upgrade together** or older binaries refuse the store. Do not
  bump mid-program; 1.3.1 was still a release candidate when this was written.
- Production Tether is never a beads target: no `bd` command may start, restart or point tests at
  `tether.service`. Server-side `S*` tasks are rows here (label `repo:tether`) whose *code* lives on
  `android-parity/<task>` branches in `~/git/tether` and ships as a PR the owner merges.
- No credentials, tokens or secrets ever go into a bead (notes/comments are versioned and pushed).
- Installed 2026-09-26; the store was seeded while the program was still `NOT STARTED`.
