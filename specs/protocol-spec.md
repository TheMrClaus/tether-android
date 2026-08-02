# Tether Wire Protocol & Auth — Kotlin Client Spec

Ground truth (code is authoritative):
- `aidash/lib/protocol.ts` (types, PROTOCOL_VERSION)
- `aidash/lib/protocol-validate.mjs` (what the server ACCEPTS)
- `aidash/server.mjs` (auth + WS upgrade + dispatch)
- `aidash/hooks/use-tether.ts` (reference client)
- `aidash/lib/pending-input.mjs` (durable-send constants)

## 1. AUTH

- Server: single Node HTTP server, default port 4173. `PROTOCOL_VERSION = 40`.
- `GET /healthz` — unauthenticated: `{ ok, uptimeMs, sessions, outstandingBackground, protocolVersion }`. Cheapest pre-flight version check.
- `GET /api/auth/session` — always 200 `{ "authenticated": bool }`.

### POST /api/auth/login (password)
Body cap 4096 bytes. JSON form (use this): `Content-Type: application/json`, `{"password":"..."}` → 200 `{"ok":true}` + `Set-Cookie: tether_session=...`. Form-encoded variant returns 303 with the cookie on the redirect.
Failures: 401 `{"error":"That password is not correct."}`; 429 `{"error":"Too many attempts. Try again in a few minutes."}` (10 failed attempts / 15 min per IP; success always clears).

### Cookie
- Name `tether_session`. Value (URL-encoded): `issuedAtMillis.randomHex32.base64urlHmac` — self-contained signed bearer token, **TTL 7 days**, no refresh: re-login on expiry/401.
- Set-Cookie attrs: HttpOnly; SameSite=Strict; Path=/; Max-Age=604800 (+ Secure behind https).
- Store in EncryptedSharedPreferences (it is a 7-day bearer credential).

### POST /api/auth/logout → 200 + cookie cleared.

### Authelia proxy alternative
Request (HTTP or WS upgrade) is authenticated if `AIDASH_PROXY_TOKEN` is configured server-side and headers `X-Tether-Proxy-Token: <token>` AND `Remote-User: <non-empty>` are both present. OR the cookie path. Support both: cookie is primary; allow optional proxy-token config.

### WS upgrade requirements (CRITICAL)
- URL: `wss://<host>/ws` (or ws:// on http). No subprotocol. No query params.
- Send `Cookie: tether_session=<value>` (or proxy header pair) on the upgrade.
- **MUST send an `Origin` header whose URL host (host+port) exactly equals the `Host` header being sent** — e.g. connecting to `wss://tether.example.com/ws` → `Origin: https://tether.example.com`. OkHttp does NOT set Origin automatically. Omitting it → raw `HTTP/1.1 401 Unauthorized` + socket destroyed (indistinguishable from bad cookie).
- Keepalive: server sends WS protocol-level PING every 30s and terminates clients that miss a PONG — OkHttp answers PONGs automatically. No application-level ping JSON.
- Text frames only, one JSON object per frame. Inbound (client→server) frame cap 32 MiB.

### Version handshake
First frame from server is always `ready` carrying `protocolVersion`. If != 40: stop, disable reconnect, surface "app out of date" — refuse to interpret later frames. Optional client `{"type":"hello","protocolVersion":40}` → server replies `version_mismatch` only on mismatch.

## 2. LIMITS
WS_FRAME_BYTES 33554432 · USER_MESSAGE_BYTES 65536 · APPROVAL_INPUT_BYTES 65536 · ID_LENGTH 128 · ATTACHMENT_NAME_BYTES 512 · ATTACHMENT_MEDIA_TYPE_BYTES 128 · ATTACHMENT_DATA_B64_BYTES 12582912 · ATTACHMENTS_TOTAL_B64_BYTES 25165824 · MAX_ATTACHMENTS 10 · LAST_SEEN_ENTRIES 2000 · PERMISSION_PATH_BYTES 4096 · PERMISSION_PATHS 64. Queue cap 50.
"non-empty bounded string" = length 1..128 unless stated.

## 3. CLIENT → SERVER MESSAGES
Unknown type → `{"type":"error","message":"unknown message type: <t>"}`. Validation failure → error frame. Session-referencing messages error with "That session no longer exists." if unknown.

- `hello` { protocolVersion: number } — optional.
- `create` { provider (required, "claude"|"codex"|"gemini"|"opencode"), cwd?, name? (≤128, server truncates to 80), permissionMode? ∈ default|acceptEdits|plan|dontAsk|bypassPermissions (default "default"), sandboxPolicy? ∈ read-only|workspace-write|off (claude/codex only; "off" claude-only), useWorktree?: bool } → `created` to requester + `session` broadcast.
- `resume` { historyId (required), cwd (required) } → `created` (may dedup to an existing live session).
- `discover` { cwd (required), lastSeen?: {historyId: epochMs} ≤2000 } → `histories`.
- `search` { cwd (required), query (required, ≤512B; <2 trimmed chars → zero hits) } → `search-results`.
- `browse` { cwd? } → `directories`.
- `attach` { sessionId (required), afterSeq?: number } → one `snapshot` to requester. afterSeq does not shrink the payload; state is always complete.
- `send` { sessionId, text (≤65536B; "" allowed only with attachments), idempotencyKey (required, ≤128, client-minted), attachments?: [{name ≤512B, mediaType ≤128B, data: base64 no-prefix, per-file ≤12582912 b64 bytes, total ≤25165824, max 10}] }. Server dedups ONLY the last accepted key per session (in-memory). No direct reply — ack is the `turn_started` event with matching idempotencyKey. Error strings include: "This session has been archived — create a new one to continue.", "A turn is already in progress for this session.", "The concurrent-turn limit has been reached. Try again shortly.", etc.
- `queue-add` / `queue-edit` / `queue-remove` { sessionId, queueId (client-minted; reused as flushed turn's idempotencyKey), text (add/edit; non-empty ≤65536B) }. No attachments. Idle session: queue-add sends immediately. Queue cap 50. Edit/remove silently no-op on unknown queueId.
- `interrupt` { sessionId } → exactly one `interrupt_result` to requester; `cancel_requested` event broadcast first when a turn was active.
- `approval` { sessionId, requestId, EXACTLY ONE of choiceId (non-empty ≤128) | decision ("allow"|"deny"), grantedPermissions? (requires choiceId; {fileSystem?:{read?,write?},network?:{enabled}}; ≤64 paths ×4096B; whole ≤65536B) }. No direct reply — outcome arrives as `approval_resolved` event.
- `question` { sessionId, requestId, answers: { answers: {"<exact question text>": "<chosen option label, comma-separated for multiSelect>"}, response?: string } }. JSON ≤65536B. Outcome: `question_resolved` event.
- `set-mode` { sessionId, permissionMode } → `session` broadcast. Applies next turn.
- `set-model` { sessionId, model (≤200B; "" or "default" clears) } — claude only → `session` broadcast if changed.
- `session-controls` { sessionId } → `session-controls` reply.
- `codex-controls` { sessionId } → `codex-controls` reply.
- `codex-control-action` { sessionId, action } — exact-key envelopes with revision + operatorAction:true + operatorActionId. (v1 Android: not needed.)
- `rate-limit-resume` { sessionId, resetsAt (epoch ms, must match active prompt), action: "dismiss"|"schedule" }.
- `pin` { sessionId, pinned: bool } / `rename` { sessionId, name (trimmed 1–80) } / `archive` { sessionId } (refused while a turn is active) / `kill` { sessionId }. All → `session` broadcast.
- `advanced-settings` {} / `set-advanced-settings` { claudeCliVersion: string|null } → `advanced-settings` reply. (v1 Android: skip.)
- There is NO detach message. Detaching is client-side only.

## 4. SERVER → CLIENT MESSAGES
To requester only: created, histories, search-results, directories, snapshot (attach reply), interrupt_result, session-controls, codex-controls, codex-control-result, advanced-settings, error, version_mismatch, initial ready + log replay.
Broadcast to ALL sockets: session, event, log (live), snapshot (manager-pushed). **`event` frames arrive for sessions you never attached to — ignore any session with no cursor.**

- `ready` { protocolVersion, sessions: AgentSession[], providers: Provider[], workspaceRoot } — first frame. workspaceRoot is a DEFAULT folder, not a boundary.
  - Provider = { id, label, glyph, available: bool, capabilities?: {persistentSessions, interactiveApprovals, interactiveQuestions, sandboxChoices, streamingText, streamingToolOutput, tokenDeltas, reasoningVisibility, reasoningSummaries, plans, diffs, modelSelection, collaborationModes, providerControls, providerCatalogs — all bool} }
- `log` { entries: LogEntry[], bootId } — on connect: last 200; then live single-element batches. LogEntry = { seq, ts, level: info|warn|error, event, sid?, turnId?, nativeId?, outcome?, durationMs?, message?, ... }. Dedup by seq > lastSeq; if bootId changed discard accumulated log. Cap 500.
- `version_mismatch` { requiredVersion, message }.
- `created` { session: AgentSession }.
- `session` { session: AgentSession } — broadcast row update.
- `histories` { cwd, sessions: HistorySession[] } — HistorySession = { historyId, provider, name, cwd, updatedAt, digest?: {newTurns, snippet} }.
- `search-results` { cwd, query, hits: (HistorySession & {snippet, matchCount})[] }.
- `directories` { listing: { current, parent: string|null, entries: [{name, path}] } }.
- `interrupt_result` { sessionId, turnId, status: "requested"|"no_active_turn"|"failed", error?, stillQueued? } — only "failed" warrants a user-visible error.
- `session-controls` { sessionId, models: [{value, displayName, description?, current?, resolvedModel?}], commands: [{name, description, argumentHint?, aliases?, supported}], model? }. supported:false → never send as prompt text.
- `codex-controls` / `codex-control-result` — see protocol.ts (v1 Android: ignore).
- `advanced-settings` { claudeCliVersion, discovered, envForced, envPath }.
- `error` { message } — NO correlation id. Treat as a toast.
- `snapshot` { sessionId, throughSeq, state: SessionProjection, reset?: true } — `events` is never sent. state always complete: replace wholesale, cursor = throughSeq. reset present only when client afterSeq > server lastSeq.
- `event` { sessionId, event: {...AgentEvent, seq, ts} } — FLAT envelope, no nested payload. seq strictly previous+1 on the live stream, per session. ts = server wall-clock epoch ms — the ONLY time source for elapsed calculations (never device clock).

AgentSession (publicSession output):
```
{ id, provider, engineGeneration?, name, cwd, runtimeCwd?,
  worktree?: {path, branch, status: active|retained|removed|missing|error, notice?}|null,
  status: ready|active|waiting|exited, startedAt, updatedAt, endedAt: number|null,
  exitCode: number|null, historyId?, pinned: bool, runtimeArchived: bool,
  metrics?: SessionMetrics, mode: "headless", nativeSessionId?: string|null,
  resumeTargetNativeId?: string|null, permissionMode?, model?, sandboxPolicy?: string|null,
  lastTurnOutcome?: string|null }
SessionMetrics = { model?, effort?, totalTokens?, contextWindow?, subagents?,
  fiveHour?/weekly?/fable?: {usedPercent, windowMinutes, resetsAt?},
  contextPercent?, contextTokens?, sessionCostUSD?, gitBranch?, gitAhead?, gitBehind? }
```

## 5. ATTACH / RECONNECT / DURABLE SEND

### 5.1 Attach
Send `attach {sessionId, afterSeq?}` → server synchronously sends one complete `snapshot`. No missed-event window (single-threaded server). A second, broadcast snapshot may follow asynchronously (external-advancement probe) — handle snapshots idempotently.

### 5.2 Live event application (port exactly)
```
on event(sessionId, ev):
  cursor = cursors[sessionId] ?: return         // never attached — ignore
  if ev.seq == null: fold without cursor move; return
  if ev.seq <= cursor: return                   // duplicate
  if ev.seq > cursor + 1:                       // GAP
      if !resyncPending[sessionId] && socket open:
          resyncPending[sessionId] = true
          send attach {sessionId, afterSeq: cursor}    // exactly ONCE per gap
      return                                    // do NOT fold
  cursors[sessionId] = ev.seq
  state[sessionId] = reduce(state[sessionId], ev)
```
Clear resyncPending when that session's snapshot arrives.

### 5.3 Reconnect
Fixed **1800 ms** delay after close (no backoff/jitter in the reference client). Immediate reconnect on network-available and on app foreground (onResume) when socket not OPEN/CONNECTING. `stopped=true` (permanent) on protocol mismatch or teardown. Before each connect, check `GET /api/auth/session`; on unauthenticated → re-login (or surface login).

### 5.4 On open
Do NOT drain pending sends. Reset in-flight records to unsent. Wait for `ready`.

### 5.5 On ready
1. Verify protocolVersion == 40. 2. Store sessions/providers/workspaceRoot. 3. `browse` + `discover` for the selected workspace. 4. For each subscribed session (and sessions with pending outbound input): `attach {sessionId, afterSeq: cursors[sessionId]}`.

### 5.6 Durable send / at-most-once (MUST implement before shipping send)
- Record every prompt locally BEFORE transmission with its client-minted key (idempotencyKey for send, queueId for queue-add).
- Server dedup slot = single most recent accepted key per session, in-memory only. Re-transmitting an older key can start a duplicate turn.
- Rule: a record already transmitted may NOT be re-transmitted until this connection received a `snapshot` for its session and reconciled. Freshly minted keys are exempt.
- Reconciliation: key is ACCEPTED iff it appears in the snapshot as some turnsById[*].idempotencyKey or queuedMessages[*].queueId. Absent → never accepted → re-send.
- Live ack: `turn_started.idempotencyKey` or `queued_message_added.queueId` acks a key. Match on KEY ALONE, never kind (queue-add on idle flushes into a turn, acked by turn_started).
- Half-open detection: socket OPEN + oldest in-flight record older than 8000 ms + no inbound frame of ANY kind for 8000 ms → force-close socket to trigger reconnect. Any inbound frame counts as liveness.
- Bounds: MAX_TRIES 5, MAX_AGE_MS 600000, MAX_RECORDS 200. Expired records surface a user-visible failure containing the original text.
- Attachments are not persisted; if an attachment send can't reach the wire immediately, roll back and tell the user, keeping the draft.

## 6. RECOMMENDED CLIENT FLOW
1. /healthz → protocolVersion check. 2. Login → cookie (EncryptedSharedPreferences). 3. wss://<host>/ws with Cookie + Origin matching Host. 4. Expect ready; validate version. 5. attach sessions of interest; maintain cursors/state/resyncPending maps per §5.2. 6. Port the reducer (see reducer-spec.md). 7. Durable-send store per §5.6. 8. Fixed 1800 ms reconnect + network/foreground triggers. 9. Rely on WS PING/PONG; add the 8 s half-open force-close. 10. error frames = uncorrelated toasts. 11. Never use device clock for elapsed times — use event ts / run.startedAt.
