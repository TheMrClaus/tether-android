# Run operations — shared app and game policy

Revision 2026-11-07, following the first policy pilot review. Read the section needed for the current work.
This document is installed by `game-orchestration` and shared by both fleets. Existing owner
mandates, authorization limits, model pins, resource locks and independent judgement still apply.
These requirements do not authorize stopping another project's job, deploying, or spending money.

## Context and handover

At start, clean points and before dispatch, read the runtime's actual context usage and window W.
Use input-context occupancy, not cumulative tokens billed across turns.

| Session | Soft limit: drain and checkpoint | Hard limit: safe completion only |
|---|---|---|
| Lead | min(200,000, 0.60 x W) | min(250,000, 0.75 x W) |
| Child lane | min(100,000, 0.45 x W) | min(150,000, 0.60 x W) |

Pilot these lead limits for the next three comparable waves per run. Children at soft still
finish/checkpoint the current piece and accept no new piece; their budgets are unchanged.
A lead's soft limit is a planning threshold, not a completion ceiling or automatic handover.
Do not require the next whole build/review/release cycle to fit below soft, and do not hand over
below soft solely because that cycle might cross it. Estimate remaining LEAD context work from
recent comparable pieces (briefing, reviews, integration and checkpoint), not child tokens or
elapsed job time. Keep an explicit reserve for safe closure below hard. A bounded next step may
continue at/above soft when that estimate and reserve fit below hard and preserve a clean exit;
record the estimate once in current state. Otherwise drain at the next safe boundary. Missing
estimates are not permission to assume zero work. At hard, only safe checkpoint/cleanup/handover.
Record necessary overshoot rather than killing or orphaning work. Other handover reasons remain
valid (owner request, uncertain telemetry, lost continuity). Compaction is recovery, not budgeting;
do not lower reasoning effort to conceal overruns.

If usage/window telemetry is unavailable, record UNKNOWN, use the last trustworthy measurement
and any known provider window, and checkpoint at each piece boundary; do not claim a percentage
or wait for a guessed 300k. Request a fresh session at the next safe boundary when occupancy
cannot be established. Unsupported telemetry is a tooling gap to resolve.

Before ONE `tether_handover`, check: no session-bound child in flight, no half-integrated work,
no authorized gated push outstanding, and state/locks/receipts current. A durable job may remain
only when its external supervisor, stable ID, logs and recovery/ownership procedure are proven;
a shell background process alone is not durable. Record the successor's adoption procedure.
Never hand over on a promise to restart a possibly orphaned child. The successor verifies job
identity/status before adopting or retrying it; an uncertain outcome is reconciled before retry.

Keep ONE authoritative current state. The handover prompt carries the operator mandate/standing
instructions verbatim, the state path + exact sections, and a short delta: next action, changed
rulings, active-job ownership/adoption, open blockers and session chain. Aim for <=3k characters
for the delta excluding verbatim mandates; this is a target, never grounds to omit obligations.
Open beads, grants and gated/pushed/deployed revisions live in current state: point to them rather
than copying the entire state, policy and historical receipts into another handover document.
Archive superseded LIVE STATUS blocks; retain one current block and all unresolved obligations.
The successor verifies ownership/locks, current revision and the next action, then reads only the
policy/receipt sections needed for that action (including Open items). Reuse valid prior checks;
repeat them only for changed inputs, a new runtime configuration or an unresolved concern. Do not
reconstruct settled history or rerun accounting-format probes. Use the checkpoint command below.
End the turn after scheduling and honor the platform's first-turn handover restriction. Without
a handover tool, write the short receipt and give the operator its path; do not invent a successor.

## Jobs and environments

For every expensive build/test/render: record a job ID, run/wave/piece/attempt, owning session,
source revision/worktree, command, environment/dependency fingerprint, resource grant, supervisor
handle, timestamps, log/result paths, heartbeat or timeout, terminal result and recovery action.
A small JSON record under the run directory is sufficient until a shared runner implements this.
Jobs use QUEUED/RUNNING/PASS/FAIL/INCONCLUSIVE/CANCELLED; a lost or interrupted job is not a pass.
Launch once and wait for completion. Check status on an event, timeout or recovery need, not in a
sleep/status conversation loop (the fleet gate denies one inside a fleet checkout). Respect tool wait limits and keep the operator informed.

Use existing host-wide engine/build/browser/GPU locks. Set concurrency from measured CPU/RAM and
conflict constraints; two full suites are allowed only with explicit capacity and isolation,
never by bypassing a single-engine lock. A performance job declares its admissible environment
before dispatch: host, graphics/headless mode, background-load bounds, clock, sample count and
comparison method. Baseline and candidate use equivalent conditions; use paired/interleaved
runs where appropriate. Absolute load thresholds are host-specific, not universal constants.

A missed admission timeout stays QUEUED or becomes INCONCLUSIVE. Never run under disallowed
conditions and call that evidence valid. If contamination occurs mid-run, retain raw evidence,
mark affected samples inconclusive, and rerun only the affected rows under the declared method.
Do not repeat a known-invalid matrix. An environmental diagnosis remains a hypothesis until a
controlled run distinguishes it from a product defect. Do not pause another project without its
operator's authorization or a previously authorized resource reservation.

## Validation and acceptance

Before a large matrix, run a small instrument preflight: required generated assets/source fonts
and pinned tools exist; build/source IDs match; scene and inputs reach the intended state;
capture clock matches simulation/animation; a repeated baseline is interpretable; and a
representative negative control is detected. A slow import is not proof of a dead engine.
Retain regeneration sources/recipes during cleanup; delete only with existing authorization.

Each requirement names its observable outcome, instrument and threshold before implementation.
For a behaviour change, the maker supplies a regression test or executable negative control that
fails with the bug/guard deliberately restored and passes on the candidate. The independent
verifier examines that link and exercises the actual boundary (real input, client/server,
restart/teardown or rendered output where relevant). Text searches and pure helper tests do not
prove wiring. Pure docs/copy changes need proportionate checks, not artificial mutation tests.
Never lower thresholds or remove assertions merely to turn a gate green. A changed requirement
needs the owner's explicit ruling, preserved with the new test and prior result.

A validation receipt records command, complete input fingerprint (relevant source tree, test
code, dependencies/generated assets, configuration, environment/toolchain), time, counts
including skips, exit status and retained output. Different SHA with identical relevant inputs
requires an explicit equivalence check. Unknown equivalence means rerun; stale XML is not proof.
A reviewer reuses a valid full-suite receipt and runs focused checks/negative controls for the
contract. A test-only fix reruns that fixture plus affected checks; broader runs need a changed
input, failure or unresolved concern. Final integration runs all required gates once for the
actual candidate, reusing a matching receipt if it already exists. Dynamic/performance evidence
also needs a still-valid environment; cached source equality alone is insufficient.

Track three independent acceptance dimensions per piece: implementation, verification, release.
Each records status and evidence: PASS/FAIL/INCONCLUSIVE/NOT-RUN, or N/A with reason where a
release/test is outside the brief. A verdict also names the affected dimension. Running code can
be correct while its test is insufficient; a visual pass does not close a gameplay failure.
Test-only findings get a narrow repair, not a new design cycle. Existing rejection counting and
owner escalation still apply; changing the dimension or attempt ID never resets the counter.

Release status records the exact published artifact and remaining uncertainty. A required
acceptance criterion cannot close on FAIL/INCONCLUSIVE/NOT-RUN. An owner-authorized release with
an outstanding criterion is reported as SHIPPED WITH OPEN CRITERIA; the issue remains open until
verified or the owner explicitly changes its scope. For multiple leads targeting one release,
name one publisher and require revision-specific handoffs before stamping or publishing once.

## State and findings

Maintain a small STATE.md (or the run's explicitly named live state): LIVE STATUS, owner rulings,
active jobs, current revisions, open criteria, receipt pointers and next actions. Aim below 15k
characters. Every live plan, state, digest and handover must be <=40k characters, no line >2k.
History belongs in dated append-only records, not in the working state. Read long references by
section; mandatory policy entry points link to detail instead of copying it into every brief.

Run `game-orchestration checkpoint <run-dir>` with the run's configured live files before
dispatch/handover/wave completion; it includes state-size and findings checks in one report.
After an isolated state write, `gauntlet-tracker check <live-files...>` is sufficient. If it fails,
archive completed history and preserve LIVE STATUS, open items, rulings
and pointers; check again. `demote` fixes long lines, not every oversized file. Never delete
unresolved obligations to fit the limit. Failure blocks a new dispatch or COMPLETE claim, not
urgent recovery or an honest progress/blocker report. On adopting an old run, normalize live
state before the next dispatch; do not silently rewrite historical evidence.

Use a current-wave findings.tsv with the exact columns:
`id piece judge round class disposition reason` (tab-separated, integer round).
Class is BLOCKING or NOTE; disposition is fix/rejected/overruled/noted/note-dispatched. Keep
closure state, dimension and evidence in the linked receipt; never replace disposition with
free text like 'fixed (sha)'. Preserve finding identity across reviews; distinguish occurrences
by id + judge + round. Multiple rounds are observations, not additional unique defects.

After each update run `game-orchestration findings <current-wave-ledger> --json`. A nonzero exit
or missing reviewed pieces means metrics are INVALID/PARTIAL, not zero findings. Reconcile the
ledger against the wave's review-receipt index before reporting held rates. A clean review gets
a receipt/index entry, not a fabricated finding. Historical malformed ledgers are retained and
labelled; carry unresolved findings with provenance into the valid current wave. Never rewrite
history or drop adverse findings merely to obtain a green metric. Judge rates use complete,
validated current-wave data only; per-wave checks are not whole-history aggregates.

## Run ledger and cost checkpoint

Keep a provider-neutral run index, JSON/JSONL or equivalent structured records, linking
run -> wave -> deliverable -> attempt -> agent/job. Each record carries stable IDs, role,
provider/model/effort, parent/native IDs, source revision, timestamps, result, receipt and usage
source. Record retries, cancellations, inherited unfinished work and all handover successors.
Missing values are UNKNOWN. The lead can update this clerical index directly; no extra agent.

At every wave/handover, report cached input, uncached input, cache creation where exposed, output,
and elapsed/queue/execution time where measurable. Separate lead/roles and accepted/unfinished
work. Include native children, delegates, detached CLI runs and compactions without double
counting: Claude message IDs use final usage, Codex response IDs use final usage; do not sum
cumulative snapshots or add reasoning output twice. Image/vendor credits and actual monetary
charges are separate, with source and plan/rates/date if known. Raw tokens are never dollars.

`game-orchestration cost <native-session-id> --json` currently covers Claude transcript trees.
A Codex run or external job requires native records and explicit parent linkage; the helper's
failure/missing coverage is PARTIAL, not zero cost. If complete aggregation is unavailable,
report the known subtotal, missing paths and responsible tooling task; it does not itself block
product delivery. A known approved spending cap still binds. Never invent a budget or silently
turn subscription usage into API-price estimates.

## Pilot and tooling boundary

For the next three comparable waves per run, compare processed tokens per accepted deliverable
across the WHOLE successor chain, including startup/closing work and cache creation (not tokens
per session). Record useful work between handovers and transition time separately from job/owner
waits. Also compare brief-to-verification time, duplicate validation, environment-inconclusive attempts, escaped
defects/reopened criteria, routine owner interruptions and demonstrated review corrections.
Separate scope and incomplete work; compare like-for-like tasks, not unrelated releases.
Target 25% less processed usage with no worse acceptance or escaped-defect results. This is an
experiment target, not a promised saving. Keep the defaults after the pilot pending a measured
recommendation and owner ruling; do not silently revert or relax judgement/model pins.

Available: installer drift checks and `game-orchestration checkpoint` (stable JSON or concise
text for context, revisions, live-state sizes, declared jobs/receipts, findings and Claude usage).
Configure paths once per run; usage is partial outside the supported native tree. See
`docs/agents/checkpoint.md` for the command and schema. Leads invoke it at boundaries; no hooks.
Follow-up engineering: universal usage/run ledger aggregation, transactional state/ledger writes, cached
validation keyed by complete inputs, and a resource-aware durable job runner with ownership
transfer and completion events. Until those exist, use the manual records, existing locks and
bounded waits described here; never claim scheduling, accounting or context limits are
mechanically enforced merely because this document requires them.
