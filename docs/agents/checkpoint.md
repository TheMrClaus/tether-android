# One operational checkpoint

`game-orchestration checkpoint` is a read-only, deterministic report for existing run evidence.
Use it before dispatch, handover and wave completion. Keep one config per run so successors do
not rediscover paths or JSON formats. It neither starts jobs nor edits state or transfers locks.

## Configure once

Create `<run-dir>/checkpoint.json` with schema version 1 and the CURRENT files only:

```json
{
  "schema_version": 1,
  "repo": "/home/marcelloc/git/your-repo",
  "state": ["STATE.md", "wave-plan.md"],
  "findings": ["wave-findings.tsv"],
  "jobs": ["jobs"],
  "receipts": ["receipts/current-piece.md"]
}
```

Paths resolve relative to the run directory; absolute paths and `~` are accepted. Select existing
files that actually matter; do not copy placeholder paths. Update the config at wave/scope
changes. Do not recursively include archived ledgers or historical plans. `jobs` accepts JSON
files or directories (only immediate `*.json` files). `receipts` lists evidence files; hashes and
presence are checked, not the truth of their claims or input equivalence. Empty arrays are an
explicit declaration of no listed files, never proof no other jobs/reviews exist.

Without a config, only existing `STATE.md`, `findings.tsv` and `jobs/` are discovered. Missing
sections are UNKNOWN. Supply `--repo` if not running in the source repository. `--state`,
`--findings`, `--jobs` and `--receipt` may repeat; each supplied flag replaces its config list.
`--config` selects an alternate config relative to the run directory. CLI `--repo` is relative
to the command's working directory. A configured `repo` is relative to the run directory.

## Invoke once per boundary

Obtain the current context occupancy and actual window from the runtime (for example Tether's
context tool), then pass BOTH numbers from that fresh measurement. Never persist context or
session ID in checkpoint.json: successors must supply their own identity and fresh telemetry.

```sh
~/bin/game-orchestration checkpoint ~/gauntlet/example \
  --session "$CLAUDE_CODE_SESSION_ID" --provider claude \
  --context-tokens 134000 --context-window 1000000
```

The numbers above are examples, not defaults. At 134k/1M a lead is BELOW_SOFT: no handover is
required just because the next complete cycle might exceed soft. Lead soft/hard are
min(200k, 60% W) / min(250k, 75% W); `--role child` uses unchanged 100k/45% and 150k/60% limits.
Soft is a planning threshold: bounded continuation needs a remaining-lead-work estimate plus a
safe-closure reserve below hard. Hard permits only safe checkpoint/cleanup/handover.

Use `--json` for a saved receipt or automation. Redirect to a separate checkpoint result file,
never over checkpoint.json, a ledger or another input. Without `--session`, usage is UNKNOWN.
For Codex use `--provider codex`; native usage remains UNKNOWN here and must be linked separately.
The helper does not estimate context from usage totals, infer a window from a requested model,
contact the provider, price tokens, or claim universal accounting. The usage helper is the
existing native Claude session-tree reader (lead + native subagents); detached/delegated runs
and successor-chain reconciliation remain explicitly outside its proven coverage.

## Fixed report contract (schema_version = 1)

Every JSON result has `schema_version`, `observed_at`, `run_dir`, `status`, `context`, `revision`,
`state`, `jobs`, `receipts`, `findings`, `usage`, `errors`, `warnings`, and
`authorizes_handover_or_release: false`. There is no prose outside JSON. Each section has `status`;
unknown values are null/UNKNOWN, never invented zeros or PASS. Section details:

- `context`: tokens, window, soft, hard, band and action. Caller-supplied runtime observation;
  omitted telemetry is UNKNOWN. Above soft is advisory, not a failed check. Above hard says safe
  closure only; it does not kill a job or prevent saving the checkpoint.
- `revision`: observed Git repo, HEAD, branch and dirty flag; no fetch, checkout or index refresh.
  Dirty is informative, not an automatic failure or permission to stage someone else's work.
- `state.files`: paths, hashes, chars and longest line. Enforces <=40k chars and <=2k per line;
  warns above the 15k target or on multiple LIVE STATUS blocks. No automatic history deletion.
- `jobs.records`: declared ID, status, owner and supervisor. Supports one job object, an array,
  or `{"jobs": [...]}`. Fields: `id`/`job_id`/`job`, `status`/`state`/`result`,
  `owner`/`owning_session`/`owner_session`, `supervisor`/`supervisor_handle`.
  Status is QUEUED/RUNNING/PASS/FAIL/INCONCLUSIVE/CANCELLED or UNKNOWN. No process probing;
  even a record saying PASS or an empty list is not live evidence or handover authorization.
- `receipts.files`: declared paths, hashes and byte counts; `claims_verified: false`. The lead
  still reads the verdict/Open items and proves relevance/equivalence before evidence reuse.
- `findings.summary`: stable existing validator object (`files`, `judges`, `rounds`, `flagged`,
  `malformed`). VALID_SYNTAX does not assert review coverage or closure of every criterion.
- `usage.rows`: existing per-role array (`role`, `agents`, `msgs`, `inp`, `out`, `cr`, `cw`).
  `usage.totals` is always an object when known, null when unknown; same numeric fields plus
  `processed = inp + out + cr + cw`. Coverage stays PARTIAL for the native Claude subtotal.

Exit 0 means the inspected files have no detected blocking format/size/read errors; the report
still says PARTIAL because evidence coverage and live ownership require reconciliation. Exit 1
means a named file/check failed. Exit 2 means bad configuration/context arguments. No exit code
is an acceptance, deployment or handover verdict. A recorded job failure must be triaged even
though reading its valid record is not a format failure. Missing telemetry/accounting by itself
does not block product delivery; known spending limits and the policy's safety rules still bind.
