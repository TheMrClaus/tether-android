# tether-android — agent instructions

The native Android client for Tether. The **native parity program** — rebuilding the app against the
web client, screen for screen — lives in `docs/parity/`.

> `CLAUDE.md` in this repo carries the same content (engines that read CLAUDE.md rather than AGENTS.md).

## Read before you work

1. `docs/parity/PLAN.md` — the program: what parity means, the phases, and **§3 the Tracker Protocol**
   (binding; it defines claim / checkpoint / evidence / DONE).
2. `docs/parity/TRACKER.md` — the live board. Start at **▶ RESUME HERE**.
3. `docs/parity/BEADS.md` — the machine layer: how to claim, checkpoint and close work with `bd`.

## Tracking is beads (not the markdown file)

Row state, claims, dependencies and evidence live in this repo's **beads store** (`.beads/`, issue
prefix `ta`), seeded from `TRACKER.md` with the program's own task IDs (`T0.1`, `S1.1`, `P4`), so
briefs, commits and handovers keep resolving.

```
bd ready                             # what is claimable right now — dispatch from this, not from memory
bd update <id> --claim               # atomic claim, BEFORE you touch a file (two agents cannot hold one)
bd update <id> --append-notes "…"    # checkpoints and evidence as you go
bd update <id> -s done               # with your evidence line: gate output, sha, paths
bd update <id> -s verified           # a DIFFERENT actor promotes it — nobody verifies their own work
bd update <id> -s blocked --append-notes "needs owner: …"   # a 👤 gate
bd dolt push                         # when you push; syncs issue history to this repo's own origin
```

`TRACKER.md` is the **human digest**, refreshed from the store — never keep the same row current in
both places. An abandoned claim (over 2 h, no newer note) is recoverable with `bd stale`; a lost race
exits 13, so re-read instead of retrying.

## Standing rules

- **The app is exactly as capable and as trusted as the web console (owner, binding, repeated).** If something is
  allowed in the browser, it is allowed in the app: same actions, same permissions, same defaults, same frame
  fields (sandbox tier, permission mode, approvals). Never add an app-only restriction, refusal, stricter default
  or extra gate, and never record one as a "divergence". The only exceptions are rules the browser itself enforces
  too (e.g. passkeys over https). If unsure, the answer is: do what the web does. Asking the owner whether the app
  may do something the web does is itself a mistake.
- **Never touch the production Tether service.** This is the client's rebuild.
- `S*` tasks execute in `~/git/tether` and ship as pull requests; `T*` tasks execute in this repo.
- Commit by explicit path; never `git add -A`; no AI `Co-authored-by` trailers.
- Never write a credential, token or secret into a bead — the store is versioned and pushed.
- `bd` is pinned (v1.3.0, `~/bin/bd`) — never upgrade it mid-program: the store carries a Dolt schema
  version and an older binary refuses a newer store.

<!-- app-orchestration:begin (managed by ~/bin/game-orchestration --fleet app — do not edit by hand) -->
## Orchestration policy (binding)

Read `docs/agents/orchestration.md` and follow it: orchestrate, don't implement (an interactive,
non-run session may make one small single-file, non-design, non-core edit); dispatch the repo roster
in `.claude/agents/` by name with no model/effort override; all design work goes to `design`; every
raster asset goes to the Codex image lane (`astra`, High) the design lane drives; no 3D in this
fleet; no agent grades its own work. Commit by explicit path, never `git add -A`. Jev (TypeSafe) is
advisory only — never the design critic, never a verdict.
<!-- app-orchestration:end -->

