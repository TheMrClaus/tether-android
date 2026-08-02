# Tether AgentEvent Vocabulary + Pure Reducer — Kotlin Port Spec

Ground truth (read these when in doubt — the code is authoritative):
- `aidash/engines/events.mjs` — the pure reducer (lines 1–1618; 1620+ are provider adapters, NOT needed on Android — the server sends normalized events)
- `aidash/lib/protocol.ts` — TS types
- `aidash/tests/events.test.mjs` — golden behavioural tests
- `aidash/hooks/use-tether.ts` — the browser consumer

Design invariants to carry into Kotlin:
1. The reducer is **pure**: no I/O, no clocks, no RNG, no argument mutation. Every clock reading comes from `event.ts` (journal-stamped).
2. Never mutate input state or event; return new objects.
3. Return the same instance for no-op paths where cheap (data-class equality otherwise suffices).

## 1. Envelope

Every event is `{ type: string, turnId: string | null, ...payload }` plus journal-stamped `seq` (number, monotonic per session) and `ts` (Unix ms). Absence of `seq`/`ts` is legal and must degrade gracefully — never throw. `PROTOCOL_VERSION = 40`.

## 1.1 Constants

```
TURN_OUTCOMES = ok | cancelled | error | outcome_unknown
SESSION_STATUS = ready | active | waiting | exited   // reducer NEVER produces "exited"
RATE_LIMIT_RESUME_DELAY_MS = 120000
RATE_LIMIT_RESUME_MAX_HORIZON_MS = 18000000
```

Projection limits: approvalChoices 8, approvalChoiceIdChars 128, paths 64, planSteps 100, modelReroutes 20, reviews 50, compactions 50, providerNotices 50, mcpServers 128, idChars 200, labelChars 200, proseChars 2000, commandChars 16000, diffChars 256000, pathChars 4096. TODO: items 100, textChars 2000. MAX_WARNINGS = 50.

Closed vocabularies (validated by reducer):
```
PROVIDER_IDS               = claude, codex, gemini, opencode
APPROVAL_KINDS             = tool, command, file-change, network, permissions
PLAN_STEP_STATUSES         = pending, in_progress, completed
TODO_STATUSES              = pending, in_progress, completed (separate set)
REVIEW_COMPLETION_STATUSES = completed, cancelled, failed
MCP_HEALTH_STATUSES        = starting, ready, failed, cancelled, needs-auth, disabled, unknown
MCP_SERVER_STATUSES        = connected, failed, needs-auth, pending, disabled (CLI inventory)
PROVIDER_NOTICE_LEVELS     = info, warning, error
RATE_LIMIT_STATUSES        = allowed, allowed_warning, rejected
PERMISSION_DENIAL_REASON_VALUES = classifier, safety_check, rule, mode, working_dir, sandbox, hook, prompt_tool, async_agent, other, unknown
PERMISSION_DENIAL_REASON_CODE   = ^[A-Za-z][A-Za-z0-9_-]{0,39}$
```

## 1.2 Complete event list (exact literals)

### Session identity / CLI process facts
- `native_session_id` { turnId: string, nativeSessionId: string, cliCapabilities?: string[], cliVersion?: string, cliInventory?: CliInventory } — turnId ignored by reducer.
  - CliInventory = { commands: CliCommand[], tools: string[], mcpServers: [{name, status}] }
  - CliCommand = { name, description?, argumentHint?, aliases?: string[] }
- `cli_inventory_reset` { turnId: null }
- `cli_commands_changed` { turnId: null, commands: CliCommand[] } — REPLACE commands only.

### Turn lifecycle
- `turn_started` { turnId, idempotencyKey?: string|null, continuation?: boolean }
- `user_message_accepted` { turnId, text, attachments?: [{name, mediaType}] } — attachments read by reducer even though undeclared in protocol.ts.
- `cancel_requested` { turnId }
- `cancelled` { turnId }
- `turn_end` { turnId, outcome: TurnOutcome }
- `process_exit` { turnId, code: number|null, signal: string|null }
- `turn_activity` { turnId, activeMs, runCount } — journal-compaction only.

### Streaming assistant text
- `message_started` { turnId, blockId }
- `message_delta` { turnId, blockId, text }
- `message_completed` { turnId, blockId, text, aborted?: true } — aborted is only ever literal true.

### Thinking
- `thinking_delta` { turnId, blockId, text }
- `thinking_completed` { turnId, blockId, text }
- `thinking_stop` { turnId, blockId }

### Tools
- `tool_start` { turnId, toolId, name, input: unknown }
- `tool_output_delta` { turnId, toolId, chunk: string }
- `tool_progress` { turnId, toolId, parentToolUseId?, elapsedSeconds: number }
- `tool_end` { turnId, toolId, output: unknown, isError: boolean }

### Approvals
- `approval_request` { turnId, requestId, toolId, name, input, choices?: ApprovalChoice[], metadata?: ApprovalRequestMetadata }
  - ApprovalChoice = { choiceId, label, description?, permissionGrant?: "exact"|"subset" }
  - ApprovalRequestMetadata = { provider (required, in PROVIDER_IDS), kind (required, in APPROVAL_KINDS), reason?, command?, cwd?, paths?: string[], network?: {host, protocol?, port?}, requestedPermissions?: GrantedPermissions }
  - GrantedPermissions = { fileSystem?: {read?: string[], write?: string[]}, network?: {enabled: boolean} }
- `approval_resolved` { turnId, requestId, choiceId } XOR { turnId, requestId, decision: "allow"|"deny" } — reducer reads NEITHER; only deletes pendingApprovals[requestId].
- `approval_expired` { turnId, requestId }

### Questions
- `question_request` { turnId, requestId, toolId, questions: QuestionPrompt[] }
  - QuestionPrompt = { question, header, multiSelect: boolean, options: [{label, description, preview?}] }
- `question_resolved` { turnId, requestId }
- `question_cancelled` { turnId, requestId }

### Permission denials
- `permission_denied` { turnId: string|null, toolId, name, reason: PermissionDenialReason, reasonCode?, subagent?: true }

### Usage / metrics
- `usage` { turnId } & TurnUsage = { model?, rawModel?, perTurnTokens?, cumulativeTokens?, contextWindow?, estimatedCostUSD?, modelUsages?: ModelUsageEntry[] }
  - ModelUsageEntry = { model, canonicalModel?, provider?, inputTokens?, outputTokens?, cacheReadInputTokens?, cacheCreationInputTokens?, webSearchRequests?, costUSD?, contextWindow?, maxOutputTokens? }
- `token_progress` { turnId, tokens } — CUMULATIVE for the turn, never a delta.

### API retry / rate limits
- `api_retry` { turnId, attempt, maxRetries?, delayMs?, errorStatus?, error? } — error vocab (open string): authentication_failed | oauth_org_not_allowed | billing_error | rate_limit | overloaded | invalid_request | model_not_found | server_error | max_output_tokens | unknown
- `rate_limit` { turnId: string|null, status, limitType?, utilization? (0–1), resetsAt? (epoch ms) }
- `rate_limit_resume_scheduled` { turnId: null, resetsAt, resumeAt }
- `rate_limit_resume_dismissed` { turnId: null, resetsAt }
- `rate_limit_resume_fired` { turnId: null, resetsAt }

### Rich provider projections
- `plan_updated` { turnId, explanation?: string|null, steps: [{step, status}] }
- `todo_updated` { turnId: null, items: [{content, activeForm, status}] } — SESSION scoped
- `diff_updated` { turnId, unifiedDiff: string }
- `model_rerouted` { turnId, fromModel, toModel, reason }
- `review_started` { turnId, reviewId, target? }
- `review_completed` { turnId, reviewId, status ∈ REVIEW_COMPLETION_STATUSES, result? }
- `mcp_health_updated` { turnId: null, name, status: McpHealthStatus, error?, failureReason? }
- `context_compacted` { turnId, itemId }
- `provider_notice` { turnId: string|null, noticeId, level, code?, message }

### Background tasks — ALL FOUR ARE REDUCER NO-OPS
- `task_started`, `task_progress`, `task_completed`, `background_tasks_changed` (taskIds: replace semantics for live consumers)
- `background_pending` — journal marker, also a no-op.

### Subagents
- `subagent_message` { turnId, parentToolUseId, items: SubagentItem[], usage?: SubagentUsage }
  - SubagentItem = {kind:"message", key, text} | {kind:"thinking", key, text} | {kind:"tool", key, name, input} | {kind:"tool_result", key, output, isError}
  - SubagentUsage = { model?, inputTokens?, outputTokens?, cacheReadInputTokens?, cacheCreationInputTokens? } — ABSOLUTE running totals, SET never accumulate.

### Errors / diagnostics
- `warning` { turnId, message?, raw? }
- `unknown_event` { turnId, raw }
- `error` { turnId: string|null, message }

### Session breadcrumbs
- `background_interrupted` { turnId: null, outstanding }
- `background_abandoned` { turnId: null, outstanding, reason?: engine_died|idle|background_hardcap|warm_cap }
- `external_advancement` { turnId: null, count } (derived, not journaled)

### Queued composer messages
- `queued_message_added` { turnId: null, queueId, text }
- `queued_message_updated` { turnId: null, queueId, text }
- `queued_message_removed` { turnId: null, queueId }

## 2. State shape

See the committed Kotlin models in `app/src/main/java/com/tether/app/protocol/model/Projection.kt` — those are the authoritative Kotlin shapes. Semantics:

- `initialSessionState`: status "ready", all lists/maps empty, nullable fields null, `turnOrder` [], `activeTurnId` null.
- `newTurnProjection(turnId, idempotencyKey, continuation=false, startedAt=null)`: status "running", outcome null, liveTokens null (unknown ≠ 0), run null, runCount 0, activeMs 0, empty collections. `TurnRun = { index (0-based), startedAt, tokensStart }`.
- `TurnProjection.exit` is written by `process_exit` but undeclared in protocol.ts — include it (nullable).
- `TurnBlock.elapsedSeconds` is DELETED (absent) on tool_end — model as nullable, null = absent.
- `aborted` on message blocks: only ever literal true; false is normalized away.

### Block key (blockId) conventions
| Block kind | blockId format |
|---|---|
| user message | `user:${turnId}` (minted by reducer in user_message_accepted) |
| assistant text | `${messageId}:t${ordinal}` |
| thinking | `${messageId}:th${ordinal}` |
| aborted-no-text marker | `${messageId}:aborted` |
| tool call | raw provider tool_use id (e.g. `toolu_...`), NO colon |
| Codex live text | `codex:${itemId}`; replay `codex-msg-${n}` |

Streamed and final text blocks share the same blockId (the double-render fix): `upsertBlock` patches instead of appending. The Android client receives blockIds pre-minted by the server — it never mints them except `user:${turnId}`.

## 3. Reducer semantics

### 3.0 Top-level pipeline (three pure passes, in order)
```
reduce(state, event) = syncTurnRun(clearResolvedApiRetry(reduceEvent(state, event), event), event)
```

**Pass 2 — clearResolvedApiRetry.** Allow-list API_RETRY_RESOLVED_BY:
message_started, message_delta, message_completed, thinking_delta, thinking_completed, thinking_stop, tool_start, tool_progress, tool_output_delta, tool_end, permission_denied, subagent_message, plan_updated, diff_updated, model_rerouted, review_started, review_completed, context_compacted, usage, token_progress, cancelled, turn_end.
If event.type ∈ set AND turnsById[event.turnId]?.apiRetry != null → set that turn's apiRetry = null. Targets event.turnId directly (not activeTurnId). Deliberately an allow-list: approval_request, rate_limit, warning, api_retry leave the marker standing.

**Pass 3 — syncTurnRun** (v38 run bookkeeping):
```
turnId = event.turnId ?? state.activeTurnId; if null → state
turn = turnsById[turnId]; if absent → state
ts = nonNegativeFiniteNumber(event.ts); if null → state
working = turnIsWorking(turn)
if working == (turn.run != null) → state
if working: turn.run = { index: turn.runCount, startedAt: ts, tokensStart: turn.liveTokens ?? 0 }; turn.runCount += 1
else:       turn.activeMs += max(0, ts - turn.run.startedAt); turn.run = null

turnIsWorking(turn) = turn != null && (status == "running" || status == "cancelling")
                      && pendingApprovals.isEmpty() && pendingQuestions.isEmpty()
```

### 3.1 Guards
- `isOpenCurrentTurn(state, turnId) = activeTurnId == turnId && turnsById[turnId]?.status != "done"`
- Every turn-scoped case starts with this guard. Done turns never reopen. EXCEPTIONS: (1) `subagent_message` uses updateTurnById and may amend a done turn's existing tool card (drops if parent block missing); (2) `tool_progress` with parentToolUseId patches nested entries in a possibly-done turn; (3) `turn_activity` (compaction-only) writes activeMs/runCount unguarded.
- Session-level cases (turnId null) skip the guard.

### 3.2 upsertBlock(turn, blockId, build)
build receives existing (or undefined). If existing → patch blocksById only; else append blockId to turn.blocks AND set blocksById. Order established by FIRST event mentioning a blockId.

### 3.3 Per-event transitions (condensed; see events.mjs for exact bounds)

- **native_session_id**: normalize capabilities (string filter), version (non-empty), inventory (normalizeProjectedCliInventory: metadata-less commands inherit previous same-name command). If nothing changed → same state. Absence never clears fields.
- **cli_inventory_reset**: cliInventory = null (no-op if already).
- **cli_commands_changed**: replace commands only, keep tools/mcpServers; no-op if not array or unchanged.
- **api_retry**: guarded; turn.apiRetry = {attempt, maxRetries?:null, delayMs?:null, errorStatus?:null, error?:null}.
- **rate_limit**: session-level, last-write-wins; writes status verbatim. When status=="rejected" && resetsAt finite>0 && horizon (resetsAt - event.ts) in (0, 18000000] && rateLimitResume?.resetsAt != resetsAt → rateLimitResume = {status:"awaiting_choice", resetsAt, resumeAt: resetsAt + 120000}.
- **rate_limit_resume_scheduled**: only if current exists, resetsAt matches, status=="awaiting_choice", resumeAt == resetsAt+120000 → status "scheduled".
- **rate_limit_resume_dismissed**: matches && status != "fired" → "dismissed".
- **rate_limit_resume_fired**: matches && status=="scheduled" → "fired".
- **todo_updated**: normalize items (cap 100, skip blank content / invalid status, bound 2000 chars); **empty normalized list → no-op**. TodoProjection = { items, activeForm: first in_progress item's (activeForm or content) else null, completed: count(completed), total }. Value-equality dedup.
- **plan_updated**: guarded; whole-plan replacement; steps cap 100, invalid dropped; explanation bounded 2000 or null.
- **diff_updated**: guarded; requires string; diff = { unifiedDiff bounded 256000 }. Replacement.
- **model_rerouted**: guarded; all three fields required post-bounding; dedup vs last entry; append, keep last 20.
- **review_started/completed**: guarded; reviewId bounded-required; completed requires valid status. Upsert by reviewId with shallow-merge {...existing, ...review}; keep last 50.
- **mcp_health_updated**: session-level; name required; status validated else "unknown"; value dedup; NEW names dropped when map size ≥ 128.
- **context_compacted**: guarded; dedup by itemId; append; keep last 50.
- **provider_notice**: noticeId+message required; level validated else "warning"; dedup by noticeId (same array → no-op); append; keep last 50. turnId null → session list; else guarded → turn list.
- **permission_denied**: reason validated else "unknown"; reasonCode regex-checked else dropped; subagent only when literal true. Upsert by toolId: new → append; existing → enrich (name: new||old, reason: old=="unknown" ? new : old, reasonCode: old ?? new, subagent OR). Value-equal → same array. No cap. turnId null → session unattributed list; else guarded → turn list.
- **turn_started**: duplicate turnId → no-op (load-bearing for ext<seq>-N namespaces). Another open turn → no-op (one in flight ever). Else create turn (startedAt from event.ts), status "active", activeTurnId set, append to turnOrder.
- **user_message_accepted**: guarded; upsert block `user:${turnId}` kind user_message, text, attachments if non-empty array. Replaces wholesale on repeat.
- **message_started**: guarded; upsert preserving existing (idempotent; never wipes deltas): existing ?? {blockId, kind:"message", text:"", done:false}.
- **message_delta**: guarded; append: {...(existing ?? {blockId, kind:"message", done:false}), text: (existing?.text ?? "") + event.text}.
- **message_completed**: guarded; wholesale REPLACE: {blockId, kind:"message", text, done:true, aborted: true only if event.aborted === true}.
- **thinking_delta / thinking_completed**: same as message counterparts with kind "thinking".
- **thinking_stop**: guarded AND patch-only (no-op if block absent — never creates); set done:true.
- **tool_start**: guarded; REPLACE: {blockId: toolId, kind:"tool", name, input, output:null, isError:false, done:false}.
- **tool_output_delta**: guarded; append to output as string: {...(existing ?? {blockId, kind:"tool", done:false}), output: (existing?.output as? string ?? "") + chunk}.
- **tool_progress**: patch-only, two branches. Nested (parentToolUseId != null): may amend done turn; find parent block (kind tool), child = parent.subagent?.entries[toolId]; no-op unless child exists, kind tool, !done, elapsed differs; set child.elapsedSeconds. Main: guarded; tool block must exist, kind tool, !done, differs; set elapsedSeconds. Never creates anything.
- **tool_end**: guarded; upsert: take existing ?? {blockId, kind:"tool"}, DELETE elapsedSeconds, set output, isError, done:true. Pairing purely by toolId == blockId; name/input preserved via spread.
- **approval_request**: guarded; normalize choices (cap 8; choiceId must be 1..128 chars — REJECT not truncate; label bounded-required; dedup choiceId; only choiceId/label/description/permissionGrant∈{exact,subset} survive) and metadata (must be object with valid provider+kind; bounded reason 2000/command 16000/cwd 4096/paths 64×4096; network needs valid host, port int 0..65535; requestedPermissions normalized). pendingApprovals[requestId] = approval (last-write-wins). status = deriveSessionStatus → "waiting".
- **question_request**: guarded; pendingQuestions[requestId] = {requestId, toolId, questions verbatim — unvalidated}. status → "waiting".
- **question_resolved / question_cancelled**: guarded; delete pendingQuestions[requestId]; recompute status.
- **approval_resolved / approval_expired**: guarded; delete pendingApprovals[requestId]; recompute status. Payload (choiceId/decision) NOT read.
- deriveSessionStatus(turn): !turn || done → "ready"; pendingApprovals or pendingQuestions non-empty → "waiting"; else "active" (including "cancelling").
- **cancel_requested**: guarded; turn.status = "cancelling". Session status untouched.
- **cancelled**: guarded; turn done/outcome "cancelled"; session status "ready", activeTurnId null, lastTurnOutcome "cancelled".
- **process_exit**: guarded; turn.exit = {code, signal}. Does not end the turn.
- **usage**: guarded; replace turn.usage verbatim (no validation/bounding).
- **token_progress**: guarded; tokens = nonNegativeFiniteNumber else no-op; next = liveTokens == null ? tokens : max(liveTokens, tokens); monotonic clamp; null ≠ 0.
- **turn_activity**: unguarded; turn must exist; overwrite activeMs/runCount (whichever defined, absolute).
- **subagent_message**: updateTurnById (may amend done turn); parent block must exist and be kind tool, else DROP. foldSubagentItems: order/entries maps; message/thinking REPLACE by key; tool MERGE {...prev, key, kind, name, input, done: prev?.done ?? false}; tool_result MERGE onto clearElapsedProgress(prev ?? {key, kind:"tool"}) + output/isError/done:true; usage = event.usage ?? existing.usage (SET, never accumulate; usage-less never blanks).
- **warning / unknown_event**: guarded; turn.error = turn.error ?? legacyUnknownErrorMessage(raw) (unknown_event only: raw.message when raw.type=="error"; raw.error.message when raw.type=="turn.failed") ?? keep; append {type, message, raw} to warnings, keep last 50.
- **error**: turnId null → lastError = message. Else guarded → turn.error = turn.error ?? message (FIRST error wins).
- **turn_end**: guarded; turn done, outcome = event.outcome VERBATIM (not validated); session status "ready", activeTurnId null, lastTurnOutcome = outcome.
- **background_interrupted / background_abandoned**: dedup by seq (if seq defined && any notice has same seq → no-op); append {kind: type, outstanding ?? 0, seq, reason?}. No cap.
- **external_advancement**: same seq-dedup; append {kind, count, seq}.
- **queued_message_added**: dedup by queueId → no-op; else append {queueId, text}.
- **queued_message_updated**: absent queueId → no-op (never resurrects); else map text.
- **queued_message_removed**: absent → no-op; else filter out.
- **default**: return state unchanged (unknown types are silent no-ops).

## 3.4 Ordering (CLIENT'S job, not the reducer's)
- Keep cursor per session. No snapshot yet → drop events. seq <= cursor → drop. seq > cursor+1 → gap: do NOT fold; send one `attach {sessionId, afterSeq: cursor}` per gap (resync-pending flag); wait for snapshot. seq == cursor+1 → advance cursor and fold. Non-numeric seq → fold without cursor movement.
- Snapshot frame replaces projection wholesale, cursor = throughSeq, clear resync flag.

## 3.5 Kotlin porting checklist
1. Immutable data classes + copy(); never mutate inputs.
2. No clocks in the reducer — only event.ts.
3. Absent vs null: elapsedSeconds (deleted on tool_end), aborted (only true), reasonCode/subagent/description/permissionGrant/target/result — nullable Kotlin fields, omit nulls on serialization.
4. boundedDisplayText truncates by Unicode CODE POINTS, not UTF-16 chars.
5. Choice IDs 1..128: rejected, never truncated.
6. Three-pass reduce; do not inline run/api-retry logic into cases.
7. Unknown event types are silent no-ops.
8. turnOrder is render order; blocks is per-turn block render order; both append-only, established by first mention.

## 3.6 Golden test expectations (mirror tests/events.test.mjs)
For a turn with tool_start(Bash echo) → token_progress(99) → tool_end("hello-from-tool-spike") → message_started/delta/completed(":t0") → usage(claude-sonnet-5, perTurnTokens 115) → turn_end(ok):
- state.status == "ready", lastTurnOutcome == "ok", activeTurnId == null
- turn.blocks order: [toolId, "<msgId>:t0"] — NO thinking block (thinking_stop is patch-only)
- tool block: done true, output "hello-from-tool-spike", isError false, name "Bash", elapsedSeconds absent
- message block: text replaced by final, done true
- liveTokens == 99; usage.model == "claude-sonnet-5"
- Reducer purity: folding must not mutate the input event or prior state.
