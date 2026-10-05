# tether-android — agent instructions

The native Android client for Tether. The **native parity program** — rebuilding the app against the
web client, screen for screen — lives in `docs/parity/`.

> `AGENTS.md` in this repo carries the same content (engines that read AGENTS.md rather than CLAUDE.md).

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
## Orchestration policy (binding — every session in this repo)

This repo runs the fleet's **app** orchestration policy: **the session is a lead that orchestrates**,
work goes to the repo roster in `.claude/agents/` (pinned models), and no piece is judged by the
agent that made it. A run's lead never builds; an interactive session may make one small,
single-file, non-design, non-core edit itself (policy rule 3).

- Spawn roster roles **by name, no model/effort override**; prove the models before wave 1 and at
  every wave end (`game-orchestration models --fleet app <session-id>`).
- All design → `design`. Every raster asset (icons, buttons, illustrations, textures, reference
  images) → the **Codex image lane** the design lane drives: `codex exec -m gpt-6-astra -c
  model_reasoning_effort=high` + the built-in `imagegen` skill — one lane at a time, recipe
  committed with the asset. **No 3D in this fleet** (no Meshy/Blender/Unity). Complex code →
  `builder`. Light code/recon → `scout`. **Models and efforts are pinned in `.claude/agents/`
  frontmatter — never restate them here.**
- **Budget:** one pass + one check + a written receipt per lane, then stop — lanes never self-iterate
  (the judges judge), and their answer back is that receipt, never raw output.
- Plans are challenged by `wave-check` **before** briefs go out; design verdicts come from the
  critics against one reference, with `design-director` ruling where they disagree. Never
  self-judge. UI claims are verified with a real-browser capture at the run doc's viewports.
- The lead keeps the build gates, the shared dev instance (single-instance: wait, never kill), the
  pushes and the deploys — and verifies a deploy against the served artifact.
- Jev (TypeSafe) is **advisory**: triage, evidence filtering and the tool-call gate — never the
  design critic, never a verdict, never who does the work.
- Full policy: `docs/agents/orchestration.md`; how a run works: `docs/agents/gauntlet-core.md`.
<!-- app-orchestration:end -->

