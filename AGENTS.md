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

## Public release notes (binding)

- Release notes are concise user-outcome bullets: aim for 3-5, fewer for a small fix release. Target about 120 characters per bullet, 5 bullets at most.
- Write plain language about what changed for the user. Group under "What's new" / "Bug fixes" headings only when that helps.
- Never include git subjects, commit hashes, bead IDs, lane names, test counts, or internal implementation jargon.
- Curated notes are required before publication. There is no automatic commit-log fallback: no curated notes, no release.
- The technical audit trail belongs in the PR and the receipts, not in the public notes.
- Follow the Omnio editorial style (plain text, user outcomes, no unrelated feature content).

<!-- app-orchestration:begin (managed by ~/bin/game-orchestration --fleet app — do not edit by hand) -->
## Orchestration

A run has one lead. Claim the run with `~/bin/lead-lock` before dispatch. The lead plans and integrates. A maker delivers one bounded piece and a different actor verifies it. Outside a run the lead may make one small single-file edit under routing rule 3.

Leads dispatch pinned repo roles by name. Started in Codex? Use the owner's model and dispatch Codex agents only, preserving role duties and independence. Give each lane its task ID, Goal, Done and Return when. Commit by explicit path. Twice-failed problems and night decisions go to `advisor` (routing rule 11).

Check context at start and before dispatch (W = window). Lead limits: lead soft min(200k, 0.60 x W), hard min(250k, 0.75 x W). Child limits: child lane soft min(100k, 0.45 x W), hard min(150k, 0.60 x W). At soft, plan a checkpoint with closure reserve. At hard, only safe closure and handover. Use `docs/agents/run-operations.md` › Context and handover.
Read the applicable task in `docs/agents/apps.md`.
<!-- app-orchestration:end -->
