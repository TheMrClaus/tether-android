# Orchestration policy — binding for every session in this repo

This is the default working mode for **any** session in **any** app repo of the fleet — not a
global Claude/Codex setting: each repo carries its own tracked copy, so a session started anywhere
in the repo (lead, leaf, interactive, spawned child) reads it. Canonical text and the installer:
`~/bin/game-orchestration --fleet app` (`install --fleet app --all` to re-apply, `verify --fleet
app --all` for drift). What a run is — the bar, waves and the standing brief rules — is in
`docs/agents/gauntlet-core.md`.

**One line:** the session you start here is a **lead that orchestrates**; work goes to a named
repo-local agent with a pinned model+effort, and **no piece is judged by the agent that made it**.
A run's lead never builds; an interactive session may make one small edit itself (rule 3).

## A Codex lead — owner starts the session in Codex

**This policy binds a Codex lead exactly as any other lead.** Two exceptions, and no others:

- **The lead model is the owner's choice.** It runs the Codex model the owner selected, never claims
  to be Opus 5.5, and rule 6's model proof reads the Codex model ids it actually runs.
- **It deploys Codex agents only.** Every role keeps its duties, read-only limits and independence
  rules, but every agent it spawns is a Codex agent on a Codex model; it never applies the Claude
  pins (the roster in `orchestration-roles.md`): read each role's duty, not its model.

Outside the night protocol it stays ask-first like every app lead; inside it, a fresh Codex agent
fills the advisor duty (roles rule 11).

## Night protocol — weekdays 00:00–08:00 (owner rulings 2026-10-26, 2026-11-21)

**From midnight to 08:00, Monday to Friday (host local time), `advisor` decides instead of the
owner.** The owner is asleep; the run does not idle on an answer. This section overrides every
"ask the owner" rule below for that window, except the hard stops.

- **`advisor` decides, provisionally — every time (owner 2026-11-21).** The lead takes each open
  question to `advisor` (roles rule 11) with the options, the recommendation `DECISIONS.md` or the
  run doc carries, and the evidence; `advisor` rules and the lead carries it out. A 👤 bead is
  released the same way; a piece at its third REJECT is parked rather than argued a fourth time.
  The lead never answers a question itself: no `advisor` ruling, no decision — the question
  becomes a morning-report card and other work continues. Then keep working.
- **Log every call as it happens** — `~/gauntlet/<run>/night/<YYYY-MM-DD>.md`: what was asked, what
  `advisor` decided and why, the recommendation it followed or set aside, and the one line that
  would overturn it. No log, no decision.
- **Hard stops, at any hour:** spending money or vendor credits beyond an approved budget;
  destructive data/git operations (delete, force-push, history rewrite); licensing, legal or upstream
  filings; retiring or starting a project; changing this policy; overruling a closed `DEC-0xx` /
  `OWNER` entry without new evidence; **and any stop-and-ask gate a repo's own run doc defines** (a
  model-proof mismatch, missing evidence) — those are instrument failures, not questions the night
  may answer. Those become cards at the top of the morning report and the work behind them waits.
  `advisor` cannot lift one.
- **Owner present = normal mode.** If he speaks in the session during the window, his instruction
  wins and that session stays normal for the night.
- **08:00 — the morning report, then normal.** At 08:00 write `~/gauntlet/<run>/morning/<date>.md`
  and send it: what shipped (shas), every night decision with its overturn line, what is blocked on
  him (ranked, with options), what is next, spend. A wave that ended overnight reports through it.
  **08:00 never stops work in flight:** the lane, gate or piece running then finishes to its natural
  boundary (calls strictly needed for it go in a `## Tail` section of that report), and only then
  does normal ask-first mode resume.

## Roster

Before the first dispatch read `orchestration-roles.md` > Roster and Routing rules.
Named roles retain their pinned models/effort and permissions; maker and judge differ.

## Routing rules

The numbered routing rules (including rules 2, 3 and 6) are in
`orchestration-roles.md`. Read the applicable rules before assigning a lane.

## Cost discipline

Revision 2026-11-07: keep independent judgement; reduce repeated context and invalid work. These
rules apply to Claude and Codex leads and children. Model pins and effort stay as in the roster.
Processed tokens are not money: report cached input, fresh input/cache creation and output
separately. Do not infer plan charges or equal cache prices across models from token counts.

1. **Bound the assignment.** One piece, one implementation pass, the specified checks, a receipt,
   then hand back. Correct a concrete failed check within the brief; do not self-iterate on taste,
   broaden scope or weaken assertions. A judge-requested fix round is a fresh lane with the prior
   receipt and triaged finding IDs. Resume only unfinished work within the context limits below.
2. **Read and return less.** Briefs name exact sections and acceptance criteria. Read receipts by
   verdict and `## Open items`; always read caveats. Final hand-backs are at most ~1.5k characters:
   verdict, receipt path, essential blockers. Logs, patches and measurements stay in files.
3. **Batch clerical work.** Batch related scout recon/refutation; an art/design maker still owns
   one piece. The lead may run existing deterministic checks, aggregate structured results and
   maintain state directly. New diagnostic scripts, interpretation and independent verification
   go to a scoped scout. Do not spawn an agent just to relay a path or update a routine status.
4. **Context limits are window-aware.** Read `docs/agents/run-operations.md` > Context and handover
   at session start and before dispatch. Lead pilot: soft = min(200k, 60% of W), hard = min(250k, 75%); child budgets unchanged.
   Soft means plan a clean checkpoint, not fit the next entire cycle below soft. Estimate the
   remaining LEAD work and hard-limit reserve; do not hand over early solely on that forecast.
5. **Wait on events.** Start a job once, retain its handle/log, use a blocking wait or completion
   notification. Never alternate model turns between sleep and status/tail calls, including
   `clock.sleep` (the fleet gate denies a sleep-then-poll inside a fleet checkout). Use bounded tool waits when the host requires them; communicate meaningful
   progress without adding redundant status probes. Durable job and resource rules are in
   `run-operations.md` > Jobs and environments.
6. **Verify independently; reuse execution evidence.** A verifier challenges the acceptance
   contract with focused tests/negative controls and examines the actual artifact. It does not
   repeat a full suite on unchanged inputs merely to be independent. Reuse requires matching
   source, dependencies, environment and command; the final integrated candidate still needs
   all required gates. See `run-operations.md` > Validation and acceptance.
7. **Account at every boundary.** Every wave report and handover includes usage by role, lead vs
   children, attempts/retries, and scope/completeness. Report cost per accepted piece by role —
   every attempt and fix round counted — because a cheaper pass that needs a second round is not
   cheaper. Include all providers, native children,
   delegates and detached spawns; attribute a response only once. The current cost helper covers
   Claude transcripts, not every Codex path: use native usage evidence or mark coverage PARTIAL,
   never treat missing usage as zero. Missing telemetry is a tooling gap, not an invented bill or
   permission to ignore a known spending limit. Run ledger and pilot measures: `run-operations.md`.
8. **Hand over safely.** Start draining at the soft limit, before context forces a restart. Never
   abandon a child to a read-only predecessor. Keep the mandate verbatim, current state, open
   items, exact next action and chain in the handover. Transfer only jobs with proven durable
   ownership; otherwise wait for a safe checkpoint. No half-integrated changes or gated changes
   left unpushed when a push is authorized. A lane without a handover tool writes a receipt.
9. **Refuters remain scouts.** Read-only refutation runs on `scout`; `builder` is for code that
   lands. No blanket model downgrades or weaker judges. Effort and model move only by
   `orchestration-roles.md` rule 9 or an owner ruling. At each clean point check context, state
   size and current-wave findings. A lead ending with jobs in flight names the jobs, dependency
   and next expected event.

Read run-operations by section. Use `game-orchestration checkpoint` at boundaries (see
`docs/agents/checkpoint.md`); one current state plus a handover delta avoids duplicate setup.

## One live lead per run

Two leads writing one run's plan, tracker and lanes corrupt it, so a run has exactly one live lead,
enforced by a lock in the run-artefacts dir (`~/bin/lead-lock`, host-wide):

- At run start the lead runs `~/bin/lead-lock claim <run-artefacts-dir>`.
- A successor (a handover, a restart) runs `~/bin/lead-lock takeover <dir> --from <predecessor session id>`
  — the id the handover prompt names. With no lock present, takeover simply claims.
- **Exit 2 means another live lead holds the lock: stop and ask the owner.** Never delete the lock by
  hand.
- `~/bin/lead-lock release <dir>` at run end.

## Verification discipline (every claim)

- Instrument preflight, full-suite evidence reuse, negative controls and separate implementation,
  verification/release acceptance are binding: `run-operations.md` > Validation and acceptance.
  Independent judgement does not require duplicate full-suite execution on identical inputs.
- A claim is verified against the artifact, not against the agent's summary. Independent context,
  independent instrument. For anything user-visible that means **a capture from the real app**, not
  a description of the diff.
- **A number entering a brief must be re-derived by someone other than the brief's author.**
- The verifier never edits. Findings are BLOCKING (bar line + evidence + smallest change) or NOTE,
  and the lead triages every one before anything is dispatched (gauntlet-core › Findings: notes are
  not dispatched, a re-review reopens only prior `fix` findings and regressions, the held rate is
  measured per judge). The lead never sends the same unchanged candidate back for another verdict.
- Gates are read from their real output line (e.g. `failed` **and** `total`), not from `$?` in a
  pipe. A shrinking total is a red flag.
- **Round limits:** a *review* of one deliverable is one round, plus a re-review only when blocking
  fixes materially change it — **maximum three**. This caps re-reviews, not the build → judge loop:
  a piece that loses to its reference goes back in as a *changed* candidate and is judged fresh,
  for as many passes as the run doc allows — until its third REJECT (next rule).
- **Three rejections → the owner rules (owner 2026-10-15).** When the same piece (one deliverable,
  any maker, any round) collects its THIRD counted REJECT (one with a BLOCKING finding that survived
  triage) — from `design-director`, a refuter, the critics or
  `wave-check` — the lead stops that piece: **no fourth round, and the lead never rules on it itself.**
  It goes to the owner as a question card: the piece, the three verdicts (one line each: the gap and
  the verdict path), what each round tried, and 2–3 concrete options — including changing the design
  or dropping it. Other work continues; the piece waits for the owner's ruling, which is recorded in
  the run's rulings file. Unattended (overnight) runs are no exception: the card goes at the top of
  the morning report, and under the night protocol the lead parks the piece — never a fourth round,
  never a new direction, and `advisor` does not rule on it — until the owner rules. Measured: the Crownjump armor label ran 4 rounds against a pixel bar until the
  owner replaced the design in one ruling; the Ice Queen took 6 rounds.

## Tracking (beads — the machine layer)

Every repo in this fleet carries its own **beads** store (`.beads/`, issue prefix per repo) — the
live state of claims, dependencies and evidence. GitHub issues and the repo's markdown docs stay the
human-facing tracker; beads is where the machine answers "what is claimable right now". `bd` lives
at `~/bin/bd` (symlinked into `/usr/local/bin`) and is **pinned**: never upgrade it mid-run — the
store carries a Dolt schema version and an older binary refuses a newer store.

- **Dispatch from the frontier, never from memory.** `bd ready` is the claimable set (unblocked,
  unclaimed, not deferred); `bd blocked` is what is waiting on someone else. Never hand a leaf work
  that `bd ready` does not offer.
- **Claim before you touch a file:** `bd update <id> --claim` — atomic, so two agents can never hold
  one bead. A lost race exits 13: re-read, do not retry blindly. An abandoned claim (over 2 h) is
  recovered with `bd stale` then `bd update <id> --assignee <you> --force` — never quietly stolen.
- **Every agent logs its own work** (this is the write protocol): checkpoints and evidence go in the
  bead — `bd update <id> --append-notes "done… half… files… next…"`, narrative in
  `bd comment <id> "…"`. A claim with no note is not progress, and `bd history <id>` is the trail.
- **Done is two steps, exactly like a review round:** the worker sets `-s done` with its evidence
  line (`--append-notes "EVIDENCE: gate line, sha, captures, paths"`); a **different** actor
  promotes it to `verified`. Nobody verifies their own bead.
- **A 👤 gate is a bead:** `bd update <id> -s blocked --append-notes "needs owner: …"` and label it
  `human`, so it lands in the owner's inbox (`bd human list`, `bd human respond <id> -r "…"`).
- **Never write a secret into a bead.** Beads is a pushed database: `bd dolt push` syncs issue
  history to the repo's own origin under `refs/dolt/data`.
- **One writer per board.** If a repo still has a markdown tracker as its writer, beads stays a
  read-only snapshot until that tracker hands over — never keep the same rows current in both places.

Setup, seeding and the full command map: `~/wiki/wiki/concepts/beads-tracking.md`.

## Environment rules

Before resource use read `orchestration-environment.md` and `run-operations.md` > Jobs
and environments. Existing host-wide locks, engine ownership and grants still bind.

## Changing this policy

- **A pin that moves to a new model is re-baselined, not carried over.** Effort names do not
  mean the same amount of thinking across models: start at the new model's documented default,
  trial the neighbouring levels, and audit that role's prompt for instructions written for the
  old model before `install --all`. `xhigh` and above stay only where a gain was measured.
- **2026-11-15 (owner, fleet-wide now):** lead and `artist`/`design` xhigh → high; blind critics
  high → medium; step-up ladder (roles rule 9); Fable 5.2 lead criteria (roles rule 10); briefs
  open with Goal / Done / Return when. Measured at the next wave end against the week of
  2026-11-09. A session started before this install keeps the pins it loaded until its next
  handover or restart: that is not a rule 6 mismatch — record `pre-2026-11-15 pin` and carry on.
- **2026-11-21 (owner, fleet-wide now):** `advisor` added — Fable 5.2 at high, roles rule 11:
  optional advice, mandatory before a third attempt at a twice-failed problem, and the decider
  inside the night protocol, which the app fleet now has too. Measured at the next wave end
  (advisor calls and Fable usage, night rulings overturned at 08:00). A session started before
  this install keeps the rules and roster it loaded until its next handover or restart: record
  `pre-2026-11-21 roster` and carry on.

---

Repo-specific rig (gates, deploy, captures, run docs): see this repo's own docs (`CLAUDE.md`,
`docs/`). Where a repo has no run doc, the rules above plus `docs/agents/gauntlet-core.md` are the
whole policy.
