// Normalized agent event model, pure session/turn reducer, and pure
// provider adapters (raw provider JSON -> normalized AgentEvent[]).
//
// Everything in this file is a pure function: no I/O, no Date.now()/random,
// no mutation of arguments. Callers thread state explicitly. This is what
// makes the reducer and adapters fixture-testable (§12) and safe to run
// during journal replay on boot.
//
// AgentEvent shape (§3.3 of HEADLESS_AGENTS_PLAN.md): { type, turnId, ...payload }.
// `seq` and wall-clock `ts` are NOT assigned here — they are stamped by the
// durable journal (Phase 1 task 2) at append time, which is the only place
// that legitimately owns "monotonic per session" and "current time".
//
// One event type beyond the literal §3.3 list: `native_session_id`. The spec
// (§3.1) requires the provider's system/init (Claude) or thread.started
// (Codex) event to authoritatively set `nativeSessionId` every turn. That is
// a session-identity fact, not a UI-facing turn event, but the reducer is the
// single place session state may change — so it needs a event vocabulary
// entry for it. It is not broadcast to the browser.
//
// v17 (SDK_SURFACE_PLAN T1/T4) rides on that same identity event and two new
// ones:
//   - `native_session_id` gained optional `cliCapabilities` / `cliVersion`,
//     lifted from the Claude system/init record (`capabilities`,
//     `claude_code_version`). `cliCapabilities` is the CLI's OPEN set of
//     protocol capability strings — feature-detect with it instead of
//     version-sniffing `pkg.version`. Unknown strings are carried through
//     untouched; absence means "feature off", never an error. Deliberately NOT
//     the same thing as `ProviderCapabilities` (lib/protocol.ts), which is
//     Tether's own static per-engine declaration — hence the `cli` prefix.
//   - `api_retry`: the SDK is retrying an HTTP call inside the current turn.
//     Visibility only. This is NOT turn retry and must never become it (the
//     never-auto-retry-a-turn invariant in CLAUDE.md).
//   - `rate_limit`: the CLI pushed new subscription rate-limit status.
//     v127 (issue #195) adds an optional `grace` ("wrap_up" |
//     "wrap_up_then_credits"): Claude's Wrap-Up Allowance is open, read from the
//     @internal `rateLimitGraceActive` field. Folded into `rateLimit.grace` and
//     dropped at every turn boundary.
//
// v20 (SDK_SURFACE_PLAN T5) completes Claude result accounting:
//   - `usage.modelUsages` retains every modelUsage entry (including its raw
//     result-map key, optional canonical pricing model, and provider).
//   - `usage.estimatedCostUSD` carries result.total_cost_usd, the SDK's
//     whole-task-tree client-side estimate.
//   - `usage.model` is the main served model identity when it can be determined:
//     canonicalModel first, raw result-map key as the fallback. It is never
//     inferred from an arbitrary first object key.
//
// v21 (SDK_SURFACE_PLAN T8) carries SDKAssistantMessage.aborted from the
// wrapper onto exactly one completed assistant block, including a marker-only
// block for an aborted wrapper with no text.
//
// v22 (SDK_SURFACE_PLAN T9) carries auto-denied tool facts without copying tool
// input, human-readable provider reasons/messages, or raw subagent ids.
//
// v24 (SDK_SURFACE_PLAN T16) carries bounded elapsed-time milestones for known
// main and nested tools. Heartbeat/retry payloads, generated tool-use summaries,
// and the runtime-only command lifecycle stay behind the provider boundary.
//
// v25 (SDK_SURFACE_PLAN T15) carries a bounded, process-scoped CLI inventory:
// slash commands, loaded tool names, and MCP server health. Init is a snapshot;
// commands_changed replaces only the command list. Plugin paths/versions,
// authentication source, betas, output style, agents, skills, and fast-mode
// state remain behind the provider boundary because they have no present
// consumer (and several are sensitive or plugin-author-controlled).
//
// v39 adds `todo_updated`: Claude's TodoWrite list, normalized off the tool call's
// own input at the provider boundary (Codex's equivalent is the turn-scoped
// plan_updated). It is SESSION-level (turnId null), like `cli_commands_changed` and
// the task telemetry, for two reasons: a todo list outlives the turn that wrote it
// (the interesting reading is "what is this agent working on now", not "what did
// turn 3 declare"), and whole-turn journal compaction rebuilds a turn from a minimal
// event set — a turn-scoped todo event would simply be dropped there. The tool card
// is still emitted as usual; this is an ADDITIONAL projection, not a replacement.

// --------------------------------------------------------------------------
// issue #184: Claude Code's tool-abort artifacts
// --------------------------------------------------------------------------
//
// When a tool is aborted mid-flight (Stop, an interrupt-on-send, a watchdog or a
// teardown — the CLI cannot tell them apart) Claude Code writes a FIXED pair of
// literals into the transcript: a canned tool_result for the aborted call, and a
// pseudo-user text block. Relayed verbatim they read as the operator's own words
// ("The user doesn't want to proceed…"), so both are classified here, at the one
// seam where raw provider JSON becomes an AgentEvent:
//   * the canned tool_result -> `tool_end` gains `interrupted: true` (the raw text
//     stays on `output`, rendered behind a "what the CLI reported" disclosure);
//   * the pseudo-user marker -> dropped, never a user message.
// Matched EXACTLY (after trim): a genuine rejection with operator feedback carries
// extra text and is not this artifact. The wording is the CLI's, not ours — if it
// ever changes the classification simply stops matching and the text renders as
// an ordinary tool error, exactly as before this issue.
// The CLI's texts, verbatim from its own artifact table (2.1.280: the constants
// its `qde` list and `dBe()` abort-text picker use). Two of them put words in the
// operator's mouth — REJECTION_WORDING is also what a genuine CLI-prompt rejection
// writes, which is why transcript replay only trusts it for SDK-driven records
// (see isClaudeToolAbortOutput). The bracketed ones are the newer self-describing
// forms the CLI writes when a turn ends to deliver a queued message.
export const CLAUDE_TOOL_ABORT_TEXT =
  "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). STOP what you are doing and wait for the user to tell you how to proceed.";
const CLAUDE_TOOL_ABORT_TEXTS_UNAMBIGUOUS = [
  // dBe()'s default for an aborted signal.
  "The user doesn't want to take this action right now. STOP what you are doing and wait for the user to tell you how to proceed.",
  "[Tool call did not complete: the turn was ended to deliver the message that follows. Nothing refused it; re-run it if still needed.]",
  "[Tool call skipped: the turn ended to deliver the message that follows before this call ran. Nothing refused it; re-run it if still needed.]",
  "[Tool call not completed: an approval request was still unanswered when the message that follows arrived and was closed, so the action awaiting approval did not run. Nobody refused it, so this is not the user's decision; ask again if it is still needed.]",
];
// The CLI may append this note (flag-gated) to any abort text; it matches its own
// artifacts with startsWith for that reason. Only THIS suffix is accepted — a
// rejection with operator feedback appends different text and stays unmatched.
const CLAUDE_ABORT_NOTE_PREFIX = "\n\nNote: The user's next message may contain a correction or preference.";
const CLAUDE_INTERRUPT_MARKERS = new Set([
  "[Request interrupted by user for tool use]",
  "[Request interrupted by user]",
]);

function matchesAbortText(text, known) {
  if (text === known) return true;
  return text.startsWith(known) && text.slice(known.length).startsWith(CLAUDE_ABORT_NOTE_PREFIX);
}

// A tool_result content (string, or an array of text blocks) that is one of the
// CLI's abort texts. `rejectionWordingIsAbort` (default true) decides whether the
// "doesn't want to proceed … was rejected" text counts: true for a Tether-driven
// stream (every real rejection there goes through canUseTool with Tether's OWN
// deny text, so the CLI's wording can only mean an abort — the #184 incident);
// false when replaying a terminal session, where it may be a genuine rejection.
export function isClaudeToolAbortOutput(content, { rejectionWordingIsAbort = true } = {}) {
  let text = null;
  if (typeof content === "string") text = content;
  else if (Array.isArray(content) && content.length > 0) {
    if (!content.every((block) => typeof block === "string" || (block && block.type === "text" && typeof block.text === "string"))) return false;
    text = content.map((block) => (typeof block === "string" ? block : block.text)).join("");
  }
  if (text === null) return false;
  const trimmed = text.trim();
  if (CLAUDE_TOOL_ABORT_TEXTS_UNAMBIGUOUS.some((known) => matchesAbortText(trimmed, known))) return true;
  return rejectionWordingIsAbort && matchesAbortText(trimmed, CLAUDE_TOOL_ABORT_TEXT);
}

// A user-record text that is exactly one of the CLI's interrupt markers.
export function isClaudeInterruptMarkerText(text) {
  return typeof text === "string" && CLAUDE_INTERRUPT_MARKERS.has(text.trim());
}

// A Claude user record's content (string or block array) that carries NOTHING but
// an interrupt marker — so the live adapter can drop it instead of surfacing it.
function isClaudeInterruptMarkerContent(content) {
  if (typeof content === "string") return isClaudeInterruptMarkerText(content);
  if (!Array.isArray(content) || content.length === 0) return false;
  return content.every((block) => block && block.type === "text" && isClaudeInterruptMarkerText(block.text));
}

// issue #184: who asked for an interrupt (the `turn_interrupted` notice's `by`).
export const TURN_INTERRUPT_SOURCES = Object.freeze(["message-send", "operator-stop", "control-api", "parent-cancel", "watchdog", "teardown"]);
const TURN_INTERRUPT_SOURCE_SET = new Set(TURN_INTERRUPT_SOURCES);
// How many turn_interrupted notices a session keeps (oldest dropped) — one per
// interrupt is unbounded over a long session otherwise.
export const MAX_TURN_INTERRUPTED_NOTICES = 100;

export const TURN_OUTCOMES = Object.freeze({
  OK: "ok",
  CANCELLED: "cancelled",
  ERROR: "error",
  UNKNOWN: "outcome_unknown",
});

export const RATE_LIMIT_RESUME_DELAY_MS = 2 * 60 * 1000;

// How far ahead a rejected limit's reset may sit and still be worth offering to
// wait for (v35, owner decision). Replaces the earlier "Claude five_hour window
// only" rule, which named the WINDOW rather than the WAIT and was wrong in both
// directions: it refused a weekly/quota rejection that happened to reset in
// minutes, and it was unusable for Codex, whose blocking rejection is typically
// the weekly window and whose prose names no window at all.
//
// The gate is now purely "how long would we be waiting": at most five hours,
// and the reset must still be ahead of us. A multi-day reset gets no prompt —
// an unattended continuation fired days later would resume against stale intent.
export const RATE_LIMIT_RESUME_MAX_HORIZON_MS = 5 * 60 * 60 * 1000;

const SESSION_STATUS = Object.freeze({
  READY: "ready",
  ACTIVE: "active",
  WAITING: "waiting",
  EXITED: "exited",
});

// T3 public projection bounds. Provider adapters apply their own input bounds;
// the reducer repeats the caps so journal replay can never inflate a browser
// projection even if it encounters malformed or pre-normalized records.
export const PROVIDER_PROJECTION_LIMITS = Object.freeze({
  approvalChoices: 8,
  approvalChoiceIdChars: 128,
  paths: 64,
  planSteps: 100,
  modelReroutes: 20,
  modelFallbacks: 20,
  reviews: 50,
  compactions: 50,
  providerNotices: 50,
  mcpServers: 128,
  idChars: 200,
  labelChars: 200,
  proseChars: 2_000,
  commandChars: 16_000,
  diffChars: 256_000,
  pathChars: 4_096,
});

// issue #170: how many dismissed-notice keys a session remembers. Bounded like
// every other session-level list — a notice is dismissed at most once and the
// notice lists themselves are bounded, so an evicted key can only ever let a
// notice that is itself already evicted reappear.
export const MAX_DISMISSED_NOTICES = 200;

// issue #170: stable, SERVER-ASSIGNED dismissal identity for every notice the
// operator can close. Cross-device dismissal keys on this string, so it must be
// identical on every client and reproducible from a journal replay. Prefixed by
// notice family so a background marker's numeric journal seq can never collide
// with a provider notice or compaction id, and scoped by turn where the family
// repeats an id (a per-turn provider notice and a session-level one can share
// the provider's own noticeId).
export function backgroundNoticeDismissKey(kind, seq) {
  return seq !== undefined ? `${kind}:${seq}` : `${kind}:unsequenced`;
}
export function externalAdvancementDismissKey(fromCursor, toCursor) {
  return `external_advancement:${fromCursor}:${toCursor}`;
}
export function providerNoticeDismissKey(turnId, noticeId) {
  return `provider_notice:${turnId ?? "session"}:${noticeId}`;
}
export function compactionDismissKey(turnId, itemId) {
  return `context_compacted:${turnId}:${itemId}`;
}

// Is this dismiss key already acknowledged on the projection? Every
// notice-producing fold checks this BEFORE appending, so a notice folded AFTER
// its dismissal in a replay (or a derived-not-journaled external_advancement
// re-folded on boot) stays hidden instead of resurrecting.
function isNoticeDismissed(state, dismissKey) {
  if (!dismissKey) return false;
  return Array.isArray(state.dismissedNotices) && state.dismissedNotices.includes(dismissKey);
}

// issue #170: does the live projection still hold the notice this key names?
// The manager refuses to journal a dismissal for a key it cannot see, so a
// stale/forged key cannot pad the bounded dismissed set. The renderer only ever
// offers a key that came from this same projection, so an ordinary click always
// passes; the check is for the abuse path, not the user path.
export function projectionHasNoticeKey(state, dismissKey) {
  if (!state || !dismissKey) return false;
  if ((state.notices ?? []).some((notice) => notice.dismissKey === dismissKey)) return true;
  if ((state.providerNotices ?? []).some((notice) => notice.dismissKey === dismissKey)) return true;
  for (const turn of Object.values(state.turnsById ?? {})) {
    if (turn.compactions.some((item) => item.dismissKey === dismissKey)) return true;
    if (turn.providerNotices.some((notice) => notice.dismissKey === dismissKey)) return true;
  }
  return false;
}

// issue #170: fold a `notice_dismissed` acknowledgment. Records the key (so a
// notice folded LATER in the same replay stays hidden) and drops every matching
// entry from the live projection. The notice's own marker in the journal is
// never rewritten or deleted — the dismissal is a new fact appended after it.
function applyNoticeDismissal(state, dismissKey) {
  const dismissedNotices = [...(state.dismissedNotices ?? []), dismissKey].slice(-MAX_DISMISSED_NOTICES);
  const notices = state.notices.filter((notice) => notice.dismissKey !== dismissKey);
  const providerNotices = state.providerNotices.filter((notice) => notice.dismissKey !== dismissKey);
  let turnsById = state.turnsById;
  for (const [turnId, turn] of Object.entries(state.turnsById)) {
    const nextCompactions = turn.compactions.filter((item) => item.dismissKey !== dismissKey);
    const nextProviderNotices = turn.providerNotices.filter((notice) => notice.dismissKey !== dismissKey);
    if (nextCompactions.length === turn.compactions.length && nextProviderNotices.length === turn.providerNotices.length) continue;
    if (turnsById === state.turnsById) turnsById = { ...state.turnsById };
    turnsById[turnId] = { ...turn, compactions: nextCompactions, providerNotices: nextProviderNotices };
  }
  return { ...state, dismissedNotices, notices, providerNotices, turnsById };
}

// --------------------------------------------------------------------------
// Pure session/turn reducer
// --------------------------------------------------------------------------

export function initialSessionState({ tetherSessionId, provider, cwd, nativeSessionId = null }) {
  return {
    tetherSessionId,
    provider,
    cwd,
    nativeSessionId,
    // v17/T1: runtime facts about the CLI process currently serving this session,
    // learned from every system/init. `cliCapabilities` is the CLI's open set of
    // protocol capability strings; `cliVersion` is its self-reported
    // claude_code_version. Both are deliberately NOT persisted to the manifest —
    // they describe the CLI attached RIGHT NOW, and Tether can be pointed at a
    // different CLI build between restarts (the v16 Advanced version override), so
    // a persisted claim could be stale. Every open re-learns them from init.
    cliCapabilities: [],
    cliVersion: null,
    // v25/T15: process-scoped inventory advertised by Claude Code. Null means
    // there is no current CLI process snapshot (boot, cold session, or an
    // explicit process reset before spawn). Init replaces all three lists;
    // commands_changed replaces only commands. Never persisted as a trusted
    // current fact across server/process restart.
    cliInventory: null,
    // Provider-neutral MCP startup/health facts. Unlike Claude's process-scoped
    // cliInventory, this map can be updated independently by app-server.
    mcpHealth: {},
    // v17/T4: latest subscription rate-limit status pushed by the CLI
    // (rate_limit_event). Session-level, last-write-wins; null until the CLI
    // reports one. Distinct from SessionMetrics' usage windows, which the
    // discovery worker derives from the native transcript.
    rateLimit: null,
    rateLimitResume: null,
    // v95: Claude fast mode's own reported state (fast_mode event, folded from
    // the SDK's `fast_mode_state`/`fast_mode_disabled_reason`). Session-level,
    // last-write-wins; null until a live session has reported one — never
    // assumed off/on by default.
    fastModeState: null,
    fastModeDisabledReason: null,
    // v91 (issue #106): the account backing this session is logged out — its
    // OAuth session expired and the CLI could not refresh it, so every turn
    // fails in ~1.7s until the operator logs in again. Mirrors rateLimitResume's
    // lifecycle: SET when a turn is classified auth-expired (the `cause` field on
    // that turn's `error`/`turn_end`, stamped at emission by the engine — see
    // lib/claude-auth-expiry.mjs), CLEARED by the next OK-outcome turn_end on
    // this session (a turn that completed proves the credentials work again).
    // Null until one is classified.
    accountAuth: null,
    // v39: the session's live TodoWrite list (Claude), or null until the agent
    // writes one. Session-level and last-write-wins for the same reason
    // `cliInventory` is: it describes the agent's CURRENT intent, not any one
    // turn's. Replay reproduces it exactly, because the journaled todo_updated
    // events are folded through this same reducer on boot and on attach.
    todo: null,
    // v56: internal accumulator backing `todo` when the engine drives progress with
    // the INCREMENTAL Task tools (TaskCreate/TaskUpdate) rather than the whole-list
    // TodoWrite. Each entry is { id, subject, activeForm, status }; `id` is the real
    // task id once the create's tool_result assigns it, and the creating tool_use id
    // until then. On the wire (SessionProjection) because the client folds subsequent
    // todo_item_* events onto the reconnect snapshot — the UI reads `todo`, not this.
    todoTasks: [],
    status: SESSION_STATUS.READY,
    lastTurnOutcome: null,
    lastError: null, // holds a turn-less `error` event (event.turnId == null) — no turn projection could hold it
    // v22/T9: permission_denied records that arrived between persistent turns.
    // They are closed facts, not pending approvals, and assigning them to the
    // previous turn would invent causality.
    unattributedPermissionDenials: [],
    providerNotices: [],
    // issue #179: the most recent CLI model fallback on this session
    // ({ turnId, message, fromModel?, toModel?, trigger?, fallbackId? }), or null.
    // Last-write-wins, folded from the journaled `model_fallback` event, so a
    // replay/reconnect reproduces it. Each turn's own list is turn.modelFallbacks.
    lastModelFallback: null,
    // Session-level breadcrumbs that belong to no single turn (§10.4 step 6):
    // currently only `background_interrupted` (a restart killed the warm query
    // AND its in-flight background task). Derived from the durable journal
    // markers, appended to — never mutated in place — so the fold stays pure.
    notices: [],
    // issue #170: stable dismiss keys the operator acknowledged, in most-recent
    // order, bounded. Journaled (notice_dismissed) so a replay reproduces the
    // hidden state and every attached device converges, and consulted at fold
    // time so a notice folded after its own dismissal can never resurrect.
    dismissedNotices: [],
    // v54: session-level background `!` commands (the "Send to background" action),
    // newest last, folded from background_command_updated (turnId null, last-write-
    // wins per commandId). Like `notices`, replaced/appended immutably so the fold
    // stays pure and replays exactly on boot/attach.
    backgroundCommands: [],
    // v117 (issue #171): background sub-agent task lifecycle, folded from the SDK's
    // task_started/task_progress/task_completed telemetry and the authoritative
    // background_tasks_changed level. Joined back to a launcher tool block by
    // toolUseId so a background run's tab can stay "running" after its tool result
    // (a launch acknowledgement) has landed. Empty on pre-v117 journals, where the
    // run derivation reads a background launch as running (unconfirmed) rather
    // than ticking it. Bounded like every other session-level list.
    backgroundTasks: [],
    // v118 (issue #173): agent-CLI children linked to this session at SPAWN time —
    // launched by the `spawn_agent` Tether tool (origin "spawned"), or a native
    // run discovery attributed here through its own launch marker (origin
    // "discovered"). Folded from spawned_run_updated / spawned_run_output
    // (turnId null, last-write-wins per runId), newest last, bounded. DISPLAY
    // METADATA ONLY: nothing reads this projection to make a consent, approval
    // or scope decision (D3).
    spawnedRuns: [],
    // v118: every runId / native id a spawned_run_updated has ever named (bounded,
    // oldest dropped). It outlives the bounded `spawnedRuns` list so a run evicted
    // from it is never re-created by the next discovery scan (no journal churn).
    spawnedRunKeys: [],
    // Every turn's projection is retained (keyed by turnId), not just the
    // most recent one — the chat UI needs to replay the WHOLE conversation,
    // not only the latest turn. `activeTurnId` is the one currently open, or
    // null between turns ("between turns nothing runs", §3).
    turnOrder: [],
    turnsById: {},
    activeTurnId: null,
    // User messages composed while a turn was in flight, held until the next turn
    // boundary (queued_message_* markers, v14). Session-level, turnId null — like
    // `notices`, appended to / filtered, never mutated in place, so the fold stays
    // pure. Empty between turns (an idle-composed message flushes immediately).
    queuedMessages: [],
    // v130 (S13.1-C): the last MAX_REMOVED_QUEUE_IDS queueIds that left the queue
    // (withdrawn by the operator OR flushed into a turn), oldest first. The queue
    // itself only shows what is pending NOW, so without this a queue-add whose ack
    // was lost and that was then removed on ANOTHER device looked "never
    // accepted" to a reconnecting client, which re-sent it (lib/pending-input.mjs).
    removedQueueIds: [],
  };
}

// Convenience accessor mirroring the old single-slot shape, handy in tests
// and UI code that only cares about "the turn in progress right now".
export function currentTurn(state) {
  return state.activeTurnId ? state.turnsById[state.activeTurnId] : null;
}

// issue #183: the tool calls of the session's in-flight turn that are genuinely
// RUNNING — started, not ended, and not parked on an approval or question (a
// gated call has not run yet, so interrupting it destroys nothing: before #183 a
// message typed at an approval card cancelled the turn, and it still does).
// Read off the folded projection — engine-agnostic, unlike the persistent-only
// openToolIds set. `excludeToolId` drops the call whose own tool_start is being
// evaluated (a just-requested call has done nothing yet).
export function runningToolIds(state, excludeToolId = null) {
  const turn = currentTurn(state);
  const running = new Set();
  if (!turn) return running;
  const parked = new Set();
  for (const request of Object.values(turn.pendingApprovals ?? {})) if (request?.toolId) parked.add(request.toolId);
  for (const request of Object.values(turn.pendingQuestions ?? {})) if (request?.toolId) parked.add(request.toolId);
  for (const blockId of turn.blocks) {
    if (blockId === excludeToolId || parked.has(blockId)) continue;
    const block = turn.blocksById[blockId];
    if (block?.kind === "tool" && block.done !== true) running.add(blockId);
  }
  return running;
}

export function openToolCount(state, excludeToolId = null) {
  return runningToolIds(state, excludeToolId).size;
}

// issue #183: background tasks that are provably still running, off the folded
// projection. EVIDENCE only — never the eviction heuristic (pendingBackgroundToolIds
// deliberately over-marks and is cleared only by a continuation, so a foreground
// Agent would pin every later message into "deferred" for the warm session's life).
//   * `levelTaskIds` (the CLI's authoritative background_tasks_changed level, when
//     this process has seen one) wins outright;
//   * otherwise a task whose task_started has no terminal task_completed.
// A task launched by a call already counted as a running tool is not counted twice.
export function liveBackgroundTaskCount(state, { levelTaskIds = null, excludeToolIds = new Set() } = {}) {
  const tasks = Array.isArray(state?.backgroundTasks) ? state.backgroundTasks : [];
  const excluded = (task) => Boolean(task?.toolUseId) && excludeToolIds.has(task.toolUseId);
  if (levelTaskIds) {
    let count = 0;
    for (const taskId of levelTaskIds) {
      if (!excluded(tasks.find((task) => task.taskId === taskId))) count += 1;
    }
    return count;
  }
  return tasks.filter((task) => task.status === "running" && !excluded(task)).length;
}

// Issue #17: the sidebar's "Last Active" sort must reflect real conversation
// activity — a user message or an agent turn — never a bare state-transition
// stamp. A caller used to key that sort off a `Date.now()` read taken on
// EVERY session state change, including opening/warming a session (no new
// turn) and codex/opencode control-applied config changes (model/effort/mode)
// — so merely viewing an old session jumped it to the top.
//
// `turnOrder`/`turnsById` only change on a real turn boundary (turn_started:
// a user send, or an engine-driven continuation after background work), never
// on attach/open/config alone, so the most recent turn's `startedAt` (the
// journal-stamped `ts` of ITS turn_started, §37 — not a fresh clock read) is
// exactly the timestamp callers need. Returns null when no turn has ever
// landed in this state (a brand-new session), so callers can fall back to
// their own seed (e.g. the session's createdAt/startedAt).
export function lastMessageActivityAt(state) {
  const lastTurnId = state.turnOrder.length ? state.turnOrder[state.turnOrder.length - 1] : null;
  const startedAt = lastTurnId ? state.turnsById[lastTurnId]?.startedAt : null;
  return typeof startedAt === "number" ? startedAt : null;
}

function newTurnProjection(turnId, idempotencyKey, continuation = false, startedAt = null, commandRun = null) {
  return {
    turnId,
    idempotencyKey: idempotencyKey ?? null,
    // v54: foreground `!` command-run metadata ({ command, cwd, logFile }) or null.
    // Marks this turn as a command panel; carried on turn_started so replay/compaction
    // reproduce it.
    commandRun: commandRun ?? null,
    // v37: the journal-stamped `ts` of turn_started, not a clock read — this
    // module is pure, and sourcing it from the event is exactly what makes the
    // elapsed reading identical on the server, on every attached client, and on
    // journal replay. Null for turns journaled before v37.
    startedAt,
    // v37: monotonic mid-turn token estimate; see the protocol note. Stays null
    // until the first token_progress, so "unknown" and "zero" stay distinct.
    liveTokens: null,
    // v38 run bookkeeping — see syncTurnRun. `run` is the OPEN run or null (null
    // whenever the agent is not working: parked on an approval, or finished);
    // `runCount` is how many have started, which is also what picks the spinner
    // word; `activeMs` is the banked duration of every run that has already closed,
    // so a session total stays right no matter how often the turn parked.
    run: null,
    runCount: 0,
    activeMs: 0,
    // True for an unprompted continuation turn (the engine streams it after a
    // background task reports back — no send() call preceded it). The UI shows
    // a "↻ continued" affordance so a user-less agent turn isn't confusing
    // (§10.4 step 6). User turns carry no `continuation` on turn_started → false.
    continuation: Boolean(continuation),
    status: "running", // running | cancelling | done
    outcome: null,
    blocks: [],
    blocksById: {},
    pendingApprovals: {},
    pendingQuestions: {},
    // v104: durable records of resolved AskUserQuestion prompts (the operator's
    // chosen answer), preserved after the interactive card is cleared.
    answeredQuestions: [],
    permissionDenials: [],
    usage: null,
    // v17/T4: the HTTP retry the SDK is currently attempting inside this turn, or
    // null. Transient by construction — cleared the moment the provider produces
    // real content again (see API_RETRY_RESOLVED_BY), so the UI badge disappears
    // on its own without any timer. HTTP-level only: it never affects `outcome`.
    apiRetry: null,
    // The transcript renders this first snapshot while `plan` keeps following
    // plan_updated events for the live progress bar.
    initialPlan: null,
    plan: null,
    diff: null,
    modelReroutes: [],
    // issue #179: CLI model fallbacks that switched THIS turn to another model.
    modelFallbacks: [],
    reviews: [],
    compactions: [],
    providerNotices: [],
    warnings: [],
  };
}

// Codex CLI versions before Tether learned their top-level failure records were
// journaled as unknown_event. Keep those existing journals useful on replay by
// recognizing only the two documented terminal-error shapes. The raw record is
// still retained in warnings for diagnostics.
function legacyUnknownErrorMessage(raw) {
  if (!raw || typeof raw !== "object") return null;
  if (raw.type === "error" && typeof raw.message === "string" && raw.message.trim()) return raw.message;
  if (raw.type === "turn.failed" && typeof raw.error?.message === "string" && raw.error.message.trim()) {
    return raw.error.message;
  }
  return null;
}

// v17/T4: an `api_retry` says "the SDK is between HTTP attempts right now". Any
// of these events proves the provider got past it, so the transient marker is
// dropped. Terminal events are included so a turn can never be archived with a
// stale "retrying…" badge frozen on it. Deliberately an allow-list: an event that
// carries no forward progress (approval_request, rate_limit, warning, another
// api_retry) must leave the marker standing.
const API_RETRY_RESOLVED_BY = new Set([
  "message_started",
  "message_delta",
  "message_completed",
  "thinking_delta",
  "thinking_completed",
  "thinking_stop",
  "tool_start",
  "tool_progress",
  "tool_output_delta",
  "tool_end",
  "permission_denied",
  "subagent_message",
  "plan_updated",
  // v56: incremental Task-tool progress (TaskCreate/TaskUpdate), folded onto the same
  // session `todo` projection as todo_updated. See normalizeTaskCreate for why they are
  // todo_item_* rather than task_* (the task_* vocabulary is background-subagent telemetry).
  "todo_item_created",
  "todo_item_id_assigned",
  "todo_item_updated",
  "diff_updated",
  "model_rerouted",
  "review_started",
  "review_completed",
  "context_compacted",
  "usage",
  // v37: tokens moving means the retried HTTP call landed. Worth listing even
  // though the deltas above resolve the marker too — a thinking-only stretch emits
  // token_progress and NOTHING else, because Claude redacts the reasoning text, so
  // the digested `thinking_tokens` count is the sole evidence of progress there.
  "token_progress",
  "cancelled",
  "turn_end",
]);

// Pure post-pass over reduceEvent's output. Targets `event.turnId` directly
// rather than `activeTurnId` because turn_end/cancelled clear the active turn
// before this runs. No-op (returns the same object) in the overwhelmingly common
// case where no retry is outstanding.
function clearResolvedApiRetry(state, event) {
  if (!API_RETRY_RESOLVED_BY.has(event.type)) return state;
  const turn = state.turnsById[event.turnId];
  if (!turn || turn.apiRetry == null) return state;
  return { ...state, turnsById: { ...state.turnsById, [event.turnId]: { ...turn, apiRetry: null } } };
}

function sameStringList(a, b) {
  if (!Array.isArray(a) || a.length !== b.length) return false;
  return a.every((value, index) => value === b[index]);
}

export const CLI_INVENTORY_LIMITS = Object.freeze({
  commands: 128,
  tools: 256,
  mcpServers: 64,
  aliasesPerCommand: 16,
  nameChars: 100,
  descriptionChars: 500,
  argumentHintChars: 200,
});

const MCP_SERVER_STATUSES = new Set(["connected", "failed", "needs-auth", "pending", "disabled"]);

// Maps Claude's cliInventory MCP server status vocabulary (normalizeClaudeMcpServers,
// above) onto the shared McpHealthStatus vocabulary (McpHealthStatus in lib/protocol.ts)
// so a Claude init record can ALSO emit mcp_health_updated events, in addition to the
// cliInventory.mcpServers list. Anything not in this table (i.e. "unknown") maps to
// "unknown" via the `?? "unknown"` fallback at the call site.
const CLAUDE_MCP_STATUS_TO_HEALTH = Object.freeze({
  connected: "ready",
  pending: "starting",
  failed: "failed",
  "needs-auth": "needs-auth",
  disabled: "disabled",
});

function identifier(value, { stripLeadingSlash = false } = {}) {
  if (typeof value !== "string") return null;
  const normalized = stripLeadingSlash ? value.trim().replace(/^\//, "") : value.trim();
  if (!normalized || Array.from(normalized).length > CLI_INVENTORY_LIMITS.nameChars) return null;
  return normalized;
}

function boundedDisplayText(value, maxChars) {
  if (typeof value !== "string") return undefined;
  const chars = Array.from(value);
  return chars.length <= maxChars ? value : chars.slice(0, maxChars).join("");
}

const PROVIDER_IDS = new Set(["claude", "codex", "opencode"]);
const APPROVAL_KINDS = new Set(["tool", "command", "file-change", "network", "permissions"]);
const PLAN_STEP_STATUSES = new Set(["pending", "in_progress", "completed"]);
const REVIEW_COMPLETION_STATUSES = new Set(["completed", "cancelled", "failed"]);
const MCP_HEALTH_STATUSES = new Set(["starting", "ready", "failed", "cancelled", "needs-auth", "disabled", "unknown"]);
const PROVIDER_NOTICE_LEVELS = new Set(["info", "warning", "error"]);

function boundedIdentifier(value) {
  if (typeof value !== "string" || value.length === 0) return null;
  return boundedDisplayText(value, PROVIDER_PROJECTION_LIMITS.idChars);
}

// Choice IDs are opaque round-trip tokens, not display text. Match inbound
// validation's string-length bound exactly and reject oversize values; trimming
// or truncating could turn distinct upstream choices into the same identifier.
function boundedApprovalChoiceId(value) {
  if (
    typeof value !== "string"
    || value.length === 0
    || value.length > PROVIDER_PROJECTION_LIMITS.approvalChoiceIdChars
  ) return null;
  return value;
}

function normalizeStringList(values, { limit, maxChars }) {
  if (!Array.isArray(values)) return undefined;
  const result = [];
  for (const value of values) {
    if (result.length >= limit) break;
    if (typeof value !== "string" || value.length === 0) continue;
    result.push(boundedDisplayText(value, maxChars));
  }
  return result;
}

/**
 * Issue #222: does a requested-permissions profile exceed the projection bounds
 * (a path over `pathChars` code points, or a list over `paths` entries)? The
 * projection TRUNCATES such a value, and web + native clients grant exactly the
 * bounded copy — so a path cut at a `/` would be granted as an ANCESTOR
 * directory, a broader grant than the provider asked for. A request that cannot
 * be shown whole is therefore never grantable (see the approval_request case).
 */
function requestedPermissionsExceedBounds(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const fileSystem = value.fileSystem;
  if (!fileSystem || typeof fileSystem !== "object" || Array.isArray(fileSystem)) return false;
  for (const access of ["read", "write"]) {
    const list = fileSystem[access];
    if (!Array.isArray(list)) continue;
    if (list.length > PROVIDER_PROJECTION_LIMITS.paths) return true;
    for (const entry of list) {
      if (typeof entry === "string" && Array.from(entry).length > PROVIDER_PROJECTION_LIMITS.pathChars) return true;
    }
  }
  return false;
}

function normalizeGrantedPermissions(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const result = {};
  if (value.fileSystem && typeof value.fileSystem === "object" && !Array.isArray(value.fileSystem)) {
    const fileSystem = {};
    const read = normalizeStringList(value.fileSystem.read, {
      limit: PROVIDER_PROJECTION_LIMITS.paths,
      maxChars: PROVIDER_PROJECTION_LIMITS.pathChars,
    });
    const write = normalizeStringList(value.fileSystem.write, {
      limit: PROVIDER_PROJECTION_LIMITS.paths,
      maxChars: PROVIDER_PROJECTION_LIMITS.pathChars,
    });
    if (read !== undefined) fileSystem.read = read;
    if (write !== undefined) fileSystem.write = write;
    result.fileSystem = fileSystem;
  }
  if (value.network && typeof value.network === "object" && !Array.isArray(value.network) && typeof value.network.enabled === "boolean") {
    result.network = { enabled: value.network.enabled };
  }
  return result;
}

function normalizeApprovalChoices(choices) {
  if (!Array.isArray(choices)) return undefined;
  const normalized = [];
  const seen = new Set();
  for (const choice of choices) {
    if (normalized.length >= PROVIDER_PROJECTION_LIMITS.approvalChoices) break;
    const choiceId = boundedApprovalChoiceId(choice?.choiceId);
    const label = boundedDisplayText(choice?.label, PROVIDER_PROJECTION_LIMITS.labelChars);
    if (!choiceId || !label || seen.has(choiceId)) continue;
    seen.add(choiceId);
    const description = boundedDisplayText(choice?.description, PROVIDER_PROJECTION_LIMITS.proseChars);
    const permissionGrant =
      choice?.permissionGrant === "exact" || choice?.permissionGrant === "subset"
        ? choice.permissionGrant
        : undefined;
    normalized.push({
      choiceId,
      label,
      ...(description === undefined ? {} : { description }),
      ...(permissionGrant === undefined ? {} : { permissionGrant }),
    });
  }
  return normalized;
}

function normalizeApprovalMetadata(metadata) {
  if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) return undefined;
  if (!PROVIDER_IDS.has(metadata.provider) || !APPROVAL_KINDS.has(metadata.kind)) return undefined;
  const normalized = { provider: metadata.provider, kind: metadata.kind };
  const reason = boundedDisplayText(metadata.reason, PROVIDER_PROJECTION_LIMITS.proseChars);
  const command = boundedDisplayText(metadata.command, PROVIDER_PROJECTION_LIMITS.commandChars);
  const cwd = boundedDisplayText(metadata.cwd, PROVIDER_PROJECTION_LIMITS.pathChars);
  const paths = normalizeStringList(metadata.paths, {
    limit: PROVIDER_PROJECTION_LIMITS.paths,
    maxChars: PROVIDER_PROJECTION_LIMITS.pathChars,
  });
  if (reason !== undefined) normalized.reason = reason;
  if (command !== undefined) normalized.command = command;
  if (cwd !== undefined) normalized.cwd = cwd;
  if (paths !== undefined) normalized.paths = paths;
  if (metadata.network && typeof metadata.network === "object" && !Array.isArray(metadata.network)) {
    const host = boundedDisplayText(metadata.network.host, PROVIDER_PROJECTION_LIMITS.labelChars);
    if (host) {
      normalized.network = { host };
      const protocol = boundedDisplayText(metadata.network.protocol, PROVIDER_PROJECTION_LIMITS.labelChars);
      if (protocol !== undefined) normalized.network.protocol = protocol;
      if (Number.isInteger(metadata.network.port) && metadata.network.port >= 0 && metadata.network.port <= 65_535) {
        normalized.network.port = metadata.network.port;
      }
    }
  }
  const requestedPermissions = normalizeGrantedPermissions(metadata.requestedPermissions);
  if (requestedPermissions !== undefined) normalized.requestedPermissions = requestedPermissions;
  // v64 (O3): opencode permission.asked carries bounded glob-formatted command
  // patterns ("patterns[]") and optional "always[]" arrays (patterns an
  // "always allow" reply would grant). Both are display-only; the upstream
  // decision itself round-trips via the choiceId, not through these arrays.
  // Bounded like the permission-path list so a hostile or runaway stream can
  // never inflate a projection.
  const permissionPatterns = normalizeStringList(metadata.permissionPatterns, {
    limit: PROVIDER_PROJECTION_LIMITS.paths,
    maxChars: PROVIDER_PROJECTION_LIMITS.commandChars,
  });
  if (permissionPatterns !== undefined) normalized.permissionPatterns = permissionPatterns;
  const alwaysAllowPatterns = normalizeStringList(metadata.alwaysAllowPatterns, {
    limit: PROVIDER_PROJECTION_LIMITS.paths,
    maxChars: PROVIDER_PROJECTION_LIMITS.commandChars,
  });
  if (alwaysAllowPatterns !== undefined) normalized.alwaysAllowPatterns = alwaysAllowPatterns;
  return normalized;
}

function normalizePlanSteps(steps) {
  if (!Array.isArray(steps)) return [];
  const normalized = [];
  for (const item of steps) {
    if (normalized.length >= PROVIDER_PROJECTION_LIMITS.planSteps) break;
    const step = boundedDisplayText(item?.step, PROVIDER_PROJECTION_LIMITS.proseChars);
    if (!step || !PLAN_STEP_STATUSES.has(item?.status)) continue;
    normalized.push({ step, status: item.status });
  }
  return normalized;
}

// v39 TodoWrite bounds. The journal PERSISTS every todo_updated, and the browser
// folds the same events, so an unbounded list would be written to disk and shipped
// to the client verbatim. `items` matches PROVIDER_PROJECTION_LIMITS.planSteps (100)
// — the closest analogue, Codex's plan — because a real TodoWrite list is a handful
// of entries and anything past a hundred is a bug or an attack, not a plan a human
// will read. Item text reuses the plan step's prose cap. Applied in BOTH the adapter
// and the reducer, so a hand-written or pre-normalization journal line cannot inflate
// a projection on replay.
export const TODO_LIMITS = Object.freeze({
  items: PROVIDER_PROJECTION_LIMITS.planSteps,
  textChars: PROVIDER_PROJECTION_LIMITS.proseChars,
});

// Deliberately not PLAN_STEP_STATUSES, even though the two vocabularies coincide
// today: they come from different providers on different events, and sharing the set
// would quietly couple a future divergence in one to the other (same reasoning as
// TodoStatus vs PlanStepStatus in lib/protocol.ts).
const TODO_STATUSES = new Set(["pending", "in_progress", "completed"]);

// "Nothing a human could read": a non-string, an empty string, or whitespace only.
function isBlank(value) {
  return typeof value !== "string" || value.trim() === "";
}

// Raw TodoWrite `todos` -> bounded TodoItemProjection[]. Never throws: a non-array,
// a null entry, a missing/blank `content`, or an unrecognized `status` yields fewer
// items rather than an error or an invented value. An item with an unusable status is
// DROPPED (as plan steps are) rather than coerced to "pending", so the projection
// never asserts progress the provider did not state. `activeForm` is optional on
// older CLI builds and normalizes to "" — the fallback to `content` happens once, in
// todoProjection, so every surface agrees on it.
function normalizeTodoItems(items) {
  if (!Array.isArray(items)) return [];
  const normalized = [];
  for (const item of items) {
    if (normalized.length >= TODO_LIMITS.items) break;
    const content = boundedDisplayText(item?.content, TODO_LIMITS.textChars);
    if (isBlank(content) || !TODO_STATUSES.has(item?.status)) continue;
    // A blank/absent activeForm normalizes to the SAME value ("") whichever way the
    // provider expressed it, so the fallback below has one condition to test rather
    // than three. The text itself is never trimmed — it is display prose.
    const activeForm = boundedDisplayText(item?.activeForm, TODO_LIMITS.textChars);
    normalized.push({
      content,
      activeForm: isBlank(activeForm) ? "" : activeForm,
      status: item.status,
    });
  }
  return normalized;
}

// Derives the wire TodoProjection from an already-normalized item list. `activeForm`
// is the FIRST in_progress item's activeForm — a malformed list can mark several, and
// "which todo is active" must have exactly one definition — falling back to that
// item's `content` when the provider sent no usable activeForm, and null when nothing
// is in progress.
function todoProjection(items) {
  const active = items.find((item) => item.status === "in_progress");
  return {
    items,
    activeForm: active ? (active.activeForm || active.content) : null,
    completed: items.filter((item) => item.status === "completed").length,
    total: items.length,
  };
}

function sameTodo(a, b) {
  if (a === null || b === null) return a === b;
  if (a.total !== b.total || a.completed !== b.completed || a.activeForm !== b.activeForm) return false;
  return a.items.every((item, index) => (
    item.content === b.items[index]?.content &&
    item.activeForm === b.items[index]?.activeForm &&
    item.status === b.items[index]?.status
  ));
}

// v56: the Task tools (TaskCreate/TaskUpdate) — the CLI's incremental replacement for
// TodoWrite as of CLI 2.1.142, where TodoWrite is disabled by default. Unlike
// TodoWrite (one call rewrites the whole `todos` array), the Task tools are
// INCREMENTAL: TaskCreate adds one item and its assigned id comes back only in the
// matching tool_result; TaskUpdate patches one item by taskId (status "deleted"
// removes it). We accumulate them into `todoTasks` (reducer state) and derive the
// SAME TodoProjection, so every existing todo surface works whichever tool the CLI
// emits. The events are named todo_item_* on purpose — the task_started/task_progress/
// task_completed vocabulary already belongs to the SDK's background-subagent telemetry
// (claudeTaskEvents), an unrelated concept, and reusing it would silently entangle the
// two. Normalizers never throw: an unreadable field yields fewer items, never an error
// or an invented value, exactly like normalizeTodoItems.
function normalizeTaskCreate(input) {
  if (!input || typeof input !== "object") return null;
  const subject = boundedDisplayText(input.subject ?? input.content, TODO_LIMITS.textChars);
  if (isBlank(subject)) return null;
  const activeForm = boundedDisplayText(input.activeForm ?? input.active_form, TODO_LIMITS.textChars);
  const status = TODO_STATUSES.has(input.status) ? input.status : "pending";
  return { subject, activeForm: isBlank(activeForm) ? "" : activeForm, status };
}

// A TaskUpdate patch, or null when it carries nothing usable. Reads the id
// defensively (taskId | id | task_id): the streamed tool_use input is the raw shape
// the model emitted, and Claude Code's key repair (id/task_id -> taskId) is not
// reflected in the stream. "deleted" is a real status here — it removes the item.
function normalizeTaskUpdate(input) {
  if (!input || typeof input !== "object") return null;
  const taskId = boundedIdentifier(input.taskId ?? input.id ?? input.task_id);
  if (!taskId) return null;
  const patch = { taskId };
  if (input.status === "deleted" || TODO_STATUSES.has(input.status)) patch.status = input.status;
  const subject = boundedDisplayText(input.subject ?? input.content, TODO_LIMITS.textChars);
  if (!isBlank(subject)) patch.subject = subject;
  const activeForm = boundedDisplayText(input.activeForm ?? input.active_form, TODO_LIMITS.textChars);
  if (!isBlank(activeForm)) patch.activeForm = activeForm;
  // A bare id with nothing to change carries no information.
  if (patch.status === undefined && patch.subject === undefined && patch.activeForm === undefined) return null;
  return patch;
}

// Best-effort extraction of the assigned task id from a TaskCreate tool_result, which
// carries `{ task: { id, subject } }`. The SDK may deliver that as an object, a JSON
// string, or an array of text blocks, so coerce defensively. Never throws; returns
// null when no id is readable — the create then stays keyed by its tool_use id until a
// later TaskUpdate links it (the reducer's implicit-create fallback).
function taskAssignmentId(content) {
  const text = coerceTaskResultText(content);
  if (!text) return null;
  // The SHIPPED CLI (2.1.x) returns prose, not the documented `{ task: { id } }` JSON:
  //   TaskCreate result -> "Task #<n> created successfully: <subject>"
  //   TaskUpdate  input  -> { taskId: "<n>", status: ... }
  // so the linking key is the small ordinal `<n>`. Match the create phrasing
  // specifically — "Updated task #<n> status" (a TaskUpdate result) deliberately does
  // NOT match, so we never mint a bogus assignment off an update's own result. The JSON
  // path is kept as a forward-compat fallback in case a later CLI honors the doc.
  const created = text.match(/\btask #(\d+) created\b/i);
  if (created) return boundedIdentifier(created[1]);
  try {
    const obj = JSON.parse(text);
    if (obj && typeof obj === "object") return boundedIdentifier(obj.task?.id ?? obj.taskId ?? obj.task_id);
  } catch {
    // not JSON — no id to recover
  }
  return null;
}

// A tool_result's content as a single string, or null. Handles the three shapes the SDK
// delivers: a raw string, an array of text blocks, or an already-structured object
// (stringified so the JSON path above can read it). Bounded so a huge result is skipped.
function coerceTaskResultText(content) {
  if (typeof content === "string") return content.length && content.length <= 100_000 ? content : null;
  if (Array.isArray(content)) {
    const text = content.map((block) => (typeof block === "string" ? block : typeof block?.text === "string" ? block.text : "")).join("");
    return text.length && text.length <= 100_000 ? text : null;
  }
  if (content && typeof content === "object") {
    try {
      const serialized = JSON.stringify(content);
      return serialized.length <= 100_000 ? serialized : null;
    } catch {
      return null;
    }
  }
  return null;
}

// v56: recompute the shared TodoProjection from the accumulated Task-tool list and
// dedupe exactly like todo_updated, so an identical projection never churns the client
// or the journal. An empty list (every item deleted) projects to null — the bar hides —
// which is correct because deletion here is EXPLICIT, unlike the "unreadable list"
// fail-safe the todo_updated fold guards against.
function foldTodoTasks(state, todoTasks) {
  const items = normalizeTodoItems(
    todoTasks.map((task) => ({ content: task.subject, activeForm: task.activeForm, status: task.status })),
  );
  const todo = items.length ? todoProjection(items) : null;
  const nextTodo = sameTodo(state.todo, todo) ? state.todo : todo;
  if (state.todoTasks === todoTasks && state.todo === nextTodo) return state;
  return { ...state, todoTasks, todo: nextTodo };
}

// v54: `!` command-mode normalizers. Bounds mirror the provider projection caps so a
// hand-written or replayed journal line can never inflate a projection.
export const MAX_BACKGROUND_COMMANDS = 100;
const BACKGROUND_COMMAND_STATUSES = new Set(["running", "finished", "stopped", "error", "interrupted"]);

function normalizeCommandRunMeta(meta) {
  if (!meta || typeof meta !== "object" || Array.isArray(meta)) return null;
  const command = boundedDisplayText(meta.command, PROVIDER_PROJECTION_LIMITS.commandChars);
  const cwd = boundedDisplayText(meta.cwd, PROVIDER_PROJECTION_LIMITS.pathChars);
  const logFile = boundedDisplayText(meta.logFile, PROVIDER_PROJECTION_LIMITS.pathChars);
  if (command === undefined || cwd === undefined || logFile === undefined) return null;
  return { command, cwd, logFile };
}

function normalizeBackgroundCommand(event) {
  const commandId = boundedIdentifier(event.commandId);
  const command = boundedDisplayText(event.command, PROVIDER_PROJECTION_LIMITS.commandChars);
  const cwd = boundedDisplayText(event.cwd, PROVIDER_PROJECTION_LIMITS.pathChars);
  const logFile = boundedDisplayText(event.logFile, PROVIDER_PROJECTION_LIMITS.pathChars);
  if (!commandId || command === undefined || cwd === undefined || logFile === undefined) return null;
  if (!BACKGROUND_COMMAND_STATUSES.has(event.status)) return null;
  return {
    commandId,
    command,
    cwd,
    logFile,
    status: event.status,
    exitCode: Number.isInteger(event.exitCode) ? event.exitCode : null,
    signal: typeof event.signal === "string" ? (boundedDisplayText(event.signal, PROVIDER_PROJECTION_LIMITS.labelChars) ?? null) : null,
    startedAt: nonNegativeFiniteNumber(event.startedAt) ?? 0,
    endedAt: nonNegativeFiniteNumber(event.endedAt) ?? null,
    outputTruncated: event.outputTruncated === true,
  };
}

// v118 (issue #173): spawned agent-CLI run normalizers. Same bounding discipline
// as the background commands above — a hand-written or forged journal line can
// never inflate the projection, and an unknown status/origin is dropped whole.
export const MAX_SPAWNED_RUNS = 50;
// v130 (S13.1-C, SYNC_DESIGN §6.1 C): how many removed queueIds the projection
// retains (oldest dropped). Bounds the cross-device "withdrawn queue item"
// evidence a reconnecting client reconciles against (lib/pending-input.mjs).
export const MAX_REMOVED_QUEUE_IDS = 50;
export const MAX_SPAWNED_RUN_KEYS = 2000;
export const SPAWNED_RUN_OUTPUT_CAP_CHARS = 64 * 1024;
const SPAWNED_RUN_STATUSES = new Set(["running", "finished", "error", "stopped", "interrupted"]);
const SPAWNED_RUN_ORIGINS = new Set(["spawned", "discovered"]);
const SPAWNED_RUN_MODES = new Set(["review", "build"]);

function normalizeSpawnedRun(event) {
  const runId = boundedIdentifier(event.runId);
  const provider = boundedIdentifier(event.provider);
  if (!runId || !provider) return null;
  if (!SPAWNED_RUN_STATUSES.has(event.status) || !SPAWNED_RUN_ORIGINS.has(event.origin)) return null;
  const optionalText = (value, max) => (typeof value === "string" && value ? boundedDisplayText(value, max) : null);
  return {
    runId,
    origin: event.origin,
    provider,
    title: optionalText(event.title, PROVIDER_PROJECTION_LIMITS.labelChars),
    prompt: optionalText(event.prompt, PROVIDER_PROJECTION_LIMITS.commandChars),
    mode: SPAWNED_RUN_MODES.has(event.mode) ? event.mode : null,
    model: optionalText(event.model, PROVIDER_PROJECTION_LIMITS.labelChars),
    cwd: optionalText(event.cwd, PROVIDER_PROJECTION_LIMITS.pathChars),
    logFile: optionalText(event.logFile, PROVIDER_PROJECTION_LIMITS.pathChars),
    nativeId: typeof event.nativeId === "string" ? boundedIdentifier(event.nativeId) : null,
    parentTurnId: typeof event.parentTurnId === "string" ? boundedIdentifier(event.parentTurnId) : null,
    toolId: typeof event.toolId === "string" ? boundedIdentifier(event.toolId) : null,
    status: event.status,
    exitCode: Number.isInteger(event.exitCode) ? event.exitCode : null,
    signal: typeof event.signal === "string" ? (boundedDisplayText(event.signal, PROVIDER_PROJECTION_LIMITS.labelChars) ?? null) : null,
    startedAt: nonNegativeFiniteNumber(event.startedAt) ?? 0,
    endedAt: nonNegativeFiniteNumber(event.endedAt) ?? null,
  };
}

const SPAWNED_RUN_COMPARED_FIELDS = [
  "origin", "provider", "title", "prompt", "mode", "model", "cwd", "logFile", "nativeId",
  "parentTurnId", "toolId", "status", "exitCode", "signal", "startedAt", "endedAt",
];

function sameSpawnedRun(a, b) {
  return SPAWNED_RUN_COMPARED_FIELDS.every((field) => a[field] === b[field]);
}

// v122 (issue #190): the pictures on a spawned run. Only a content-addressed
// tool-media URL is accepted — a forged journal line can never point the
// browser at an arbitrary URL or inflate the projection.
export const MAX_SPAWNED_RUN_MEDIA = 48;
const SPAWNED_RUN_MEDIA_URL = /^\/api\/tool-media\/[0-9a-f]{64}\.(?:png|jpg|jpeg|gif|webp|mp4)$/;
const SPAWNED_RUN_MEDIA_SOURCES = new Set(["input", "viewed"]);

function normalizeSpawnedRunMedia(item) {
  if (!item || typeof item !== "object" || item.type !== "media_ref") return null;
  if (typeof item.url !== "string" || !SPAWNED_RUN_MEDIA_URL.test(item.url)) return null;
  const mediaKind = item.mediaKind === "video" ? "video" : item.mediaKind === "image" ? "image" : null;
  if (!mediaKind || typeof item.mediaType !== "string" || !item.mediaType.startsWith(`${mediaKind}/`)) return null;
  return {
    type: "media_ref",
    mediaKind,
    mediaType: item.mediaType.slice(0, 40),
    url: item.url,
    bytes: nonNegativeFiniteNumber(item.bytes) ?? 0,
    source: SPAWNED_RUN_MEDIA_SOURCES.has(item.source) ? item.source : "viewed",
    label: typeof item.label === "string" && item.label ? (boundedDisplayText(item.label, PROVIDER_PROJECTION_LIMITS.labelChars) ?? null) : null,
  };
}

// Append the run's identities to the bounded seen-key list (returns the SAME
// array when nothing is new, so an unchanged fold stays referentially equal).
function withSpawnedRunKeys(keys, run) {
  const list = Array.isArray(keys) ? keys : [];
  const fresh = [run.runId, run.nativeId].filter((key) => typeof key === "string" && key && !list.includes(key));
  if (fresh.length === 0) return list;
  return [...list, ...fresh].slice(-MAX_SPAWNED_RUN_KEYS);
}

// Drop one row to make room: the oldest row that is NOT running (a live child's
// row must keep receiving its output and stay addressable by spawn_status), or
// the oldest row outright when every row is running.
function evictSpawnedRun(runs) {
  const index = runs.findIndex((run) => run.status !== "running");
  return runs.filter((_, i) => i !== (index === -1 ? 0 : index));
}

function sameBackgroundCommand(a, b) {
  return a.commandId === b.commandId
    && a.status === b.status
    && a.exitCode === b.exitCode
    && a.signal === b.signal
    && a.endedAt === b.endedAt
    && a.outputTruncated === b.outputTruncated
    && a.command === b.command
    && a.logFile === b.logFile;
}

function upsertReview(reviews, review) {
  const existingIndex = reviews.findIndex((item) => item.reviewId === review.reviewId);
  const next = existingIndex === -1
    ? [...reviews, review]
    : reviews.map((item, index) => (index === existingIndex ? { ...item, ...review } : item));
  return next.slice(-PROVIDER_PROJECTION_LIMITS.reviews);
}

function appendProviderNotice(notices, notice) {
  if (notices.some((item) => item.noticeId === notice.noticeId)) return notices;
  return [...notices, notice].slice(-PROVIDER_PROJECTION_LIMITS.providerNotices);
}

/**
 * Normalize SDK SlashCommand[] or init slash_commands string[] at the provider
 * boundary. Names/aliases are identifiers: overlong values are dropped rather
 * than truncated into a different command. Display prose is capped. Counts are
 * finite, duplicates are first-wins, and unknown fields never cross.
 */
export function normalizeClaudeCommands(commands) {
  if (!Array.isArray(commands)) return [];
  const normalized = [];
  const seen = new Set();
  for (const raw of commands) {
    if (normalized.length >= CLI_INVENTORY_LIMITS.commands) break;
    const source = typeof raw === "string" ? { name: raw } : raw;
    const name = identifier(source?.name, { stripLeadingSlash: true });
    if (!name || seen.has(name)) continue;
    seen.add(name);
    const command = { name };
    const description = boundedDisplayText(source?.description, CLI_INVENTORY_LIMITS.descriptionChars);
    const argumentHint = boundedDisplayText(source?.argumentHint, CLI_INVENTORY_LIMITS.argumentHintChars);
    if (description !== undefined) command.description = description;
    if (argumentHint !== undefined) command.argumentHint = argumentHint;
    if (Array.isArray(source?.aliases)) {
      const aliases = [];
      const aliasSeen = new Set([name]);
      for (const rawAlias of source.aliases) {
        if (aliases.length >= CLI_INVENTORY_LIMITS.aliasesPerCommand) break;
        const alias = identifier(rawAlias, { stripLeadingSlash: true });
        if (!alias || aliasSeen.has(alias)) continue;
        aliasSeen.add(alias);
        aliases.push(alias);
      }
      if (aliases.length) command.aliases = aliases;
    }
    normalized.push(command);
  }
  return normalized;
}

export function normalizeClaudeTools(tools) {
  if (!Array.isArray(tools)) return [];
  const normalized = [];
  const seen = new Set();
  for (const raw of tools) {
    if (normalized.length >= CLI_INVENTORY_LIMITS.tools) break;
    const name = identifier(raw);
    if (!name || seen.has(name)) continue;
    seen.add(name);
    normalized.push(name);
  }
  return normalized;
}

export function normalizeClaudeMcpServers(servers) {
  if (!Array.isArray(servers)) return [];
  const normalized = [];
  const seen = new Set();
  for (const raw of servers) {
    if (normalized.length >= CLI_INVENTORY_LIMITS.mcpServers) break;
    const name = identifier(raw?.name);
    if (!name || seen.has(name)) continue;
    seen.add(name);
    normalized.push({
      name,
      status: MCP_SERVER_STATUSES.has(raw?.status) ? raw.status : "unknown",
    });
  }
  return normalized;
}

function sameCliCommand(a, b) {
  return (
    a?.name === b?.name &&
    a?.description === b?.description &&
    a?.argumentHint === b?.argumentHint &&
    sameStringList(a?.aliases ?? [], b?.aliases ?? [])
  );
}

function sameCliInventory(a, b) {
  if (a === null || b === null) return a === b;
  if (
    a.commands.length !== b.commands.length ||
    a.tools.length !== b.tools.length ||
    a.mcpServers.length !== b.mcpServers.length
  ) return false;
  return (
    a.commands.every((command, index) => sameCliCommand(command, b.commands[index])) &&
    sameStringList(a.tools, b.tools) &&
    a.mcpServers.every((server, index) => (
      server.name === b.mcpServers[index]?.name &&
      server.status === b.mcpServers[index]?.status
    ))
  );
}

function normalizeProjectedCliCommands(commands) {
  return Array.isArray(commands) ? normalizeClaudeCommands(commands) : null;
}

function normalizeProjectedCliInventory(inventory, previous) {
  if (!inventory || typeof inventory !== "object" || Array.isArray(inventory)) return null;
  const commands = normalizeClaudeCommands(inventory.commands);
  // Init carries command names only. Preserve richer metadata learned from a
  // commands_changed push for names that remain in this same process snapshot.
  // cli_inventory_reset clears `previous` before a new process starts, so this
  // cannot leak metadata across CLI processes.
  const previousCommands = new Map((previous?.commands ?? []).map((command) => [command.name, command]));
  const mergedCommands = commands.map((command) => {
    const prior = previousCommands.get(command.name);
    return prior && command.description === undefined && command.argumentHint === undefined && command.aliases === undefined
      ? prior
      : command;
  });
  return {
    commands: mergedCommands,
    tools: normalizeClaudeTools(inventory.tools),
    mcpServers: normalizeClaudeMcpServers(inventory.mcpServers),
  };
}

const RATE_LIMIT_STATUSES = new Set(["allowed", "allowed_warning", "rejected"]);

// v127 (issue #195): Claude's Wrap-Up Allowance, as the adapter derives it from
// SDKRateLimitInfo's @internal `rateLimitGraceActive` (+ `overageStatus`).
const RATE_LIMIT_GRACE = new Set(["wrap_up", "wrap_up_then_credits"]);

// Drop the wrap-up fact at a turn boundary. The CLI keeps `rateLimitGraceActive`
// set while requests are refused, so without this a turn started AFTER the wall
// (which the allowance does not cover — "a new message sent after the wall is
// handled as usual") would inherit the previous turn's grace flag. Returns the
// same object when there is nothing to clear, so turn folds stay churn-free.
function clearRateLimitGrace(state) {
  if (!state.rateLimit || state.rateLimit.grace === undefined) return state;
  const rateLimit = { ...state.rateLimit };
  delete rateLimit.grace;
  return { ...state, rateLimit };
}

// SDK FastModeState (sdk.d.ts): 'off' | 'cooldown' | 'on'.
const FAST_MODE_STATES = new Set(["off", "cooldown", "on"]);

// Build a `fast_mode` AgentEvent from a raw SDK record's `fast_mode_state` /
// `fast_mode_disabled_reason` fields (system/init or result), or null when the
// record carries no recognizable state — an absent/malformed field must never
// be read as "off", since that would be an assumption this module's own rule
// forbids.
function claudeFastModeEvent(record, turnId) {
  if (!FAST_MODE_STATES.has(record.fast_mode_state)) return null;
  const event = { type: "fast_mode", turnId, state: record.fast_mode_state };
  if (typeof record.fast_mode_disabled_reason === "string" && record.fast_mode_disabled_reason) {
    event.disabledReason = record.fast_mode_disabled_reason;
  }
  return event;
}

// The journal-stamped `ts` of an event, or null when absent (a hand-built event
// in a test, or a pre-ts journal line). Shared by the `rate_limit` and
// `limit_hit` offer gates: this module is pure and is folded on both the server
// and the client, and the journal is re-folded from disk on every boot; using
// the journal-stamped time keeps the decision identical across all three. A
// direct unit-test event with no `ts` therefore makes no offer, which is
// correct: every real event is stamped by SessionJournal.append before it is
// ever reduced.
function eventTs(event) {
  return Number.isFinite(event?.ts) ? event.ts : null;
}

// Whether a rejected/reset-limit fact warrants the durable resume offer, and
// the `awaiting_choice` state for it — shared by the `rate_limit` and
// `limit_hit` cases so the gate lives in exactly one place.
//
// The offer is gated on the WAIT, not on which window was exhausted (v35): any
// provider that reports a rejection with a reset instant qualifies, as long as
// that reset is still ahead and at most RATE_LIMIT_RESUME_MAX_HORIZON_MS away.
// `stampedNow` is the journal-stamped `ts` of the event being folded (see
// eventTs). Returns null when no offer is warranted — either the window does
// not qualify, or an identical window is already prompting (dedupe by
// resetsAt, which is what keeps a duplicate push from re-prompting after a
// dismiss/fire).
function offerRateLimitResume(state, { resetsAt, limitType, stampedNow }) {
  if (stampedNow === null || !Number.isFinite(resetsAt) || resetsAt <= 0) return null;
  const horizon = resetsAt - stampedNow;
  if (!(horizon > 0 && horizon <= RATE_LIMIT_RESUME_MAX_HORIZON_MS)) return null;
  if (state.rateLimitResume?.resetsAt === resetsAt) return null;
  return {
    status: "awaiting_choice",
    resetsAt,
    resumeAt: resetsAt + RATE_LIMIT_RESUME_DELAY_MS,
    ...(limitType ? { limitType } : {}),
  };
}

function isOpenCurrentTurn(state, turnId) {
  return state.activeTurnId === turnId && state.turnsById[turnId]?.status !== "done";
}

function updateTurn(state, updater) {
  if (!state.activeTurnId) return state;
  const turnId = state.activeTurnId;
  return { ...state, turnsById: { ...state.turnsById, [turnId]: updater(state.turnsById[turnId]) } };
}

function updateTurnById(state, turnId, updater) {
  const turn = state.turnsById[turnId];
  if (!turn) return state;
  const updated = updater(turn);
  // An updater that declines the change (a duplicate fact on journal replay, a
  // missing parent block) returns the SAME turn. Propagate that identity instead
  // of rebuilding state around it: the browser folds these events with this same
  // reducer, and a fresh object there is a re-render of the whole transcript.
  if (updated === turn) return state;
  return { ...state, turnsById: { ...state.turnsById, [turnId]: updated } };
}

function samePermissionDenial(a, b) {
  return a.toolId === b.toolId
    && a.name === b.name
    && a.reason === b.reason
    && (a.reasonCode ?? null) === (b.reasonCode ?? null)
    && (a.error ?? null) === (b.error ?? null)
    && Boolean(a.subagent) === Boolean(b.subagent);
}

// Dedupe by tool_use_id. A later standalone SDKPermissionDeniedMessage may
// enrich an aggregate-only `unknown` entry with a reason/subagent bit, while a
// byte-identical replay remains a true no-op. Never carries provider prose.
function upsertPermissionDenial(denials, denial) {
  const index = denials.findIndex((item) => item.toolId === denial.toolId);
  if (index === -1) return [...denials, denial];
  const existing = denials[index];
  const enriched = {
    ...existing,
    name: denial.name || existing.name,
    reason: existing.reason === "unknown" ? denial.reason : existing.reason,
    // The richer entry wins the same way `reason` does: the result aggregate
    // carries no discriminator, so it must not blank one already recorded.
    ...(existing.reasonCode || denial.reasonCode
      ? { reasonCode: existing.reasonCode ?? denial.reasonCode }
      : {}),
    // issue #52: the standalone denial alone carries the abort `error` text; the
    // aggregate never does, so fill it in when the richer entry arrives later.
    ...(existing.error || denial.error
      ? { error: existing.error ?? denial.error }
      : {}),
    ...(existing.subagent || denial.subagent ? { subagent: true } : {}),
  };
  if (samePermissionDenial(existing, enriched)) return denials;
  return denials.map((item, itemIndex) => (itemIndex === index ? enriched : item));
}

// Only the closed discriminator vocabulary crosses as `reasonCode` — an
// identifier-shaped token, length-capped. Anything else (prose, an object, a
// sentence the SDK grew later) is dropped rather than forwarded: the adjacent
// decision_reason PROSE must never ride on a denial.
const PERMISSION_DENIAL_REASON_CODE = /^[A-Za-z][A-Za-z0-9_-]{0,39}$/;

function permissionDenialReasonCode(value) {
  return typeof value === "string" && PERMISSION_DENIAL_REASON_CODE.test(value) ? value : null;
}

// Kept in sync BY HAND with PermissionDenialReason in lib/protocol.ts and the
// adapter map (PERMISSION_DENIAL_REASONS). A value outside this set is a newer server (or a replayed
// journal from one) talking to this reducer: fold it to "unknown" rather than
// projecting a bucket the UI has no copy for.
const PERMISSION_DENIAL_REASON_VALUES = new Set([
  "classifier",
  "safety_check",
  "rule",
  "mode",
  "working_dir",
  "sandbox",
  "hook",
  "prompt_tool",
  "async_agent",
  "other",
  "unknown",
]);

// Adds `blockId` to the ordered `blocks` list the FIRST time it's seen, then
// only ever patches `blocksById` on every subsequent call — regardless of
// which event type introduces the block first (message_started/tool_start,
// but also a message_completed/tool_end that arrives with no prior "started"
// event). `build` receives the existing block (undefined if new).
function upsertBlock(turn, blockId, build) {
  const existing = turn.blocksById[blockId];
  const next = build(existing);
  if (existing !== undefined) {
    return { ...turn, blocksById: { ...turn.blocksById, [blockId]: next } };
  }
  return {
    ...turn,
    blocks: [...turn.blocks, blockId],
    blocksById: { ...turn.blocksById, [blockId]: next },
  };
}

// Codex app-server can finish a tool set after it has already announced the
// assistant's final answer. Completion-only items then create their cards at the
// tail of `blocks`, burying the report even though the provider did send it. On a
// successful Codex turn the last assistant message is the report (the app-server
// calls this phase `final_answer`; older models may omit the phase), so close the
// turn by moving that one existing block behind every late tool completion.
//
// Do this at turn_end rather than message_completed: only the terminal boundary
// proves that the full late-tool tail has arrived. The projection is the durable
// ordering source, so journal compaction also preserves the corrected order.
function putCodexFinalReportLast(turn) {
  let reportIndex = -1;
  for (let index = turn.blocks.length - 1; index >= 0; index -= 1) {
    if (turn.blocksById[turn.blocks[index]]?.kind === "message") {
      reportIndex = index;
      break;
    }
  }
  if (reportIndex < 0 || reportIndex === turn.blocks.length - 1) return turn;
  const reportId = turn.blocks[reportIndex];
  return {
    ...turn,
    blocks: [
      ...turn.blocks.slice(0, reportIndex),
      ...turn.blocks.slice(reportIndex + 1),
      reportId,
    ],
  };
}

// Folds one subagent record's normalized items into a parent tool block's
// nested `subagent` thread (a mini message/thinking/tool log rendered under the
// Task card). Keyed like the main projection: message items by
// `${messageId}:tN`, thinking items by `${messageId}:thN`, tool items by their
// tool_use id, and a tool_result item MERGES into the tool entry sharing that id
// (sets output/isError/done) — so a subagent tool call and its result render as
// one entry, exactly like the top-level tool cards.
// Pure and immutable: returns a new thread, never mutates `existing`.
function clearElapsedProgress(tool) {
  if (!Object.hasOwn(tool, "elapsedSeconds")) return tool;
  const next = { ...tool };
  delete next.elapsedSeconds;
  return next;
}

function foldSubagentItems(existing, items, usage) {
  const order = existing ? [...existing.order] : [];
  const entries = existing ? { ...existing.entries } : {};
  for (const item of items) {
    const prev = entries[item.key];
    if (prev === undefined) order.push(item.key);
    if (item.kind === "message") {
      entries[item.key] = { key: item.key, kind: "message", text: item.text };
    } else if (item.kind === "thinking") {
      entries[item.key] = { key: item.key, kind: "thinking", text: item.text };
    } else if (item.kind === "tool") {
      entries[item.key] = { ...(prev ?? {}), key: item.key, kind: "tool", name: item.name, input: item.input, done: prev?.done ?? false };
    } else if (item.kind === "tool_result") {
      const withoutProgress = clearElapsedProgress(prev ?? { key: item.key, kind: "tool" });
      entries[item.key] = {
        ...withoutProgress,
        output: item.output,
        isError: item.isError,
        done: true,
        ...(item.interrupted === true ? { interrupted: true } : {}),
      };
    }
  }
  // v40: usage is SET, never accumulated. The adapter already emits this run's
  // absolute running total, which is what makes journal compaction's single
  // aggregate subagent_message replay to the same numbers as the live stream.
  // A carried-forward previous total survives an event that has no usage of its
  // own, so an item-only record can never blank a run's readings.
  const carried = usage ?? existing?.usage;
  return { order, entries, ...(carried ? { usage: carried } : {}) };
}

function deriveSessionStatus(turn) {
  if (!turn || turn.status === "done") return SESSION_STATUS.READY;
  if (Object.keys(turn.pendingApprovals).length > 0) return SESSION_STATUS.WAITING;
  if (Object.keys(turn.pendingQuestions).length > 0) return SESSION_STATUS.WAITING;
  // lib/protocol.ts's SessionStatus has no "cancelling" value, so a turn
  // mid-cancellation is reported as ACTIVE at the session level — a coarser
  // signal than the turn projection itself carries. Callers that need to
  // distinguish "cancelling" should read turnsById[turnId].status directly
  // rather than session.status.
  return SESSION_STATUS.ACTIVE;
}

/**
 * reduce(state, event) -> nextState
 *
 * Folds one normalized AgentEvent into session state. Idempotent w.r.t. the
 * "never reopen a completed turn" invariant (§3.3, Appendix B #6): once a
 * turn's status is "done", ordinary further events carrying that turnId are a
 * no-op. The one deliberate exception is subagent_message: forwarded child
 * transcript records can arrive after their parent turn's result, and may amend
 * only the already-existing parent tool's nested thread without reopening it.
 *
 * Every turn's projection is retained in `turnsById` (see `currentTurn()`
 * for the one currently open, if any) — this is what "exact UI replay" of a
 * whole multi-turn conversation needs, not just the latest turn.
 */
export function reduce(state, event) {
  // Three passes, all pure: fold the event, expire the transient api_retry marker
  // if this event proved the provider moved on (v17/T4), then open or close the
  // current RUN (v38). Kept out of the switch so every content case doesn't have to
  // remember to do them.
  return syncTurnRun(clearResolvedApiRetry(reduceEvent(state, event), event), event);
}

// v38: a RUN is one contiguous stretch of the agent actually working — from when it
// picks the work up to when it hands control back. It is deliberately NOT the turn:
// a turn that stops to ask for an approval has handed control to the operator, and
// the clock and token count for that stretch are finished. Answering starts a new
// run, with its own clock, its own count, and its own spinner word.
//
// Derived here as a post-pass rather than in each case, because the condition is a
// property of the resulting STATE (is a turn open, and is anything pending on the
// operator?) rather than of any one event. That means approval_request,
// question_request, their resolutions, expiry, cancellation and turn_end all get
// correct boundaries without six separate call sites agreeing with each other.
function turnIsWorking(turn) {
  if (!turn) return false;
  // "cancelling" still counts: the engine is tearing the turn down, which is work
  // the operator is waiting on and wants a clock for.
  if (turn.status !== "running" && turn.status !== "cancelling") return false;
  if (Object.keys(turn.pendingApprovals).length > 0) return false;
  if (Object.keys(turn.pendingQuestions).length > 0) return false;
  return true;
}

function syncTurnRun(state, event) {
  const turnId = event.turnId ?? state.activeTurnId;
  if (turnId == null) return state;
  const turn = state.turnsById[turnId];
  if (!turn) return state;

  // Every boundary is stamped with the journal's clock, never a local one, so the
  // elapsed reading is identical on the server, on every attached client, and on
  // replay. An unstamped event (a hand-built one in a test, a pre-v38 journal line)
  // cannot time anything, so it leaves the run bookkeeping exactly as it found it.
  const ts = nonNegativeFiniteNumber(event.ts);
  if (ts == null) return state;

  const working = turnIsWorking(turn);
  if (working === (turn.run != null)) return state;

  if (working) {
    const run = {
      index: turn.runCount,
      startedAt: ts,
      // Token counts are cumulative for the TURN, so a run's own usage is the
      // difference from here. Recorded at the boundary so it survives a reload.
      tokensStart: turn.liveTokens ?? 0,
    };
    return {
      ...state,
      turnsById: { ...state.turnsById, [turnId]: { ...turn, run, runCount: turn.runCount + 1 } },
    };
  }

  // Closing: bank the elapsed so the session total stays right across any number of
  // runs. max(0) because a clock that stepped backwards between two journal stamps
  // must not subtract from the total.
  const elapsed = Math.max(0, ts - turn.run.startedAt);
  return {
    ...state,
    turnsById: { ...state.turnsById, [turnId]: { ...turn, run: null, activeMs: turn.activeMs + elapsed } },
  };
}

// v117 (issue #171): one background task's projected lifecycle. A background
// Agent run returns its tool result the moment it is LAUNCHED, so the launcher
// block's `done` cannot answer "is it still running?" — these records are that
// signal, joined back to the launcher by toolUseId. Bounded like every other
// session-level list so a runaway or hostile stream cannot inflate the projection
// or the journal.
export const BACKGROUND_TASK_LIMITS = Object.freeze({
  tasks: 200,
  idChars: PROVIDER_PROJECTION_LIMITS.idChars,
  labelChars: PROVIDER_PROJECTION_LIMITS.labelChars,
});

// A task record's status is either a terminal outcome or non-terminal (running).
// `task_progress`'s pending/paused/running all read as running: none of them is a
// completion, and the derivation must never tick a run it cannot prove finished.
function normalizeTaskStatus(value) {
  if (TASK_TERMINAL_STATUSES.has(value)) return value;
  if (TASK_PROGRESS_STATUSES.has(value)) return "running";
  return null;
}

function taskIdentifier(value) {
  if (typeof value !== "string" || value.length === 0) return null;
  return boundedDisplayText(value, BACKGROUND_TASK_LIMITS.idChars);
}

// Upsert one task by taskId (the SDK's durable correlation key). A terminal status
// is STICKY: the SDK explicitly allows the authoritative level to precede either
// edge, and whole-journal replay can reorder nothing but a late duplicate must
// still not reopen a task that already reported completed/failed/stopped/killed.
function foldBackgroundTask(state, taskId, patch) {
  const tasks = state.backgroundTasks;
  const index = tasks.findIndex((task) => task.taskId === taskId);
  const existing = index === -1 ? undefined : tasks[index];
  const base = existing ?? { taskId, toolUseId: null, status: "running", live: false };
  const next = { ...base, ...patch, taskId };
  for (const key of Object.keys(next)) {
    if (next[key] === undefined) delete next[key];
  }
  if (next.status === undefined) next.status = "running";
  if (existing && TASK_TERMINAL_STATUSES.has(existing.status)) next.status = existing.status;
  const list = index === -1 ? [...tasks, next] : tasks.slice();
  if (index === -1) {
    if (list.length > BACKGROUND_TASK_LIMITS.tasks) list.splice(0, list.length - BACKGROUND_TASK_LIMITS.tasks);
  } else {
    list[index] = next;
  }
  return { ...state, backgroundTasks: list };
}

// Replace the live set wholesale (the SDK's level signal). A terminal task is
// never marked live; a level id we have never seen an edge for still gets a home
// so a later task_started can attach its toolUseId without losing the live fact.
function applyBackgroundTaskLevel(state, taskIds) {
  const live = new Set(taskIds);
  const seen = new Set();
  const next = [];
  let changed = false;
  for (const task of state.backgroundTasks) {
    seen.add(task.taskId);
    const isLive = live.has(task.taskId) && !TASK_TERMINAL_STATUSES.has(task.status);
    if (isLive === task.live) {
      next.push(task);
      continue;
    }
    changed = true;
    next.push({ ...task, live: isLive });
  }
  for (const taskId of taskIds) {
    if (seen.has(taskId)) continue;
    changed = true;
    next.push({ taskId, toolUseId: null, status: "running", live: true });
  }
  if (!changed) return state;
  return { ...state, backgroundTasks: next.slice(-BACKGROUND_TASK_LIMITS.tasks) };
}

function reduceEvent(state, event) {
  switch (event.type) {
    case "native_session_id": {
      // v17/T1: the same event now optionally carries the serving CLI's advertised
      // capability set and version. Absence NEVER clears what we already learned —
      // journal compaction re-synthesizes a bare native_session_id
      // (projectionToMinimalEvents), and a replay of that must not erase live facts.
      const capabilities = Array.isArray(event.cliCapabilities) ? event.cliCapabilities.filter((c) => typeof c === "string") : null;
      const version = typeof event.cliVersion === "string" && event.cliVersion ? event.cliVersion : null;
      const inventory = normalizeProjectedCliInventory(event.cliInventory, state.cliInventory);
      const idUnchanged = state.nativeSessionId === event.nativeSessionId;
      const capabilitiesUnchanged = capabilities === null || sameStringList(state.cliCapabilities, capabilities);
      const versionUnchanged = version === null || state.cliVersion === version;
      const inventoryUnchanged = inventory === null || sameCliInventory(state.cliInventory, inventory);
      if (idUnchanged && capabilitiesUnchanged && versionUnchanged && inventoryUnchanged) return state;
      return {
        ...state,
        nativeSessionId: event.nativeSessionId,
        ...(capabilities === null ? {} : { cliCapabilities: capabilities }),
        ...(version === null ? {} : { cliVersion: version }),
        ...(inventory === null ? {} : { cliInventory: inventory }),
      };
    }

    case "cli_inventory_reset":
      return state.cliInventory === null ? state : { ...state, cliInventory: null };

    case "cli_commands_changed": {
      const commands = normalizeProjectedCliCommands(event.commands);
      if (commands === null) return state;
      const inventory = {
        commands,
        tools: state.cliInventory?.tools ?? [],
        mcpServers: state.cliInventory?.mcpServers ?? [],
      };
      return sameCliInventory(state.cliInventory, inventory) ? state : { ...state, cliInventory: inventory };
    }

    case "api_retry": {
      // v17/T4: HTTP-level retry INSIDE the current turn. Visibility only — it
      // never touches status/outcome, and it must never be read as licence to
      // re-send a turn (the never-auto-retry-a-turn invariant). Ignored outside an
      // open turn, like every other turn-scoped event.
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) => ({
        ...turn,
        apiRetry: {
          attempt: event.attempt,
          maxRetries: event.maxRetries ?? null,
          delayMs: event.delayMs ?? null,
          errorStatus: event.errorStatus ?? null,
          error: event.error ?? null,
        },
      }));
    }

    case "rate_limit": {
      // v17/T4: session-level, last-write-wins. Deduped so a repeated identical
      // push doesn't churn the projection (and therefore the client re-render).
      const next = {
        status: event.status,
        limitType: event.limitType ?? null,
        utilization: event.utilization ?? null,
        resetsAt: event.resetsAt ?? null,
      };
      // v127 (issue #195): the Wrap-Up Allowance, last-write-wins with the rest —
      // a push without it (the grace window closed) clears it. Key present only
      // while set, so every pre-v127 projection stays byte-identical. Also cleared
      // at turn_started/turn_end (see clearRateLimitGrace): wrap-up is a fact
      // about the response in flight, never a standing session state.
      if (RATE_LIMIT_GRACE.has(event.grace)) next.grace = event.grace;
      const prev = state.rateLimit;
      const unchanged = prev && prev.status === next.status && prev.limitType === next.limitType && prev.utilization === next.utilization && prev.resetsAt === next.resetsAt && prev.grace === next.grace;
      let rateLimitResume = state.rateLimitResume ?? null;
      // The offer is gated on the WAIT, not on which window was exhausted, and
      // is provider-neutral: any provider that reports a rejection with a reset
      // instant qualifies (Claude via the SDK's rate_limit_event, Codex via the
      // usage-limit prose parsed at its engine boundary, Claude's per-session
      // limit via the issue #102 `limit_hit` event).
      const offered = next.status === "rejected"
        ? offerRateLimitResume(state, { resetsAt: next.resetsAt, limitType: next.limitType, stampedNow: eventTs(event) })
        : null;
      if (offered) rateLimitResume = offered;
      if (unchanged && rateLimitResume === state.rateLimitResume) return state;
      return { ...state, rateLimit: next, rateLimitResume };
    }

    case "fast_mode": {
      // v95: session-level, last-write-wins, exactly like rate_limit above.
      // Also reached via a synthetic emission (turnId: null) from
      // engines/claude-persistent.mjs when a live model switch force-clears a
      // stale "on" — that is a truthful report of an applyFlagSettings(false)
      // the engine already issued, not an assumption made here.
      const disabledReason = event.disabledReason ?? null;
      if (state.fastModeState === event.state && state.fastModeDisabledReason === disabledReason) return state;
      return { ...state, fastModeState: event.state, fastModeDisabledReason: disabledReason };
    }

    case "limit_hit": {
      // v88 (issue #102): a Claude SESSION-limit turn error ("You've hit your
      // session limit · resets 12:50am (Europe/Madrid)") — the per-session
      // window, which the SDK does NOT push as a structured rate_limit_event.
      // The classifier (lib/claude-session-limit.mjs, server-side only) parses
      // the reset from the prose; this fold turns that parsed instant into the
      // SAME durable resume choice as a rejected subscription/usage limit, so
      // scheduling, dismissal, durability, and firing need no second machine.
      // turnId is null for the same reason rate_limit's is (see above): the
      // schedule must survive whole-turn journal compaction.
      //
      // Deliberately does NOT touch `state.rateLimit` — that field is
      // subscription-telemetry; the session limit is a per-session fact and the
      // failed turn's own `error` already renders it.
      const offered = offerRateLimitResume(state, {
        resetsAt: event.resetAt,
        limitType: event.limitType,
        stampedNow: eventTs(event),
      });
      if (!offered) return state;
      return { ...state, rateLimitResume: offered };
    }

    case "rate_limit_resume_scheduled": {
      const current = state.rateLimitResume;
      if (
        !current || current.resetsAt !== event.resetsAt || current.status !== "awaiting_choice" ||
        !Number.isFinite(event.resumeAt) || event.resumeAt !== event.resetsAt + RATE_LIMIT_RESUME_DELAY_MS
      ) return state;
      return {
        ...state,
        rateLimitResume: {
          status: "scheduled",
          resetsAt: event.resetsAt,
          resumeAt: event.resumeAt,
          ...(current.limitType ? { limitType: current.limitType } : {}),
        },
      };
    }

    case "rate_limit_resume_dismissed": {
      const current = state.rateLimitResume;
      if (!current || current.resetsAt !== event.resetsAt || current.status === "fired") return state;
      return { ...state, rateLimitResume: { ...current, status: "dismissed" } };
    }

    case "rate_limit_resume_fired": {
      const current = state.rateLimitResume;
      // v88: `awaiting_choice` is also accepted — the operator's "Resume now"
      // fires the same durable continuation immediately, without a timer.
      if (
        !current || current.resetsAt !== event.resetsAt ||
        (current.status !== "scheduled" && current.status !== "awaiting_choice")
      ) return state;
      return { ...state, rateLimitResume: { ...current, status: "fired" } };
    }

    case "todo_updated": {
      // v39: session-level, last-write-wins. Deliberately NOT gated on an open turn
      // (unlike plan_updated): the list is the agent's standing intent and must
      // survive the turn that wrote it, and the persistent engine can stream a
      // TodoWrite from a continuation turn or between turns.
      //
      // An unreadable or entirely-malformed list is IGNORED rather than folded as an
      // empty one — the same fail-safe as background_tasks_changed: "we could not
      // read it" must never render as "the agent has nothing left to do". The last
      // good list therefore stands until a readable write replaces it wholesale.
      const items = normalizeTodoItems(event.items);
      if (items.length === 0) return state;
      const todo = todoProjection(items);
      // Deduped like rateLimit/mcpHealth: a repeated identical write (the model
      // re-sending the same list) must not churn the projection or the client render.
      return sameTodo(state.todo, todo) ? state : { ...state, todo };
    }

    case "todo_item_created": {
      // v56: one TaskCreate item, keyed by its tool_use id until todo_item_id_assigned
      // swaps in the real task id. Bounded like the TodoWrite list so a runaway or
      // hostile stream cannot inflate the projection or the journal.
      if (state.todoTasks.length >= TODO_LIMITS.items) return state;
      const subject = boundedDisplayText(event.subject, TODO_LIMITS.textChars);
      if (isBlank(subject) || typeof event.toolId !== "string" || !event.toolId) return state;
      const activeForm = boundedDisplayText(event.activeForm, TODO_LIMITS.textChars);
      const entry = {
        id: event.toolId,
        subject,
        activeForm: isBlank(activeForm) ? "" : activeForm,
        status: TODO_STATUSES.has(event.status) ? event.status : "pending",
      };
      return foldTodoTasks(state, [...state.todoTasks, entry]);
    }

    case "todo_item_id_assigned": {
      // The TaskCreate tool_result assigns the real id; relabel the pending entry so a
      // later TaskUpdate (which references that real id) matches it. A no-op when no
      // pending entry carries this tool_use id (e.g. a false parse on an unrelated
      // tool_result) — safe, because only real creates are keyed by a tool_use id.
      const taskId = boundedIdentifier(event.taskId);
      if (!taskId) return state;
      let changed = false;
      const next = state.todoTasks.map((task) => {
        if (task.id !== event.toolId || task.id === taskId) return task;
        changed = true;
        return { ...task, id: taskId };
      });
      return changed ? foldTodoTasks(state, next) : state;
    }

    case "todo_item_updated": {
      // v56: patch one item by id, or remove it (status "deleted"). An update for an id
      // we never saw created — the create's id-assignment parse missed, or a resumed
      // transcript begins mid-list — becomes an IMPLICIT create when it carries enough
      // to render (a subject and not a deletion), so the list is never silently short.
      const taskId = boundedIdentifier(event.taskId);
      if (!taskId) return state;
      let found = false;
      const next = state.todoTasks.flatMap((task) => {
        if (task.id !== taskId) return [task];
        found = true;
        if (event.status === "deleted") return [];
        const subject = boundedDisplayText(event.subject, TODO_LIMITS.textChars);
        const activeForm = boundedDisplayText(event.activeForm, TODO_LIMITS.textChars);
        return [{
          ...task,
          ...(TODO_STATUSES.has(event.status) ? { status: event.status } : {}),
          ...(isBlank(subject) ? {} : { subject }),
          ...(isBlank(activeForm) ? {} : { activeForm }),
        }];
      });
      if (found) return foldTodoTasks(state, next);
      const subject = boundedDisplayText(event.subject, TODO_LIMITS.textChars);
      if (event.status === "deleted" || isBlank(subject) || state.todoTasks.length >= TODO_LIMITS.items) return state;
      const activeForm = boundedDisplayText(event.activeForm, TODO_LIMITS.textChars);
      return foldTodoTasks(state, [...state.todoTasks, {
        id: taskId,
        subject,
        activeForm: isBlank(activeForm) ? "" : activeForm,
        status: TODO_STATUSES.has(event.status) ? event.status : "pending",
      }]);
    }

    case "plan_updated": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const explanation = event.explanation == null
        ? null
        : boundedDisplayText(event.explanation, PROVIDER_PROJECTION_LIMITS.proseChars) ?? null;
      const plan = { explanation, steps: normalizePlanSteps(event.steps) };
      return updateTurn(state, (turn) => ({
        ...turn,
        initialPlan: turn.initialPlan ?? plan,
        plan,
      }));
    }

    case "diff_updated": {
      if (!isOpenCurrentTurn(state, event.turnId) || typeof event.unifiedDiff !== "string") return state;
      const unifiedDiff = boundedDisplayText(event.unifiedDiff, PROVIDER_PROJECTION_LIMITS.diffChars);
      return updateTurn(state, (turn) => ({ ...turn, diff: { unifiedDiff } }));
    }

    case "model_rerouted": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const fromModel = boundedIdentifier(event.fromModel);
      const toModel = boundedIdentifier(event.toModel);
      const reason = boundedDisplayText(event.reason, PROVIDER_PROJECTION_LIMITS.proseChars);
      if (!fromModel || !toModel || !reason) return state;
      return updateTurn(state, (turn) => {
        const reroute = { fromModel, toModel, reason };
        const last = turn.modelReroutes.at(-1);
        if (last?.fromModel === fromModel && last?.toModel === toModel && last?.reason === reason) return turn;
        return {
          ...turn,
          modelReroutes: [...turn.modelReroutes, reroute].slice(-PROVIDER_PROJECTION_LIMITS.modelReroutes),
        };
      });
    }

    case "model_fallback": {
      // issue #179: the CLI switched a turn to its configured fallback model.
      // Folds three facts: the turn's own record (turn.modelFallbacks), the
      // session's last fallback (lastModelFallback — what the model reading keys
      // on), and a dismissable warning notice carrying the CLI's text verbatim
      // behind a class-aware lead-in (the existing provider-notice machinery).
      const message = boundedDisplayText(event.message, PROVIDER_PROJECTION_LIMITS.proseChars);
      if (!message) return state;
      const fromModel = boundedIdentifier(event.fromModel);
      const toModel = boundedIdentifier(event.toModel);
      const trigger = boundedDisplayText(event.trigger, PROVIDER_PROJECTION_LIMITS.labelChars);
      const fallbackId = boundedIdentifier(event.fallbackId);
      const fallback = {
        message,
        ...(fallbackId ? { fallbackId } : {}),
        ...(fromModel ? { fromModel } : {}),
        ...(toModel ? { toModel } : {}),
        ...(trigger ? { trigger } : {}),
      };
      const turnScoped = event.turnId != null;
      if (turnScoped && !isOpenCurrentTurn(state, event.turnId)) return state;
      const existing = turnScoped ? (state.turnsById[event.turnId].modelFallbacks ?? []) : [];
      if (fallbackId && existing.some((item) => item.fallbackId === fallbackId)) return state;
      // Deterministic identity: the CLI's uuid when present, else the ordinal of
      // this fallback within its turn — a replay folds the same events in the
      // same order, so it reproduces the same dismiss key. (A turn-less fallback
      // has no ordinal scope; the CLI only emits them mid-request, so that path
      // is defensive and a uuid-less repeat collapses onto one notice.)
      const noticeId = `model_fallback:${fallbackId ?? (turnScoped ? existing.length : "session")}`;
      const dismissKey = providerNoticeDismissKey(event.turnId, noticeId);
      const notice = { noticeId, level: "warning", code: modelFallbackLeadIn(trigger), message, dismissKey };
      const dismissed = isNoticeDismissed(state, dismissKey);
      const next = { ...state, lastModelFallback: { turnId: event.turnId ?? null, ...fallback } };
      if (!turnScoped) {
        if (dismissed) return next;
        return { ...next, providerNotices: appendProviderNotice(state.providerNotices, notice) };
      }
      return updateTurn(next, (turn) => ({
        ...turn,
        modelFallbacks: [...(turn.modelFallbacks ?? []), fallback].slice(-PROVIDER_PROJECTION_LIMITS.modelFallbacks),
        providerNotices: dismissed ? turn.providerNotices : appendProviderNotice(turn.providerNotices, notice),
      }));
    }

    case "review_started": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const reviewId = boundedIdentifier(event.reviewId);
      if (!reviewId) return state;
      const target = boundedDisplayText(event.target, PROVIDER_PROJECTION_LIMITS.proseChars);
      const review = { reviewId, status: "started", ...(target === undefined ? {} : { target }) };
      return updateTurn(state, (turn) => ({ ...turn, reviews: upsertReview(turn.reviews, review) }));
    }

    case "review_completed": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const reviewId = boundedIdentifier(event.reviewId);
      if (!reviewId || !REVIEW_COMPLETION_STATUSES.has(event.status)) return state;
      const result = boundedDisplayText(event.result, PROVIDER_PROJECTION_LIMITS.proseChars);
      const review = { reviewId, status: event.status, ...(result === undefined ? {} : { result }) };
      return updateTurn(state, (turn) => ({ ...turn, reviews: upsertReview(turn.reviews, review) }));
    }

    case "mcp_health_updated": {
      const name = boundedIdentifier(event.name);
      if (!name) return state;
      const status = MCP_HEALTH_STATUSES.has(event.status) ? event.status : "unknown";
      const error = boundedDisplayText(event.error, PROVIDER_PROJECTION_LIMITS.proseChars);
      const failureReason = boundedDisplayText(event.failureReason, PROVIDER_PROJECTION_LIMITS.labelChars);
      const health = {
        name,
        status,
        ...(error === undefined ? {} : { error }),
        ...(failureReason === undefined ? {} : { failureReason }),
      };
      const previous = state.mcpHealth[name];
      if (
        previous?.status === health.status &&
        previous?.error === health.error &&
        previous?.failureReason === health.failureReason
      ) return state;
      if (!previous && Object.keys(state.mcpHealth).length >= PROVIDER_PROJECTION_LIMITS.mcpServers) return state;
      return { ...state, mcpHealth: { ...state.mcpHealth, [name]: health } };
    }

    case "context_compacted": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const itemId = boundedIdentifier(event.itemId);
      if (!itemId) return state;
      const dismissKey = compactionDismissKey(event.turnId, itemId);
      if (isNoticeDismissed(state, dismissKey)) return state;
      return updateTurn(state, (turn) => {
        if (turn.compactions.some((item) => item.itemId === itemId)) return turn;
        return {
          ...turn,
          compactions: [...turn.compactions, { itemId, dismissKey }].slice(-PROVIDER_PROJECTION_LIMITS.compactions),
        };
      });
    }

    // v117 (issue #171): SDK background-task lifecycle. Session-level (turnId is
    // always null) because the records arrive between logical turns, and never
    // gated on the open turn. The join back to a launcher is `toolUseId`.
    case "task_started": {
      const taskId = taskIdentifier(event.taskId);
      if (!taskId) return state;
      const toolUseId = boundedIdentifier(event.toolUseId);
      const taskType = boundedDisplayText(event.taskType, BACKGROUND_TASK_LIMITS.labelChars);
      const subagentType = boundedDisplayText(event.subagentType, BACKGROUND_TASK_LIMITS.labelChars);
      return foldBackgroundTask(state, taskId, {
        status: "running",
        ...(toolUseId ? { toolUseId } : {}),
        ...(taskType === undefined ? {} : { taskType }),
        ...(subagentType === undefined ? {} : { subagentType }),
      });
    }

    case "task_progress": {
      const taskId = taskIdentifier(event.taskId);
      if (!taskId) return state;
      const toolUseId = boundedIdentifier(event.toolUseId);
      const lastToolName = boundedDisplayText(event.lastToolName, BACKGROUND_TASK_LIMITS.labelChars);
      const totalTokens = nonNegativeFiniteNumber(event.totalTokens);
      const toolUses = nonNegativeFiniteNumber(event.toolUses);
      const durationMs = nonNegativeFiniteNumber(event.durationMs);
      return foldBackgroundTask(state, taskId, {
        status: normalizeTaskStatus(event.status) ?? "running",
        ...(toolUseId ? { toolUseId } : {}),
        ...(lastToolName === undefined ? {} : { lastToolName }),
        ...(totalTokens === undefined ? {} : { totalTokens }),
        ...(toolUses === undefined ? {} : { toolUses }),
        ...(durationMs === undefined ? {} : { durationMs }),
      });
    }

    case "task_completed": {
      const taskId = taskIdentifier(event.taskId);
      if (!taskId) return state;
      const status = normalizeTaskStatus(event.status);
      if (!status || !TASK_TERMINAL_STATUSES.has(status)) return state;
      const toolUseId = boundedIdentifier(event.toolUseId);
      const totalTokens = nonNegativeFiniteNumber(event.totalTokens);
      const toolUses = nonNegativeFiniteNumber(event.toolUses);
      const durationMs = nonNegativeFiniteNumber(event.durationMs);
      return foldBackgroundTask(state, taskId, {
        status,
        live: false,
        ...(toolUseId ? { toolUseId } : {}),
        ...(totalTokens === undefined ? {} : { totalTokens }),
        ...(toolUses === undefined ? {} : { toolUses }),
        ...(durationMs === undefined ? {} : { durationMs }),
      });
    }

    case "background_tasks_changed": {
      // A malformed payload yields [] — but the adapter fails safe and emits
      // NOTHING on a malformed level, so a real empty array here is the
      // "everything finished" signal and must clear liveness.
      const taskIds = Array.isArray(event.taskIds)
        ? event.taskIds.map(taskIdentifier).filter((id) => id !== null)
        : [];
      return applyBackgroundTaskLevel(state, taskIds);
    }

    case "provider_notice": {
      const noticeId = boundedIdentifier(event.noticeId);
      const message = boundedDisplayText(event.message, PROVIDER_PROJECTION_LIMITS.proseChars);
      if (!noticeId || !message) return state;
      const notice = {
        noticeId,
        level: PROVIDER_NOTICE_LEVELS.has(event.level) ? event.level : "warning",
        message,
      };
      const code = boundedDisplayText(event.code, PROVIDER_PROJECTION_LIMITS.labelChars);
      if (code !== undefined) notice.code = code;
      const dismissKey = providerNoticeDismissKey(event.turnId, noticeId);
      notice.dismissKey = dismissKey;
      if (isNoticeDismissed(state, dismissKey)) return state;
      if (event.turnId == null) {
        const providerNotices = appendProviderNotice(state.providerNotices, notice);
        return providerNotices === state.providerNotices ? state : { ...state, providerNotices };
      }
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) => {
        const providerNotices = appendProviderNotice(turn.providerNotices, notice);
        return providerNotices === turn.providerNotices ? turn : { ...turn, providerNotices };
      });
    }

    case "permission_denied": {
      const reasonCode = permissionDenialReasonCode(event.reasonCode);
      // issue #52: `error` is the abort denial's bounded rejection text. The
      // reducer re-applies the prose cap so journal replay can never inflate it.
      const error = boundedDisplayText(event.error, PROVIDER_PROJECTION_LIMITS.proseChars);
      const denial = {
        toolId: event.toolId,
        name: event.name,
        reason: PERMISSION_DENIAL_REASON_VALUES.has(event.reason) ? event.reason : "unknown",
        ...(reasonCode ? { reasonCode } : {}),
        ...(error ? { error } : {}),
        ...(event.subagent === true ? { subagent: true } : {}),
      };
      if (event.turnId == null) {
        const permissionDenials = upsertPermissionDenial(state.unattributedPermissionDenials, denial);
        if (permissionDenials === state.unattributedPermissionDenials) return state;
        return { ...state, unattributedPermissionDenials: permissionDenials };
      }
      // Attributed to a turn BY ID, not to the open current turn. A denial is a
      // closed provider fact about a call that turn made, and the SDK can forward
      // a subagent's refusal after its parent's result — routing that to the live
      // turn (or dropping it) put the card under a report it had nothing to do
      // with. Same deliberate exception as subagent_message (T7): it patches a
      // completed turn's denial list and touches neither status/outcome nor the
      // session's active turn. upsertPermissionDenial keeps replay idempotent.
      //
      // A turn this projection no longer holds (compacted away, or a window that
      // never loaded it) falls back to the unattributed bucket: an attributed
      // denial must never be LESS visible than an unattributed one.
      if (!Object.hasOwn(state.turnsById, event.turnId)) {
        const permissionDenials = upsertPermissionDenial(state.unattributedPermissionDenials, denial);
        if (permissionDenials === state.unattributedPermissionDenials) return state;
        return { ...state, unattributedPermissionDenials: permissionDenials };
      }
      return updateTurnById(state, event.turnId, (turn) => {
        const permissionDenials = upsertPermissionDenial(turn.permissionDenials, denial);
        return permissionDenials === turn.permissionDenials ? turn : { ...turn, permissionDenials };
      });
    }

    case "turn_started": {
      if (Object.hasOwn(state.turnsById, event.turnId)) return state; // duplicate turn_started, ignore
      if (state.activeTurnId && state.turnsById[state.activeTurnId]?.status !== "done") return state; // one in-flight turn, ever
      const turn = newTurnProjection(
        event.turnId,
        event.idempotencyKey,
        event.continuation,
        // The journal stamps `ts` before this folds (persist-before-broadcast), and
        // broadcasts/replays the same stamped event, so every folder sees it. Guard
        // anyway: a hand-built event in a test or an old journal line has no ts.
        nonNegativeFiniteNumber(event.ts) ?? null,
        normalizeCommandRunMeta(event.commandRun),
      );
      return {
        ...clearRateLimitGrace(state),
        status: SESSION_STATUS.ACTIVE,
        activeTurnId: event.turnId,
        turnOrder: [...state.turnOrder, event.turnId],
        turnsById: { ...state.turnsById, [event.turnId]: turn },
      };
    }

    case "user_message_accepted": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      // v108: the engine-facing prompt when it differs from the display text (a
      // takeover journals the compact notice while the agent gets the full brief; a
      // delegate mention rewrites the instruction the same way). Bounded like a
      // command payload — a brief is prose, but an operator can paste a long one.
      const engineText = boundedDisplayText(event.engineText, PROVIDER_PROJECTION_LIMITS.commandChars);
      return updateTurn(state, (turn) =>
        upsertBlock(turn, `user:${event.turnId}`, () => ({
          blockId: `user:${event.turnId}`,
          kind: "user_message",
          text: event.text,
          ...(engineText !== undefined && engineText !== event.text ? { engineText } : {}),
          // #89: journal-stamped send-time for the small bubble label.
          ...blockEventTs(event),
          // v15: attachment descriptors (name + mediaType), when the operator
          // attached files/images. Omitted otherwise so legacy blocks are unchanged.
          ...(Array.isArray(event.attachments) && event.attachments.length ? { attachments: event.attachments } : {}),
        })),
      );
    }

    case "message_started": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.blockId, (existing) => existing ?? { blockId: event.blockId, kind: "message", text: "", done: false, ...blockEventTs(event) }),
      );
    }

    case "message_delta": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.blockId, (existing) => ({
          ...(existing ?? { blockId: event.blockId, kind: "message", done: false }),
          text: (existing?.text ?? "") + event.text,
        })),
      );
    }

    case "message_completed": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.blockId, () => ({
          blockId: event.blockId,
          kind: "message",
          text: event.text,
          done: true,
          // #89: journal-stamped completion instant; the UI shows it as the
          // agent message's send-time.
          ...blockEventTs(event),
          // The provider schema is the literal `aborted?: true`. Normalize at
          // the reducer boundary too so malformed/legacy `false` never becomes
          // projected state.
          ...(event.aborted === true ? { aborted: true } : {}),
        })),
      );
    }

    case "command_output_started": {
      // v54: foreground `!` command output stream. One block per command turn,
      // mirroring message_started.
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const command = boundedDisplayText(event.command, PROVIDER_PROJECTION_LIMITS.commandChars);
      const logFile = boundedDisplayText(event.logFile, PROVIDER_PROJECTION_LIMITS.pathChars);
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.blockId, (existing) => existing ?? {
          blockId: event.blockId,
          kind: "command_output",
          ...(command === undefined ? {} : { command }),
          ...(logFile === undefined ? {} : { logFile }),
          segments: [],
          done: false,
        }),
      );
    }

    case "command_output_delta": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      if (typeof event.text !== "string" || event.text.length === 0) return state;
      const stream = event.stream === "stderr" ? "stderr" : "stdout";
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.blockId, (existing) => {
          const base = existing ?? { blockId: event.blockId, kind: "command_output", segments: [], done: false };
          const segments = base.segments ? [...base.segments] : [];
          const last = segments[segments.length - 1];
          // Coalesce consecutive same-stream chunks so the array stays small (the
          // session manager already byte-caps how much it streams live).
          if (last && last.stream === stream) {
            segments[segments.length - 1] = { stream, text: last.text + event.text };
          } else {
            segments.push({ stream, text: event.text });
          }
          return { ...base, segments };
        }),
      );
    }

    case "command_output_completed": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.blockId, (existing) => ({
          ...(existing ?? { blockId: event.blockId, kind: "command_output", segments: [] }),
          done: true,
          exitCode: Number.isInteger(event.exitCode) ? event.exitCode : null,
          signal: typeof event.signal === "string" ? event.signal : null,
          timedOut: event.timedOut === true,
          killed: event.killed === true,
          outputTruncated: event.outputTruncated === true,
          durationMs: nonNegativeFiniteNumber(event.durationMs) ?? 0,
        })),
      );
    }

    case "background_command_updated": {
      // v54: session-level, last-write-wins per commandId. Bounded; drops the oldest
      // when full. Deduped so a repeated identical push doesn't churn the projection.
      const next = normalizeBackgroundCommand(event);
      if (!next) return state;
      const index = state.backgroundCommands.findIndex((command) => command.commandId === next.commandId);
      if (index === -1) {
        const base = state.backgroundCommands.length >= MAX_BACKGROUND_COMMANDS
          ? state.backgroundCommands.slice(1)
          : state.backgroundCommands;
        return { ...state, backgroundCommands: [...base, { ...next, segments: [] }] };
      }
      // v55: this lifecycle event carries no output — carry the streamed segments
      // (folded by background_command_output) across the status update so a finished
      // command keeps its captured output.
      const existing = state.backgroundCommands[index];
      const merged = { ...next, segments: existing.segments ?? [] };
      if (sameBackgroundCommand(existing, next)) return state;
      return {
        ...state,
        backgroundCommands: state.backgroundCommands.map((command, i) => (i === index ? merged : command)),
      };
    }

    case "background_command_output": {
      // v55: append a capped output delta to the matching background command. The
      // server byte-caps how much it streams, so the segment array stays bounded; we
      // coalesce consecutive same-stream chunks exactly like command_output_delta.
      const commandId = boundedIdentifier(event.commandId);
      if (!commandId || typeof event.text !== "string" || event.text.length === 0) return state;
      const index = state.backgroundCommands.findIndex((command) => command.commandId === commandId);
      if (index === -1) return state; // started event always precedes; ignore a stray delta
      const stream = event.stream === "stderr" ? "stderr" : "stdout";
      const existing = state.backgroundCommands[index];
      const segments = existing.segments ? [...existing.segments] : [];
      const last = segments[segments.length - 1];
      if (last && last.stream === stream) {
        segments[segments.length - 1] = { stream, text: last.text + event.text };
      } else {
        segments.push({ stream, text: event.text });
      }
      return {
        ...state,
        backgroundCommands: state.backgroundCommands.map((command, i) => (i === index ? { ...command, segments } : command)),
      };
    }

    case "spawned_run_updated": {
      // v118 (issue #173): session-level, last-write-wins per runId, bounded (the
      // oldest drops when full). The lifecycle event carries no output — the
      // captured `output` (folded by spawned_run_output) is carried across.
      const next = normalizeSpawnedRun(event);
      if (!next) return state;
      const runs = Array.isArray(state.spawnedRuns) ? state.spawnedRuns : [];
      const index = runs.findIndex((run) => run.runId === next.runId);
      const spawnedRunKeys = withSpawnedRunKeys(state.spawnedRunKeys, next);
      if (index === -1) {
        const base = runs.length >= MAX_SPAWNED_RUNS ? evictSpawnedRun(runs) : runs;
        return { ...state, spawnedRunKeys, spawnedRuns: [...base, { ...next, output: "", outputTruncated: false }] };
      }
      const existing = runs[index];
      if (sameSpawnedRun(existing, next)) return spawnedRunKeys === state.spawnedRunKeys ? state : { ...state, spawnedRunKeys };
      const merged = {
        ...next,
        output: existing.output ?? "",
        outputTruncated: existing.outputTruncated === true,
        ...(Array.isArray(existing.media) ? { media: existing.media } : {}),
      };
      return { ...state, spawnedRunKeys, spawnedRuns: runs.map((run, i) => (i === index ? merged : run)) };
    }

    case "spawned_run_output": {
      // v118: append a live output delta to its run, capped per run so a chatty
      // child can never grow the projection without bound (the full output is in
      // the run's log file).
      const runId = boundedIdentifier(event.runId);
      if (!runId || typeof event.text !== "string" || event.text.length === 0) return state;
      const runs = Array.isArray(state.spawnedRuns) ? state.spawnedRuns : [];
      const index = runs.findIndex((run) => run.runId === runId);
      if (index === -1) return state; // the lifecycle event always precedes; ignore a stray delta
      const existing = runs[index];
      const current = existing.output ?? "";
      if (current.length >= SPAWNED_RUN_OUTPUT_CAP_CHARS) {
        if (existing.outputTruncated) return state;
        return { ...state, spawnedRuns: runs.map((run, i) => (i === index ? { ...run, outputTruncated: true } : run)) };
      }
      const room = SPAWNED_RUN_OUTPUT_CAP_CHARS - current.length;
      const slice = event.text.length > room ? event.text.slice(0, room) : event.text;
      const output = current + slice;
      return {
        ...state,
        spawnedRuns: runs.map((run, i) => (i === index
          ? { ...run, output, outputTruncated: run.outputTruncated === true || slice.length < event.text.length }
          : run)),
      };
    }

    case "spawned_run_media": {
      // v122 (issue #190): append a run's pictures (content-addressed refs only),
      // deduped by url, bounded (the oldest drop). The lifecycle event always
      // precedes; a stray media event for an unknown run is ignored.
      const runId = boundedIdentifier(event.runId);
      if (!runId || !Array.isArray(event.media) || event.media.length === 0) return state;
      const runs = Array.isArray(state.spawnedRuns) ? state.spawnedRuns : [];
      const index = runs.findIndex((run) => run.runId === runId);
      if (index === -1) return state;
      const existing = runs[index];
      const current = Array.isArray(existing.media) ? existing.media : [];
      const seen = new Set(current.map((item) => item.url));
      const fresh = [];
      for (const raw of event.media.slice(0, MAX_SPAWNED_RUN_MEDIA)) {
        const item = normalizeSpawnedRunMedia(raw);
        if (!item || seen.has(item.url)) continue;
        seen.add(item.url);
        fresh.push(item);
      }
      if (fresh.length === 0) return state;
      const media = [...current, ...fresh].slice(-MAX_SPAWNED_RUN_MEDIA);
      return { ...state, spawnedRuns: runs.map((run, i) => (i === index ? { ...run, media } : run)) };
    }

    case "thinking_delta": {
      // Streamed extended-thinking (live path). Mirrors message_delta: upsert-
      // creating the thinking block on the first delta, then appending. Display
      // is gated client-side by the `showThinking` preference.
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.blockId, (existing) => ({
          ...(existing ?? { blockId: event.blockId, kind: "thinking", done: false }),
          text: (existing?.text ?? "") + event.text,
        })),
      );
    }

    case "thinking_completed": {
      // Self-contained thinking block (replay path, where the on-disk transcript
      // carries the full text). Mirrors message_completed: sets the whole text
      // and marks done.
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.blockId, () => ({ blockId: event.blockId, kind: "thinking", text: event.text, done: true })),
      );
    }

    case "thinking_stop": {
      // Closes a thinking block that was BUILT from streamed deltas (the live
      // assistant record that ends it carries empty thinking). Patch-only: never
      // create a block, so an empty thinking with no prior streaming (e.g. a
      // reasoning-less turn) leaves nothing behind.
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      if (!state.turnsById[state.activeTurnId]?.blocksById[event.blockId]) return state;
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.blockId, (existing) => ({ ...existing, done: true })),
      );
    }

    case "tool_start": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.toolId, () => ({
          blockId: event.toolId,
          kind: "tool",
          name: event.name,
          input: event.input,
          output: null,
          isError: false,
          done: false,
        })),
      );
    }

    case "tool_output_delta": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.toolId, (existing) => ({
          ...(existing ?? { blockId: event.toolId, kind: "tool", done: false }),
          output: (existing?.output ?? "") + event.chunk,
        })),
      );
    }

    case "tool_progress": {
      // Patch-only: progress can never create a tool card. Main-tool progress is
      // accepted only on the open turn. Parent-tagged progress is the T7-style
      // exception that may amend a completed owning turn, but only the matching
      // existing nested tool entry under the matching existing parent card.
      if (event.parentToolUseId != null) {
        const turn = state.turnsById[event.turnId];
        const parent = turn?.blocksById[event.parentToolUseId];
        const child = parent?.kind === "tool" ? parent.subagent?.entries[event.toolId] : null;
        if (
          !turn ||
          !parent ||
          !child ||
          child.kind !== "tool" ||
          child.done === true ||
          child.elapsedSeconds === event.elapsedSeconds
        ) return state;
        return updateTurnById(state, event.turnId, (turn) => {
          return {
            ...turn,
            blocksById: {
              ...turn.blocksById,
              [event.parentToolUseId]: {
                ...parent,
                subagent: {
                  ...parent.subagent,
                  entries: {
                    ...parent.subagent.entries,
                    [event.toolId]: { ...child, elapsedSeconds: event.elapsedSeconds },
                  },
                },
              },
            },
          };
        });
      }
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const tool = state.turnsById[event.turnId]?.blocksById[event.toolId];
      if (!tool || tool.kind !== "tool" || tool.done === true || tool.elapsedSeconds === event.elapsedSeconds) return state;
      return updateTurn(state, (turn) => {
        return {
          ...turn,
          blocksById: {
            ...turn.blocksById,
            [event.toolId]: { ...tool, elapsedSeconds: event.elapsedSeconds },
          },
        };
      });
    }

    case "tool_end": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) =>
        upsertBlock(turn, event.toolId, (existing) => {
          const withoutProgress = clearElapsedProgress(existing ?? { blockId: event.toolId, kind: "tool" });
          return {
            ...withoutProgress,
            output: event.output,
            isError: event.isError,
            done: true,
            // issue #184: the adapter classified the CLI's canned abort text.
            ...(event.interrupted === true ? { interrupted: true } : {}),
          };
        }),
      );
    }

    case "approval_request": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      let choices = normalizeApprovalChoices(event.choices);
      let metadata = normalizeApprovalMetadata(event.metadata);
      // Issue #222: a requested permission set the projection cannot carry whole
      // is NOT grantable — never truncate it into a (possibly broader) grant.
      // Drop the truncated copy and every choice that would grant it; only the
      // refusals remain, and a grant payload has nothing to confirm against.
      if (requestedPermissionsExceedBounds(event.metadata?.requestedPermissions)) {
        if (metadata) {
          metadata = { ...metadata };
          delete metadata.requestedPermissions;
        }
        if (choices) choices = choices.filter((choice) => choice.permissionGrant === undefined);
      }
      // v131: `createdAt` is the journal-stamped `ts` of this request event —
      // never a clock read — so the pending request's age is identical on the
      // server, on every client folding the same events, and on replay. Added as
      // a KEY only when the event is stamped (a direct unit-test fold without
      // `ts` keeps the pre-v131 shape byte-for-byte).
      const createdAt = nonNegativeFiniteNumber(event.ts);
      const approval = {
        requestId: event.requestId,
        toolId: event.toolId,
        name: event.name,
        input: event.input,
        ...(choices === undefined ? {} : { choices }),
        ...(metadata === undefined ? {} : { metadata }),
        ...(createdAt == null ? {} : { createdAt }),
      };
      const next = updateTurn(state, (turn) => ({
        ...turn,
        pendingApprovals: {
          ...turn.pendingApprovals,
          [event.requestId]: approval,
        },
      }));
      return { ...next, status: deriveSessionStatus(currentTurn(next)) };
    }

    case "question_request": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      // v131: journal-stamped creation time, same rule as approval_request.
      const createdAt = nonNegativeFiniteNumber(event.ts);
      const next = updateTurn(state, (turn) => ({
        ...turn,
        pendingQuestions: {
          ...turn.pendingQuestions,
          [event.requestId]: {
            requestId: event.requestId,
            toolId: event.toolId,
            questions: event.questions,
            ...(createdAt == null ? {} : { createdAt }),
          },
        },
      }));
      return { ...next, status: deriveSessionStatus(currentTurn(next)) };
    }

    case "question_resolved":
    case "question_cancelled": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const next = updateTurn(state, (turn) => {
        const pendingQuestions = { ...turn.pendingQuestions };
        delete pendingQuestions[event.requestId];
        return { ...turn, pendingQuestions };
      });
      return { ...next, status: deriveSessionStatus(currentTurn(next)) };
    }

    case "question_answered": {
      // v104: durable transcript record of the operator's reply. Appended, not
      // deleting anything — question_resolved (emitted separately) clears the
      // interactive card. Idempotent on requestId so a replay never double-adds.
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const items = Array.isArray(event.items) ? event.items : [];
      return updateTurn(state, (turn) => {
        const existing = Array.isArray(turn.answeredQuestions) ? turn.answeredQuestions : [];
        if (existing.some((a) => a.requestId === event.requestId)) return turn;
        return {
          ...turn,
          answeredQuestions: [
            ...existing,
            {
              requestId: event.requestId,
              toolId: event.toolId,
              items,
              ...(event.response ? { response: event.response } : {}),
            },
          ],
        };
      });
    }

    case "approval_resolved":
    case "approval_expired": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const next = updateTurn(state, (turn) => {
        const pendingApprovals = { ...turn.pendingApprovals };
        delete pendingApprovals[event.requestId];
        return { ...turn, pendingApprovals };
      });
      return { ...next, status: deriveSessionStatus(currentTurn(next)) };
    }

    case "cancel_requested": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) => ({ ...turn, status: "cancelling" }));
    }

    case "cancelled": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const next = updateTurn(state, (turn) => ({
        ...turn,
        status: "done",
        outcome: TURN_OUTCOMES.CANCELLED,
        // issue #184: this closes the turn, so a later turn_end (persistent path)
        // is a no-op here — the manager-stamped cause has to ride `cancelled` too.
        ...(turn.errorCause == null && event.cause === "interrupted" ? { errorCause: "interrupted" } : {}),
      }));
      return { ...next, status: SESSION_STATUS.READY, activeTurnId: null, lastTurnOutcome: TURN_OUTCOMES.CANCELLED };
    }

    case "process_exit": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) => ({ ...turn, exit: { code: event.code, signal: event.signal } }));
    }

    case "usage": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      return updateTurn(state, (turn) => ({
        ...turn,
        usage: {
          model: event.model,
          rawModel: event.rawModel,
          perTurnTokens: event.perTurnTokens,
          cumulativeTokens: event.cumulativeTokens,
          contextWindow: event.contextWindow,
          estimatedCostUSD: event.estimatedCostUSD,
          numTurns: event.numTurns,
          modelUsages: event.modelUsages,
        },
      }));
    }

    case "token_progress": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const tokens = nonNegativeFiniteNumber(event.tokens);
      if (tokens == null) return state;
      return updateTurn(state, (turn) => {
        // Clamp monotonic. The adapter counts up within a turn, but a provider that
        // restates a lower total (or a replayed journal whose milestones interleave)
        // must never make the operator watch the counter tick backwards.
        const next = turn.liveTokens == null ? tokens : Math.max(turn.liveTokens, tokens);
        return next === turn.liveTokens ? turn : { ...turn, liveTokens: next };
      });
    }

    case "turn_activity": {
      // v38 compaction restore (see the protocol note). DELIBERATELY not guarded by
      // isOpenCurrentTurn: it is emitted after turn_end in a compacted turn's
      // minimal event set, so by the time it folds the turn is `done` by
      // construction. It writes nothing an engine can influence — only the two
      // derived counters — so it cannot reopen or otherwise disturb a closed turn.
      const turn = state.turnsById[event.turnId];
      if (!turn) return state;
      const activeMs = nonNegativeFiniteNumber(event.activeMs);
      const runCount = nonNegativeFiniteNumber(event.runCount);
      if (activeMs == null && runCount == null) return state;
      return {
        ...state,
        turnsById: {
          ...state.turnsById,
          [event.turnId]: {
            ...turn,
            ...(activeMs == null ? {} : { activeMs }),
            ...(runCount == null ? {} : { runCount }),
          },
        },
      };
    }

    case "subagent_message": {
      // A Task-tool child (subagent) emitted a message or tool activity. Attach
      // it to the parent Task tool card (keyed by parentToolUseId — the tool_use
      // id of the Agent/Task call) so the UI can render it as a nested session
      // under that card. T7 deliberately permits this ONE event kind to patch a
      // completed turn: the SDK forwards background-child records between the
      // parent's result and its later continuation init. It never changes the
      // completed turn's status/outcome or the session's active turn. If the
      // parent card isn't present, drop it rather than invent a block.
      return updateTurnById(state, event.turnId, (turn) => {
        const parent = turn.blocksById[event.parentToolUseId];
        if (!parent || parent.kind !== "tool") return turn;
        return {
          ...turn,
          blocksById: {
            ...turn.blocksById,
            [event.parentToolUseId]: { ...parent, subagent: foldSubagentItems(parent.subagent, event.items, event.usage) },
          },
        };
      });
    }

    case "warning":
    case "unknown_event": {
      // Retained diagnostically (§3.3) but capped in the in-memory projection
      // so a noisy provider can't grow this unboundedly; the durable journal
      // keeps the full raw record regardless of this cap.
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const MAX_WARNINGS = 50;
      const legacyError = event.type === "unknown_event" ? legacyUnknownErrorMessage(event.raw) : null;
      return updateTurn(state, (turn) => ({
        ...turn,
        // Preserve the first/root error. Engines may append a generic
        // "ended without completing" backstop after a provider-specific error;
        // that fallback must not erase the actionable reason.
        error: turn.error ?? legacyError ?? undefined,
        warnings: [...turn.warnings, { type: event.type, message: event.message, raw: event.raw }].slice(-MAX_WARNINGS),
      }));
    }

    case "error": {
      // v91 (issue #106): `cause` is the engine's emission-time classification of
      // the error prose ("auth_expired" — see lib/claude-auth-expiry.mjs — or, v99
      // issue #125, "usage_limit" — see lib/codex-usage-limit.mjs). "auth_expired"
      // is a SESSION fact (the account is logged out, not this turn), so it also
      // latches onto state.accountAuth — including for a turnId:null error, which
      // no turn projection could hold. "usage_limit" is Codex-only and has no
      // analogous session-level latch here; its account-level fact rides the
      // separate `rate_limit` event instead (lib/codex-usage-limit.mjs).
      const authExpired = event.cause === "auth_expired";
      const accountAuth = authExpired && state.accountAuth?.status !== "expired"
        ? { status: "expired", turnId: event.turnId ?? null }
        : state.accountAuth;
      if (event.turnId == null) return { ...state, lastError: event.message, accountAuth };
      if (!isOpenCurrentTurn(state, event.turnId)) {
        return accountAuth === state.accountAuth ? state : { ...state, accountAuth };
      }
      const next = updateTurn(state, (turn) => ({
        ...turn,
        error: turn.error ?? event.message,
        // First-wins, exactly like `error` — the reason that ENDED the turn is the
        // first one surfaced, and compaction re-emits this pair together. Added as
        // a KEY only when there is a cause, so an unclassified turn's projection is
        // byte-for-byte what it was before v91.
        ...(turn.errorCause == null && event.cause ? { errorCause: event.cause } : {}),
      }));
      return accountAuth === state.accountAuth ? next : { ...next, accountAuth };
    }

    case "turn_end": {
      if (!isOpenCurrentTurn(state, event.turnId)) return state;
      const next = updateTurn(state, (turn) => {
        const completed = {
          ...turn,
          status: "done",
          outcome: event.outcome,
          // v91 (issue #106): the persistent engine's settle-on-close path carries
          // the classified cause on turn_end and emits NO separate `error` for that
          // turn, so the turn projection has to pick it up here too (this is also
          // what makes it survive whole-turn journal compaction). Key added only
          // when there is a cause — an ordinary turn's projection is unchanged.
          ...(turn.errorCause == null && event.cause ? { errorCause: event.cause } : {}),
        };
        return state.provider === "codex" && event.outcome === TURN_OUTCOMES.OK
          ? putCodexFinalReportLast(completed)
          : completed;
      });
      // v91 (issue #106): an OK turn proves the account's credentials work again,
      // so it clears the logged-out latch; a turn that ENDED auth-expired sets it
      // (the UNKNOWN-outcome settle path has no `error` event of its own). Scoped
      // to THIS session's open turn by the isOpenCurrentTurn guard above, so another
      // session's turn_end can never clear this one's latch.
      const accountAuth = event.outcome === TURN_OUTCOMES.OK
        ? null
        : event.cause === "auth_expired" && state.accountAuth?.status !== "expired"
          ? { status: "expired", turnId: event.turnId }
          : state.accountAuth;
      // v127 (issue #195): the wrap-up allowance ends with the response it covered.
      return { ...clearRateLimitGrace(next), status: SESSION_STATUS.READY, activeTurnId: null, lastTurnOutcome: event.outcome, accountAuth };
    }

    case "background_interrupted":
    case "background_abandoned": {
      // Session-level, NEVER turn-level (gotcha #1): background work was lost, so
      // the session must surface the loss rather than hang waiting on a
      // continuation that will never arrive. turnId is null — do NOT reopen a done
      // turn. background_interrupted (§10.4 step 6) is written by reconcileOnBoot
      // when a restart killed a warm query with outstanding work; background_
      // abandoned (§10.4 step 7) is broadcast when a warm session is evicted at
      // RUNTIME with outstanding work. Both journal markers stay the source of
      // truth; this fold is the derived, UI-facing projection. Deduped by seq so
      // the fold is idempotent even if the same stamped marker is replayed twice
      // (a seqless marker in a direct unit-test fold is appended as-is).
      const seq = event.seq;
      if (seq !== undefined && state.notices.some((notice) => notice.seq === seq)) return state;
      // issue #170: seq is the stable, server-assigned identity for the dismiss
      // key (journal-stamped, identical across replays). A seqless marker (a
      // direct unit-test fold) gets the one shared `:unsequenced` key — there is
      // at most one such notice in a real journal.
      const dismissKey = backgroundNoticeDismissKey(event.type, seq);
      if (isNoticeDismissed(state, dismissKey)) return state;
      // `reason` (background_abandoned only) tells the UI whether the warm session
      // was idle-recycled or its agent child crashed/was terminated. Omitted from
      // the notice when absent so pre-reason markers stay byte-identical.
      const notice = { kind: event.type, outstanding: event.outstanding ?? 0, seq, dismissKey };
      if (event.reason !== undefined) notice.reason = event.reason;
      return {
        ...state,
        notices: [...state.notices, notice],
      };
    }

    case "turn_interrupted": {
      // issue #184: WHY a turn died. Session-level (turnId null) so whole-turn
      // compaction can never strip it; `interruptedTurnId` names the turn it
      // explains, which the UI renders inline at that turn's outcome row.
      // Journaled by the manager when the interrupted turn's terminal event lands
      // (never merely on request), carrying the counts it captured at request
      // time. Deduped by seq like the background markers; bounded per kind.
      const seq = event.seq;
      if (seq !== undefined && state.notices.some((notice) => notice.seq === seq)) return state;
      if (!TURN_INTERRUPT_SOURCE_SET.has(event.by)) return state;
      const interruptedTurnId = boundedIdentifier(event.interruptedTurnId);
      if (!interruptedTurnId) return state;
      const dismissKey = backgroundNoticeDismissKey("turn_interrupted", seq);
      if (isNoticeDismissed(state, dismissKey)) return state;
      const count = (value) => (Number.isInteger(value) && value > 0 ? value : 0);
      const notice = {
        kind: "turn_interrupted",
        by: event.by,
        interruptedTurnId,
        stoppedTools: count(event.stoppedTools),
        stoppedBackground: count(event.stoppedBackground),
        seq,
        dismissKey,
      };
      const queueId = boundedIdentifier(event.queueId);
      if (queueId) notice.queueId = queueId;
      if (typeof event.ts === "number" && Number.isFinite(event.ts)) notice.at = event.ts;
      let notices = [...state.notices, notice];
      const interruptedCount = notices.reduce((n, item) => n + (item.kind === "turn_interrupted" ? 1 : 0), 0);
      if (interruptedCount > MAX_TURN_INTERRUPTED_NOTICES) {
        let drop = interruptedCount - MAX_TURN_INTERRUPTED_NOTICES;
        notices = notices.filter((item) => {
          if (drop > 0 && item.kind === "turn_interrupted") {
            drop -= 1;
            return false;
          }
          return true;
        });
      }
      return { ...state, notices };
    }

    case "external_advancement": {
      // Session-level, turnId null (§8): the native transcript advanced OUTSIDE
      // Tether (terminal/VSCode) past the accounted-for cursor. Folds into notices
      // as a divider the client renders; the external tail's actual turns are
      // folded separately as normal user/assistant events, so this case only adds
      // the marker. Carries `count` (turns that advanced elsewhere), NOT
      // `outstanding`. Deduped by seq so the fold is idempotent under replay (a
      // seqless marker in a direct unit-test fold is appended as-is).
      //
      // issue #170: unlike the background_* markers this event is derived from the
      // native transcript and never journaled, so it has no seq. The server stamps
      // a `noticeId` derived from the cursor range it surfaced (`from:to`) —
      // deterministic across a re-detection of the SAME range and distinct for a
      // NEW one, which is exactly what a cross-device dismissal needs.
      const seq = event.seq;
      if (seq !== undefined && state.notices.some((notice) => notice.seq === seq)) return state;
      const noticeId = boundedIdentifier(event.noticeId);
      if (noticeId && state.notices.some((notice) => notice.kind === "external_advancement" && notice.dismissKey === noticeId)) return state;
      const dismissKey = noticeId ?? (seq !== undefined ? `external_advancement:seq:${seq}` : "external_advancement:unsequenced");
      if (isNoticeDismissed(state, dismissKey)) return state;
      return {
        ...state,
        notices: [...state.notices, { kind: "external_advancement", count: event.count, seq, dismissKey }],
      };
    }

    case "notice_dismissed": {
      // issue #170: the operator closed one notice on one device. Append-only:
      // this records the ack as a NEW session-level journal fact (turnId null) so
      // a replay reproduces the hidden state everywhere, and the existing
      // snapshot/broadcast path carries it to every attached client. The notice's
      // original marker is untouched. Idempotent — a repeated ack is a no-op.
      const dismissKey = boundedIdentifier(event.dismissKey);
      if (!dismissKey || isNoticeDismissed(state, dismissKey)) return state;
      return applyNoticeDismissal(state, dismissKey);
    }

    case "queued_message_added": {
      // Session-level, turnId null (v14): a message the user composed while a turn
      // was in flight, awaiting flush at the next turn boundary. Deduped by queueId
      // so a replayed marker (reconnect/restart) is idempotent. Appended (flush
      // order = oldest first), never mutated in place, so the fold stays pure.
      if (state.queuedMessages.some((m) => m.queueId === event.queueId)) return state;
      return {
        ...state,
        queuedMessages: [
          ...state.queuedMessages,
          { queueId: event.queueId, text: event.text, ...(event.flushMode ? { flushMode: event.flushMode } : {}),
            ...(event.origin ? { origin: event.origin } : {}),
            ...(event.noticeKind ? { noticeKind: event.noticeKind } : {}),
            // v136 (issue #229): for a deferred ("next-call") message, the
            // journal-stamped `ts` of the add — never a clock read — so "waited
            // 14m" is identical on every device and after a reload. A KEY only for
            // that mode and only when stamped (every other queued row, and a direct
            // unit-test fold, keeps the pre-v136 shape).
            ...(event.flushMode && nonNegativeFiniteNumber(event.ts) !== undefined ? { queuedAt: nonNegativeFiniteNumber(event.ts) } : {}) },
        ],
      };
    }

    case "queued_message_updated": {
      // Edit a still-pending queued message's text. A no-op if it already flushed
      // (queueId gone) — never resurrects a removed message. Preserves flushMode.
      if (!state.queuedMessages.some((m) => m.queueId === event.queueId)) return state;
      return {
        ...state,
        queuedMessages: state.queuedMessages.map((m) => (m.queueId === event.queueId ? { ...m, text: event.text } : m)),
      };
    }

    case "queued_message_removed": {
      // Remove a queued message — by the user (cancel) OR by the flush path (the
      // message is leaving the queue to become a turn). A no-op if already gone.
      if (!state.queuedMessages.some((m) => m.queueId === event.queueId)) return state;
      // v130 (S13.1-C): remember the id (bounded, deduped, newest last) so a
      // snapshot proves it was accepted even after it left the queue. `?? []`: a
      // projection folded before v130 has no list yet.
      const removed = (state.removedQueueIds ?? []).filter((id) => id !== event.queueId);
      removed.push(event.queueId);
      return {
        ...state,
        queuedMessages: state.queuedMessages.filter((m) => m.queueId !== event.queueId),
        removedQueueIds: removed.length > MAX_REMOVED_QUEUE_IDS ? removed.slice(-MAX_REMOVED_QUEUE_IDS) : removed,
      };
    }

    // background_pending stays a journal-only reduce()-NO-OP (falls through to
    // default): it's the transition breadcrumb the boot reconciler reads to DERIVE
    // background_interrupted, never surfaced directly. Keeping it a no-op is also
    // what lets a pre-step-6 journal replay cleanly.
    default:
      return state;
  }
}

// --------------------------------------------------------------------------
// Claude adapter (Agent SDK / CLI stream-json records -> AgentEvent[])
// --------------------------------------------------------------------------

export function createClaudeAdapterState(turnId) {
  // streamTextOrdinal / streamTextByIndex track TEXT blocks within the current
  // streaming message so a streamed text block and its final counterpart share
  // a blockId (see claudeContentEvents). streamThinkingOrdinal / streamThinking-
  // ByIndex do the same for THINKING blocks (own `:th` namespace). Both reset on
  // every stream_event message_start.
  return {
    turnId,
    currentMessageId: null,
    streamTextOrdinal: 0,
    streamTextByIndex: {},
    streamThinkingOrdinal: 0,
    streamThinkingByIndex: {},
    // tool_use ids whose standalone permission_denied advisory already emitted.
    // Result.permission_denials repeats the same facts at turn end; the threaded
    // list lets the pure adapter deterministically emit each denial once.
    permissionDeniedToolIds: [],
    // tool_use id -> parent tool_use id (null for a main tool). A progress
    // record is emitted only if its exact tool/parent pair was introduced by an
    // assistant tool_use first, so malformed or out-of-order telemetry can
    // never fabricate a card or even an orphan progress journal row.
    toolParentsById: {},
    // tool_use id -> last emitted finite milestone index. The fixed ladder
    // gives every tool at most TOOL_PROGRESS_MILESTONES_SECONDS.length durable
    // progress rows, no matter how many heartbeat frames the CLI emits.
    toolProgressMilestones: {},
    // Last model reported by a TOP-LEVEL assistant message. Result.modelUsage
    // includes subagents too, so this is the only non-order-dependent clue for
    // choosing the main served entry. Assistant usage itself is deliberately
    // ignored: parallel tool-call frames can repeat one message.id and its
    // usage, while the result-level modelUsage is already the whole-tree total.
    servedRawModel: null,
    // v40: parent tool_use id -> that subagent run's RUNNING TOTAL usage
    // (own token counts + last served model), accumulated from the run's own
    // child records. Kept here, in adapter state, for two reasons:
    //   1. The emitted event carries the absolute total rather than a delta, so
    //      journal compaction's one-aggregate-subagent_message-per-parent replay
    //      is idempotent (a reducer that ADDED would double-count every replay).
    //   2. It survives the persistent engine's late-child routing, which re-runs
    //      claudeToEvents against the retained adapter state of an ALREADY
    //      COMPLETED turn (engines/claude-persistent.mjs handleForwardedSubagentRecord).
    // This is a SEPARATE counter from the main turn's live token scopes. The v37
    // `tokenScopesApply` guard below deliberately keeps child frames out of those,
    // and nothing here may ever be added back into them.
    subagentUsageByParent: {},
    // v37 live token estimate, per turn (this state is recreated per logical turn
    // by both Claude engines, so nothing leaks across turns).
    //
    // OUTPUT tokens only — what the model is producing. Input/cache tokens are
    // both enormous and step-shaped, so folding them in would swamp the signal the
    // operator is actually watching ("is it still working?"). The authoritative
    // whole-tree total still arrives as `usage` at turn end.
    //
    // Two nested "cumulative" scopes have to be unwound, because neither provider
    // signal is cumulative for the TURN:
    //   message_delta.usage.output_tokens  -> cumulative for the current MESSAGE
    //   thinking_tokens.estimated_tokens   -> cumulative for the current thinking
    //                                         BLOCK (of which a message may have
    //                                         several)
    // So: bank the finished message at each message_start (liveTokensSettled), bank
    // each finished thinking block within the message (liveThinkingSettled), and
    // track the innermost in-flight scope directly.
    liveTokensSettled: 0,
    liveTokensCurrent: 0,
    liveThinkingSettled: 0,
    // The current thinking block's own running total, kept apart from
    // liveTokensCurrent so that a message_delta's authoritative output_tokens can
    // raise the message total without corrupting the per-block bookkeeping.
    liveThinkingBlock: 0,
    // Last value actually emitted as token_progress, for milestone gating.
    liveTokensEmitted: 0,
  };
}

// Milestone gate for token_progress. The raw signal is per-delta, so an ungated
// emit would journal thousands of rows for one turn; a FIXED step would still emit
// hundreds on a long reasoning stretch. This ladder is proportional instead —
// ~10% growth plus a floor — which costs ~50 rows for a 20k-token turn and puts the
// fine resolution where it is legible (the first few hundred tokens) rather than
// where the rendered "12.4k" cannot show it anyway.
export function nextTokenProgressThreshold(emitted) {
  if (!(emitted > 0)) return 1;
  return Math.ceil(emitted * 1.1) + 24;
}

// Pure helper: fold a new observation of the CURRENT MESSAGE's cumulative output
// tokens into the adapter state, emitting token_progress only on a milestone
// crossing. max() keeps the message total monotonic where the thinking estimate and
// the authoritative usage disagree — they measure the same tokens (the SDK's own
// MessageDeltaUsage doc: output_tokens is the inclusive total, and thinking is one
// of its categories), so taking the larger is what avoids double-counting.
//
// `thinkingBlock`, when given, is the current thinking block's own running total;
// it is recorded separately so the block can be banked when the next one starts.
function claudeTokenProgress(state, observed, thinkingBlock) {
  const tokens = nonNegativeFiniteNumber(observed);
  if (tokens == null) return { events: [], state };
  const current = Math.max(state.liveTokensCurrent, tokens);
  const block = thinkingBlock === undefined ? state.liveThinkingBlock : thinkingBlock;
  if (current === state.liveTokensCurrent && block === state.liveThinkingBlock) return { events: [], state };
  const next = { ...state, liveTokensCurrent: current, liveThinkingBlock: block };
  const total = next.liveTokensSettled + current;
  if (total < nextTokenProgressThreshold(state.liveTokensEmitted)) return { events: [], state: next };
  return {
    events: [{ type: "token_progress", turnId: state.turnId, tokens: total }],
    state: { ...next, liveTokensEmitted: total },
  };
}

// Fixed and finite by design: an arbitrarily long-running tool still produces
// at most twelve durable progress rows. Values are display milestones, not
// billing/accounting time. A first observation emits its actual elapsed value;
// later observations emit only after crossing another rung.
export const TOOL_PROGRESS_MILESTONES_SECONDS = Object.freeze([
  0, 1, 5, 15, 30, 60, 120, 300, 600, 1800, 3600, 7200,
]);

function toolProgressMilestoneIndex(elapsedSeconds) {
  for (let index = TOOL_PROGRESS_MILESTONES_SECONDS.length - 1; index >= 0; index -= 1) {
    if (elapsedSeconds >= TOOL_PROGRESS_MILESTONES_SECONDS[index]) return index;
  }
  return -1;
}

function withKnownClaudeTools(state, content, parentToolUseId) {
  if (!Array.isArray(content)) return state;
  const tools = content.filter(
    (block) => block?.type === "tool_use" && typeof block.id === "string" && block.id,
  );
  if (tools.length === 0) return state;
  const toolParentsById = { ...state.toolParentsById };
  for (const tool of tools) toolParentsById[tool.id] = parentToolUseId;
  return { ...state, toolParentsById };
}

function claudeToolProgress(record, state) {
  const toolId = typeof record.tool_use_id === "string" && record.tool_use_id
    ? record.tool_use_id
    : null;
  const parentToolUseId = record.parent_tool_use_id === null
    ? null
    : typeof record.parent_tool_use_id === "string" && record.parent_tool_use_id
      ? record.parent_tool_use_id
      : undefined;
  const elapsedSeconds = nonNegativeFiniteNumber(record.elapsed_time_seconds);
  if (
    toolId === null ||
    parentToolUseId === undefined ||
    elapsedSeconds === undefined ||
    typeof record.tool_name !== "string" ||
    !Object.hasOwn(state.toolParentsById, toolId) ||
    state.toolParentsById[toolId] !== parentToolUseId
  ) {
    // Never retain the raw record: subagent_retry contains a private agent id
    // and provider-authored category/prose may grow in future SDK versions.
    return { events: [], state };
  }
  const milestone = toolProgressMilestoneIndex(elapsedSeconds);
  const previous = state.toolProgressMilestones[toolId];
  if (milestone < 0 || (previous !== undefined && milestone <= previous)) {
    return { events: [], state };
  }
  return {
    events: [{
      type: "tool_progress",
      turnId: state.turnId,
      toolId,
      ...(parentToolUseId === null ? {} : { parentToolUseId }),
      elapsedSeconds,
    }],
    state: {
      ...state,
      toolProgressMilestones: {
        ...state.toolProgressMilestones,
        [toolId]: milestone,
      },
    },
  };
}

function nonNegativeFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : undefined;
}

// #89: journal-stamped wall-clock ts of the creating event, for the small
// send-time on user/agent bubbles. Absent on hand-built events (tests) and
// pre-v38 journal lines — callers fall back to the turn's startedAt.
function blockEventTs(event) {
  const ts = nonNegativeFiniteNumber(event?.ts);
  return ts == null ? {} : { ts };
}

// v40: raw Claude usage key -> SubagentUsage key, for a subagent's own records.
const SUBAGENT_USAGE_FIELDS = [
  ["inputTokens", "input_tokens"],
  ["outputTokens", "output_tokens"],
  ["cacheReadInputTokens", "cache_read_input_tokens"],
  ["cacheCreationInputTokens", "cache_creation_input_tokens"],
];

/**
 * Fold ONE subagent-tagged assistant record's usage into that run's running total.
 *
 * Returns the new absolute total, or `previous` unchanged when the record carried
 * nothing usable — so a run with no usage at all keeps `undefined` rather than
 * becoming a zeroed reading. That distinction is load-bearing downstream: a
 * resumed session replays no child records whatsoever (the native-transcript path
 * skips parent_tool_use_id), and its runs must read as "not captured", never "free".
 *
 * SUMMING per record is the correct billing shape here, not a max: these are
 * complete-message records, one per API call the child made, and every call bills
 * its own input/cache tokens. This mirrors how result.modelUsage totals the tree.
 * (The main path deliberately ignores assistant usage instead — parallel tool-call
 * frames there can repeat one message.id and its usage. Child records are forwarded
 * once each, deduped by uuid upstream in the persistent engine, so they do not have
 * that hazard.)
 */
function accumulateSubagentUsage(previous, record) {
  const model = typeof record.message?.model === "string" && record.message.model ? record.message.model : null;
  const usage = record.message?.usage;
  const counted = usage && typeof usage === "object" && !Array.isArray(usage);
  if (!model && !counted) return previous;
  const next = { ...(previous ?? {}) };
  // Last served model wins: a run that somehow switched reports where it ended
  // up rather than inventing a blend it never actually ran on.
  if (model) next.model = model;
  if (counted) {
    for (const [target, source] of SUBAGENT_USAGE_FIELDS) {
      const value = nonNegativeFiniteNumber(usage[source]);
      if (value === undefined) continue;
      next[target] = (next[target] ?? 0) + value;
    }
  }
  return next;
}

function normalizedClaudeModelUsages(modelUsage) {
  if (!modelUsage || typeof modelUsage !== "object" || Array.isArray(modelUsage)) return [];
  return Object.entries(modelUsage).flatMap(([model, usage]) => {
    if (!model || !usage || typeof usage !== "object" || Array.isArray(usage)) return [];
    const entry = { model };
    if (typeof usage.canonicalModel === "string" && usage.canonicalModel) entry.canonicalModel = usage.canonicalModel;
    if (typeof usage.provider === "string" && usage.provider) entry.provider = usage.provider;
    for (const key of [
      "inputTokens",
      "outputTokens",
      "cacheReadInputTokens",
      "cacheCreationInputTokens",
      "webSearchRequests",
      "costUSD",
      "contextWindow",
      "maxOutputTokens",
    ]) {
      const value = nonNegativeFiniteNumber(usage[key]);
      if (value !== undefined) entry[key] = value;
    }
    return [entry];
  });
}

function mainClaudeModelUsage(modelUsages, servedRawModel) {
  if (typeof servedRawModel === "string" && servedRawModel) {
    const rawExact = modelUsages.find((entry) => entry.model === servedRawModel);
    if (rawExact) return rawExact;
    const canonicalMatches = modelUsages.filter((entry) => entry.canonicalModel === servedRawModel);
    if (canonicalMatches.length === 1) return canonicalMatches[0];
    // Preserve the provider-observed main model even if a malformed/partial
    // result omitted its modelUsage entry (or several provider entries claimed
    // the same canonical identity). No context/cost is invented.
    return { model: servedRawModel };
  }
  // With exactly one entry there is no ambiguity. With several and no
  // top-level assistant model, decline to guess — object insertion order is not
  // semantic and often puts a subagent first.
  return modelUsages.length === 1 ? modelUsages[0] : null;
}

// Text blocks are keyed `${messageId}:t${textOrdinal}`, where the ordinal
// counts only TEXT blocks in content order; thinking blocks are keyed
// `${messageId}:th${thinkingOrdinal}` in their OWN ordinal namespace. The
// streaming path below assigns the same ordinals independently, so "the Nth
// text/thinking block of this message" matches across the streamed and final
// records EVEN WHEN blocks sit at different raw content-array indices between
// the two. That divergence is real: the SDK streams a thinking block (content
// index 0) that the final text-bearing assistant record omits, shifting the
// text block from stream index 1 to final index 0. Keying by raw index
// previously produced two blocks — a streamed one stuck done:false plus the
// rendered completed one. Ordinal keying makes them the same block. tool_use
// keeps its own toolId as blockId.
//
// Thinking reconciles the SAME way text does. On the live path the model's
// reasoning arrives as streamed thinking_delta (below), and the assistant
// record that closes that block carries an EMPTY `thinking:""` — so here an
// empty thinking block emits a `thinking_stop` that just marks the streamed
// block done (a no-op if none was streamed). On the replay path the on-disk
// transcript persists the FULL thinking text in the assistant record, so here
// a non-empty thinking block emits a self-contained `thinking_completed`
// (text + done). Either way the block is keyed identically, so if both a
// streamed build AND a full final record occur they collapse into one block.
function claudeContentEvents(turnId, messageId, content, aborted = false) {
  const events = [];
  let textOrdinal = 0;
  let thinkingOrdinal = 0;
  const textCount = content.filter((block) => block?.type === "text").length;
  for (let index = 0; index < content.length; index += 1) {
    const block = content[index];
    if (block.type === "text") {
      events.push({
        type: "message_completed",
        turnId,
        blockId: `${messageId}:t${textOrdinal}`,
        text: block.text,
        // SDKAssistantMessage.aborted belongs to the whole wrapper. Attach it
        // once, to the wrapper's final text block, so a multi-block response
        // gets one marker rather than one per bubble.
        ...(aborted === true && textOrdinal === textCount - 1 ? { aborted: true } : {}),
      });
      textOrdinal += 1;
    } else if (block.type === "tool_use") {
      events.push({ type: "tool_start", turnId, toolId: block.id, name: block.name, input: block.input });
      // v39: TodoWrite carries the agent's own progress list in its tool input
      // ({ todos: [{ content, activeForm, status }] }). Project it as a session-level
      // fact IN ADDITION to the tool card — see the todo_updated note in the file
      // header for why turnId is null. Emitted here rather than in the `assistant`
      // case so the same detection serves the live stream AND native-transcript
      // replay (both funnel through this function), and so a SUBAGENT's own TodoWrite
      // — which routes through claudeSubagentItemsFromAssistant instead — can never
      // overwrite the session's list.
      if (block.name === "TodoWrite") {
        const todos = normalizeTodoItems(block.input?.todos);
        // Nothing readable in the list -> emit nothing, so a malformed call neither
        // reaches the journal nor clears a good list (the reducer repeats this guard).
        if (todos.length > 0) events.push({ type: "todo_updated", turnId: null, items: todos });
      } else if (block.name === "TaskCreate") {
        // v56: incremental todo list. Keyed by the tool_use id here; the real task id
        // arrives in the tool_result (todo_item_id_assigned, emitted in the `user` case).
        const created = normalizeTaskCreate(block.input);
        if (created) {
          events.push({
            type: "todo_item_created",
            turnId: null,
            toolId: block.id,
            subject: created.subject,
            ...(created.activeForm ? { activeForm: created.activeForm } : {}),
            status: created.status,
          });
        }
      } else if (block.name === "TaskUpdate") {
        const patch = normalizeTaskUpdate(block.input);
        if (patch) {
          events.push({
            type: "todo_item_updated",
            turnId: null,
            taskId: patch.taskId,
            ...(patch.status === undefined ? {} : { status: patch.status }),
            ...(patch.subject === undefined ? {} : { subject: patch.subject }),
            ...(patch.activeForm === undefined ? {} : { activeForm: patch.activeForm }),
          });
        }
      }
    } else if (block.type === "thinking") {
      const blockId = `${messageId}:th${thinkingOrdinal}`;
      const text = typeof block.thinking === "string" ? block.thinking.trim() : "";
      events.push(text
        ? { type: "thinking_completed", turnId, blockId, text }
        : { type: "thinking_stop", turnId, blockId });
      thinkingOrdinal += 1;
    }
  }
  // An aborted tool-only / thinking-only wrapper still needs a visible,
  // journalable provider fact. A zero-text message block renders only its
  // interrupted marker and is keyed in the assistant message namespace so the
  // external-advancement identity guard continues to recognize the wrapper.
  if (aborted === true && textCount === 0) {
    events.push({ type: "message_completed", turnId, blockId: `${messageId}:aborted`, text: "", aborted: true });
  }
  return events;
}

// Normalizes ONE subagent-tagged assistant record (parent_tool_use_id set) into
// the item list a `subagent_message` event carries. Text and thinking blocks
// become independently-keyed items (same ordinal namespaces as the main path);
// tool_use blocks become `tool` items keyed by the tool_use id. These are
// complete-message blocks only: stream_event deltas have no parent attribution.
function claudeSubagentItemsFromAssistant(record) {
  const content = record.message?.content;
  if (!Array.isArray(content)) return [];
  const messageId = typeof record.message?.id === "string" ? record.message.id : "sub";
  const items = [];
  let textOrdinal = 0;
  let thinkingOrdinal = 0;
  for (const block of content) {
    if (block.type === "text") {
      items.push({ kind: "message", key: `${messageId}:t${textOrdinal}`, text: typeof block.text === "string" ? block.text : "" });
      textOrdinal += 1;
    } else if (block.type === "thinking") {
      const text = typeof block.thinking === "string" ? block.thinking.trim() : "";
      if (text) items.push({ kind: "thinking", key: `${messageId}:th${thinkingOrdinal}`, text });
      thinkingOrdinal += 1;
    } else if (block.type === "tool_use") {
      items.push({ kind: "tool", key: block.id, name: block.name, input: block.input });
    }
  }
  return items;
}

// Normalizes ONE subagent-tagged user record (its tool_result blocks) into
// `tool_result` items keyed by tool_use id, which merge into the matching tool
// item in the reducer (see foldSubagentItems).
function claudeSubagentItemsFromUser(record, permissionDeniedToolIds = []) {
  const content = record.message?.content;
  if (!Array.isArray(content)) return [];
  return content
    .filter((item) => item.type === "tool_result")
    .map((item) => ({
      kind: "tool_result",
      key: item.tool_use_id,
      // When the structured denial advisory preceded this result, do not copy
      // its human-readable rejection prose into the normalized journal.
      output: permissionDeniedToolIds.includes(item.tool_use_id) ? null : item.content,
      isError: Boolean(item.is_error),
      // issue #184: a sub-agent's aborted call carries the same canned CLI text.
      ...(!permissionDeniedToolIds.includes(item.tool_use_id) && item.is_error && isClaudeToolAbortOutput(item.content)
        ? { interrupted: true }
        : {}),
    }));
}

const TASK_TERMINAL_STATUSES = new Set(["completed", "failed", "stopped", "killed"]);
const TASK_PROGRESS_STATUSES = new Set(["pending", "running", "paused"]);
// SDK decision_reason_type -> wire reason. Full vocabulary as of sdk 0.3.219
// (sdk.d.ts: 'rule' | 'mode' | 'subcommandResults' | 'permissionPromptTool' |
// 'hook' | 'asyncAgent' | 'sandboxOverride' | 'workingDir' | 'safetyCheck' |
// 'classifier' | 'other'); see PermissionDenialReason in lib/protocol.ts for what
// each bucket means. `subcommandResults` is the compound-bash wrapper the SDK puts
// around a nested safetyCheck, so it shares that bucket — the exact token survives
// in `reasonCode`. An unmapped/absent value stays "unknown", and `reasonCode` then
// names the token to add here.
const PERMISSION_DENIAL_REASONS = new Map([
  ["classifier", "classifier"],
  ["safetyCheck", "safety_check"],
  ["subcommandResults", "safety_check"],
  ["rule", "rule"],
  ["mode", "mode"],
  ["workingDir", "working_dir"],
  ["sandboxOverride", "sandbox"],
  ["hook", "hook"],
  ["permissionPromptTool", "prompt_tool"],
  ["asyncAgent", "async_agent"],
  ["other", "other"],
]);

function claudePermissionDeniedEvent(record, turnId) {
  if (
    typeof record?.tool_name !== "string" ||
    record.tool_name.length === 0 ||
    typeof record?.tool_use_id !== "string" ||
    record.tool_use_id.length === 0
  ) return null;
  const reason = PERMISSION_DENIAL_REASONS.get(record.decision_reason_type) ?? "unknown";
  const reasonCode = permissionDenialReasonCode(record.decision_reason_type);
  // issue #52: an abort (stream-close) makes the SDK emit a denial with NO
  // decision_reason_type — both `reason` and `reasonCode` come back empty and the
  // card dead-ends on generic copy while the transcript holds the real cause
  // ("Tool permission request failed: AbortError: Stream closed"). The ONE prose
  // exception to the v36 privacy boundary: when no discriminator exists at all,
  // the SDK `message` (the rejection text returned to the model) crosses as a
  // bounded `error` so the operator sees why. A present discriminator — mapped,
  // unmapped, or prose — never leaks the message: `reasonCode` names the token.
  const error = reason === "unknown" && typeof record.decision_reason_type !== "string"
    ? boundedDisplayText(record.message, PROVIDER_PROJECTION_LIMITS.proseChars)
    : undefined;
  return {
    type: "permission_denied",
    turnId,
    toolId: record.tool_use_id,
    name: record.tool_name,
    reason,
    ...(reasonCode ? { reasonCode } : {}),
    ...(error ? { error } : {}),
    // `agent_id` itself is not needed for the UI and does not cross the privacy
    // boundary. Its presence is enough to label the closed fact as subagent work.
    ...(typeof record.agent_id === "string" && record.agent_id ? { subagent: true } : {}),
  };
}

// --------------------------------------------------------------------------
// issue #179: Claude Code's automatic model fallback
// --------------------------------------------------------------------------
//
// When the primary model fails in a way the CLI classifies as switchable, and a
// fallback model is configured (`fallbackModel` in settings / --fallback-model),
// the CLI re-issues the rest of THIS turn on the fallback and emits
//   { type: "system", subtype: "model_fallback", trigger, original_model,
//     fallback_model, content, uuid, session_id }
// (its own zod schema, marked "@internal … Not yet in the public SDKMessage
// union" in CLI 2.1.260+; `content` is the CLI-authored line, e.g. "Switched to
// Sonnet 5 due to high demand for Opus 5.5 (1M context)"). Turn-scoped: the CLI
// re-tries the primary on the next user turn. Before #179 the adapter dropped it,
// so the downgrade was silent.
//
// `trigger` is an open string on our side (the CLI enumerates model_not_found,
// permission_denied, overloaded, server_error, last_resort, model_blocked); the
// class below only picks the notice's short lead-in, never the message itself.
export const MODEL_FALLBACK_TRIGGER_CLASSES = Object.freeze({
  overloaded: "capacity",
  server_error: "capacity",
  model_not_found: "unavailable",
  permission_denied: "unavailable",
  model_blocked: "unavailable",
  last_resort: "error",
});

export function modelFallbackClass(trigger) {
  return typeof trigger === "string" && Object.hasOwn(MODEL_FALLBACK_TRIGGER_CLASSES, trigger)
    ? MODEL_FALLBACK_TRIGGER_CLASSES[trigger]
    : null;
}

// The short, class-aware lead-in rendered before the CLI's own verbatim text.
export function modelFallbackLeadIn(trigger) {
  switch (modelFallbackClass(trigger)) {
    case "capacity": return "Model switched · capacity";
    case "unavailable": return "Model switched · not available";
    case "error": return "Model switched · unretryable error";
    default: return "Model switched";
  }
}

function claudeModelFallbackEvent(record, turnId) {
  const fromModel = boundedIdentifier(record.original_model);
  const toModel = boundedIdentifier(record.fallback_model);
  const trigger = boundedDisplayText(record.trigger, PROVIDER_PROJECTION_LIMITS.labelChars);
  const fallbackId = boundedIdentifier(record.uuid);
  // The CLI's own line, VERBATIM (only length-bounded). A record without one
  // still says what happened, in the plainest words the fields support.
  const content = typeof record.content === "string" && record.content.trim()
    ? boundedDisplayText(record.content, PROVIDER_PROJECTION_LIMITS.proseChars)
    : null;
  const message = content ?? (toModel ? `The CLI switched this turn to ${toModel}.` : "The CLI switched this turn to a fallback model.");
  return {
    type: "model_fallback",
    turnId,
    message,
    ...(fallbackId ? { fallbackId } : {}),
    ...(fromModel ? { fromModel } : {}),
    ...(toModel ? { toModel } : {}),
    ...(trigger ? { trigger } : {}),
  };
}

// Server-side-only capture (issue #52): a bounded field set of a standalone
// permission-denial record whose discriminator is absent OR unmapped, for the
// engines to log so an unexplainable denial is discoverable in the service
// journal without opening the provider transcript. Pure (no I/O) so this module
// stays shared with the browser; the ENGINE decides to log. Returns null for
// anything this Tether build can already explain. `message`/`decision_reason`
// are the operator's OWN transcript prose, bounded to display length — this
// capture never crosses to any client.
export function unmappedDenialCapture(record) {
  if (record?.type !== "system" || record?.subtype !== "permission_denied") return null;
  if (typeof record.decision_reason_type === "string" && PERMISSION_DENIAL_REASONS.has(record.decision_reason_type)) {
    return null;
  }
  return {
    toolName: boundedDisplayText(record.tool_name, PROVIDER_PROJECTION_LIMITS.labelChars) ?? null,
    toolUseId: boundedDisplayText(record.tool_use_id, PROVIDER_PROJECTION_LIMITS.idChars) ?? null,
    decisionReasonType: boundedDisplayText(record.decision_reason_type, PROVIDER_PROJECTION_LIMITS.labelChars) ?? null,
    decisionReason: boundedDisplayText(record.decision_reason, PROVIDER_PROJECTION_LIMITS.proseChars) ?? null,
    message: boundedDisplayText(record.message, PROVIDER_PROJECTION_LIMITS.proseChars) ?? null,
  };
}

function addTaskUsage(event, usage) {
  if (!usage || typeof usage !== "object") return event;
  if (typeof usage.total_tokens === "number") event.totalTokens = usage.total_tokens;
  if (typeof usage.tool_uses === "number") event.toolUses = usage.tool_uses;
  if (typeof usage.duration_ms === "number") event.durationMs = usage.duration_ms;
  return event;
}

// Normalize SDK task telemetry into a deliberately small, session-level wire
// surface. These records can arrive between logical turns, so turnId is always
// null: taskId is the durable correlation key and whole-turn compaction must
// never erase lifecycle facts. Prompt/description/summary/output_file/error are
// intentionally omitted — the task lifecycle needs metadata, not copied user
// prompts or provider output paths/content.
function claudeTaskEvents(record) {
  if (record.subtype === "background_tasks_changed") {
    // Fail safe on a malformed level payload: treating "unreadable" as empty
    // could evict a process that still has real work. A valid empty ARRAY is the
    // only signal that clears the live set.
    if (
      !Array.isArray(record.tasks) ||
      record.tasks.some((task) => typeof task?.task_id !== "string" || task.task_id.length === 0)
    ) return [];
    const taskIds = record.tasks.map((task) => task.task_id);
    return [{ type: "background_tasks_changed", turnId: null, taskIds: [...new Set(taskIds)] }];
  }

  if (typeof record.task_id !== "string" || record.task_id.length === 0) return [];
  const base = { turnId: null, taskId: record.task_id };

  if (record.subtype === "task_started") {
    const event = { type: "task_started", ...base };
    if (typeof record.tool_use_id === "string" && record.tool_use_id) event.toolUseId = record.tool_use_id;
    if (typeof record.task_type === "string" && record.task_type) event.taskType = record.task_type;
    if (typeof record.subagent_type === "string" && record.subagent_type) event.subagentType = record.subagent_type;
    if (typeof record.skip_transcript === "boolean") event.ambient = record.skip_transcript;
    return [event];
  }

  if (record.subtype === "task_progress") {
    const event = addTaskUsage({ type: "task_progress", ...base }, record.usage);
    if (typeof record.tool_use_id === "string" && record.tool_use_id) event.toolUseId = record.tool_use_id;
    if (typeof record.last_tool_name === "string" && record.last_tool_name) event.lastToolName = record.last_tool_name;
    return [event];
  }

  if (record.subtype === "task_notification" && TASK_TERMINAL_STATUSES.has(record.status)) {
    const event = addTaskUsage({ type: "task_completed", ...base, status: record.status }, record.usage);
    if (typeof record.tool_use_id === "string" && record.tool_use_id) event.toolUseId = record.tool_use_id;
    if (typeof record.skip_transcript === "boolean") event.ambient = record.skip_transcript;
    return [event];
  }

  if (record.subtype === "task_updated") {
    const status = record.patch?.status;
    if (TASK_TERMINAL_STATUSES.has(status)) {
      return [{ type: "task_completed", ...base, status }];
    }
    if (TASK_PROGRESS_STATUSES.has(status)) {
      return [{ type: "task_progress", ...base, status }];
    }
  }

  return [];
}

/**
 * claudeToEvents(record, state) -> { events, state: nextState }
 *
 * `record` is one parsed line of `claude --output-format stream-json`
 * output (or the equivalent Agent SDK message). `state` is this adapter's
 * own threaded state (see createClaudeAdapterState) — NOT the session
 * reducer state above. Pure: identical (record, state) always yields
 * identical (events, nextState).
 */
export function claudeToEvents(record, state) {
  const turnId = state.turnId;

  switch (record.type) {
    case "tool_progress":
      return claudeToolProgress(record, state);

    case "tool_use_summary":
      // Runtime inspection at CLI 2.1.219 shows this generated prose arrives
      // after the NEXT assistant response and may describe several preceding
      // tool ids. There is no coherent single-card attachment rule, and copying
      // or duplicating arbitrary provider prose would widen the privacy surface.
      return { events: [], state };

    case "command_lifecycle":
      // Runtime-only at SDK 0.3.219. It tracks queued/folded command UUIDs, which
      // do not correlate with Tether's own queue ids or tool ids. Intentionally
      // suppress every shape so raw command identifiers never reach the journal.
      return { events: [], state };

    case "system": {
      if (record.subtype === "init") {
        // SDKSystemMessage (sdk.d.ts, `SDKSystemMessage`) carries far more than
        // session_id — agents, apiKeySource, betas, claude_code_version, cwd, tools,
        // mcp_servers, model, permissionMode, slash_commands, output_style, skills,
        // plugins, fast_mode_state, capabilities. We lift the two process facts
        // Tether cannot get anywhere else (v17/T1):
        //   capabilities        -> feature-detection, replacing pkg.version sniffing
        //   claude_code_version -> which CLI build actually served this session,
        //                          which the v16 Advanced version override needs to
        //                          be verifiable rather than merely requested.
        // v25/T15 also lifts the bounded inventory with present consumers:
        //   slash_commands -> composer advertisement + inspector
        //   tools          -> inspector
        //   mcp_servers    -> inspector health
        // The rest is either already sourced elsewhere (model, permissionMode) or
        // deliberately suppressed; see SDK_SURFACE_PLAN §5.5/session 11.
        const event = { type: "native_session_id", turnId, nativeSessionId: record.session_id };
        // Open set: unknown strings pass through untouched (the CLI's own contract)
        // and non-strings are dropped. A LIVE init always carries the normalized
        // field, including [] when an older CLI omitted it: that is the explicit
        // "feature off" signal and must clear capabilities learned from a prior CLI
        // process. Journal compaction's synthetic native_session_id omits the field,
        // which is how the reducer distinguishes replay from a fresh init.
        const capabilities = Array.isArray(record.capabilities) ? record.capabilities.filter((c) => typeof c === "string" && c.length > 0) : [];
        event.cliCapabilities = capabilities;
        if (typeof record.claude_code_version === "string" && record.claude_code_version) event.cliVersion = record.claude_code_version;
        const normalizedMcpServers = normalizeClaudeMcpServers(record.mcp_servers);
        event.cliInventory = {
          commands: normalizeClaudeCommands(record.slash_commands),
          tools: normalizeClaudeTools(record.tools),
          mcpServers: normalizedMcpServers,
        };
        // v95: fast_mode_state/fast_mode_disabled_reason, also carried on this
        // record (see the field list above) — a separate session-level event
        // rather than a field on native_session_id, so it folds through the
        // same reducer path as the result-frame report and the engine's own
        // synthetic force-clear.
        const fastModeEvent = claudeFastModeEvent(record, turnId);
        // Also surface each server as an mcp_health_updated event (turnId null,
        // session-level — matching codex/opencode's existing emission shape) so
        // Claude's MCP health renders through the same generic card as codex/
        // opencode, rather than only appearing as inert text in cliInventory.
        // Reuse normalizedMcpServers (rather than re-parsing record.mcp_servers)
        // so the two representations can never disagree.
        const mcpHealthEvents = normalizedMcpServers.map((server) => ({
          type: "mcp_health_updated",
          turnId: null,
          name: server.name,
          status: CLAUDE_MCP_STATUS_TO_HEALTH[server.status] ?? "unknown",
        }));
        return { events: [event, ...(fastModeEvent ? [fastModeEvent] : []), ...mcpHealthEvents], state };
      }
      if (record.subtype === "commands_changed") {
        // SDKCommandsChangedMessage is a fire-and-forget full replacement, not
        // a patch. Session-level and safe between turns: it cannot bind a send
        // or mint a continuation in the persistent demux.
        return {
          events: [{
            type: "cli_commands_changed",
            turnId: null,
            commands: normalizeClaudeCommands(record.commands),
          }],
          state,
        };
      }
      if (record.subtype === "api_retry") {
        // SDKAPIRetryMessage: the SDK is retrying an HTTP call within this turn.
        // Purely informational — without it a quietly-retrying turn is
        // indistinguishable from a hung one. It is NOT turn retry: nothing here
        // resends a turn, and nothing downstream may (CLAUDE.md invariant).
        if (typeof record.attempt !== "number") return { events: [{ type: "unknown_event", turnId, raw: record }], state };
        const event = { type: "api_retry", turnId, attempt: record.attempt };
        if (typeof record.max_retries === "number") event.maxRetries = record.max_retries;
        if (typeof record.retry_delay_ms === "number") event.delayMs = record.retry_delay_ms;
        if (typeof record.error_status === "number") event.errorStatus = record.error_status;
        if (typeof record.error === "string") event.error = record.error;
        return { events: [event], state };
      }
      if (record.subtype === "permission_denied") {
        const event = claudePermissionDeniedEvent(record, turnId);
        // Never retain the raw denial record: even a malformed one carries
        // provider-authored prose and may carry future sensitive fields.
        if (!event) return { events: [{ type: "warning", turnId, message: "Malformed permission denial record" }], state };
        if (state.permissionDeniedToolIds.includes(event.toolId)) return { events: [], state };
        return {
          events: [event],
          state: { ...state, permissionDeniedToolIds: [...state.permissionDeniedToolIds, event.toolId] },
        };
      }
      if (
        record.subtype === "task_started" ||
        record.subtype === "task_progress" ||
        record.subtype === "task_notification" ||
        record.subtype === "task_updated" ||
        record.subtype === "background_tasks_changed"
      ) {
        return { events: claudeTaskEvents(record), state };
      }
      if (record.subtype === "model_fallback") {
        // issue #179: journal the CLI's model switch and surface it (see
        // claudeModelFallbackEvent). Never a retry of the turn — the CLI already
        // re-issued on the fallback; Tether only records that it did.
        return { events: [claudeModelFallbackEvent(record, turnId)], state };
      }
      if (record.subtype === "thinking_tokens") {
        // v37: SDKThinkingTokensMessage — the SDK's own digest of the redacted
        // thinking stream, and the ONLY live token signal during a long think
        // (the API otherwise streams bare pings). Its doc names this exact use:
        // "Approximate progress for spinners/pills, not the authoritative billed
        // output_tokens."
        //
        // Use `estimated_tokens` (the running total) and NOT the raw
        // thinking_delta.estimated_tokens this message is digested from: that raw
        // field is the per-FRAME increment, which is why both fields exist here.
        // Folding the increment as if it were a total pins the count at one
        // frame's worth (~5 tokens) for the whole think.
        //
        // The total is per thinking BLOCK, so completed blocks are banked
        // separately (see liveThinkingSettled).
        //
        // NOTE on attribution: SDKThinkingTokensMessage declares exactly
        // {type, subtype, estimated_tokens, estimated_tokens_delta, uuid,
        // session_id} — no parent_tool_use_id — so a subagent's digest would be
        // INDISTINGUISHABLE from the main model's and there is nothing to filter on.
        // What keeps subagent reasoning out of this count today is upstream: the
        // CLI's subagent forwarder only forwards assistant/user messages, so a
        // child's system/thinking_tokens never reaches us. If that ever changes the
        // count will silently include subagent reasoning; the pin canary in
        // tests/claude-persistent.test.mjs is the prompt to re-check it. Deliberately
        // NOT guarding on a field the type does not have — a guard that cannot fire
        // reads as protection that isn't there.
        const blockTotal = nonNegativeFiniteNumber(record.estimated_tokens);
        if (blockTotal == null) return { events: [], state };
        return claudeTokenProgress(state, state.liveThinkingSettled + blockTotal, blockTotal);
      }
      // "status" (e.g. subtype "requesting") is transient telemetry, not a
      // decision-relevant fact — intentionally ignored, not "unknown".
      return { events: [], state };
    }

    case "rate_limit_event": {
      // SDKRateLimitEvent: pushed whenever the CLI's view of the subscription
      // rate limit changes. Session-level. Complements (does not replace) the
      // usage windows the discovery worker derives from the native transcript —
      // this one arrives live, mid-turn, and is the only signal for a `rejected`.
      const info = record.rate_limit_info;
      if (!info || !RATE_LIMIT_STATUSES.has(info.status)) return { events: [], state };
      // SESSION-LEVEL, never turn-scoped (turnId:null, matching the Codex emitter
      // in lib/codex-usage-limit.mjs): a rate limit is a fact about the account,
      // not the turn that happened to surface it. This is load-bearing for the
      // durable resume schedule. A limit is normally hit MID-TURN, so stamping the
      // active turnId here would let whole-turn journal compaction (which rebuilds
      // a done turn from its projection and drops everything else with that turnId)
      // ERASE the rejection. On the next boot the re-fold would then be missing the
      // `awaiting_choice` state that `rate_limit_resume_scheduled` requires as its
      // precondition — so a schedule the operator set would silently vanish across
      // a restart/rebuild. turnId:null keeps it out of any turn's compaction group.
      // The reducer ignores the field entirely (rate_limit folds to session state),
      // so null costs nothing.
      const event = { type: "rate_limit", turnId: null, status: info.status };
      if (typeof info.rateLimitType === "string") event.limitType = info.rateLimitType;
      if (typeof info.utilization === "number") event.utilization = info.utilization;
      // SDKRateLimitInfo.resetsAt is Unix seconds (the CLI derives it directly
      // from the anthropic-ratelimit-*-reset headers). Tether timestamps are
      // milliseconds throughout, including resetTime(), so normalize at the
      // provider boundary rather than leaking a second unit onto the wire.
      if (typeof info.resetsAt === "number") event.resetsAt = info.resetsAt * 1000;
      // v127 (issue #195): the Wrap-Up Allowance. `rateLimitGraceActive` is an
      // @internal field of the CLI's own SDKRateLimitInfo schema — absent from
      // sdk.d.ts, but declared in the CLI's zod schema and set by the headless
      // `[rateLimitFrames]` emitter (verified in the 2.1.283 binary) exactly while
      // the account's usage-limit grace window is open. Per that schema's own
      // description: it "stays true at hard exhaustion (status rejected)", it
      // persists while requests are refused (so the REDUCER scopes it to the turn
      // that reported it and the UI expires it via resetsAt), and while it is set
      // an `overageStatus` of allowed/allowed_warning means usage credits cover
      // the overflow — the CLI's "brief included wrap-up, then usage credits"
      // variant. Strict `=== true`: anything else (absent on every account not in
      // the rollout, and on API-key/Bedrock/Vertex) adds no key at all — the
      // state simply never appears rather than being guessed.
      if (info.rateLimitGraceActive === true) {
        event.grace = info.overageStatus === "allowed" || info.overageStatus === "allowed_warning"
          ? "wrap_up_then_credits"
          : "wrap_up";
      }
      return { events: [event], state };
    }

    case "stream_event": {
      const inner = record.event;
      if (!inner || typeof inner.type !== "string") {
        return { events: [{ type: "unknown_event", turnId, raw: record }], state };
      }
      // v37: a forwarded SUBAGENT partial frame (SDKPartialAssistantMessage carries
      // parent_tool_use_id) must not move this turn's token scopes. The provider's
      // accumulator is per-query, so a child's frames say nothing about how far the
      // MAIN model has got: a child message_start would bank the parent's in-flight
      // message early, and a child thinking-block start would bank a parent block the
      // provider is still counting — after which the parent's next digest adds that
      // same running total a second time, permanently (message_start folds the
      // inflated value into liveTokensSettled and the reducer clamps monotonic).
      //
      // This ONLY suppresses token bookkeeping. Everything else about how these
      // frames are handled — currentMessageId, the text/thinking ordinal maps,
      // servedRawModel — is left exactly as it was before v37. An earlier attempt
      // returned early for the whole record, which silently re-keyed a child's text
      // onto the parent's message id (and could collide with a real parent block).
      // Token accounting is the only thing v37 added here, so it is the only thing
      // v37 may change.
      const tokenScopesApply = record.parent_tool_use_id == null;
      if (inner.type === "message_start") {
        if (typeof inner.message?.id !== "string") {
          return { events: [{ type: "unknown_event", turnId, raw: record }], state };
        }
        return {
          events: [],
          state: {
            ...state,
            currentMessageId: inner.message.id,
            streamTextOrdinal: 0,
            streamTextByIndex: {},
            streamThinkingOrdinal: 0,
            streamThinkingByIndex: {},
            servedRawModel: typeof inner.message.model === "string" && inner.message.model
              ? inner.message.model
              : state.servedRawModel,
            // v37: bank the message that just ended and start the next one at zero.
            // A turn is many messages (each tool round-trip opens another), and
            // message_delta's output_tokens restarts per message. The thinking
            // scopes are nested inside the message, so they reset with it.
            ...(tokenScopesApply
              ? {
                liveTokensSettled: state.liveTokensSettled + state.liveTokensCurrent,
                liveTokensCurrent: 0,
                liveThinkingSettled: 0,
                liveThinkingBlock: 0,
              }
              : {}),
          },
        };
      }
      if (inner.type === "content_block_start") {
        if (inner.content_block?.type === "text" && state.currentMessageId) {
          const ordinal = state.streamTextOrdinal;
          const blockId = `${state.currentMessageId}:t${ordinal}`;
          return {
            events: [{ type: "message_started", turnId, blockId }],
            state: {
              ...state,
              streamTextOrdinal: ordinal + 1,
              streamTextByIndex: { ...state.streamTextByIndex, [inner.index]: ordinal },
            },
          };
        }
        if (inner.content_block?.type === "thinking" && state.currentMessageId) {
          // Register the raw-index → thinking-ordinal mapping so the deltas below
          // can find their block. No event yet — the first thinking_delta upserts
          // the block (mirrors how a text block would, minus the eager empty
          // bubble a collapsed thinking block doesn't need).
          const ordinal = state.streamThinkingOrdinal;
          return {
            events: [],
            state: {
              ...state,
              streamThinkingOrdinal: ordinal + 1,
              streamThinkingByIndex: { ...state.streamThinkingByIndex, [inner.index]: ordinal },
              // v37: thinking_tokens.estimated_tokens restarts at zero for each
              // thinking block (the CLI resets its accumulator on exactly this
              // event), so bank the block that just closed. Without this a message
              // with two thinking blocks would have the second one's lower running
              // total swallowed by max(), freezing the counter mid-think.
              ...(tokenScopesApply
                ? {
                  liveThinkingSettled: state.liveThinkingSettled + state.liveThinkingBlock,
                  liveThinkingBlock: 0,
                }
                : {}),
            },
          };
        }
        return { events: [], state }; // tool_use block start, or a delta with no open message: wait/skip
      }
      if (inner.type === "content_block_delta" && inner.delta?.type === "text_delta" && state.currentMessageId) {
        const ordinal = state.streamTextByIndex[inner.index];
        // A text_delta only maps to a block whose content_block_start we saw;
        // otherwise skip (err safe — no orphan block) rather than invent one.
        if (ordinal === undefined) return { events: [], state };
        const blockId = `${state.currentMessageId}:t${ordinal}`;
        return { events: [{ type: "message_delta", turnId, blockId, text: inner.delta.text }], state };
      }
      if (inner.type === "content_block_delta" && inner.delta?.type === "thinking_delta" && state.currentMessageId) {
        const ordinal = state.streamThinkingByIndex[inner.index];
        if (ordinal === undefined) return { events: [], state };
        // The Agent SDK REDACTS reasoning text from the live stream: every
        // thinking_delta arrives as `{thinking:"", estimated_tokens}` (verified
        // live 2026-07-20; matches the `reasoningVisibility:false` capability).
        // So the streamed text is unavailable and we skip empty deltas rather
        // than build a blank block or journal noise. The full thinking text IS
        // persisted to the on-disk transcript, so the REPLAY path recovers it;
        // this branch stays wired so the moment the SDK exposes streamed text,
        // it flows with no further change.
        //
        // v37 deliberately does NOT read `estimated_tokens` here. It looks like a
        // running total but is the per-FRAME increment — which is why the SDK also
        // publishes a digested `system`/`thinking_tokens` message carrying BOTH
        // `estimated_tokens` (the running total) and `estimated_tokens_delta`. The
        // live token counter reads that message instead; folding this raw field as
        // a total would pin the count at one frame's worth for the whole think.
        const text = inner.delta.thinking;
        if (!text) return { events: [], state };
        const blockId = `${state.currentMessageId}:th${ordinal}`;
        return { events: [{ type: "thinking_delta", turnId, blockId, text }], state };
      }
      if (inner.type === "message_delta") {
        // v37: cumulative output tokens for the message now streaming — the
        // authoritative counterpart to the thinking estimate. Arrives once per
        // message (at its end), so it corrects the estimate rather than driving the
        // counter. Emits nothing but token_progress; the turn's real whole-tree
        // usage still comes from `result`. Pre-v37 this frame was a plain no-op, so
        // skipping a child's is behaviour-identical to before.
        if (!tokenScopesApply) return { events: [], state };
        return claudeTokenProgress(state, inner.usage?.output_tokens);
      }
      return { events: [], state };
    }

    case "assistant": {
      // Subagent-tagged messages (Task-tool children) must never be folded into
      // the main turn's blocks (Appendix B #6). Instead they are surfaced as a
      // structured `subagent_message` carrying the parent tool_use id, so the
      // reducer can nest them under the Task card (never silently dropped).
      if (record.parent_tool_use_id != null) {
        const items = claudeSubagentItemsFromAssistant(record);
        const parentToolUseId = record.parent_tool_use_id;
        const byParent = state.subagentUsageByParent ?? {};
        const previousUsage = byParent[parentToolUseId];
        // v40: bank this run's own tokens/model. Absolute total, never a delta —
        // see SubagentUsage in lib/protocol.ts for why compaction requires that.
        const usage = accumulateSubagentUsage(previousUsage, record);
        const nextState = usage === previousUsage
          ? state
          : { ...state, subagentUsageByParent: { ...byParent, [parentToolUseId]: usage } };
        // Emit on usage alone as well as on items. A record carrying tokens but
        // nothing renderable would otherwise never reach the projection if no
        // further child record followed it, silently under-reporting the run.
        if (items.length === 0 && usage === previousUsage) return { events: [], state: nextState };
        return {
          events: [{
            type: "subagent_message",
            turnId,
            parentToolUseId,
            items,
            ...(usage ? { usage } : {}),
          }],
          state: withKnownClaudeTools(nextState, record.message?.content, parentToolUseId),
        };
      }
      if (typeof record.message?.id !== "string" || !Array.isArray(record.message?.content)) {
        return { events: [{ type: "unknown_event", turnId, raw: record }], state };
      }
      return {
        events: claudeContentEvents(turnId, record.message.id, record.message.content, record.aborted === true),
        state: withKnownClaudeTools(
          typeof record.message.model === "string" && record.message.model
            ? { ...state, servedRawModel: record.message.model }
            : state,
          record.message.content,
          null,
        ),
      };
    }

    case "user": {
      // Mirrors the `assistant` guard above: a subagent's own tool_result must
      // not be folded into the main turn either (Appendix B #6). Surfaced as a
      // structured `subagent_message` so its result merges into the nested tool
      // entry under the Task card.
      if (record.parent_tool_use_id != null) {
        const items = claudeSubagentItemsFromUser(record, state.permissionDeniedToolIds);
        if (items.length === 0) return { events: [], state };
        return { events: [{ type: "subagent_message", turnId, parentToolUseId: record.parent_tool_use_id, items }], state };
      }
      const content = record.message?.content;
      // issue #184: the CLI's `[Request interrupted by user…]` pseudo-user block is
      // a harness artifact, never the operator's words — drop it (either shape).
      if (isClaudeInterruptMarkerContent(content)) return { events: [], state };
      if (!Array.isArray(content)) return { events: [{ type: "unknown_event", turnId, raw: record }], state };
      const events = [];
      for (const item of content) {
        if (item.type !== "tool_result") continue;
        const denied = state.permissionDeniedToolIds.includes(item.tool_use_id);
        events.push({
          type: "tool_end",
          turnId,
          toolId: item.tool_use_id,
          // The structured denial event is the durable explanation. Its matching
          // tool_result prose is render-unsafe and intentionally not journaled.
          output: denied ? null : item.content,
          isError: Boolean(item.is_error),
          // issue #184: the canned abort text is an interruption, not a rejection.
          ...(!denied && item.is_error && isClaudeToolAbortOutput(item.content) ? { interrupted: true } : {}),
        });
        // v56: a TaskCreate result assigns the real task id. Emit the link so the
        // reducer can relabel the pending create; a non-Task result simply never
        // parses to an id (or parses to one that matches no pending create — a no-op).
        if (!item.is_error) {
          const assignedTaskId = taskAssignmentId(item.content);
          if (assignedTaskId && typeof item.tool_use_id === "string" && item.tool_use_id) {
            events.push({ type: "todo_item_id_assigned", turnId: null, toolId: item.tool_use_id, taskId: assignedTaskId });
          }
        }
      }
      return { events, state };
    }

    case "result": {
      const permissionDeniedToolIds = [...state.permissionDeniedToolIds];
      const denialEvents = [];
      if (Array.isArray(record.permission_denials)) {
        for (const denial of record.permission_denials) {
          const event = claudePermissionDeniedEvent(denial, turnId);
          if (!event || permissionDeniedToolIds.includes(event.toolId)) continue;
          // SDKResult*.permission_denials has no reason/agent fields. The same
          // normalizer therefore produces `unknown`, and deliberately ignores
          // its privacy-sensitive tool_input.
          denialEvents.push(event);
          permissionDeniedToolIds.push(event.toolId);
        }
      }
      const modelUsages = normalizedClaudeModelUsages(record.modelUsage);
      const mainUsage = mainClaudeModelUsage(modelUsages, state.servedRawModel);
      // Anthropic's usage object treats cache as additive, not a subset of
      // input: "Total input tokens in a request is the summation of
      // input_tokens, cache_creation_input_tokens, and cache_read_input_tokens"
      // (Messages API). inputTokens excludes the cache buckets, so a correct
      // whole-tree total must add them back — matching the other two Claude
      // readers (usage-analytics parseClaudeFile, session-discovery
      // computeClaudeTranscriptMetrics), which both sum all four fields.
      const fastModeEvent = claudeFastModeEvent(record, turnId);
      const wholeTreeTokens = modelUsages.length > 0
        ? modelUsages.reduce((total, usage) => total
          + (usage.inputTokens ?? 0) + (usage.outputTokens ?? 0)
          + (usage.cacheReadInputTokens ?? 0) + (usage.cacheCreationInputTokens ?? 0), 0)
        : (nonNegativeFiniteNumber(record.usage?.input_tokens) ?? 0)
          + (nonNegativeFiniteNumber(record.usage?.output_tokens) ?? 0)
          + (nonNegativeFiniteNumber(record.usage?.cache_read_input_tokens) ?? 0)
          + (nonNegativeFiniteNumber(record.usage?.cache_creation_input_tokens) ?? 0);
      const events = [
        ...denialEvents,
        {
          type: "usage",
          turnId,
          model: mainUsage?.canonicalModel ?? mainUsage?.model,
          rawModel: mainUsage?.model,
          perTurnTokens: wholeTreeTokens,
          cumulativeTokens: undefined,
          contextWindow: mainUsage?.contextWindow,
          estimatedCostUSD: nonNegativeFiniteNumber(record.total_cost_usd),
          // 0 means the CLI answered from its own command layer without calling a
          // model — the shouldQuery:false path a local slash command (/context,
          // /usage, /compact) takes. Carried so the UI can say so instead of
          // presenting a zero-token, zero-cost turn as a model response.
          numTurns: nonNegativeFiniteNumber(record.num_turns),
          modelUsages,
        },
        // v95: SDKResultSuccess/SDKResultError both carry fast_mode_state /
        // fast_mode_disabled_reason (sdk.d.ts) — the other authoritative
        // read-back point besides system/init.
        ...(fastModeEvent ? [fastModeEvent] : []),
        { type: "turn_end", turnId, outcome: record.is_error ? TURN_OUTCOMES.ERROR : TURN_OUTCOMES.OK },
      ];
      return {
        events,
        state: permissionDeniedToolIds.length === state.permissionDeniedToolIds.length
          ? state
          : { ...state, permissionDeniedToolIds },
      };
    }

    default:
      return { events: [{ type: "unknown_event", turnId, raw: record }], state };
  }
}

// --------------------------------------------------------------------------
// Claude transcript replay (on-disk ~/.claude/projects/**.jsonl -> AgentEvent[])
// --------------------------------------------------------------------------
//
// Unlike claudeToEvents (which normalizes the LIVE stream of one turn), this
// reconstructs a WHOLE prior conversation from Claude's own on-disk transcript
// so a resumed session opens showing its history instead of blank (§8 "derive
// UI replay from native JSONL"). It is pure: (records) -> AgentEvent[]. The
// caller stamps seq/ts and folds through `reduce` exactly like live events.
//
// Turn grouping: a real user text message opens a turn; the assistant
// message(s) and the tool_result user messages that follow belong to it until
// the next user text message. Fidelity is text + tool cards + thinking blocks
// (all via claudeContentEvents); the client `showThinking` preference gates
// whether the thinking blocks are displayed.
// Skipped: sidechain/meta records, subagent-tagged records (parent_tool_use_id,
// Appendix B #6), synthetic assistant messages, and Claude Code's internal
// command-echo user strings (`<local-command-…>`, `<command-…>`, `<bash-…>`).

// Claude Code's internal command-echo wrappers, injected as user records:
// `<command-name>/model…`, `<local-command-stdout>…`, `<bash-input>…`, etc. These
// are not real user prompts. Match ONLY these known tags (anchored) — a blunt
// `startsWith("<")` also drops genuine prompts that happen to open with markup
// (e.g. "<div> won't center") (edge #6). issue #184: the CLI's exact
// `[Request interrupted by user…]` markers are harness artifacts too — never a
// user bubble, never a turn opener.
function isCommandEcho(text) {
  return /^\s*<\/?(command-|local-command|bash-|task-notification)/.test(text) || isClaudeInterruptMarkerText(text);
}

// A user string is real prompt text unless it is a command-echo wrapper. Returns
// trimmed text, or null to skip.
function replayUserText(content) {
  if (typeof content !== "string") return null;
  const text = content.trim();
  if (!text || isCommandEcho(text)) return null;
  return text;
}

function replayUserArrayText(content) {
  const text = content
    .filter((block) => block && block.type === "text" && typeof block.text === "string")
    .map((block) => block.text)
    .join("\n")
    .trim();
  // Command echoes also appear in array form (a single text block) — filter them
  // the same way as the string form so they don't leak in as user bubbles (edge #6).
  if (!text || isCommandEcho(text)) return null;
  return text;
}

// `turnIdPrefix` namespaces the synthetic turnIds this fold mints ("<prefix>-N",
// numbered from 1 with no global offset). Callers that fold DISJOINT record ranges
// of the SAME transcript into ONE projection (pre-adoption replay [0..through] +
// the §8 external tail [cursor..count]) MUST pass distinct prefixes, or the tail's
// "replay-1" collides with the replay's "replay-1" and reduce()'s existing-turn
// no-op guard silently drops the whole external turn. Default "replay" keeps every
// existing caller (pre-adoption replay) byte-for-byte unchanged.
export function claudeTranscriptToEvents(records, { turnIdPrefix = "replay" } = {}) {
  const events = [];
  let turnCount = 0;
  let turnId = null; // the currently-open replay turn, or null between turns

  const closeTurn = () => {
    if (turnId) {
      events.push({ type: "turn_end", turnId, outcome: TURN_OUTCOMES.OK });
      turnId = null;
    }
  };
  const startTurn = (userText) => {
    closeTurn();
    turnCount += 1;
    turnId = `${turnIdPrefix}-${turnCount}`;
    events.push({ type: "turn_started", turnId });
    if (userText != null) events.push({ type: "user_message_accepted", turnId, text: userText });
  };

  for (const record of records) {
    if (!record || typeof record !== "object") continue;
    if (record.isSidechain || record.isMeta || record.parent_tool_use_id != null) continue;
    // Claude Code injects background/subagent completion notices as plain user
    // records (`<task-notification>…`, string content, isMeta:null / isSidechain:
    // false, so the guard above misses them). They carry an authoritative
    // `origin.kind` — skip on that so they never render as a user bubble. The
    // isCommandEcho tag match is a fallback for any that lack the origin field.
    if (record.origin?.kind === "task-notification") continue;

    if (record.type === "user") {
      const content = record.message?.content;
      if (typeof content === "string") {
        const text = replayUserText(content);
        if (text != null) startTurn(text);
        continue;
      }
      if (!Array.isArray(content)) continue;
      const toolResults = content.filter((item) => item && item.type === "tool_result");
      if (toolResults.length) {
        // Tool results belong to the turn already in flight; if somehow none is
        // open (malformed transcript) skip them rather than invent a turn.
        if (turnId) {
          for (const item of toolResults) {
            events.push({
              type: "tool_end",
              turnId,
              toolId: item.tool_use_id,
              output: item.content,
              isError: Boolean(item.is_error),
              // issue #184: mirror the live path's abort classification — but a
              // terminal ("cli") session's "doesn't want to proceed" may be the
              // operator's real rejection at a CLI prompt, so only an SDK-driven
              // record trusts that wording.
              ...(item.is_error && isClaudeToolAbortOutput(item.content, { rejectionWordingIsAbort: record.entrypoint !== "cli" })
                ? { interrupted: true }
                : {}),
            });
            // v56: mirror the live path — a TaskCreate result assigns the real task id,
            // so a resumed session links its incremental Task list exactly as a live one
            // did (without this, TaskUpdates by real id never match creates keyed by the
            // tool_use id, and every item replays stuck at "pending").
            if (!item.is_error) {
              const assignedTaskId = taskAssignmentId(item.content);
              if (assignedTaskId && typeof item.tool_use_id === "string" && item.tool_use_id) {
                events.push({ type: "todo_item_id_assigned", turnId: null, toolId: item.tool_use_id, taskId: assignedTaskId });
              }
            }
          }
        }
        // A user record can carry BOTH tool_result blocks AND new prompt text —
        // the results close the in-flight turn's tools, then the text opens the
        // next turn. Surface that text instead of dropping it (edge #6).
        const extra = replayUserArrayText(content);
        if (extra != null) startTurn(extra);
        continue;
      }
      const text = replayUserArrayText(content);
      if (text != null) startTurn(text);
      continue;
    }

    if (record.type === "assistant") {
      // Synthetic messages are Claude Code's own injected notices, not model
      // output — never render them as assistant turns.
      if (record.message?.model === "<synthetic>") continue;
      if (typeof record.message?.id !== "string" || !Array.isArray(record.message?.content)) continue;
      // An assistant message with no preceding user text (e.g. a transcript that
      // begins mid-turn) still needs a turn to hang on.
      if (!turnId) startTurn(null);
      for (const event of claudeContentEvents(turnId, record.message.id, record.message.content, record.aborted === true)) {
        events.push(event);
      }
    }
    // Every other record type (mode, permission-mode, file-history-*,
    // last-prompt, attachment, queue-operation, system, summary) is control/
    // telemetry noise for replay — intentionally skipped.
  }
  closeTurn();

  // Drop turns with no renderable content. An assistant record that is
  // thinking-only or empty at a turn boundary (no preceding user text) otherwise
  // opens a turn that shows a blank bubble — a user message, assistant text, or a
  // tool card makes a turn worth showing; orphaned reasoning with no prompt or
  // answer does not (edge #6). Thinking blocks ride along inside kept turns; they
  // just don't, alone, keep a turn alive.
  return dropEmptyReplayTurns(events);
}

// §8 external-advancement tail. MUST mint a turnId namespace disjoint from the
// pre-adoption replay (which uses the default "replay-"): both fold slices of the
// SAME transcript into the SAME boot state, and reduce()'s turn_started de-dup
// (`Object.hasOwn(state.turnsById, turnId)`) would silently DROP the external
// turns on any collision, leaving a divider with no bubbles. Centralized here (not
// an inline arg at the call site) so the namespace can't be forgotten, and locked
// by a unit test. See S8_EXTERNAL_ADVANCEMENT_DESIGN.md.
//
// `index` disambiguates SUCCESSIVE ext folds that accumulate into ONE live state
// (a boot detection, then a live detection; or two live detections) — each fold's
// counter restarts at 1, so without a per-fold prefix a later fold's "ext-1"
// collides with an earlier "ext-1" and reduce()'s existing-turn no-op silently
// drops it (the boot-vs-live / live-vs-live axis of the f948884 CRITICAL). The
// manager passes a per-session monotonic index, mirroring the durable-range
// `xr<index>-` scheme. `ext<index>-N` stays disjoint from `replay-*` and `xr*-*`.
export function claudeExternalTailToEvents(records, index = 0) {
  return claudeTranscriptToEvents(records, { turnIdPrefix: `ext${index}` });
}

// §8 identity guard — the set of native assistant message ids ALREADY projected
// into `state`. Assistant blocks are keyed `${messageId}:t<n>` / `${messageId}:th<n>`
// (see claudeContentEvents), so the id is the blockId's prefix before the LAST
// colon; tool blocks are keyed by a bare `toolu_*` id (no colon) and are skipped.
//
// WHY: §8 external detection is otherwise purely POSITIONAL (transcript record
// count vs nativeReplayCursor). The cursor is bumped fire-and-forget at turn_end,
// but the CLI flushes its transcript on its OWN async schedule — so a turn's last
// records can land AFTER the count is taken, leaving the cursor permanently short.
// The next quiet attach then reads those records as "appended past the cursor" and
// folds TETHER'S OWN completed turn back in as an external one, rendering the same
// assistant response a second time under the "advanced outside Tether" divider.
// Positional arithmetic cannot tell that tail apart from genuine outside work;
// message identity can. Pure (no I/O) — safe for the shared browser import.
export function projectedMessageIds(state) {
  const ids = new Set();
  for (const turn of Object.values(state?.turnsById ?? {})) {
    for (const blockId of Object.keys(turn?.blocksById ?? {})) {
      const cut = String(blockId).lastIndexOf(":");
      if (cut > 0) ids.add(String(blockId).slice(0, cut));
    }
  }
  return ids;
}

// §8 identity guard (companion to projectedMessageIds): drop transcript records
// that Tether already owns, so only genuinely-external work survives to be folded.
// Drops an `assistant` record whose `message.id` is already projected, and any
// `user` record chained (via parentUuid) to a dropped record — those carry the
// tool_results of a dropped assistant turn and would otherwise render as orphan
// tool cards. Turns left with nothing renderable are removed downstream by
// dropEmptyReplayTurns, so a fully-accounted tail yields zero events.
//
// Conservative by construction: an empty/absent id set is a pass-through, and a
// genuinely-external turn always carries message ids Tether has never projected,
// so real outside work is never suppressed. Pure — no I/O, no mutation of input.
export function dropJournalOwnedRecords(records, knownMessageIds) {
  if (!knownMessageIds || knownMessageIds.size === 0) return records;
  const droppedUuids = new Set();
  const kept = [];
  for (const record of records) {
    const messageId = record?.message?.id;
    if (record?.type === "assistant" && messageId && knownMessageIds.has(messageId)) {
      if (record.uuid) droppedUuids.add(record.uuid);
      continue;
    }
    if (record?.type === "user" && record?.parentUuid && droppedUuids.has(record.parentUuid)) {
      if (record.uuid) droppedUuids.add(record.uuid); // keep the chain walking
      continue;
    }
    kept.push(record);
  }
  return kept;
}

// §8 DURABLE surfaced-range fold. A previously-surfaced external range that could
// NOT be persisted by promoting the replay boundary (the interleaved case: Tether
// turns sit between the pre-adoption boundary and this external tail) is re-folded
// onto boot state from the native transcript so it survives restarts. Each range
// MUST use a turnId namespace disjoint from `replay-`, `ext-`, AND every OTHER
// range, or reduce()'s turn_started existing-turn no-op silently drops the second
// range (claudeTranscriptToEvents restarts its counter at 1 per call). The range's
// list index makes the prefix unique: "xr0-1", "xr1-1", … Locked by a unit test.
export function claudeSurfacedRangeToEvents(records, index) {
  return claudeTranscriptToEvents(records, { turnIdPrefix: `xr${index}` });
}

const RENDERABLE_REPLAY_EVENTS = new Set([
  "user_message_accepted", "message_started", "message_completed", "tool_start", "tool_end",
]);

function dropEmptyReplayTurns(events) {
  const kept = [];
  let turnBuffer = null; // events of the currently-open turn, or null between turns
  for (const event of events) {
    if (event.type === "turn_started") {
      turnBuffer = [event];
    } else if (turnBuffer) {
      turnBuffer.push(event);
      if (event.type === "turn_end") {
        if (turnBuffer.some((e) => RENDERABLE_REPLAY_EVENTS.has(e.type))) kept.push(...turnBuffer);
        turnBuffer = null;
      }
    } else {
      kept.push(event); // defensive: an event outside any turn (should not occur)
    }
  }
  if (turnBuffer) kept.push(...turnBuffer); // defensive: unterminated final turn
  return kept;
}

// --------------------------------------------------------------------------
// Codex adapter (`codex exec --json` records -> AgentEvent[])
// --------------------------------------------------------------------------
//
// Verified live (§2.2, re-verified 2026-07-23 against codex-cli 0.144.6, real
// ChatGPT auth): thread.started {thread_id}, turn.started {}, item.started /
// item.completed { item:{id,type,...} }, turn.completed { usage }. Item types
// seen live: "agent_message" {text} (item.completed only — no item.started),
// "command_execution" {command, aggregated_output, exit_code, status} where
// status ∈ in_progress|completed|failed and BOTH item.started (in_progress,
// exit_code null) and item.completed fire. "error" items surface as warnings
// (§2.3 #3 — the "skills context budget" notice is a benign informational one,
// never turn-ending). See [[tether-phase5-codex-spike]] for the full capture.
//
// item.started is SWALLOWED (no events). A command_execution fires both
// item.started (status in_progress) and item.completed, so opening the tool card
// on start would leave it permanently "running" whenever the turn goes terminal
// with no item.completed — i.e. exactly the SIGKILL/cancel/crash paths the engine
// classifies as outcome_unknown/cancelled (the reducer marks the turn done but
// does not close open tool blocks). So we open AND close the card atomically on
// item.completed instead — a card can never be orphaned, at the cost of no live
// "running" indicator (tool output arrives whole anyway, as it does for Claude).

export function createCodexAdapterState(turnId) {
  return { turnId, errorMessage: null };
}

export function codexToEvents(record, state) {
  const turnId = state.turnId;

  switch (record.type) {
    case "thread.started":
      return { events: [{ type: "native_session_id", turnId, nativeSessionId: record.thread_id }], state };

    case "turn.started":
    case "item.started":
      // No projection: item.started carries no closed result, and opening a card
      // here risks orphaning it on an interrupted turn (see the note above).
      return { events: [], state };

    case "item.completed": {
      const item = record.item;
      if (!item || typeof item.type !== "string") {
        return { events: [{ type: "unknown_event", turnId, raw: record }], state };
      }
      if (item.type === "error") {
        // issue #121: Codex's skills loader emits the "skills context budget"
        // notice as an error item on every session. It is benign metadata whose
        // only real fix is plugin hygiene OUTSIDE Tether (`codex plugin remove
        // <unused>`) — demote it to silence rather than surface it as a warning
        // (never a turn-ending error, never activity/error state).
        if (typeof item.message === "string" && /skills context budget|descriptions were shortened/i.test(item.message)) {
          return { events: [], state };
        }
        return { events: [{ type: "warning", turnId, message: item.message, raw: record }], state };
      }
      if (item.type === "agent_message") {
        const blockId = `codex:${item.id}`;
        return {
          events: [
            { type: "message_started", turnId, blockId },
            { type: "message_completed", turnId, blockId, text: item.text },
          ],
          state,
        };
      }
      if (item.type === "command_execution") {
        // exit_code can be null on a sandbox/spawn failure, so key isError on the
        // explicit status too (verified live: status "failed" with exit_code 1).
        const isError = item.status === "failed" || (item.exit_code ?? 0) !== 0;
        return {
          events: [
            { type: "tool_start", turnId, toolId: item.id, name: "command_execution", input: { command: item.command } },
            { type: "tool_end", turnId, toolId: item.id, output: item.aggregated_output, isError },
          ],
          state,
        };
      }
      return { events: [{ type: "unknown_event", turnId, raw: record }], state };
    }

    case "turn.completed": {
      const events = [
        {
          type: "usage",
          turnId,
          model: undefined,
          perTurnTokens: (record.usage?.input_tokens ?? 0) + (record.usage?.output_tokens ?? 0),
          cumulativeTokens: undefined,
          contextWindow: undefined,
        },
        { type: "turn_end", turnId, outcome: TURN_OUTCOMES.OK },
      ];
      return { events, state };
    }

    case "error": {
      if (typeof record.message !== "string" || !record.message.trim()) {
        return { events: [{ type: "unknown_event", turnId, raw: record }], state };
      }
      return {
        events: record.message === state.errorMessage ? [] : [{ type: "error", turnId, message: record.message }],
        state: { ...state, errorMessage: record.message },
      };
    }

    case "turn.failed": {
      const message = typeof record.error?.message === "string" && record.error.message.trim()
        ? record.error.message
        : null;
      if (!message) {
        return { events: [{ type: "unknown_event", turnId, raw: record }], state };
      }
      return {
        events: [
          ...(message === state.errorMessage ? [] : [{ type: "error", turnId, message }]),
          { type: "turn_end", turnId, outcome: TURN_OUTCOMES.ERROR },
        ],
        state: { ...state, errorMessage: message },
      };
    }

    default:
      return { events: [{ type: "unknown_event", turnId, raw: record }], state };
  }
}

// --------------------------------------------------------------------------
// Codex transcript replay (on-disk $CODEX_HOME/sessions/**/rollout-*.jsonl
// -> AgentEvent[])
// --------------------------------------------------------------------------
//
// The Codex counterpart of claudeTranscriptToEvents: reconstructs a WHOLE prior
// conversation from Codex's own on-disk rollout JSONL so a resumed session opens
// showing its history instead of blank (§8). Pure: (records) -> AgentEvent[]; the
// caller stamps seq/ts and folds through `reduce` exactly like live events, and
// reuses dropEmptyReplayTurns for the same "no blank turns" guarantee.
//
// Rollout record model (verified live against codex-cli 0.144.6 rollouts, both
// the older `function_call` shape and the newer `custom_tool_call` "responses"
// shape). Every line is { type, payload }. The records used for replay:
//   - event_msg / user_message   -> the CLEAN user prompt (payload.message). This
//       is the real prompt; the parallel `response_item/message` role:"user"
//       records carry INJECTED context (AGENTS.md, developer/permissions
//       instructions, environment) — the analogue of Claude's command-echo
//       wrappers — and are skipped.
//   - response_item / message role:"assistant" -> assistant text (output_text
//       content blocks; empty ones skipped). Chosen over event_msg/agent_message
//       so assistant text and tool cards come from ONE ordered stream.
//   - response_item / function_call (+ function_call_output)      -> tool card
//   - response_item / custom_tool_call (+ custom_tool_call_output) -> tool card
//   - event_msg / sub_agent_activity -> exact spawn call ↔ sibling child id
//       binding, used only by codexTranscriptWithSubagentsToEvents
// Everything else (session_meta, turn_context, world_state, token_count,
// reasoning/agent_reasoning [encrypted, reasoningVisibility:false], task_started/
// task_complete, non-assistant messages) is control/telemetry noise for replay.
//
// Turn grouping mirrors Claude: a user_message opens a turn; the assistant text
// and tool cards that follow belong to it until the next user_message. A tool or
// assistant record with no open turn (transcript begins mid-turn) opens a
// null-user turn so its content still has somewhere to hang.

// Normalize a Codex tool output (function_call_output.output is a plain string;
// custom_tool_call_output.output is either a string or an array of {type,text}
// parts; some shapes wrap {output, metadata}) into a single display string.
function codexOutputToText(output) {
  if (output == null) return "";
  if (typeof output === "string") return output;
  if (Array.isArray(output)) {
    return output.map((part) => (typeof part === "string" ? part : typeof part?.text === "string" ? part.text : "")).join("");
  }
  if (typeof output === "object" && typeof output.output === "string") return output.output;
  return String(output);
}

// Build the tool card's input. A function_call carries `arguments` as a JSON
// string; parse it (fall back to the raw string). A custom_tool_call carries a
// raw string `input` (a shell snippet / patch body).
function codexCallInput(payload) {
  if (payload.type === "function_call") {
    if (typeof payload.arguments === "string") {
      try {
        return JSON.parse(payload.arguments);
      } catch {
        return { arguments: payload.arguments };
      }
    }
    return payload.arguments ?? {};
  }
  return typeof payload.input === "string" ? { input: payload.input } : payload.input ?? {};
}

function codexReplayToolName(payload) {
  const name = typeof payload?.name === "string" && payload.name ? payload.name : "tool";
  if (payload?.namespace !== "collaboration") return name;
  const camelName = name.replace(/_([a-z])/g, (_match, letter) => letter.toUpperCase());
  return `collaboration:${camelName}`;
}

const CODEX_REPLAY_SUBAGENT_LIMITS = Object.freeze({
  items: 512,
  messageChars: 256_000,
  toolOutputChars: 256_000,
});

function codexReplayChildItems(records) {
  const items = [];
  const failedCalls = new Set();
  for (const record of records ?? []) {
    const payload = record?.payload;
    if (record?.type !== "response_item" || !payload || typeof payload !== "object") continue;
    if (payload.type === "message" && payload.role === "assistant" && Array.isArray(payload.content)) {
      let ordinal = 0;
      for (const block of payload.content) {
        if (block?.type !== "output_text" || typeof block.text !== "string" || !block.text.trim()) continue;
        if (items.length >= CODEX_REPLAY_SUBAGENT_LIMITS.items) return items;
        items.push({
          kind: "message",
          key: `${typeof payload.id === "string" && payload.id ? payload.id : "msg"}:${ordinal}`,
          text: replayBoundedText(block.text, CODEX_REPLAY_SUBAGENT_LIMITS.messageChars),
        });
        ordinal += 1;
      }
      continue;
    }
    if ((payload.type === "function_call" || payload.type === "custom_tool_call") && payload.call_id) {
      if (payload.status === "failed") failedCalls.add(payload.call_id);
      if (items.length >= CODEX_REPLAY_SUBAGENT_LIMITS.items) return items;
      items.push({
        kind: "tool",
        key: payload.call_id,
        name: codexReplayToolName(payload),
        input: replayBoundedValue(codexCallInput(payload)),
      });
      continue;
    }
    if ((payload.type === "function_call_output" || payload.type === "custom_tool_call_output") && payload.call_id) {
      if (items.length >= CODEX_REPLAY_SUBAGENT_LIMITS.items) return items;
      items.push({
        kind: "tool_result",
        key: payload.call_id,
        output: replayBoundedText(codexOutputToText(payload.output), CODEX_REPLAY_SUBAGENT_LIMITS.toolOutputChars),
        isError: failedCalls.has(payload.call_id),
      });
    }
  }
  return items;
}

function codexReplayChildUsage(records) {
  let usage = null;
  let model = null;
  for (const record of records ?? []) {
    const payload = record?.payload;
    if (record?.type === "event_msg" && payload?.type === "token_count"
      && payload.info?.total_token_usage && typeof payload.info.total_token_usage === "object") {
      usage = payload.info.total_token_usage;
    }
    if (record?.type === "turn_context") {
      const candidate = payload?.model ?? payload?.collaboration_mode?.settings?.model;
      if (typeof candidate === "string" && candidate) model = candidate;
    }
  }
  if (!usage) return null;
  const hasSplit = Number.isFinite(usage.input_tokens) || Number.isFinite(usage.output_tokens)
    || Number.isFinite(usage.cached_input_tokens) || Number.isFinite(usage.cache_write_input_tokens);
  const inputTokens = hasSplit
    ? Math.max(0, Number(usage.input_tokens) || 0)
    : Math.max(0, Number(usage.total_tokens) || 0);
  const outputTokens = hasSplit ? Math.max(0, Number(usage.output_tokens) || 0) : 0;
  const cacheReadInputTokens = hasSplit ? Math.max(0, Number(usage.cached_input_tokens) || 0) : 0;
  const cacheCreationInputTokens = hasSplit ? Math.max(0, Number(usage.cache_write_input_tokens) || 0) : 0;
  if (inputTokens + outputTokens + cacheReadInputTokens + cacheCreationInputTokens === 0) return null;
  return {
    ...(model ? { model } : {}),
    inputTokens,
    outputTokens,
    cacheReadInputTokens,
    cacheCreationInputTokens,
  };
}

function codexReplayChildMetadata(records, activity = null, descriptor = null) {
  const metadata = records?.find((record) => record?.type === "session_meta")?.payload;
  const spawn = metadata?.source?.subagent?.thread_spawn;
  const firstText = (...values) => {
    for (const value of values) {
      if (typeof value === "string" && value.trim()) return value.trim();
    }
    return null;
  };
  const agentNickname = firstText(
    metadata?.agent_nickname,
    spawn?.agent_nickname,
    descriptor?.agentNickname,
  );
  const agentPath = firstText(
    metadata?.agent_path,
    spawn?.agent_path,
    descriptor?.agentPath,
    activity?.agent_path,
  );
  return { agentNickname, agentPath };
}

function codexReplaySubagentBindings(records, readChild, childThreadIds) {
  if (typeof readChild !== "function") return new Map();
  const spawnCallIds = [];
  const activities = new Map();
  for (const record of records ?? []) {
    const payload = record?.payload;
    if (record?.type === "response_item"
      && (payload?.type === "function_call" || payload?.type === "custom_tool_call")
      && payload.call_id && codexReplayToolName(payload) === "collaboration:spawnAgent") {
      spawnCallIds.push(payload.call_id);
    }
    if (record?.type === "event_msg" && payload?.type === "sub_agent_activity"
      && typeof payload.event_id === "string" && typeof payload.agent_thread_id === "string") {
      activities.set(payload.event_id, payload);
    }
  }
  const bindings = new Map();
  const boundChildIds = new Set();
  const loadBinding = (childThreadId, activity = null) => {
    let child;
    try { child = readChild(childThreadId); } catch { return null; }
    const childRecords = Array.isArray(child) ? child : child?.records;
    if (!Array.isArray(childRecords)) return null;
    const { agentNickname, agentPath } = codexReplayChildMetadata(childRecords, activity, child);
    return {
      childThreadId,
      childRecords,
      title: agentNickname ?? agentPath,
    };
  };
  for (const callId of spawnCallIds) {
    const activity = activities.get(callId);
    if (!activity) continue;
    const binding = loadBinding(activity.agent_thread_id, activity);
    if (!binding) continue;
    bindings.set(callId, binding);
    boundChildIds.add(binding.childThreadId);
  }
  // Some older rollouts lack sub_agent_activity. Discovery/DB still gives the
  // parent-child edge; pair the remaining children and spawn calls in their
  // stable recorded order as a compatibility fallback.
  const remainingCalls = spawnCallIds.filter((callId) => !bindings.has(callId));
  const remainingChildren = (Array.isArray(childThreadIds) ? childThreadIds : [])
    .filter((childThreadId) => typeof childThreadId === "string" && !boundChildIds.has(childThreadId));
  for (let index = 0; index < Math.min(remainingCalls.length, remainingChildren.length); index += 1) {
    const binding = loadBinding(remainingChildren[index]);
    if (binding) bindings.set(remainingCalls[index], binding);
  }
  return bindings;
}

export function codexTranscriptToEvents(records, { turnIdPrefix = "replay" } = {}) {
  return codexTranscriptToEventsInternal(records, { turnIdPrefix });
}

export function codexTranscriptWithSubagentsToEvents(
  records,
  { turnIdPrefix = "replay", readChild = () => null, childThreadIds = [] } = {},
) {
  return codexTranscriptToEventsInternal(records, { turnIdPrefix, readChild, childThreadIds });
}

/**
 * §8 external-advancement fold for a Codex rollout tail (issue #148).
 *
 * The counterpart of `claudeExternalTailToEvents`: the records a LIVE run wrote
 * past Tether's accounted-for cursor, folded with a DISJOINT turnId namespace
 * (`ext<index>-N`) so successive tail folds and the session's own replayed turns
 * can coexist in one projection without colliding (the reducer drops a turn id
 * it has already seen).
 *
 * Child (sub-agent) rollouts are deliberately NOT tailed here: a watched thread's
 * children are separate files with their own cursors, and the watch view is about
 * the parent run's progress. The pre-adoption replay still folds them
 * (`codexTranscriptWithSubagentsToEvents`).
 */
export function codexExternalTailToEvents(records, index = 0) {
  return codexTranscriptToEvents(records, { turnIdPrefix: `ext${index}` });
}

function codexTranscriptToEventsInternal(
  records,
  { turnIdPrefix = "replay", readChild = null, childThreadIds = [] } = {},
) {
  const events = [];
  let turnCount = 0;
  let turnId = null; // the currently-open replay turn, or null between turns
  let messageSeq = 0; // globally-unique blockId source for assistant message blocks
  const failedCalls = new Set(); // call_ids whose tool call reported status "failed"
  const subagentBindings = codexReplaySubagentBindings(records, readChild, childThreadIds);

  const closeTurn = () => {
    if (turnId) {
      events.push({ type: "turn_end", turnId, outcome: TURN_OUTCOMES.OK });
      turnId = null;
    }
  };
  const startTurn = (userText) => {
    closeTurn();
    turnCount += 1;
    turnId = `${turnIdPrefix}-${turnCount}`;
    events.push({ type: "turn_started", turnId });
    if (userText != null) events.push({ type: "user_message_accepted", turnId, text: userText });
  };
  const ensureTurn = () => {
    if (!turnId) startTurn(null);
  };

  for (const record of records) {
    if (!record || typeof record !== "object") continue;
    const payload = record.payload;
    if (!payload || typeof payload !== "object") continue;

    if (record.type === "event_msg" && payload.type === "user_message") {
      const text = typeof payload.message === "string" ? payload.message.trim() : "";
      if (text) startTurn(text);
      continue;
    }

    if (record.type === "response_item") {
      if (payload.type === "message" && payload.role === "assistant" && Array.isArray(payload.content)) {
        for (const block of payload.content) {
          if (block?.type === "output_text" && typeof block.text === "string" && block.text.trim()) {
            ensureTurn();
            const blockId = `codex-msg-${(messageSeq += 1)}`;
            events.push({ type: "message_started", turnId, blockId });
            events.push({ type: "message_completed", turnId, blockId, text: block.text });
          }
        }
        continue;
      }
      if ((payload.type === "function_call" || payload.type === "custom_tool_call") && payload.call_id) {
        ensureTurn();
        if (payload.status === "failed") failedCalls.add(payload.call_id);
        const binding = subagentBindings.get(payload.call_id);
        const rawInput = codexCallInput(payload);
        const input = binding?.title && rawInput && typeof rawInput === "object" && !Array.isArray(rawInput)
          ? { ...rawInput, description: binding.title }
          : rawInput;
        events.push({
          type: "tool_start",
          turnId,
          toolId: payload.call_id,
          name: codexReplayToolName(payload),
          input,
        });
        if (binding) {
          const items = codexReplayChildItems(binding.childRecords);
          const usage = codexReplayChildUsage(binding.childRecords);
          if (items.length > 0 || usage !== null) {
            events.push({
              type: "subagent_message",
              turnId,
              parentToolUseId: payload.call_id,
              items,
              ...(usage === null ? {} : { usage }),
            });
          }
        }
        continue;
      }
      if ((payload.type === "function_call_output" || payload.type === "custom_tool_call_output") && payload.call_id) {
        // A result closes the tool card already opened by its call. If somehow no
        // turn is open (malformed transcript) skip rather than invent one.
        if (turnId) {
          events.push({
            type: "tool_end",
            turnId,
            toolId: payload.call_id,
            output: codexOutputToText(payload.output),
            isError: failedCalls.has(payload.call_id),
          });
        }
        continue;
      }
    }
    // Every other record type is control/telemetry noise for replay — skipped.
  }
  closeTurn();

  // Drop turns with no renderable content (shared with the Claude replay path).
  return dropEmptyReplayTurns(events);
}

// --------------------------------------------------------------------------
// OpenCode (opencode-ai CLI) adapter
// --------------------------------------------------------------------------
//
// `opencode run --format json` emits one JSON object per line on stdout. Each
// record carries { type, timestamp, sessionID, part }. The record types seen
// during a turn (verified live against opencode-ai 1.18.x):
//   - step_start:  a model step begins (one per assistant message / tool round).
//                  part.type === "step-start".
//   - text:        a final assistant text chunk (part.type === "text",
//                  part.text). opencode does NOT stream deltas — the whole text
//                  arrives in one record per step.
//   - tool_use:    a tool call. part.type === "tool", part.tool is the tool
//                  name, part.callID is the stable id, part.state holds status
//                  ("completed" | "error" | "running"), input, output, and
//                  metadata. A single record carries the COMPLETED tool (the
//                  CLI emits it once the tool has finished), so it maps to a
//                  tool_start+tool_end pair (the reducer opens the card then
//                  immediately closes it — same shape as the codex
//                  item.completed/command_execution path).
//   - step_finish: a step ended. part.reason === "stop" means the turn is over;
//                  "tool-calls" means more steps follow. part.tokens carries the
//                  cumulative token usage for the whole turn so far.
//
// There is no separate turn.started/turn.completed envelope. The turn is the
// full `opencode run` invocation: it starts when the first step_start arrives
// (or the first record of any kind, since sessionID is known up front) and
// ends on the first step_finish with reason "stop" (or the process exiting
// without one — recovered as outcome_unknown by the engine).
//
// sessionID is stable for the whole `opencode run` (and stable across resume
// via `--session <id>`), so it is the native session id; the adapter emits it
// once as native_session_id the first time it sees any record.
//
// There are no interactive per-tool approvals in the `run` subcommand: with
// `--auto` every tool runs; without it, a tool that needs permission is
// auto-rejected and reported with state.status "error". The engine therefore
// advertises interactiveApprovals: false (like codex).

export function createOpencodeAdapterState(turnId) {
  return { turnId, emittedNativeId: false, errorMessage: null };
}

function opencodeToolName(part) {
  return typeof part?.tool === "string" && part.tool ? part.tool : "tool";
}

function opencodeToolOutput(state) {
  // state.output is a plain string for most tools; some tools embed the output
  // under state.metadata.output. Prefer the top-level output, then metadata.
  if (typeof state?.output === "string") return state.output;
  if (state?.metadata && typeof state.metadata.output === "string") return state.metadata.output;
  if (state?.error && typeof state.error === "string") return state.error;
  return "";
}

export function opencodeToEvents(record, state) {
  const turnId = state.turnId;

  // Every record carries sessionID; emit native_session_id once (the engine
  // also reads it off the first record, but emitting here keeps the adapter
  // self-sufficient and mirrors how the codex adapter surfaces thread.started).
  if (!state.emittedNativeId && typeof record.sessionID === "string" && record.sessionID) {
    const events = [{ type: "native_session_id", turnId, nativeSessionId: record.sessionID }];
    state = { ...state, emittedNativeId: true };
    const rest = opencodeToEventsByType(record, state, turnId);
    return { events: [...events, ...rest.events], state: rest.state };
  }
  return opencodeToEventsByType(record, state, turnId);
}

function opencodeToEventsByType(record, state, turnId) {
  switch (record.type) {
    case "step_start":
      return { events: [], state };

    case "text": {
      const text = typeof record.part?.text === "string" ? record.part.text : "";
      if (!text) return { events: [], state };
      const blockId = `opencode:${record.part?.id ?? record.timestamp ?? turnId}`;
      return {
        events: [
          { type: "message_started", turnId, blockId },
          { type: "message_completed", turnId, blockId, text },
        ],
        state,
      };
    }

    case "tool_use": {
      const part = record.part ?? {};
      const toolId = typeof part.callID === "string" && part.callID ? part.callID : `opencode-tool-${record.timestamp ?? turnId}`;
      const name = opencodeToolName(part);
      const input = part.state?.input ?? {};
      const toolState = part.state ?? {};
      const isError = toolState.status === "error";
      const output = opencodeToolOutput(toolState);
      return {
        events: [
          { type: "tool_start", turnId, toolId, name, input },
          { type: "tool_end", turnId, toolId, output, isError },
        ],
        state,
      };
    }

    case "step_finish": {
      const part = record.part ?? {};
      const tokens = part.tokens ?? {};
      const perTurnTokens =
        (typeof tokens.input === "number" ? tokens.input : 0) +
        (typeof tokens.output === "number" ? tokens.output : 0);
      const events = [
        {
          type: "usage",
          turnId,
          model: undefined,
          perTurnTokens,
          cumulativeTokens: undefined,
          contextWindow: undefined,
        },
      ];
      // "stop" ends the turn; anything else (tool-calls, length, content-filter,
      // …) means more steps follow — only the process exiting without a "stop"
      // is treated as outcome_unknown by the engine.
      if (part.reason === "stop") {
        events.push({ type: "turn_end", turnId, outcome: TURN_OUTCOMES.OK });
      }
      return { events, state };
    }

    case "error": {
      // opencode emits error records in two shapes:
      //   1. { type: "error", message: "..." }                      (string)
      //   2. { type: "error", error: { name, data: { message } } }  (object, e.g. ProviderAuthError)
      // Extract a human-readable message from whichever shape arrived.
      const message = typeof record.message === "string" && record.message.trim()
        ? record.message
        : typeof record.error === "string" && record.error.trim()
          ? record.error
          : record.error && typeof record.error === "object"
            ? (typeof record.error.data?.message === "string" && record.error.data.message.trim()
                ? `${record.error.name ?? "error"}: ${record.error.data.message}`
                : typeof record.error.name === "string" && record.error.name.trim()
                  ? record.error.name
                  : null)
            : null;
      if (!message) return { events: [{ type: "unknown_event", turnId, raw: record }], state };
      if (message === state.errorMessage) return { events: [], state };
      return {
        events: [{ type: "error", turnId, message }],
        state: { ...state, errorMessage: message },
      };
    }

    default:
      return { events: [{ type: "unknown_event", turnId, raw: record }], state };
  }
}

// --------------------------------------------------------------------------
// OpenCode transcript replay (on-disk session export -> AgentEvent[])
// --------------------------------------------------------------------------
//
// `opencode export <sessionID>` emits a JSON document with an `info` and a
// `messages` array. Each message has { info: { role, time, ... }, parts: [...] }.
// A user message opens a turn (info.role === "user"); assistant text and tool
// parts fill it until the next user message. Pure: (records) -> AgentEvent[];
// the caller stamps seq/ts and folds through `reduce` exactly like live events.
//
// This is a best-effort replay for the resume picker. The authoritative native
// transcript is opencode's own SQLite store; this adapter reads only the
// exported JSON shape so a resumed chat opens showing prior conversation.
export function opencodeTranscriptToEvents(exported, { turnIdPrefix = "replay" } = {}) {
  return opencodeTranscriptToEventsInternal(exported, { turnIdPrefix });
}

// opencodeTranscriptToEvents + child-session folding. opencode's `task` tool
// runs its sub-agent in a SEPARATE native session (a sibling row in the SQLite
// store whose `parent_id` names this session). The live serve-v2 adapter folds
// that child's records into the parent task block as a nested subagent thread
// (issue #20); this replay variant does the same for DISCOVERED sessions, whose
// parent transcript carries the child id on the task part's
// `state.metadata.sessionId`. `readChild(id)` is injected (the SQLite reader
// lives server-side — this module is browser-shared and strictly pure); a child
// that is missing/unreadable is skipped, never fabricated. The folded thread
// renders as the same subagent TAB the live path uses, so a terminal-run
// opencode session's sub-agent shows up inside the chat exactly like Claude's.
export function opencodeTranscriptWithSubagentsToEvents(exported, { turnIdPrefix = "replay", readChild = () => null } = {}) {
  return opencodeTranscriptToEventsInternal(exported, { turnIdPrefix, readChild });
}

function opencodeTranscriptToEventsInternal(exported, { turnIdPrefix = "replay", readChild = null } = {}) {
  const events = [];
  if (!exported || typeof exported !== "object") return events;
  const messages = Array.isArray(exported.messages) ? exported.messages : [];
  let turnCount = 0;
  let turnId = null;
  let messageSeq = 0;

  const closeTurn = () => {
    if (turnId) {
      events.push({ type: "turn_end", turnId, outcome: TURN_OUTCOMES.OK });
      turnId = null;
    }
  };
  const startTurn = (userText) => {
    closeTurn();
    turnCount += 1;
    turnId = `${turnIdPrefix}-${turnCount}`;
    events.push({ type: "turn_started", turnId });
    if (userText != null) events.push({ type: "user_message_accepted", turnId, text: userText });
  };
  const ensureTurn = () => {
    if (!turnId) startTurn(null);
  };

  for (const message of messages) {
    const role = message?.info?.role;
    const parts = Array.isArray(message?.parts) ? message.parts : [];
    if (role === "user") {
      // Reconstruct the user prompt from text parts.
      const text = parts
        .map((part) => (part?.type === "text" && typeof part.text === "string" ? part.text : ""))
        .join("")
        .trim();
      if (text) startTurn(text);
      continue;
    }
    if (role === "assistant") {
      for (const part of parts) {
        if (part?.type === "text" && typeof part.text === "string" && part.text.trim()) {
          ensureTurn();
          messageSeq += 1;
          const blockId = `opencode-msg-${messageSeq}`;
          events.push({ type: "message_started", turnId, blockId });
          events.push({ type: "message_completed", turnId, blockId, text: part.text });
        } else if (part?.type === "tool" && typeof part.tool === "string") {
          ensureTurn();
          const toolId = typeof part.callID === "string" && part.callID ? part.callID : `opencode-tool-${messageSeq}`;
          const input = part.state?.input ?? {};
          const isError = part.state?.status === "error";
          const output = opencodeToolOutput(part.state ?? {});
          events.push({ type: "tool_start", turnId, toolId, name: part.tool, input });
          events.push({ type: "tool_end", turnId, toolId, output, isError });
          // Only opencode's `task` tool launches a sub-agent (issue #20); a
          // coincidental metadata.sessionId on another tool is not a child
          // binding.
          if (readChild && part.tool === "task") appendReplayChildThread(events, part, toolId, turnId, readChild);
        }
      }
    }
  }
  closeTurn();
  return dropEmptyReplayTurns(events);
}

// Bound the folded child thread the same way the live serve-v2 adapter bounds
// its nested items (OPENCODE_SERVE_ADAPTER_LIMITS). Keeping the replay limits in
// lockstep means a session's subagent tab truncates identically whether it was
// streamed live or rebuilt from discovery.
const OPENCODE_REPLAY_SUBAGENT_LIMITS = Object.freeze({
  items: 512,
  messageChars: 256_000,
  toolOutputChars: 256_000,
});

function replayChildDocToItems(doc) {
  // `doc` is the readOpencodeTranscript shape: { info, messages }, where each
  // message is { id, info: { role }, parts: [ { type, text?, tool?, callID?,
  // id?, state? } ] }. Stateless mapping of the child's FINAL parts — replay
  // has no deltas, and each part's stored state is already cumulative.
  const items = [];
  const childUserMessageIds = new Set();
  for (const message of doc?.messages ?? []) {
    const role = message?.info?.role;
    if (role === "user") {
      // The child's user message is the task prompt echoed back; the run panel
      // already shows it from the launcher's own input.prompt.
      if (typeof message?.id === "string" && message.id) childUserMessageIds.add(message.id);
      continue;
    }
    if (role !== "assistant") continue;
    const messageId = typeof message?.id === "string" && message.id ? message.id : "msg";
    let partIndex = 0;
    for (const part of message?.parts ?? []) {
      partIndex += 1;
      const partType = part?.type;
      const partID = typeof part?.id === "string" && part.id ? part.id : null;
      if (partID && childUserMessageIds.has(partID)) continue;
      if (partType === "text" || partType === "reasoning") {
        const text = typeof part?.text === "string" ? part.text : "";
        if (!text) continue;
        if (items.length >= OPENCODE_REPLAY_SUBAGENT_LIMITS.items) return items;
        items.push({
          kind: partType === "text" ? "message" : "thinking",
          // The SQLite store does not retain the part's public id on
          // text/reasoning parts (only `type`/`text`/`time`), so key on
          // message id + position — stable across replays, and the reducer
          // merges by key so the fullest text still wins.
          key: partID ?? `${messageId}:${partIndex}`,
          text: replayBoundedText(text, OPENCODE_REPLAY_SUBAGENT_LIMITS.messageChars),
        });
      } else if (partType === "tool") {
        const callID = typeof part?.callID === "string" && part.callID ? part.callID : null;
        if (!callID) continue;
        const toolState = part?.state && typeof part.state === "object" ? part.state : {};
        const status = typeof toolState.status === "string" ? toolState.status : null;
        if (status !== "completed" && status !== "error") continue;
        if (items.length >= OPENCODE_REPLAY_SUBAGENT_LIMITS.items) return items;
        items.push({
          kind: "tool",
          key: callID,
          name: opencodeToolName(part),
          input: replayBoundedValue(toolState.input ?? {}),
        });
        if (items.length >= OPENCODE_REPLAY_SUBAGENT_LIMITS.items) return items;
        const exit = Number.isInteger(toolState.metadata?.exit) ? toolState.metadata.exit : null;
        items.push({
          kind: "tool_result",
          key: callID,
          output: replayBoundedText(opencodeToolOutput(toolState), OPENCODE_REPLAY_SUBAGENT_LIMITS.toolOutputChars),
          isError: status === "error" || (exit !== null && exit !== 0),
        });
      }
    }
  }
  return items;
}

function replayChildUsage(doc) {
  // The child's cumulative token totals and last served model. `info` is the
  // RAW SQLite session row readOpencodeTranscript returns: tokens live in flat
  // `tokens_*` columns and `model` is a JSON string — but the serve API shape
  // ({ tokens, model }) is also accepted so fixtures and future readers match
  // either source. All-zero readings are skipped so an unstarted child keeps
  // "uncaptured is null, never 0".
  const info = doc?.info;
  const tokens = info?.tokens && typeof info.tokens === "object" && !Array.isArray(info.tokens)
    ? info.tokens
    : {
        input: info?.tokens_input,
        output: info?.tokens_output,
        cache: { read: info?.tokens_cache_read, write: info?.tokens_cache_write },
      };
  const inputTokens = Number(tokens.input);
  const outputTokens = Number(tokens.output);
  if (!Number.isFinite(inputTokens) || !Number.isFinite(outputTokens)) return null;
  const cache = tokens.cache && typeof tokens.cache === "object" ? tokens.cache : {};
  const cacheReadInputTokens = Number(cache.read) || 0;
  const cacheCreationInputTokens = Number(cache.write) || 0;
  if (inputTokens + outputTokens + cacheReadInputTokens + cacheCreationInputTokens === 0) return null;
  const rawModel = info?.model;
  let model = null;
  if (typeof rawModel === "string") {
    try { model = JSON.parse(rawModel); } catch { model = null; }
  } else if (rawModel && typeof rawModel === "object") {
    model = rawModel;
  }
  const modelId = typeof model === "string"
    ? model
    : typeof model?.modelID === "string"
      ? model.modelID
      : typeof model?.id === "string"
        ? model.id
        : null;
  return {
    ...(modelId ? { model: modelId } : {}),
    inputTokens,
    outputTokens,
    cacheReadInputTokens,
    cacheCreationInputTokens,
  };
}

function appendReplayChildThread(events, part, parentToolUseId, turnId, readChild) {
  const metadata = part?.state?.metadata;
  const childId = metadata && typeof metadata.sessionId === "string" && metadata.sessionId
    ? metadata.sessionId
    : null;
  if (!childId) return;
  let child;
  try {
    child = readChild(childId);
  } catch {
    return; // a failed read is a skipped fold, never a thrown replay
  }
  if (!child || typeof child !== "object") return;
  const items = replayChildDocToItems(child);
  const usage = replayChildUsage(child);
  if (items.length === 0 && usage === null) return;
  const folded = {
    type: "subagent_message",
    turnId,
    parentToolUseId,
    items,
    ...(usage === null ? {} : { usage }),
  };
  events.push(folded);
}

function replayBoundedText(value, maxChars) {
  if (typeof value !== "string") return "";
  return value.length <= maxChars ? value : value.slice(0, maxChars);
}

function replayBoundedValue(value, depth = 0) {
  if (value == null || typeof value === "boolean") return value;
  if (typeof value === "string") return replayBoundedText(value, PROVIDER_PROJECTION_LIMITS.proseChars);
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (depth >= 5) return "[truncated]";
  if (Array.isArray(value)) {
    return value.slice(0, 64).map((item) => replayBoundedValue(item, depth + 1));
  }
  if (typeof value === "object") {
    const out = {};
    for (const [key, item] of Object.entries(value).slice(0, 64)) {
      out[String(key).slice(0, PROVIDER_PROJECTION_LIMITS.labelChars)] = replayBoundedValue(item, depth + 1);
    }
    return out;
  }
  return null;
}

// Reasonix's durable store keeps an OpenAI-shaped message list. This adapter is
// intentionally replay-only: it reconstructs readable user/assistant/tool turns
// without attempting to reproduce live ACP status notifications.
export function reasonixTranscriptToEvents(messages, { turnIdPrefix = "replay" } = {}) {
  const events = [];
  let turnId = null;
  let turnCount = 0;
  let blockSeq = 0;
  const openTools = new Map();
  const closeTurn = () => {
    if (!turnId) return;
    events.push({ type: "turn_end", turnId, outcome: TURN_OUTCOMES.OK });
    turnId = null;
    openTools.clear();
  };
  const startTurn = (text) => {
    closeTurn();
    turnCount += 1;
    turnId = `${turnIdPrefix}-${turnCount}`;
    events.push({ type: "turn_started", turnId });
    if (text) events.push({ type: "user_message_accepted", turnId, text });
  };
  for (const message of Array.isArray(messages) ? messages : []) {
    if (message?.role === "user") {
      const text = typeof message.content === "string" ? message.content.trim() : "";
      if (text) startTurn(text);
      continue;
    }
    if (!turnId || message?.role === "system") continue;
    if (message.role === "assistant") {
      const thinking = typeof message.reasoning_content === "string" ? message.reasoning_content.trim() : "";
      if (thinking) {
        blockSeq += 1;
        events.push({ type: "thinking_completed", turnId, blockId: `reasonix-thinking-${blockSeq}`, text: thinking });
      }
      const text = typeof message.content === "string" ? message.content.trim() : "";
      if (text) {
        blockSeq += 1;
        const blockId = `reasonix-message-${blockSeq}`;
        events.push({ type: "message_started", turnId, blockId });
        events.push({ type: "message_completed", turnId, blockId, text });
      }
      for (const call of Array.isArray(message.tool_calls) ? message.tool_calls : []) {
        const fn = call?.function && typeof call.function === "object" ? call.function : call;
        const toolId = typeof call?.id === "string" && call.id ? call.id : `reasonix-tool-${++blockSeq}`;
        const name = typeof fn?.name === "string" && fn.name ? fn.name : "tool";
        let input = fn?.arguments ?? {};
        if (typeof input === "string") {
          try { input = JSON.parse(input); } catch { input = { arguments: input }; }
        }
        openTools.set(toolId, name);
        events.push({ type: "tool_start", turnId, toolId, name, input });
      }
    } else if (message.role === "tool") {
      const toolId = typeof message.tool_call_id === "string" && message.tool_call_id
        ? message.tool_call_id
        : `reasonix-tool-${++blockSeq}`;
      const name = openTools.get(toolId) ?? message.name ?? "tool";
      const output = typeof message.content === "string" ? message.content : JSON.stringify(message.content ?? null);
      if (!openTools.has(toolId)) events.push({ type: "tool_start", turnId, toolId, name, input: {} });
      events.push({ type: "tool_end", turnId, toolId, output, isError: false });
      openTools.delete(toolId);
    }
  }
  closeTurn();
  return dropEmptyReplayTurns(events);
}

// Gemini CLI JSON/JSONL transcripts use `type:user|gemini` messages. Like the
// other replay adapters, this projects only durable conversational content.
export function geminiTranscriptToEvents(document, { turnIdPrefix = "replay" } = {}) {
  const events = [];
  const messages = Array.isArray(document?.messages) ? document.messages : [];
  let turnId = null;
  let turnCount = 0;
  let blockSeq = 0;
  const closeTurn = () => {
    if (!turnId) return;
    events.push({ type: "turn_end", turnId, outcome: TURN_OUTCOMES.OK });
    turnId = null;
  };
  for (const message of messages) {
    if (message?.type === "user") {
      const text = typeof message.content === "string" ? message.content.trim() : "";
      if (!text) continue;
      closeTurn();
      turnCount += 1;
      turnId = `${turnIdPrefix}-${turnCount}`;
      events.push({ type: "turn_started", turnId });
      events.push({ type: "user_message_accepted", turnId, text });
      continue;
    }
    if (message?.type !== "gemini" || !turnId) continue;
    const thinking = typeof message.thoughts === "string" ? message.thoughts.trim() : "";
    if (thinking) {
      blockSeq += 1;
      events.push({ type: "thinking_completed", turnId, blockId: `gemini-thinking-${blockSeq}`, text: thinking });
    }
    const text = typeof message.content === "string" ? message.content.trim() : "";
    if (text) {
      blockSeq += 1;
      const blockId = `gemini-message-${blockSeq}`;
      events.push({ type: "message_started", turnId, blockId });
      events.push({ type: "message_completed", turnId, blockId, text });
    }
    for (const call of Array.isArray(message.toolCalls) ? message.toolCalls : []) {
      const toolId = typeof call?.id === "string" && call.id ? call.id : `gemini-tool-${++blockSeq}`;
      const name = typeof call?.name === "string" && call.name ? call.name : "tool";
      const output = typeof call?.result === "string" ? call.result : JSON.stringify(call?.result ?? call?.resultDisplay ?? null);
      events.push({ type: "tool_start", turnId, toolId, name, input: call?.args ?? {} });
      events.push({ type: "tool_end", turnId, toolId, output, isError: call?.status === "error" });
    }
  }
  closeTurn();
  return dropEmptyReplayTurns(events);
}

// --------------------------------------------------------------------------
// Reasonix (reasonix acp — Agent Client Protocol over stdio JSON-RPC) adapter
// --------------------------------------------------------------------------
//
// `reasonix acp` speaks ACP v1 as NDJSON JSON-RPC 2.0 on stdout (diagnostics on
// stderr). The Tether engine drives it as a PERSISTENT per-session child: one
// `reasonix acp` process per Tether session, with sessions created/resumed via
// session/new / session/resume and turns driven by session/prompt. During a
// prompt turn the agent streams `session/update` notifications and may send
// `session/request_permission` requests (interactive approvals — the Claude
// parity this engine exists for). ACP v1 reference: agentclientprotocol.com
// (protocol/v1/{prompt-turn,tool-calls,agent-plan,session-setup}.md).
//
// LIVE update kinds (session/update -> AgentEvent), normalized per turn:
//   - agent_message_chunk        -> message_delta (streamed text; messageId is
//                                   the blockId namespace so one assistant
//                                   message = one Tether block)
//   - agent_message_chunk with a "thinking"/"reasoning" annotation -> thinking_delta
//   - agent_thought_chunk        -> thinking_delta (Reasonix's actual reasoning
//                                   channel; a SEPARATE update kind, and the one
//                                   it really emits — blockId namespaced ":think"
//                                   so reasoning never merges into the text block)
//   - tool_call                  -> tool_start (status pending), or
//                                   subagent_message for a namespaced child tool
//   - tool_call_update           -> tool_start/tool_end transition, or nested
//                                   subagent_message tool/result entries
//   - plan                       -> plan_updated (derived from todo_write)
//   - usage_update               -> usage (session context/cost; emitted at
//                                   turn_end from the prompt stop reason)
//   - available_commands_update  -> cli_commands_changed (session-level)
//   - current_mode_update / config_option_update -> session-level control facts
// The turn is the whole session/prompt call: it ends when the agent resolves
// the prompt request with a StopReason (end_turn / max_tokens / max_turn_requests
// / refusal / cancelled). The engine owns that turn_end emission (like the
// persistent Claude engine) so the adapter here stays a pure record -> event
// projection with NO I/O, NO Date.now() — the same discipline as every other
// adapter in this module.
//
// Permission requests (session/request_permission, an INBOUND request to us):
// the ENGINE parks them and emits approval_request; the adapter only carries a
// helper for turning the ACP options array into Tether ApprovalChoice[].
//
// The adapter retains only correlation state. ACP updates are otherwise absolute
// (a tool_call_update carries the full current tool state), but Reasonix 1.24's
// sub-agent nesting needs two small indexes: launcher call ids, and child tool id
// -> launcher id. ACP does not carry event.Tool.ParentID; Reasonix preserves the
// same relationship in its stable namespaced id (`<launcher>/<child>`), which is
// enough to emit the existing provider-neutral `subagent_message` event.

export function createReasonixAdapterState(turnId) {
  return {
    turnId,
    errorMessage: null,
    seenToolIds: new Set(),
    subagentLauncherIds: new Set(),
    subagentToolParents: new Map(),
    // Reasonix's ACP implementation NEVER sends ACP messageIds (verified against
    // the v1.19.7 wire contract: its messageChunk carries only sessionUpdate +
    // content + optional metadata), so the adapter synthesizes one per logical
    // message. messageSeq counts them, openMessageId is the message currently
    // streaming, and toolCompleted flags that a tool finished since the last
    // chunk — the boundary a NEW message starts on. Each model response emits
    // its text BEFORE its tool calls, and the next response only starts after
    // the tools complete, so this reproduces the provider's per-message
    // structure exactly: one bubble per response, tool cards interleaved, the
    // final report last. Without it every message of a turn merged into ONE
    // growing block and every tool card landed after it, burying the report.
    messageSeq: 0,
    openMessageId: null,
    toolCompleted: false,
  };
}

// The messageId a chunk belongs to. A provider-supplied messageId wins (a
// changed id is a boundary by construction — it also becomes the open message,
// so a later id-less chunk continues it). Without one — the Reasonix case —
// the first chunk of the turn and every chunk after a completed tool start a
// new synthesized message; everything else continues the open one. Thought and
// text chunks of the SAME response share the id so reasoning renders adjacent
// to its message (like Claude), never as a sibling bubble. Synthesized ids are
// turn-scoped by construction (the adapter state is per turn), and the reducer
// namespaces blocksById per turn, so `msg1` in two turns cannot collide.
function reasonixChunkMessageId(state, update) {
  const supplied = typeof update?.messageId === "string" && update.messageId ? update.messageId : null;
  if (supplied) {
    return {
      messageId: supplied,
      state: { ...state, openMessageId: supplied, toolCompleted: false },
    };
  }
  if (state.openMessageId !== null && !state.toolCompleted) {
    return { messageId: state.openMessageId, state };
  }
  const messageId = `msg${state.messageSeq + 1}`;
  return {
    messageId,
    state: {
      ...state,
      messageSeq: state.messageSeq + 1,
      openMessageId: messageId,
      toolCompleted: false,
    },
  };
}

// ACP tool kind -> Tether display name. ACP kinds are semantic categories
// (read/edit/delete/move/search/execute/think/fetch/switch_mode/other), not tool
// names; Reasonix sends them on tool_call/tool_call_update. The Tether reducer
// shows the tool card's `name`, so keep the kind when no better title exists.
export function reasonixToolName(update) {
  if (typeof update?.title === "string" && update.title.trim()) return update.title.trim();
  if (typeof update?.kind === "string" && update.kind) return update.kind;
  return "tool";
}

// Extract the input object for a tool card from a tool_call / tool_call_update.
export function reasonixToolInput(update) {
  const input = update?.rawInput;
  if (input && typeof input === "object") return input;
  return {};
}

// Extract the output text for a tool card from a tool_call_update's content
// array (ToolCallContent[] — {type:"content",content:{type:"text",text}} and
// {type:"diff",path,oldText,newText}). Returns "" when nothing readable.
export function reasonixToolOutput(update) {
  const content = update?.content;
  if (!Array.isArray(content)) return "";
  const parts = [];
  for (const item of content) {
    if (item?.type === "content" && item.content?.type === "text" && typeof item.content.text === "string") {
      parts.push(item.content.text);
    } else if (item?.type === "diff" && typeof item.newText === "string") {
      parts.push(item.newText);
    }
  }
  return parts.join("\n");
}

// Reasonix tools that each represent exactly one isolated child run. Group
// launchers (`fleet` and `parallel_tasks`) deliberately stay out: the provider
// emits one namespaced `task` dispatch per group child, so classifying the group
// too would add a duplicate summary tab alongside the real per-child tabs.
const REASONIX_SUBAGENT_LAUNCHERS = new Set(["task", "read_only_task"]);

function reasonixSubagentParent(state, toolId) {
  if (typeof toolId !== "string" || !toolId) return null;
  let parent = null;
  for (const launcherId of state.subagentLauncherIds) {
    if (toolId.startsWith(`${launcherId}/`) && (parent === null || launcherId.length > parent.length)) {
      parent = launcherId;
    }
  }
  return parent;
}

function reasonixToolState(state, toolId, { launcher = false, parent = null } = {}) {
  const seenToolIds = new Set(state.seenToolIds).add(toolId);
  const subagentLauncherIds = launcher
    ? new Set(state.subagentLauncherIds).add(toolId)
    : state.subagentLauncherIds;
  const subagentToolParents = parent
    ? new Map(state.subagentToolParents).set(toolId, parent)
    : state.subagentToolParents;
  return { ...state, seenToolIds, subagentLauncherIds, subagentToolParents };
}

// ACP session/update -> AgentEvent[]. `notification` is the full decoded
// notification object ({ method: "session/update", params: { sessionId, update } }).
export function reasonixToEvents(notification, state) {
  const turnId = state.turnId;
  const params = notification?.params;
  const update = params?.update;
  if (!update || typeof update !== "object") return { events: [], state };
  const sessionUpdate = update.sessionUpdate;

  switch (sessionUpdate) {
    case "agent_message_chunk": {
      const content = update.content;
      if (!content || typeof content !== "object") return { events: [], state };
      const text = typeof content.text === "string" ? content.text : "";
      if (!text) return { events: [], state };
      // A messageId groups chunks into one assistant message (blockId namespace);
      // reasonix sends none, so reasonixChunkMessageId synthesizes per-response
      // ids and every model response becomes its own bubble.
      const { messageId, state: nextState } = reasonixChunkMessageId(state, update);
      const blockId = `reasonix:${messageId}`;
      // Reasoning/thinking chunks carry an annotation or a kind of "think".
      if (content.annotations?.type === "thinking" || update.kind === "think") {
        return { events: [{ type: "thinking_delta", turnId, blockId, text }], state: nextState };
      }
      return { events: [{ type: "message_delta", turnId, blockId, text }], state: nextState };
    }

    // Reasonix streams reasoning as its OWN update kind, not as an annotation on
    // agent_message_chunk. Without this case every reasoning token fell through to
    // unknown_event (30+ per short turn) and the UI showed no thinking at all.
    // The blockId MUST NOT reuse the message namespace or the reducer folds
    // reasoning into the assistant text block — hence the ":think" suffix. The id
    // itself is the SAME synthesized message id as the response's text, so each
    // response's reasoning renders right above its own message (like Claude).
    case "agent_thought_chunk": {
      const content = update.content;
      if (!content || typeof content !== "object") return { events: [], state };
      const text = typeof content.text === "string" ? content.text : "";
      if (!text) return { events: [], state };
      const { messageId, state: nextState } = reasonixChunkMessageId(state, update);
      return { events: [{ type: "thinking_delta", turnId, blockId: `reasonix:${messageId}:think`, text }], state: nextState };
    }

    case "tool_call": {
      const toolId = typeof update.toolCallId === "string" ? update.toolCallId : `reasonix-tool-${turnId}`;
      const name = reasonixToolName(update);
      const input = reasonixToolInput(update);
      const parentToolUseId = reasonixSubagentParent(state, toolId);
      if (parentToolUseId) {
        return {
          events: [{
            type: "subagent_message",
            turnId,
            parentToolUseId,
            items: [{ kind: "tool", key: toolId, name, input }],
          }],
          state: reasonixToolState(state, toolId, { parent: parentToolUseId }),
        };
      }
      const launcher = REASONIX_SUBAGENT_LAUNCHERS.has(name);
      return {
        events: [{ type: "tool_start", turnId, toolId, name, input }],
        state: reasonixToolState(state, toolId, { launcher }),
      };
    }

    case "tool_call_update": {
      const toolId = typeof update.toolCallId === "string" ? update.toolCallId : `reasonix-tool-${turnId}`;
      const status = update.status;
      const known = state.seenToolIds.has(toolId);
      const parentToolUseId = state.subagentToolParents.get(toolId) ?? reasonixSubagentParent(state, toolId);
      const events = [];
      const finished = status === "completed" || status === "failed";
      if (parentToolUseId) {
        const items = [];
        if (!known) {
          // A nested tool we never opened (e.g. attachment began mid-call).
          items.push({ kind: "tool", key: toolId, name: reasonixToolName(update), input: reasonixToolInput(update) });
        }
        if (finished) {
          items.push({
            kind: "tool_result",
            key: toolId,
            output: reasonixToolOutput(update),
            isError: status === "failed",
          });
        }
        if (items.length > 0) {
          events.push({ type: "subagent_message", turnId, parentToolUseId, items });
        }
      } else {
        if (!known) {
          // A tool we never opened (e.g. the agent opened it before we attached).
          events.push({ type: "tool_start", turnId, toolId, name: reasonixToolName(update), input: reasonixToolInput(update) });
        }
        if (finished) {
          events.push({
            type: "tool_end",
            turnId,
            toolId,
            output: reasonixToolOutput(update),
            isError: status === "failed",
          });
        }
      }
      const name = reasonixToolName(update);
      return {
        events,
        state: {
          ...reasonixToolState(state, toolId, {
            launcher: !parentToolUseId && !known && REASONIX_SUBAGENT_LAUNCHERS.has(name),
            parent: parentToolUseId,
          }),
          // A finished tool is the message boundary: the next text/thought chunk
          // belongs to the next model response (see reasonixChunkMessageId).
          ...(finished ? { toolCompleted: true } : {}),
        },
      };
    }

    case "plan": {
      // Complete plan replacement (ACP: "The Client MUST replace the current
      // plan completely"). Map entries -> plan_updated steps.
      const entries = Array.isArray(update.entries) ? update.entries : [];
      const steps = entries
        .map((entry, index) => {
          if (!entry || typeof entry.content !== "string") return null;
          return {
            stepNumber: index + 1,
            status: entry.status === "completed" ? "completed" : entry.status === "in_progress" ? "in_progress" : "pending",
            content: entry.content,
          };
        })
        .filter((step) => step !== null);
      if (steps.length === 0) return { events: [], state };
      return { events: [{ type: "plan_updated", turnId, steps }], state };
    }

    case "usage_update": {
      // Session context/cost. The Tether usage event is turn-scoped; the engine
      // folds this into its turn_end emission, so the adapter just carries the
      // raw numbers for the engine to attach (via a helper below).
      return {
        events: [{
          type: "usage",
          turnId,
          model: undefined,
          rawModel: undefined,
          perTurnTokens: typeof update.used === "number" ? update.used : 0,
          cumulativeTokens: undefined,
          contextWindow: typeof update.size === "number" ? update.size : undefined,
          estimatedCostUSD: typeof update.cost?.amount === "number" ? update.cost.amount : undefined,
          modelUsages: undefined,
        }],
        state,
      };
    }

    case "available_commands_update": {
      const commands = Array.isArray(update.availableCommands) ? update.availableCommands : [];
      const normalized = commands
        .map((command) => {
          if (!command || typeof command.name !== "string" || !command.name) return null;
          return {
            name: command.name,
            ...(typeof command.description === "string" && command.description ? { description: command.description } : {}),
            ...(command.input?.hint ? { argumentHint: command.input.hint } : {}),
          };
        })
        .filter((command) => command !== null);
      return { events: [{ type: "cli_commands_changed", turnId: null, commands: normalized }], state };
    }

    case "current_mode_update":
    case "config_option_update":
    case "session_info_update":
      // Session-level control facts. No dedicated Tether event; the engine
      // mirrors them onto the public session directly. Nothing to fold here.
      return { events: [], state };

    case "user_message_chunk": {
      // ACP echoes the operator's own message back (dispatch.go replay + live
      // echo). The MANAGER already pre-emits user_message_accepted before
      // pushUserTurn, so rendering this echo would duplicate the user bubble.
      // Swallow it (no unknown_event warning noise, no second bubble).
      return { events: [], state };
    }

    default:
      return { events: [{ type: "unknown_event", turnId, raw: notification }], state };
  }
}

// ACP permission options -> Tether ApprovalChoice[] (for the engine to attach
// to approval_request). ACP kinds: allow_once/allow_always/reject_once/
// reject_always. Tether's ApprovalChoice carries only allow/deny semantics
// (choiceId + label); "always" variants are folded into the label.
export function reasonixApprovalChoices(options) {
  if (!Array.isArray(options)) return undefined;
  const choices = [];
  for (const option of options) {
    if (!option || typeof option.optionId !== "string" || !option.optionId) continue;
    const kind = typeof option.kind === "string" ? option.kind : "";
    const label = typeof option.name === "string" && option.name ? option.name : option.optionId;
    choices.push({
      choiceId: option.optionId,
      label,
      ...(kind.includes("always") ? { description: "Remember this choice" } : {}),
    });
  }
  return choices.length ? choices : undefined;
}

// --------------------------------------------------------------------------
// Generic ACP adapter (re-export)
// --------------------------------------------------------------------------
//
// The provider-neutral ACP v1 session/update adapter lives in
// engines/acp-adapter.mjs (the P2 generalization of reasonixToEvents) and is
// re-exported here so both the server and the browser import every provider
// adapter from this one shared module, exactly like reasonixToEvents. The
// module is pure (no I/O, no Date.now, no node builtins), so the browser bundle
// can import it unchanged.

export {
  createAcpAdapterState,
  acpToEvents,
  acpToolName,
  acpToolInput,
  acpToolOutput,
  acpApprovalChoices,
  computeAcpCapabilities,
  acpResumeMethod,
} from "./acp-adapter.mjs";

// --------------------------------------------------------------------------
// Pi adapter (`pi --mode rpc` JSONL events -> AgentEvent[])
// --------------------------------------------------------------------------
//
// Verified live against pi 0.84.1 (contracts/pi-rpc/0.84.1/, P0 probe): a
// Tether turn = one `prompt` command through the terminal `agent_settled`
// event. `turn_start`/`turn_end` fire per LOW-LEVEL run (once per
// assistant-message+tools cycle) and can fire more than once per prompt —
// confirmed live: a tool-call response then a follow-up text response
// produced two turn_start/turn_end pairs under one agent_settled. The ENGINE
// (engines/pi.mjs) owns that turn-boundary decision; this adapter only folds
// the per-message/per-tool event stream into blocks, exactly like the other
// adapters in this file.
//
// Pi's `message_update` deltas carry a `contentIndex` scoped to the CURRENT
// message (docs/rpc.md), unlike Reasonix's ACP stream, which sends no
// message identity at all and forces this file to synthesize one
// (reasonixChunkMessageId). Pi tells us enough directly: `msgIndex` (bumped on
// every assistant `message_start`) plus `contentIndex` gives a stable,
// collision-free blockId (`pi:<msgIndex>:<contentIndex>`) with no inference.
//
// `toolcall_start/delta/end` (the ASSISTANT MESSAGE's streamed tool-call
// arguments) are intentionally swallowed — Tether shows nothing for a tool
// until `tool_execution_start`, which carries the complete, already-parsed
// `args`. This matches the plan's table and avoids showing a tool card twice
// (once building, once executing).
//
// Token-progress note: unlike Reasonix's `_reasonix.io/session/status_update`
// (a real live per-turn token push) or Claude's SDK usage deltas, Pi exposes
// NO authoritative token count mid-stream — only the final `message.usage` at
// `message_end` and `get_session_stats` at settle. `piTokenProgress` below is
// therefore a clearly-approximate ~4-chars-per-token estimate over streamed
// text/thinking length, gated by the same milestone ladder as every other
// engine (nextTokenProgressThreshold) so it costs a handful of events per
// turn. It exists to keep the live "thinking…" counter moving, and is always
// superseded by the authoritative per-message/session usage folded in by the
// engine at message_end / turn settle. This is a deliberate, documented
// approximation — PI_PARITY_PLAN.md §3 is annotated with the same note.

export function createPiAdapterState(turnId) {
  return {
    turnId,
    // Bumped on every assistant `message_start`; content blocks within that
    // message are addressed by their `contentIndex` from pi's own stream.
    msgIndex: 0,
    // toolCallId -> length of the tool_execution_update partialResult text
    // already emitted, so a REPEATED, CUMULATIVE partialResult (docs/rpc.md:
    // "contains the accumulated output so far, not just the delta") can be
    // turned into the incremental `tool_output_delta.chunk` the reducer
    // expects, instead of re-appending the whole snapshot every update.
    toolOutputLen: new Map(),
    // Approximate live token counter (see the module-level note above).
    liveCharsEmitted: 0,
    liveTokensEmitted: 0,
  };
}

// ~4 chars/token is the standard rough English-text estimate (OpenAI's own
// guidance). Never used for billing or the authoritative per-turn/session
// usage — only to keep a live counter moving between real usage snapshots.
function piApproxTokenProgress(state, turnId, addedChars) {
  if (!(addedChars > 0)) return { events: [], state };
  const liveCharsEmitted = state.liveCharsEmitted + addedChars;
  const approxTokens = Math.floor(liveCharsEmitted / 4);
  if (approxTokens < nextTokenProgressThreshold(state.liveTokensEmitted)) {
    return { events: [], state: { ...state, liveCharsEmitted } };
  }
  return {
    events: [{ type: "token_progress", turnId, tokens: approxTokens }],
    state: { ...state, liveCharsEmitted, liveTokensEmitted: approxTokens },
  };
}

// Extract readable text from a Pi tool result/partialResult content array
// (`[{type:"text",text}]` per docs/rpc.md's ToolResultMessage / BashResult
// shapes). Non-text content (e.g. a future image result) is skipped rather
// than guessed at.
export function piToolOutputText(result) {
  const content = result?.content;
  if (!Array.isArray(content)) return "";
  const parts = [];
  for (const item of content) {
    if (item?.type === "text" && typeof item.text === "string") parts.push(item.text);
  }
  return parts.join("\n");
}

// One `message_update.assistantMessageEvent` -> AgentEvent[]. Split out of
// piToEvents for readability; still pure, still returns { events, state }.
function piAssistantMessageEvent(ame, state) {
  const turnId = state.turnId;
  if (!ame || typeof ame.type !== "string" || typeof ame.contentIndex !== "number") {
    return { events: [{ type: "unknown_event", turnId, raw: ame }], state };
  }
  const blockId = `pi:${state.msgIndex}:${ame.contentIndex}`;
  switch (ame.type) {
    case "text_start":
      return { events: [{ type: "message_started", turnId, blockId }], state };
    case "text_delta": {
      const delta = typeof ame.delta === "string" ? ame.delta : "";
      if (!delta) return { events: [], state };
      const { events: progressEvents, state: nextState } = piApproxTokenProgress(state, turnId, delta.length);
      return { events: [{ type: "message_delta", turnId, blockId, text: delta }, ...progressEvents], state: nextState };
    }
    case "text_end":
      return { events: [{ type: "message_completed", turnId, blockId, text: typeof ame.content === "string" ? ame.content : "" }], state };
    case "thinking_start":
      // Lazily created by the first thinking_delta (mirrors every other
      // adapter's thinking_delta upsert fallback) — no dedicated event.
      return { events: [], state };
    case "thinking_delta": {
      const delta = typeof ame.delta === "string" ? ame.delta : "";
      if (!delta) return { events: [], state };
      const { events: progressEvents, state: nextState } = piApproxTokenProgress(state, turnId, delta.length);
      return { events: [{ type: "thinking_delta", turnId, blockId, text: delta }, ...progressEvents], state: nextState };
    }
    case "thinking_end":
      return { events: [{ type: "thinking_completed", turnId, blockId, text: typeof ame.content === "string" ? ame.content : "" }], state };
    case "toolcall_start":
    case "toolcall_delta":
    case "toolcall_end":
      // Swallowed — see the module note. tool_execution_start carries the
      // complete parsed args; nothing to show while pi is still streaming them.
      return { events: [], state };
    default:
      return { events: [{ type: "unknown_event", turnId, raw: ame }], state };
  }
}

// Pi RPC stdout line (any `type` other than `"response"`) -> AgentEvent[].
// `event` is the already-JSON.parsed line; `state` is this turn's adapter
// state (from createPiAdapterState). Control-plane frames the engine handles
// itself before ever reaching this pure function — `extension_ui_request`
// (gatekeeper approvals/questions, P2), `agent_settled`/`agent_end`/turn
// bookkeeping (the engine owns turn_end emission, like every persistent
// engine in this file) — are NOT switched on here; the engine intercepts them
// upstream. Anything else unrecognized falls through to `unknown_event`.
export function piToEvents(event, state) {
  const turnId = state.turnId;
  if (!event || typeof event.type !== "string") return { events: [{ type: "unknown_event", turnId, raw: event }], state };

  switch (event.type) {
    case "message_start": {
      if (event.message?.role !== "assistant") return { events: [], state }; // user/toolResult echoes; manager already emitted the user bubble
      return { events: [], state: { ...state, msgIndex: state.msgIndex + 1 } };
    }

    case "message_update":
      return piAssistantMessageEvent(event.assistantMessageEvent, state);

    case "message_end": {
      // Content is already closed via text_end/thinking_end above; message_end
      // additionally carries the message's terminal usage/error. The engine
      // harvests `event.message.usage` into its per-turn accumulator itself
      // (mirrors reasonix.mjs's handleStatusUpdate pattern) — this adapter only
      // surfaces a hard error, so an all-tool-calls turn that errors before any
      // text streams still gets a visible reason instead of silently ending.
      const message = event.message;
      if (message?.role === "assistant" && message.stopReason === "error" && typeof message.errorMessage === "string" && message.errorMessage) {
        return { events: [{ type: "error", turnId, message: message.errorMessage }], state };
      }
      return { events: [], state };
    }

    case "tool_execution_start": {
      const toolId = typeof event.toolCallId === "string" ? event.toolCallId : `pi-tool-${turnId}`;
      const name = typeof event.toolName === "string" && event.toolName ? event.toolName : "tool";
      const input = event.args && typeof event.args === "object" ? event.args : {};
      return { events: [{ type: "tool_start", turnId, toolId, name, input }], state };
    }

    case "tool_execution_update": {
      const toolId = typeof event.toolCallId === "string" ? event.toolCallId : `pi-tool-${turnId}`;
      const text = piToolOutputText(event.partialResult);
      const priorLen = state.toolOutputLen.get(toolId) ?? 0;
      // partialResult is the ACCUMULATED output so far (docs/rpc.md), not a
      // delta — a shorter/unrelated snapshot (e.g. a tool that resets its own
      // buffer) is treated as a fresh start rather than producing a negative
      // slice.
      const chunk = text.length >= priorLen ? text.slice(priorLen) : text;
      const toolOutputLen = new Map(state.toolOutputLen).set(toolId, text.length);
      if (!chunk) return { events: [], state: { ...state, toolOutputLen } };
      return { events: [{ type: "tool_output_delta", turnId, toolId, chunk }], state: { ...state, toolOutputLen } };
    }

    case "tool_execution_end": {
      const toolId = typeof event.toolCallId === "string" ? event.toolCallId : `pi-tool-${turnId}`;
      const toolOutputLen = new Map(state.toolOutputLen);
      toolOutputLen.delete(toolId);
      return {
        events: [{ type: "tool_end", turnId, toolId, output: piToolOutputText(event.result), isError: event.isError === true }],
        state: { ...state, toolOutputLen },
      };
    }

    case "auto_retry_start":
      return {
        events: [{
          type: "api_retry",
          turnId,
          attempt: typeof event.attempt === "number" ? event.attempt : 1,
          maxRetries: typeof event.maxAttempts === "number" ? event.maxAttempts : null,
          delayMs: typeof event.delayMs === "number" ? event.delayMs : null,
          errorStatus: null,
          error: typeof event.errorMessage === "string" ? event.errorMessage : null,
        }],
        state,
      };

    case "auto_retry_end":
      // Resolution is implicit — API_RETRY_RESOLVED_BY already clears the
      // marker on the next forward-progress event (message/tool activity).
      // A final failure surfaces as its own warning so it isn't silently lost
      // if no further content streams before turn_end.
      if (event.success === false) {
        return { events: [{ type: "warning", turnId, message: typeof event.finalError === "string" ? event.finalError : "Pi auto-retry failed.", raw: event }], state };
      }
      return { events: [], state };

    case "compaction_end": {
      if (event.aborted === true || !event.result) return { events: [], state };
      const itemId = typeof event.result.firstKeptEntryId === "string" && event.result.firstKeptEntryId
        ? event.result.firstKeptEntryId
        : `pi-compaction-${turnId}-${state.msgIndex}`;
      return { events: [{ type: "context_compacted", turnId, itemId }], state };
    }

    case "extension_error":
      return {
        events: [{ type: "warning", turnId, message: typeof event.error === "string" ? event.error : "Pi extension error.", raw: event }],
        state,
      };

    // Control-plane / bookkeeping frames the ENGINE consumes directly and
    // never forwards here: agent_start, agent_end, agent_settled, turn_start,
    // turn_end, queue_update, compaction_start, summarization_retry_*,
    // extension_ui_request. Anything else unrecognized is diagnostic.
    default:
      return { events: [{ type: "unknown_event", turnId, raw: event }], state };
  }
}

// Replay a resolved active-branch message list (already walked leaf->root by
// the caller — session-discovery.mjs, P5) into AgentEvent[], one turn per
// user message. Mirrors reasonixTranscriptToEvents's shape: `messages` is an
// array of pi AgentMessage objects (session-format.md: UserMessage /
// AssistantMessage / ToolResultMessage / BashExecutionMessage), already in
// append order on the active branch.
export function piTranscriptToEvents(messages, { turnIdPrefix = "replay" } = {}) {
  const events = [];
  let turnIndex = 0;
  let turnId = null;
  let blockSeq = 0;

  const list = Array.isArray(messages) ? messages : [];
  for (const message of list) {
    if (!message || typeof message.role !== "string") continue;

    if (message.role === "user") {
      // Close the PREVIOUS turn before opening this one — without this, only
      // the final turn's events ever survive dropEmptyReplayTurns (a new
      // turn_started with no matching turn_end for the prior turn left its
      // buffer permanently open, so every earlier turn's content was silently
      // discarded). Bug caught live by tests/pi-events.test.mjs.
      if (turnId !== null) events.push({ type: "turn_end", turnId, outcome: TURN_OUTCOMES.OK });
      turnIndex += 1;
      turnId = `${turnIdPrefix}-${turnIndex}`;
      const text = typeof message.content === "string"
        ? message.content
        : Array.isArray(message.content)
          ? message.content.filter((c) => c?.type === "text" && typeof c.text === "string").map((c) => c.text).join("\n")
          : "";
      events.push({ type: "turn_started", turnId });
      events.push({ type: "user_message_accepted", turnId, text });
      continue;
    }
    if (turnId === null) continue; // an assistant/tool entry before any user message (should not occur)

    if (message.role === "assistant") {
      const content = Array.isArray(message.content) ? message.content : [];
      for (const block of content) {
        if (block?.type === "text" && typeof block.text === "string" && block.text) {
          blockSeq += 1;
          const blockId = `${turnIdPrefix}-msg${blockSeq}`;
          events.push({ type: "message_started", turnId, blockId });
          events.push({ type: "message_completed", turnId, blockId, text: block.text });
        } else if (block?.type === "thinking" && typeof block.thinking === "string" && block.thinking) {
          blockSeq += 1;
          events.push({ type: "thinking_completed", turnId, blockId: `${turnIdPrefix}-thinking${blockSeq}`, text: block.thinking });
        }
        // toolCall blocks carry no result here — the matching toolResult
        // message (below) is what renders the tool card, exactly like the
        // live adapter waits for tool_execution_start/end rather than the
        // assistant message's own toolcall_* stream.
      }
      if (message.stopReason === "error" && typeof message.errorMessage === "string" && message.errorMessage) {
        events.push({ type: "error", turnId, message: message.errorMessage });
      }
      continue;
    }

    if (message.role === "toolResult") {
      const toolId = typeof message.toolCallId === "string" ? message.toolCallId : `${turnIdPrefix}-tool${blockSeq}`;
      const name = typeof message.toolName === "string" && message.toolName ? message.toolName : "tool";
      events.push({ type: "tool_start", turnId, toolId, name, input: {} });
      events.push({ type: "tool_end", turnId, toolId, output: piToolOutputText(message), isError: message.isError === true });
      continue;
    }

    if (message.role === "bashExecution") {
      blockSeq += 1;
      const toolId = `${turnIdPrefix}-bash${blockSeq}`;
      events.push({ type: "tool_start", turnId, toolId, name: "bash", input: { command: message.command } });
      events.push({ type: "tool_end", turnId, toolId, output: typeof message.output === "string" ? message.output : "", isError: (message.exitCode ?? 0) !== 0 });
      continue;
    }

    if (message.role === "compactionSummary") {
      events.push({ type: "context_compacted", turnId, itemId: `${turnIdPrefix}-compaction-${turnIndex}` });
      continue;
    }
    // branchSummary / custom / other extended roles: no Tether projection yet.
  }
  if (turnId !== null) {
    events.push({ type: "turn_end", turnId, outcome: TURN_OUTCOMES.OK });
  }
  return dropEmptyReplayTurns(events);
}

// --------------------------------------------------------------------------
// DeepSeek Harness (dsh) — live incremental + transcript replay adapters
// --------------------------------------------------------------------------
//
// dsh records its runs to a zstd-compressed JSONL transcript
// (`~/.dsh/sessions/--<cwd-slug>--/session-<uuid>/session.jsonl.zstd`) written
// INCREMENTALLY while the run executes. Record types (verified against real
// transcripts, @deepseek-ai/dsh 0.1.0-rc.6, 2026-08-16):
//
//   session            header { id, createdAt, cwd } — the native session id
//   user/message       data.content[] text blocks; ONLY data.source.kind ===
//                      "user" is real user text (plugin/system reminders share
//                      the type)
//   turn/start         data.turn
//   assistant/chunk    data.chunk:
//                        block-start    { blockType: "reasoning"|"text"|"tool-call" }
//                        reasoning-delta{ text } / text-delta { text }
//                        block-end      { block: { type, text } }
//                        usage          { usage: { inputTokens, outputTokens, ... } }
//                        finish         { reason: { kind: "stop"|... } }
//   tool/call          data.arguments is a JSON STRING (parsed as input)
//   tool/result        data.output
//   assistant/message  final message (duplicates the chunk text — ignored by
//                      the live adapter so nothing renders twice)
//   session/title      data.title
//   turn/end           data.reason.kind: completed | cancelled | error | ...
//
// dsh is dev-preview (breaking changes expected): every unknown record/chunk
// type is SKIPPED silently, never thrown, so a renamed record can only cost a
// little fidelity, never wedge a turn.

const DSH_TURN_OUTCOME_BY_REASON = Object.freeze({
  completed: TURN_OUTCOMES.OK,
  cancelled: TURN_OUTCOMES.CANCELLED,
  error: TURN_OUTCOMES.ERROR,
});

export function createDshAdapterState(turnId) {
  return {
    turnId,
    emittedNativeId: false,
    // open blocks by chunk index: index -> { type, text }
    openBlocks: new Map(),
    // Accumulated usage across all steps in this turn. dsh emits one `usage`
    // chunk per step (each carrying that step's own counts), but the reducer
    // SETs turn.usage (v40: never accumulated) — so the adapter must maintain
    // the running total and emit it on every chunk so the last SET wins with
    // the correct whole-turn sum.
    usageAccum: { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, reasoningTokens: 0 },
  };
}

function dshChunkText(block) {
  return typeof block?.text === "string" ? block.text : "";
}

/**
 * Per-record live adapter (engine path): feed one transcript record, get the
 * AgentEvents it produces plus the next state. Blocks are buffered by chunk
 * index and completed (message_completed / thinking_completed) when the
 * block-end arrives — the same shape the replay adapter produces.
 */
export function dshTranscriptToEvents(record, state) {
  const turnId = state.turnId;
  const type = typeof record?.type === "string" ? record.type : "";
  const data = record?.data && typeof record.data === "object" ? record.data : {};

  // The header carries the native session id — emit it once, early, so
  // session identity/history linking works from the first poll.
  if (type === "session" && !state.emittedNativeId) {
    const id = typeof record.id === "string" ? record.id : typeof data.id === "string" ? data.id : "";
    if (id) {
      const rest = dshRecordToEvents(record, { ...state, emittedNativeId: true });
      return { events: [{ type: "native_session_id", turnId, nativeSessionId: id }, ...rest.events], state: rest.state };
    }
  }
  return dshRecordToEvents(record, state);
}

function dshRecordToEvents(record, state) {
  const turnId = state.turnId;
  const type = typeof record?.type === "string" ? record.type : "";
  const data = record?.data && typeof record.data === "object" ? record.data : {};

  switch (type) {
    case "session":
    case "permission/preset":
    case "sandbox/mode":
    case "approval/policy":
    case "agent/inbox/spliced":
    case "turn/start":
    case "step/start":
    case "step/end":
    case "request/header":
    case "request/context":
    case "reasoning-chunks":
    case "text-chunks":
      return { events: [], state };

    case "user/message": {
      // Only real user text opens a turn; plugin/system reminders share the
      // type but carry source.kind !== "user".
      if (data?.source?.kind !== "user") return { events: [], state };
      const text = dshUserText(data.content);
      if (!text) return { events: [], state };
      return {
        events: [
          { type: "turn_started", turnId },
          { type: "user_message_accepted", turnId, text },
        ],
        state,
      };
    }

    case "assistant/chunk":
      return dshChunkToEvents(data.chunk, state);

    case "tool/call": {
      const toolId = typeof data.toolCallId === "string" && data.toolCallId ? data.toolCallId : `dsh-tool-${turnId}-${data.seq ?? ""}`;
      const name = typeof data.name === "string" && data.name ? data.name : "tool";
      let input = {};
      if (typeof data.arguments === "string" && data.arguments) {
        try {
          const parsed = JSON.parse(data.arguments);
          if (parsed && typeof parsed === "object") input = parsed;
        } catch {
          input = { raw: data.arguments };
        }
      }
      return { events: [{ type: "tool_start", turnId, toolId, name, input }], state };
    }

    case "tool/result": {
      const toolId = typeof data.toolCallId === "string" && data.toolCallId ? data.toolCallId : `dsh-tool-${turnId}-${data.seq ?? ""}`;
      const output = typeof data.output === "string" ? data.output : "";
      const isError = data.isError === true || (typeof data.error === "string" && Boolean(data.error));
      return { events: [{ type: "tool_end", turnId, toolId, output, isError }], state };
    }

    case "assistant/message":
      // The final message duplicates what the chunk stream already emitted
      // (block-end carries the full text). Ignored so nothing renders twice.
      return { events: [], state };

    case "session/title":
      return { events: [], state };

    case "turn/end": {
      const kind = data?.reason?.kind;
      const outcome = DSH_TURN_OUTCOME_BY_REASON[kind] ?? TURN_OUTCOMES.UNKNOWN;
      return { events: [{ type: "turn_end", turnId, outcome }], state };
    }

    default:
      // dsh is dev-preview: unknown record types are skipped, never thrown.
      return { events: [], state };
  }
}

function dshChunkToEvents(chunk, state) {
  const turnId = state.turnId;
  if (!chunk || typeof chunk !== "object") return { events: [], state };
  const kind = typeof chunk.type === "string" ? chunk.type : "";
  const index = Number.isInteger(chunk.index) ? chunk.index : -1;

  switch (kind) {
    case "block-start": {
      const blockType = chunk.blockType === "reasoning" || chunk.blockType === "tool-call" ? chunk.blockType : "text";
      const openBlocks = new Map(state.openBlocks);
      openBlocks.set(index, { type: blockType, text: "" });
      const events = blockType === "text"
        ? [{ type: "message_started", turnId, blockId: `dsh-${index}` }]
        : [];
      return { events, state: { ...state, openBlocks } };
    }

    case "reasoning-delta":
    case "text-delta": {
      const text = dshChunkText(chunk);
      if (!text) return { events: [], state };
      const openBlocks = new Map(state.openBlocks);
      const current = openBlocks.get(index);
      if (!current) return { events: [], state }; // delta before block-start — skip
      openBlocks.set(index, { ...current, text: current.text + text });
      return { events: [], state: { ...state, openBlocks } };
    }

    case "block-end": {
      const block = chunk.block && typeof chunk.block === "object" ? chunk.block : {};
      const text = dshChunkText(block);
      const current = state.openBlocks.get(index);
      const blockType = current?.type ?? (block.type === "reasoning" ? "reasoning" : "text");
      const openBlocks = new Map(state.openBlocks);
      openBlocks.delete(index);
      if (blockType === "reasoning") {
        return {
          events: text
            ? [{ type: "thinking_completed", turnId, blockId: `dsh-thinking-${index}`, text }]
            : [],
          state: { ...state, openBlocks },
        };
      }
      // Text block: message_started was already emitted on block-start (live
      // streaming shows the block open); only complete it here. If the block
      // was never opened (replay of a bare block-end), emit both so the block
      // still renders.
      const started = current !== undefined;
      return {
        events: [
          ...(started ? [] : [{ type: "message_started", turnId, blockId: `dsh-${index}` }]),
          { type: "message_completed", turnId, blockId: `dsh-${index}`, text },
        ],
        state: { ...state, openBlocks },
      };
    }

    case "usage": {
      const usage = chunk.usage && typeof chunk.usage === "object" ? chunk.usage : {};
      const input = typeof usage.inputTokens === "number" ? usage.inputTokens : 0;
      const output = typeof usage.outputTokens === "number" ? usage.outputTokens : 0;
      const cacheRead = typeof usage.cacheReadTokens === "number" ? usage.cacheReadTokens : 0;
      const reasoning = typeof usage.reasoningTokens === "number" ? usage.reasoningTokens : 0;
      // Accumulate across steps: dsh emits one usage chunk per step, each
      // carrying that step's own counts. The reducer SETs (never accumulates),
      // so we maintain the running total and emit the sum every time — the
      // last SET wins with the correct whole-turn figure.
      const accum = {
        inputTokens: state.usageAccum.inputTokens + input,
        outputTokens: state.usageAccum.outputTokens + output,
        cacheReadTokens: state.usageAccum.cacheReadTokens + cacheRead,
        reasoningTokens: state.usageAccum.reasoningTokens + reasoning,
      };
      const perTurnTokens = accum.inputTokens + accum.outputTokens + accum.cacheReadTokens + accum.reasoningTokens;
      return {
        events: [{ type: "usage", turnId, model: undefined, perTurnTokens, cumulativeTokens: undefined, contextWindow: undefined }],
        state: { ...state, usageAccum: accum },
      };
    }

    case "finish":
      // Turn completion is carried by the turn/end record, not the finish
      // chunk — nothing to emit here.
      return { events: [], state };

    default:
      return { events: [], state };
  }
}

function dshUserText(content) {
  if (typeof content === "string") return content.trim();
  if (!Array.isArray(content)) return "";
  return content
    .filter((c) => c && c.type === "text" && typeof c.text === "string")
    .map((c) => c.text)
    .join("\n")
    .trim();
}

/**
 * Replay adapter (discovery/resume path): fold a complete transcript record
 * array into AgentEvent[], one turn per real user message. Mirrors the pi
 * replay adapter's shape; the live adapter is the per-record form.
 */
export function dshTranscriptReplay(records, { turnIdPrefix = "replay" } = {}) {
  const events = [];
  const list = Array.isArray(records) ? records : [];
  let adapterState = createDshAdapterState(turnIdPrefix);
  for (const record of list) {
    const { events: produced, state } = dshTranscriptToEvents(record, adapterState);
    adapterState = state;
    for (const event of produced) {
      // Replay turns carry no native id — the resume picker keys on historyId.
      if (event.type === "native_session_id") continue;
      events.push(event);
    }
  }
  return dropEmptyReplayTurns(events);
}
