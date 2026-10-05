# Run core — how a run works in an app repo

Canonical, fleet-wide (installed by `~/bin/game-orchestration --fleet app`; don't hand-edit here).
Each repo's **run doc** adds only what is specific to that run: scope and milestones, the named bar
files, the rig table (gate, capture, deploy commands and paths), checkpoints and the paste-ready
prompts. The roster and routing are in `docs/agents/orchestration.md`. Where a run doc and this file
disagree, the run doc wins for that run and the lead reports the contradiction.

## The loop

A piece is built, rendered or measured, and **judged blind against its bar**. If it loses, the judge
names the **single biggest gap** and the maker goes back in with a *changed* candidate. Nothing is
accepted on a maker's description of its own work. **Loop until the owner stops you**: a wave
boundary or a named owner checkpoint is the only place the run stops, and at a checkpoint the lead
stops, publishes and asks — it never decides on the owner's behalf.

## The bar — three kinds, never blended

1. **The spec** — the plan docs plus `DECISIONS.md`. **The code wins over prose** when they
   disagree: report the contradiction, don't silently "fix" either. `OWNER` decisions are never
   re-litigated without new evidence.
2. **Numbers** — the gate lines (`failed=0`, and `total` never shrinks): unit/integration tests,
   typecheck, lint, build, bundle size, API response shapes, migration dry-runs, query counts,
   performance and accessibility budgets. A number closes on the instrument's output, never on
   "looks fine".
3. **Pixels** — each screen or piece is judged against **one** reference: the bar screen, mock or
   concept the run doc's index names for it, at the viewport the run doc names. Never blend two
   references in one judgement. The artefact is a **capture from the real app**, not a mock of it.

**Reference material is third-party.** Read it to judge; never copy it into the project and never
ship it — that includes text, copy and numbers, not just images. Copying also corrupts the
judgement (a screen judged against the thing it was copied from always "wins"). Nothing user-facing
carries a reference product's name.

## What a wave is

A wave **starts** with the lead's plan in the run-artefacts dir (`w<N>-plan.md`: *Verified at
dispatch · Wave · Open questions for wave-check · LIVE STATUS*) and a `wave-check` pass over it
**before any brief goes out**.

A wave **ends** when all of this is true:

1. every live piece has its evidence — a gate line, a harness/benchmark JSON, or captures + critics,
   with `design-director`'s ruling on any disagreement;
2. every bead is closed with its evidence and verified by a different actor; the human tracker
   (GitHub issues) and the journal are updated, and the lead has rewritten STATUS;
3. anything user-visible is **deployed and the served artifact verified**;
4. the models line is recorded (`game-orchestration models --fleet app <session-id>`, routing rule
   6) — and once the run is over budget, or the owner asks, the wave report carries the per-role
   spend (`game-orchestration cost <session-id>` — per role, never per agent count);
5. a **short wave report** is posted: what landed (shas), what was refuted, what is next.

**Restart safety:** keep the run's restart record current as things land — by default `w<N>-plan.md`
› LIVE STATUS plus the tracker; a run doc may name another source of truth. A restarted lead reads
that record, never the chat.

## Measurements

- **A number entering a brief must be re-derived by someone other than the brief's author.**
- **Tell every agent: "the code wins; report contradictions."** A brief's diagnosis is a hypothesis.
- Critics: two blind replicas per screen against its bar reference, plus one naive reader per
  capture. A replica split means the piece is at the noise floor — don't brief it. Record the
  workflow script hash, the capture and the reference with every verdict.
- **Look at the pixels.** A green gate says nothing about a mis-aligned label, a 12 px tap target or
  a contrast failure. Check the capture at the real viewport, and check the phone width.

## Standing rules (carry these into every brief)

- The lead fans out and never implements (routing rule 3). The shared dev instance is the lead's:
  **"do not start or stop the dev server / deployed instance"** is in every brief.
- **Git** — commit by explicit path only; **never `git add -A`**, `git add .` or `commit -a`; never
  discard uncommitted work in a shared checkout (`checkout -- <path>`, `restore`, `stash drop`);
  work in a worktree (`~/git/<repo>-wt/<lane>`) when the work is parallel with another lane;
  `git show --stat <sha>` before pushing: deletions you didn't author mean you reverted someone;
  push the **gated sha explicitly** (`git push origin <sha>:refs/heads/main`).
- **`design` briefs** name the exact reference (bar screen/mock/concept) and viewport(s), the files
  it may touch, "read CLAUDE.md first", whether it holds the **image lane** (one lane at a time) and
  the exact command, "never start/stop the shared dev instance", and a deliverable path in the
  run-artefacts dir or a named repo path — **never an `artifacts/` folder in the repo**.
- **The tracker is beads, and every brief names its bead:** the lead claims the bead before writing
  the brief and hands over the ID; the leaf checkpoints it as it goes and closes it with evidence; a
  different lane verifies. A lane that dies leaves a checkpointed bead, not a mystery.
- **Run artefacts live outside git** — plans, verdicts, captures, pairs — in `~/gauntlet/<project>/`
  unless the run doc names another out-of-git path. Generated images are the same: the committed
  artefact is the asset plus its recipe, never a scratch render tree.
- **Context hygiene:** never read a file over 15k chars whole, and keep docs under 15k — a run doc
  fits because this file carries the shared part. A beads graph has no size ceiling (`bd list
  --json`), so where beads is the writer the markdown tracker becomes a generated digest and the
  40k rule applies to that digest alone.
- **Budget — policy, not advice, and it goes into every brief:** one pass, one check, a written
  receipt, then stop. A lane never iterates against its own taste (the judges judge) and never
  re-reads the corpus or its own transcript on later turns; a doubt is a candidate in the receipt
  and the lead decides. A brief names the exact section a lane must read — never "read X in full".
  What comes back to the lead is that receipt, never raw output or long prose. Reuse a lane (resume)
  before re-spawning it. **Cache reads cost the same on Opus and Sonnet**, so a model downgrade
  changes the output line and never the cache-read line: trajectory length is the lever.

## Running in ultracode

Ultracode (dashboard Effort row "Ultra code") integrates lanes about 1.8× faster per hour at about
the same tokens per lane, but burns about twice as fast. The orchestration rules still apply: named
roles, `effort` on every `agent()`, lanes created by the lead, and integration and deploys never
inside a workflow. On top of them:

1. **At most 3–4 lanes in flight.**
2. **No stranded work.** Every lane commits its work in progress to its own branch at each lead
   check-in (`wip(<lane>): …`) and checkpoints its bead. The lead squashes the lane before
   integrating: a session that dies leaves commits, not dirty trees.
3. **Scale the checking to the change.** A trivial change (about 20 lines or fewer, no behaviour
   change) gets no refuters — the gate and the lead's own read are enough. A real lane gets 2
   read-only refuters. One wave-check panel per wave, plus at most one delta.
