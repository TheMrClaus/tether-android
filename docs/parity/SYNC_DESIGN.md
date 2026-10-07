# Tether Android: sync layer design (Phase 13)

> Task **T13.0**. This is the design for PLAN §7 Phase 13, "works better than a browser tab". A
> plan-verifier reviews it before T13.1 starts. **It is a design only: no production code.**
> Written 2026-09-27 against tether `bde3cfa` (PROTOCOL_VERSION 129, NATIVE_PROTOCOL_FLOOR 129) and
> tether-android `main` `12d2c6a`.
>
> Citations: `tether:` means `~/git/tether` at `bde3cfa`. A bare path is this repo. Every claim about
> existing behaviour carries a `file:line`. Anything without one is a decision made here.

---

## 0. Summary of decisions

| # | Topic | Decision |
|---|---|---|
| SD1 | Source of truth | A per-server Room **mirror** holds a server **base** (the last snapshot `state`) plus the contiguous **tail** of live events after it. The UI sees `fold(base, tail)` computed by the Kotlin reducer. Memory holds a write-behind cache of that fold, never anything the DB could not rebuild. |
| SD2 | UI contract | The UI keeps observing today's `TetherClient` StateFlows (`projections`, `projectionTrees`, `sessions`, `trimmedBefore`). Room hydrates them on start and receives writes. Two flows are added: `syncStates` and `outbox`. The UI does not query Room directly. |
| SD3 | Attach | `afterSeq` = the persisted cursor. Cursors survive process death, so a cold start with a current mirror gets a stateless at-head reply instead of a full snapshot. **Exception (§3.1 rule 1a):** a session holding a PendingStore record with `tries > 0` attaches **without** afterSeq, because only a snapshot that carries `state` may authorise redelivery. Gap, reset and bounded-snapshot handling stay exactly as T1.2/T2.3 implement them. |
| SD4 | Event deltas | **Rejected for now.** The WS attach never sends events. Serving `readSince(afterSeq)` would be unsafe when the cursor is inside a turn that has since been compacted (§4.4). Catch-up means a bounded server snapshot. |
| SD5 | Offline | Every mirrored session stays readable with no network. Freshness has four states (Live / Catching up / Saved copy · age / Not downloaded). Each is shown as an icon plus text, never colour alone. Run-status badges from a saved copy are qualified ("was running · 12 min ago"). |
| SD6 | Outbox | Three layers: T1.3's **PendingStore**, unchanged; a new **staged outbox** for text composed while not connected (DataStore, written atomically with PendingStore); in-memory **held decisions** for approvals and answers. A held decision is sent once, and only if a fresh snapshot shows the identical request is still pending. Every other type is refused while offline. |
| SD7 | Background | S13.1 adds a **content-free, session-free** FCM data message `{kind:"sync"}` (normal priority, collapsible, coalesced per device) and `AgentSession.lastSeq`. T13.4 runs a **read-only** WorkManager catch-up that never transmits operator input. |
| SD8 | Wire | S13.1 is a wire change, so it needs **one PROTOCOL bump**. It is **not native-breaking**: additive and tolerant-decodable. `NATIVE_PROTOCOL_FLOOR` stays where it is. |
| SD9 | At rest | Row-level AES-256-GCM. The data key is wrapped by a new Keystore key. Index columns (ids, seq, timestamps, flags) stay in clear. **No SQLCipher**, so the encryption can be tested on the JVM. The mirror is excluded from backup and device transfer. It is wiped on logout and on revocation (4001/4002). |
| SD10 | Cache | Default budget 200 MB. Eviction runs LRU over unpinned sessions: first the re-fetchable turn details, then whole sessions. Pinned sessions, the open session, and sessions with unsent input are never evicted. "Clear cache" deletes the mirror DB file only and never touches unsent input. |
| SD11 | Multi-host | One mirror DB per **server origin**, keyed `(sessionId, seq)` inside it. v109 nodes are a server-side registry; sessions carry no `nodeId`. Nothing is keyed per node. |
| SD12 | Conflicts | The server is authoritative. A server `state` always replaces the local base. The local projection is always a pure re-fold. The reducer is the arbiter, and a fold exception drops the base and triggers a full attach. |

---

## 1. Facts this design rests on

### 1.1 Server journal, seq, attach

| Fact | Where |
|---|---|
| `seq` is assigned **only** by the per-session journal: `append` stamps `seq: _nextSeq++` and `ts`. It is persisted before broadcast. | `tether:engines/journal.mjs:95-97`, `tether:CLAUDE.md:63`, `tether:engines/session-manager.mjs:1879,1899` |
| Every live event goes through `_appendAndBroadcast`, so it is journaled and seq-stamped. No seqless event path exists. | `tether:engines/session-manager.mjs:1870-1899` |
| `broadcast()` fans every frame out to **every** socket, attached or not. The client filters by cursor. | `tether:server.mjs:4531-4545` |
| `snapshot(id, afterSeq)`: `reset` iff `afterSeq > lastSeq`. `atHead` iff `afterSeq >= lastSeq` and not reset, and then `state` is null. Otherwise it returns the full projection. | `tether:engines/session-manager.mjs:1682-1709` |
| The WS attach calls it with `includeEvents:false, tailTurns:50`, so **no events ever go out on the wire**. It sends `snapshot {throughSeq, state?, reset?, trimmedBefore?}`. | `tether:server.mjs:8666-8701` |
| Bounded snapshot: turns older than the last 50 keep their metadata (status, outcome, `idempotencyKey`…) but get `blocks: [], blocksById: {}`. | `tether:engines/session-manager.mjs:1712-1731` |
| `fetch-turns {fromIndex,toIndex}` gets `turns-detail {turns}`, which carries full turn projections. | `tether:server.mjs:8710-8723` |
| The server may push an unsolicited `snapshot` (external advancement/reconcile) to all sockets. | `tether:engines/session-manager.mjs:4073,4105`, `tether:server.mjs:8709` |
| **Compaction**: after a turn ends, `compactTurn` rewrites that DONE turn's events into a minimal set. The rewrite is projection-preserving for a full replay. It re-stamps the set **positionally with the earliest original seqs** and leaves seq gaps. `lastSeq` does not move. | `tether:engines/journal.mjs:222-236,522-561`, `tether:engines/session-manager.mjs:1734,3730-3743` |
| **No prefix truncation**. Retention forgets whole *retired* sessions only (cap 100, never pinned or live), and there is no wire "session removed" frame. | `tether:server.mjs:5180-5218`, `tether:session-retention.mjs:13,34`, `tether:engines/session-manager.mjs:1216-1243` |
| A crash-torn tail line is truncated on boot (`_truncateTornTail`). `lastSeq` can then fall **below** a client cursor, and the next attach answers `reset:true` with full state. | `tether:engines/journal.mjs:46,55-78`, `tether:engines/session-manager.mjs:1685-1689` |
| **Consequence:** an `afterSeq` never "falls off" the journal. A behind cursor always gets a full (bounded) state, a cursor at head gets a stateless reply, and a cursor ahead gets `reset` plus state. | above |

### 1.2 Server input semantics

| Fact | Where |
|---|---|
| Send dedupe is a **single in-memory slot** per session, `lastIdempotencyKey`. It is empty after a restart. | `tether:engines/session-manager.mjs:723,3127-3128,3165` |
| The durable acknowledgement is the snapshot: `turnsById[*].idempotencyKey` and `queuedMessages[*].queueId`. There is no ack frame. | `tether:lib/pending-input.mjs:18-33,132-150` |
| Records: MAX_TRIES 5, **MAX_AGE_MS 10 min** measured from `firstQueuedAt`, MAX_RECORDS 200, 256 KB persisted, UNACKED_CLOSE_MS 8 s, MAX_TOMBSTONES 500. | `tether:lib/pending-input.mjs:58-92,426-446` |
| A record may be **re-transmitted** only after its session has been reconciled against a snapshot on this connection. A never-sent record (tries 0) is exempt. | `tether:lib/pending-input.mjs:280-305` |
| Known residual: a queue item **removed on another device** leaves no trace in the projection, so it can be re-sent within the 10-minute window. Closing it needs the projection to retain removed queueIds, which is a wire change. | `tether:lib/pending-input.mjs:35-48` |
| `send.mention` (v103) is persisted with the record, because losing it would turn a delegation into a plain prompt. | `tether:lib/pending-input.mjs:94-117,158-176` |
| `approval`: the engine resolves `requestId` in the **active turn**. Unknown or resolved ids are a silent `false`, first-wins. `question` returns an `error` frame when it is stale. | `tether:server.mjs:8876-8894`, `tether:engines/session-manager.mjs:4755-4800` |
| A pending approval is `{requestId, toolId, name, input, choices?, metadata?}` in the current turn's `pendingApprovals`. `approval_resolved`/`approval_expired` delete it. It carries **no seq or ts**. | `tether:engines/events.mjs:2495-2515,2564-2573` |
| `queue-edit`/`queue-remove` are no-ops for an unknown queueId. `dismiss-notice` is a silent no-op for an unknown key. | `tether:server.mjs:8853-8871`, `tether:hooks/use-tether.ts` (as ported, `RealTetherClient.kt:1338-1339`) |

### 1.3 Push today

| Fact | Where |
|---|---|
| FCM messages carry a `notification {title, body}` plus `data {url, kind, tag}`. The title and body are generic ("A Claude session is waiting for approval."). | `tether:lib/fcm-push.mjs:296-317`, `tether:lib/push-notifications.mjs:441-513` |
| **Privacy floor:** the FCM payload is readable by Google, so it keeps the id-free `url:"/"`. Only encrypted Web Push carries the session id. Tests pin this ("Keep it that way"). | `tether:lib/push-notifications.mjs:358-366` |
| `tag` is a truncated SHA-256 of (session, request/turn) ids. | `tether:lib/push-notifications.mjs:303-309` |
| Server-side scope filter per device row: `all` / `attached` / `pinned`. | `tether:lib/fcm-push.mjs:431-445` |
| `fcm-register` requires a **device** principal and picks known body fields explicitly, so unknown fields are ignored. | `tether:server.mjs:6981-7007` |
| The app ignores any FCM message without a title (returns early). | `app/src/main/java/com/tether/app/push/TetherFcmService.kt:39-42` |

### 1.4 Native compatibility rule

A native `hello` is served for `NATIVE_PROTOCOL_FLOOR ≤ v ≤ PROTOCOL_VERSION`. Every bump is classified
as native-breaking or not, and the floor rises only for a breaking change (`tether:CLAUDE.md:61`,
`tether:lib/protocol.ts:762-781`). The `attach` validator returns only known fields, so an old server
drops new attach fields (`tether:lib/protocol-validate.mjs:1300-1301`).

### 1.5 Web baseline (`hooks/use-tether.ts`)

Per-session cursor for dedupe and `afterSeq` (`:142`). The cursor is in memory only, so a page reload
starts from a full snapshot. Snapshot handling: cursor := `throughSeq`, clear the resync flag, replace
state only when `state` is present, reconcile pending input (`:935-984`). Events: `seq ≤ cursor` is
dropped; `seq > cursor+1` triggers one resync attach per gap (`:995-1016`). Re-attach on reconnect
(`:798-799`). `visibilitychange`/`online` trigger `reconnectIfIdle` (`:1195-1211`). Lazy
`fetch-turns` (`:1528`).

### 1.6 Android today

| Area | Fact | Where |
|---|---|---|
| Connection (T1.2) | Epoch state machine. `ready` sends `hello`, then attaches subscribed, cursored and pending sessions with `afterSeq = cursor`. | `core/net/.../client/RealTetherClient.kt:74-91,1125-1168` |
| Lifecycle | After 60 s in background the socket closes and reconnects stop until foreground. | `RealTetherClient.kt:647-686`, `ConnectionPolicy.kt:25` |
| Cursor (T2.3) | `CursorTracker` ports the web rules exactly. It is **in memory only**, so after process death every attach is a full snapshot. | `core/net/.../client/CursorTracker.kt:3-81`, `RealTetherClient.kt:179` |
| Snapshot | Moves the cursor, stores `trimmedBefore`, replaces the tree, reconciles PendingStore, marks the session reconciled, drains. | `RealTetherClient.kt:1190-1221` |
| Event | CursorTracker decision, then live ack by key, then `reduce(tree, event)`. A fold exception drops the tree and sends a full attach (no afterSeq). | `RealTetherClient.kt:1223-1271` |
| turns-detail | Spreads full turns into `turnsById`. The app **never sends** `fetch-turns` yet (that is T6.1). | `RealTetherClient.kt:1278-1283`, `core/protocol/.../ClientMessage.kt:254` |
| UI state | All projection state lives in `MutableStateFlow`s in the client. Nothing about transcripts is persisted. | `RealTetherClient.kt:193-228`, `core/net/.../ui/TetherViewModel.kt:117,217` |
| Reducer | Pure fold `reduce(state, event)` over `JsObj`. It never reads a clock (guarded by `FoldPurityTest`). | `core/reducer/.../fold/Reduce.kt:11-21` |
| Durable send (T1.3) | `PendingInput` is a typed facade over the corpus-verified port. It persists `{v:2,records,cleared}` to DataStore after every change, conflated and ordered. Restored records count as possibly sent. | `core/net/.../client/PendingInput.kt:13-136`, `RealTetherClient.kt:1435-1487` |
| T1.3 gaps | The facade's `addRecord` has **no `mention`** parameter (`PendingInput.kt:42-50`), though `ClientMessage.Send` has one (`ClientMessage.kt:291-316`). The queue-removed-elsewhere residual is inherited from the web. | bead T13.3 notes |
| Approvals | Fire-and-forget `sendFrame`. They are not queued. | `RealTetherClient.kt:1512-1514` |
| Storage (T1.4) | DataStore `tether_settings.preferences_pb` (URL + pending input). Credentials are sealed with a Keystore AES-GCM key that is not user-auth-bound, so it works in the background. | `core/data/.../SettingsStore.kt:63-66,106-123`, `KeystoreCredentialKeySource.kt:10-58` |
| Cipher | `AesGcmCredentialCipher`: `[v1][IV][ct‖tag]`, with AAD binding the slot. It is JVM-testable with a software key. | `core/data/.../CredentialCipher.kt:11-26,77-133` |
| Backup | `allowBackup=true`. Credentials and the settings file are excluded from cloud backup and device transfer. | `app/src/main/AndroidManifest.xml:20-28`, `app/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml` |
| Push | `TetherFcmService` posts notifications. `PushScope` all/attached/pinned. `PushRegistrar` registers scope sets. | `app/.../push/TetherFcmService.kt`, `core/data/.../push/PushScope.kt`, `app/.../push/PushRegistrar.kt:71-140` |
| Modules | `:core:net` → `api(:core:data)`, `:core:data` → `:core:reducer`. There is no Room, WorkManager or KSP yet. | `core/net/build.gradle.kts:12-14`, `core/data/build.gradle.kts`, `gradle/libs.versions.toml` |

---

## 2. Source-of-truth model (T13.1)

### 2.1 Decision

The Room mirror is the **durable** source of truth for everything the app shows about a session. The
live projection a screen renders is always `fold(base, tail)`:

- **base**: the last `state` the server sent for the session (a full or bounded snapshot), with any
  `turns-detail` patches spliced in, which is the same operation as `RealTetherClient.kt:1281`. It can
  also be a *local checkpoint* that the reducer itself produced from an earlier base plus tail (§2.4).
- **tail**: every live event with `base.throughSeq < seq ≤ cursor`, stored verbatim. The tail is
  contiguous by construction, because `CursorTracker` folds only `seq == cursor+1` and turns every gap
  into a resync (`CursorTracker.kt:57-68`).

In memory, a per-session `SessionStore` holds `(base, tail, tree)` and publishes `tree` into today's
flows. Writes go to Room **write-behind** in batches. The invariant, tested in T13.6, is
**`tree == fold(DB.base, DB.tail)` whenever the write queue is empty**. The persisted cursor is always
≤ what is persisted.

**Alternatives rejected**

- *The UI observes Room queries directly* (the textbook single-source-of-truth pattern). Streaming
  produces `message_delta` at token rate. Each event would cost a transaction, then a table
  invalidation, then re-reading and decoding a projection blob of up to about 1 MB. That means write
  amplification and jank for no correctness gain, because the fold is deterministic. The in-memory
  tree is a cache of the DB, not a second truth.
- *Persist only projection snapshots and no events.* It saves space, but then nothing can
  cross-check the reducer, and a crash would lose the "fold equals DB" audit trail T13.6 needs. It
  also forces a blob write per event.
- *Persist raw frames and re-derive everything.* Snapshot `state` is not an event, so the base has to
  exist anyway.

### 2.2 Storage layout

**Two stores, split by what may be thrown away:**

1. **Mirror DB**: Room, file `databases/mirror-<first 16 hex of sha256(canonical origin)>.db`. It is a
   **cache**: deletable at any time and rebuilt from the server. A destructive migration fallback is
   allowed, as a last resort only (§8.4).
2. **Unsent input**: stays in DataStore (`tether_settings.preferences_pb`, T1.3's
   `tether:pendingInput` payload), plus one sibling key for the staged outbox, written **in the same
   atomic `edit {}`** (§6.3). It is never destroyed by a migration or by "clear cache".

*Deviation from PLAN D8 ("Room for … outbox"), to be logged.* The outbox maps onto T1.3's PendingStore
(§6), and the two must commit atomically together. One DataStore edit already gives atomicity for a
store capped at 200 records and 256 KB (`tether:lib/pending-input.mjs:68,75`). A second Room store
would duplicate T1.3's persistence and add a cross-store transaction problem.

**Mirror schema v1.** `sealed` means an AES-GCM blob (§8.5). Everything else is clear index data.

| Table | Key | Columns | Notes |
|---|---|---|---|
| `session_row` | `session_id` | `blob` sealed (the AgentSession JSON, verbatim), `updated_at`, `last_message_at`, `pinned`, `runtime_archived`, `server_last_seq` (S13.1, nullable), `gone_from_server`, `last_opened_at` | Upserted from `ready`, `created` and `session-update`. |
| `session_base` | `session_id` | `through_seq`, `trimmed_before` (nullable), `origin` (`server`\|`local`), `reducer_version`, `state` sealed (gzip then seal), `received_at` | Exactly one base per session. |
| `journal_event` | `(session_id, seq)` | `type`, `ts`, `payload` sealed (the canonical event JSON) | Only `seq > base.through_seq`. Insert conflict means a duplicate, which is ignored. |
| `turn_detail` | `(session_id, turn_id)` | `turn_index`, `payload` sealed, `fetched_at` | Server `turns-detail`. Spliced into the base on rebuild. Can be re-fetched, so it is evicted first. |
| `sync_state` | `session_id` | `cursor`, `last_verified_at`, `level` (`list`\|`full`), `bytes` | `cursor` is written in the **same transaction** as the rows it covers. `last_verified_at` means the server confirmed head on a live connection. |
| `meta` | `key` | `value` | Schema/app version, origin, `reducer_version`, data-key id. |

`reducer_version` = the vendored corpus manifest SHA plus the protocol the reducer port models (`TARGET_PROTOCOL_VERSION`; ta-ylh let it run ahead of the advertised hello version, and since ta-3uk the two are equal again, though the key stays on TARGET). See §2.4 for why.

### 2.3 Write path

One `MirrorWriter` coroutine actor per DB serializes all writes, so per-session order is preserved. It
batches a transaction every ≤ 100 ms or 64 operations.

| Input (existing handler) | Mirror write (one transaction) |
|---|---|
| `Snapshot` with `state` (`RealTetherClient.kt:1190`) | Replace `session_base` (origin `server`), delete that session's `journal_event` rows and the `turn_detail` rows the new state already holds in full (`turn_index ≥ trimmedBefore`, or all rows when `trimmedBefore` is absent), set `cursor := throughSeq`, set `last_verified_at := now`. |
| `Snapshot` with `reset` | As above. The cursor may go **down** (`tether:engines/session-manager.mjs:1685-1689`). A reset always carries state because it is never at head. |
| Stateless `Snapshot` (at head) | **Implemented (safer than this table's first draft, ta-705 (3)): `last_verified_at := now` only, and only when the persisted cursor already equals `throughSeq`; the cursor is NEVER set to `throughSeq` by a stateless reply** (`JournalMirror.kt` `Op.Verified`). A stateless reply carries no state, so a persisted cursor that differs (cleared by a seqless event or a gap, or absent) does not cover `throughSeq`, and claiming it would let a restart restore a copy that is missing events. At head with an equal cursor the two rules give the same row; where they differ the stateless reply changes nothing, and the next attach is a full one. This is the intended behaviour, not a gap to close. |
| `Event`, decision `Fold` | Insert `journal_event` and set `cursor := seq`. |
| `Event`, `Drop`/`Resync`/`AwaitSnapshot`/`Ignore` | Nothing. |
| `Event` with no `seq` (`Fold` without cursor movement, `CursorTracker.kt:56`) | **Not persisted.** The session's `sync_state.cursor` is cleared in the same batch, so the next attach is a full one. The in-memory fold happens as today (T2.3 parity). |
| `TurnsDetail` | Upsert `turn_detail` rows. |
| `ready` / `created` / `session-update` | Upsert `session_row`. For `ready` (a full list), rows missing from it get `gone_from_server=1`, never a delete (§5.4). |
| Fold exception (`RealTetherClient.kt:1257-1267`) | Delete the session's base, tail and cursor, then do the full attach as today. |

**Seqless events are impossible at v129.** The only WS `event` emitter is `_appendAndBroadcast`, which
broadcasts the journal-stamped event (`tether:engines/session-manager.mjs:1878-1899`), and no other
`type:"event"` frame exists in `server.mjs`. The row above is defensive: `journal_event` cannot hold a
seqless event, and folding one without persisting it would break `tree == fold(DB)`. Clearing the
cursor restores the invariant at the next attach.

**Crash safety falls out of the model.** If the process dies with a batch unflushed, the persisted
cursor is older than what the UI showed. The next attach asks from that older cursor, and the server
answers with state. Nothing is lost and nothing is double-folded. Losing up to 100 ms of writes is a
performance cost, not a correctness issue.

### 2.4 Rebuild (hydration) and local checkpoints

- **Cold start:** load `session_row`s and publish the list immediately. A projection is hydrated
  **lazily**, when a screen observes the session or the `ready` re-attach includes it. Hydration loads
  `base` and `turn_detail`, splices, folds the tail, publishes, and seeds `CursorTracker` with the
  persisted cursor (a new `seed(sessionId, cursor)`, the same state as `onSnapshot`). Seeding the
  cursor never authorises redelivery. That still takes a `state` snapshot (§3.1 rule 1a).
- **Local checkpoint:** when a tail passes **2,000 events** (or 500 at a `turn_end`), the writer stores
  `fold(base, tail)` as the new base with `origin=local` and deletes the covered events. This bounds
  hydration cost. It is safe because the fold is the same code that produced the in-memory tree, and
  the conformance tests in §10 prove the fold is exact.
- **Reducer upgrades:** a `local` base depends on the reducer version. When `meta.reducer_version`
  changes (a new APK with a new reducer port), `local` bases stay **displayable** offline but their
  cursors are cleared. The next attach is then a full one (no afterSeq), which fetches a fresh server
  base. `server` bases keep their cursors, which is exactly what the web does across a reconnect.
- **Corruption:** a base that fails to decrypt, decode or fold is dropped for that session, followed by
  a full attach. Failing to decrypt the data key itself wipes the whole mirror (§8.2).

### 2.5 What the UI observes

`TetherClient` keeps its current flows (`TetherClient.kt:23,37,44,212`), so no screen changes in
T13.1. Added:

```kotlin
val syncStates: StateFlow<Map<String, SessionSync>>   // T13.2, §5
val outbox: StateFlow<OutboxView>                     // T13.3, §6
data class SessionSync(val freshness: Freshness, val lastVerifiedAt: Long?, val partial: Boolean)
enum class Freshness { Live, CatchingUp, Saved, NotDownloaded }
```

`FakeTetherClient` returns `Live` and an empty outbox, so previews and screenshots stay unchanged.

### 2.6 Incremental migration (no big bang)

| Step | Change | Behaviour | Rollback |
|---|---|---|---|
| 1 (T13.1a) | Add Room, KSP and `MirrorWriter`. Record snapshots and events **shadow-only**. | Unchanged. Tests assert `fold(DB) == in-memory tree`. | Stop the writer. |
| 2 (T13.1b) | Hydrate flows and seed `CursorTracker` from the mirror on cold start. | A cold start with a current mirror shows the transcript instantly and gets a stateless attach reply. | `mirrorEnabled=false` pref (debug) / build flag. |
| 3 (T13.1c) | Extract projection ownership from `RealTetherClient` (`projectionTreesState`, `adapters`, `trimmedBeforeState`) into `SessionStore` (package `com.tether.app.client.sync`). | Same flows, now fed by the stores. | Revert the commit. Step 2 still stands. |
| T13.3 | Staged outbox + held decisions. | Offline sends are staged, and taps are held. | `offlineQueueEnabled=false` (remote-free local flag, default on after T13.3 verifies): filing falls back to today's `recordAndDrain` path, and approval/answer controls are disabled while not connected. Already-staged records are **promoted, not dropped**, on the next handshake, so switching the flag off never loses input. |
| T13.4 | Background catch-up. | FCM `sync` hints and periodic work run the worker. | The "Keep sessions up to date in the background" setting, plus a `backgroundSyncEnabled` build flag. Off: cancel unique and periodic work, stop routing `sync` (hints are ignored as today), and PATCH `syncHints:false`. |

The flags are local only (BuildConfig plus a debug-settings override). There is no remote config,
which the app does not have.

---

## 3. Attach and delta protocol use (T13.1)

### 3.1 Rules

1. **`afterSeq` = the persisted `sync_state.cursor`**, i.e. `max(base.throughSeq, last tail seq)`. The
   tail is contiguous, so this equals the "max contiguous local seq". A session with no cursor attaches
   **without** afterSeq (a full bounded snapshot), as today.

   **1a. Unsent input forces a full attach (BLOCKER-1 resolution).** A session that holds any
   PendingStore record with `tries > 0` at attach time (in `onReady`, `attach()` and the
   gap/fold-throw paths) attaches **without** afterSeq. One helper, `afterSeqFor(sessionId)`, returns null in that case.
   *Why:* only a snapshot **with `state`** reconciles PendingStore and adds the session to
   `reconciledSessions` (`RealTetherClient.kt:1190-1192,1205-1210`). Records restored after process death
   have `tries > 0` (`PendingInput.kt:130`) and may be re-sent only after that reconcile
   (`tether:lib/pending-input.mjs:280-305`). A persisted cursor at head gets a stateless reply, which
   would strand them until they expire at 10 min. The web attaches every pending session for exactly
   this reason (`tether:hooks/use-tether.ts:791-799`).
   *Chosen over (b)*, reconciling a stateless reply against the mirror-rebuilt projection. That would
   make redelivery safety depend on the local fold being exact at head, turning a reducer-port bug into
   a possible **second turn**. Option (a) keeps redelivery on server evidence only (C6). The cost is one
   bounded snapshot (up to about 1 MB) per session with unsent input, which is rare.
   Records with only `tries == 0` do not need a reconcile (`tether:lib/pending-input.mjs:291-294`), so
   they keep the cursor.
   *Upstream note:* the web has the same latent gap within one tab. On a reconnect with an in-memory
   cursor at head and an in-flight record, it gets a stateless reply and no reconcile (`use-tether.ts:798`,
   `:952-961`). Flag it upstream (§13.1 R10). The Android rule closes it for the app in both the
   cold-start and the in-process cases.
2. **Gap detection is unchanged.** It is `CursorTracker` over live events only. Gaps are never folded,
   and a gap sends one `attach{afterSeq: cursor}` (`CursorTracker.kt:57-68`). The mirror inherits
   contiguity from this.
3. **Server answers → mirror** (§2.3):

| Server reply | Condition (server) | Client |
|---|---|---|
| `snapshot` without `state` | cursor == head | Cursor unchanged, `last_verified_at := now`, freshness **Live**. This is the cheap cold-start path. |
| `snapshot` + `state` (+`trimmedBefore`) | cursor behind | Replace the base, clear the tail, and reconcile PendingStore as today (`RealTetherClient.kt:1205-1219`). Trimmed turns show as "Not downloaded" until fetched (§5). |
| `snapshot` + `state` + `reset` | cursor ahead (journal replaced or torn tail) | Same as above, and the cursor goes down. Logged, with no user notice: the server copy is authoritative and the replaced transcript is what the server holds. |
| Unsolicited `snapshot` + `state` | external advancement | Same as "behind". |
| "Journal truncated" | **does not exist** (§1.1) | Nothing to handle. A behind cursor is always answered with state. |

4. **Live events for non-open sessions.** The server fans out every event (`tether:server.mjs:4531`),
   so while connected the mirror stays current for **every session that has a cursor**, not only the
   open one. That is free today because `CursorTracker` already folds them.
5. **Re-attach set on `ready`** (`RealTetherClient.kt:1144-1152`): subscribed ∪ cursored ∪ pending,
   **capped**. With cursors now surviving process death, "cursored" could mean every mirrored session.
   The `ready` re-attach includes the open session, sessions with pending or staged input, pinned
   sessions, and the 10 most recently opened. The rest keep their saved copy and are attached when
   opened. Attaching at head costs one tiny frame, but a behind session costs up to about 1 MB.
   A capped-out session that gets a live event while behind hits the normal gap resync, so active
   sessions catch up on their own and idle ones cost nothing.
   **Server cost per attach:** each attach sets `socket.watchedSessionId`, which holds one value per
   socket, so the last attach wins (`tether:server.mjs:8668`). It also fires
   `detectExternalAdvancementLive`, an async native-transcript tail read for an **idle** session, which
   may broadcast an unsolicited snapshot to every client (`tether:server.mjs:8709`,
   `tether:engines/session-manager.mjs:4004-4030`). The 5 s watch poll re-reads only read-only codex
   sessions that a socket is watching (`tether:server.mjs:9774-9785`). A burst of attaches is therefore
   one tail read each, which is acceptable for the foreground set.
6. **Bounded snapshots and `fetch-turns`.** The mirror stores `trimmedBefore`. The chat's lazy loader
   (T6.1) sends `fetch-turns`, and the replies land in `turn_detail`, so a turn fetched once stays
   readable offline. A new server base with a smaller `trimmedBefore` supersedes older details (§2.3).

### 3.2 Event deltas: rejected (SD4)

"Reconnect = `attach{afterSeq}` → true deltas" (PLAN T13.1 wording) would need the server to send
`readSince(afterSeq)`. The `snapshot` type already admits `events?` (`tether:lib/protocol.ts:3285`),
and the Control API does exactly this (`tether:lib/control-api.mjs:630-645`). It is **unsafe with
compaction**:

- `compactTurnEvents` re-stamps a finished turn's minimal set with the turn's **earliest** original
  seqs (`tether:engines/journal.mjs:533-545`).
- A client whose cursor sat **inside** that turn (it saw half of it live, then disconnected) would
  receive only the minimal events whose new seq is past its cursor. Folding those onto its half-turn
  state duplicates or loses content. The result is projection-preserving only for a replay from the
  turn's start.

Doing this safely needs a compaction-aware server rule (serve events only when no compacted turn
straddles `afterSeq`, otherwise send state). That is a reducer-adjacent server change with a
high-stakes failure mode, a silently diverged transcript. The benefit is bytes saved when a long
session changes a little, and the bounded snapshot already caps that at about 1 MB
(`tether:server.mjs:8679-8682`). **Decision: keep state replacement. Log compaction-aware deltas as a
future candidate (§12 OQ4).** The same hazard applies to Control-API subscribers that fold events from
a mid-turn cursor. That should be flagged upstream (§12 R6).

**Consequence for PLAN wording:** "true deltas" means the stateless at-head reply (nothing changed,
nothing sent) plus a bounded state when behind. PLAN §7 T13.1 should be amended.

---

## 4. Offline mode and stale indicators (T13.2)

### 4.1 Freshness model (per session, derived, never stored except `last_verified_at`)

| State | Definition | Icon (Lucide) | Text (visible; also the TalkBack label) |
|---|---|---|---|
| **Live** | Connected, attached this epoch, snapshot received, no pending resync | none | none. Live is the unmarked default, as on the web. |
| **Catching up** | Connected, attach sent or gap resync pending, snapshot not yet in | `refresh-cw` (static when reduced motion is on) | "Catching up…" |
| **Saved** | Not verified on the current connection (offline, or not re-attached) | `history` | "Saved copy · updated 12 min ago" (relative, from `last_verified_at`) |
| **Not downloaded** | A list row with no base, or trimmed turns with no `turn_detail` | `cloud-off` | "Not downloaded. Connect to load" (session), "Older turns not downloaded" (turn rows) |

The global connection banner uses the existing `ConnectionState` (`TetherClient.kt:215-229`):
Disconnected or Connecting shows `wifi-off` + "Offline. Showing saved copies" or `refresh-cw` +
"Reconnecting…". This is a banner, not a modal. Nothing is shown while Connected.

### 4.2 Rules

- **Never colour alone** (PLAN constraint 2). Each state has an icon and text. Stale states use neutral
  ink tokens. **Violet is never used** for them, because violet means focus, selected or waiting only.
  Red is not used either: stale is not an error.
- **Qualify live-looking status from a saved copy.** In Saved state, run badges (running, waiting for
  approval) and the context gauge read "was running · 12 min ago". A saved copy must never claim an
  agent is waiting on you *now*. Approval and question cards from a saved copy are rendered but
  disabled, with "Connect to answer". The one exception is the held decision in §5.4: a card this
  process saw Live before the link dropped.
- **Sidebar rows:** a `history` glyph plus a short age ("12m") for Saved. The TalkBack label is the
  full sentence.
- **Composer:** it stays usable offline. Sends go to the staged outbox and appear as pending rows with
  `cloud-off` + "Waiting for connection" (§5).
- **Offline reading needs no network at all:** hydration comes from the mirror (§2.4), and list,
  transcript and search-in-loaded-content work. Server-side search (v71) shows "Needs a connection".

### 4.3 Gone and unknown sessions

A row missing from `ready` (retention-pruned; there is no removal frame, `tether:server.mjs:5186-5189`)
is kept as `gone_from_server`. It shows "No longer on the server" with an `archive` icon, is readable,
and is read-only. It is evicted first (§7).

---

## 5. Outbox (T13.3)

### 5.1 Hard invariants (tests assert each one)

- **I1 Never auto-retry a turn.** A `send`/`queue-add` key goes on the wire a second time only if a
  snapshot on this connection proves it absent. This is T1.3's `sendableRecords` gate, unchanged
  (`tether:lib/pending-input.mjs:280-305`).
- **I2 An agent's message is never consent.** Nothing received (event, push, notification) can create
  an outbound approval, answer or send. Only a UI tap creates outbox entries.
- **I3 Approvals are operator-only and fresh.** A decision is transmitted at most once. If it was made
  while disconnected, it is transmitted only after a snapshot on the new connection shows the
  **identical** request still pending. Otherwise it is dropped with a notice and **never replayed**.
- **I4 The background never transmits operator input** (§6.4).
- **I5 Nothing unsent is ever silently lost.** Each drop, expiry or eviction surfaces a notice. Expired
  or evicted text is restored to the session's draft (`DraftStore`) so the operator can re-send it
  deliberately under a new key.

### 5.2 Message classification

| Message | Offline | Mechanism | Why |
|---|---|---|---|
| `send` (text, +mention) | **Queue** | Staged outbox → PendingStore | Durable, dedupe-safe via key + snapshot reconcile. |
| `queue-add` | **Queue** | Staged outbox → PendingStore | Same as `send` (the queueId is the key). |
| `send` with attachments | **Queue, in memory only** | as above, not persisted | Parity: T1.3 and the web never persist attachments (`tether:lib/pending-input.mjs:449-454`). The pending row says "Attachment is lost if the app is closed". |
| `queue-edit`, `queue-remove` | **Queue (coalesced)** | Staged op, sent after the session snapshot, verified | Idempotent server-side no-op when stale (`tether:server.mjs:8853-8856`). `queue-remove` also discards the local record first, as today (`RealTetherClient.kt:1349-1358`). An edit is dropped if the target is no longer in `queuedMessages`. |
| `mark-seen`, `dismiss-notice` | **Queue (coalesced, last wins)** | Staged op | Idempotent, presentation-only (`tether:server.mjs:8865-8871`). |
| `approval`, `question` | **Hold** | In-memory held decision, verify-then-send-once | I3. |
| `interrupt`, `kill`, `archive`, `rate-limit-resume`, `run-command`, `background-command` | **Refused** | UI disabled, "Needs a connection" | Time-critical. A late `interrupt` could kill a *later* turn. Commands start turns and the web does not make them durable. |
| `create`, `resume`, `set-*`, `rename`, `pin`, `set-session-order`, settings, node ops, files | **Refused** | same | Last-writer-wins against other devices, and the web offers no durability. Node credentials must never be stored (T1.5). |

### 5.3 Mapping onto T1.3's PendingStore (no duplication)

- **PendingStore stays exactly as T1.3 built it**: the corpus-verified port, its rules, its persisted
  `{v:2,records,cleared}` shape, `restoredFromPreviousProcess`.
- The **staged outbox** is a small FIFO in front of it: `[{key, kind, sessionId, text, mention?,
  composedAt}]` plus coalesced ops `[{type, sessionId, target, value, composedAt}]`. It persists as a
  sibling DataStore key, `tether:outbox.v1`, in **the same `edit {}`** as `tether:pendingInput`
  (extend `SettingsStore` with one method, `writeInput(pending, outbox)`).
- **Filing:** when `connection != Connected`, or when the session already has staged records (to keep
  per-session FIFO), `send`/`queueAdd` mint the key at compose time and append to the staged outbox.
  Otherwise the existing `recordAndDrain` path is used (`RealTetherClient.kt:1323-1336`).
- **Promotion:** on the handshake of a new epoch (`onReady`, `RealTetherClient.kt:1125-1168`), staged
  records are promoted **in compose order** into PendingStore under the same key, with
  `addRecord(..., now)`. Removing a record from staging and adding it to PendingStore happen in **one
  atomic edit**, so process death cannot duplicate or lose it. After that, T1.3 governs. A promoted
  record has `tries == 0`, so it may go out immediately (`tether:lib/pending-input.mjs:291-294`). This
  is a *first* transmission, not a retry.
- **Age rule:** a staged record older than `MAX_AGE_MS` (10 min, the same constant) at promotion time is
  **not** auto-promoted. It becomes **Held: "Written 42 min ago while offline. Send now / Discard"**.
  An explicit tap promotes it under its original key, which is safe because the key never left the
  device. *Why:* the web's 10-minute bound expresses "a prompt older than this may no longer be what
  the operator wants". Delivering an hour-old prompt without asking would break that intent.
- **Coalesced ops** are sent after that session's `state` snapshot on the new connection, each verified
  against it (edit/remove target still queued, notice key still present). Otherwise they are dropped
  silently, because they are presentation-only.

### 5.4 Held decisions (approvals, answers)

- **When connected**, a tap sends immediately. The server is first-wins, so this is unchanged.
- **When not connected**, a tap is accepted only for a request this process saw **Live** before the
  link dropped. A card that has only ever come from a saved copy stays disabled (§4.2). The tap records `{sessionId, turnId, requestId, choiceId|answers, fingerprint,
  decidedAt}` **in memory only**. `fingerprint` = SHA-256 of the canonical JSON of the
  `pendingApprovals[requestId]` object (or `pendingQuestions`) plus `activeTurnId`. The card shows
  "Your decision will be sent when reconnected · Cancel".
- **On that session's `state` snapshot on the new connection:** send once if
  `pendingApprovals[requestId]` exists, its fingerprint matches, and the chosen `choiceId` is still in
  `choices`. Otherwise drop the decision and show the notice "Your decision on ‹tool name› was not
  sent: the request changed or was resolved while you were offline". A pending item carries no
  seq/ts (`tether:engines/events.mjs:2499-2506`), so the fingerprint is how "a decision older than
  its request" is detected: a re-raised request has a different turn or input and does not match.
- **A stateless snapshot** (at head) proves nothing changed, so the fingerprint is compared against the
  hydrated local projection, which is exact at head.
- **Foreground only:** a catch-up (background) epoch **never** checks, sends or discards held
  decisions (§6.2 step 1). They wait for the first foreground connection. A held decision that exists
  at all implies the process is alive with its UI state, and the R7 handover runs the check there.
- **No persistence:** process death drops held decisions. The card is still in the mirror, so the
  operator re-taps. *Why:* consent should not outlive the process that witnessed it. A persisted
  approval replayed after a restart is precisely the "blind replay" PLAN forbids.
- **TTL:** a held decision is also dropped after 10 min (the same bound, for uniformity), with the
  notice.

### 5.5 The two T1.3 gaps (from the T13.3 bead)

1. **`mention` parameter.** Add `mention: DelegateMention?` to `PendingInput.addRecord` and to
   `PendingRecord`, map it through the web port (which already persists it,
   `tether:lib/pending-input.mjs:167-171`), emit it in `ClientMessage.Send` from `drainPending`
   (`RealTetherClient.kt:1374-1379`), and carry it in the staged outbox. Test: a round trip through
   persist/restore keeps the mention, and a mention-less record stays mention-less.
2. **Queue item removed on another device.** It is closed in two steps:
   - *Client-only, this task:* a live `queued_message_removed{queueId}` event, which the mirror sees for
     every cursored session (§3.1 rule 4), tombstones that key (`forgetLocked`) before any
     redelivery. This covers removals made while this device was connected.
   - *Server, S13.1 part C:* the projection keeps a bounded `removedQueueIds` (the last 50),
     exactly the fix `tether:lib/pending-input.mjs:40-45` names. `reconcileWithSnapshot` treats those
     keys as cleared. This covers removals made while this device was offline. It is feature-detected:
     if the field is absent (an older server), behaviour stays as today and a test documents the
     residual.

---

## 6. Background catch-up (S13.1 + T13.4)

### 6.1 S13.1: the server change (one PR, one PROTOCOL bump)

**A. `AgentSession.lastSeq?: number`**: the journal head (`journal.lastSeq()`) as of serialization, on
every AgentSession the server emits (`ready`, `created`, `session-update`). With it, a catch-up worker
decides from the `ready` list alone which sessions advanced past its cursors. This **replaces** the
PLAN's "sessions-changed-since cursor". The list is already bounded by retention (100 retired, plus
live and pinned; `tether:session-retention.mjs:13`), so a separate delta query buys little. It is
revisited only if a measured `ready` exceeds the data budget (§12 OQ3). Clients must treat it as a
hint: the snapshot's `throughSeq` stays authoritative.

**B. The sync hint on the FCM relay**: a **data-only** message:

```json
{ "message": { "token": "…", "data": { "kind": "sync", "v": "1" },
  "android": { "priority": "normal", "collapse_key": "tether-sync", "ttl": "3600s" } } }
```

- **No session id, no seq, no title/body, no tag.** This keeps the existing privacy floor
  (`tether:lib/push-notifications.mjs:358-366`): Google can read FCM data. The PLAN's "session X
  advanced to seq N" is **rejected** because it would leak which session is active and how much.
  Part A gives the client the same information over the authenticated socket.
- **Opt-in per device:** `fcm-register`/PATCH body gains `syncHints: boolean`, default false. Old
  servers ignore unknown fields (`tether:server.mjs:6993-7001`), and old apps never opt in.
- **Triggers:** `turn_end`, `cancelled`, `turn_interrupted`, a human ask after `HUMAN_ASK_WAIT_MS`
  (the same arming as the visible push, `tether:lib/push-notifications.mjs:335,402-418`), and a session
  being created or archived. The same scope filter applies (`FcmDispatcher.passes`,
  `tether:lib/fcm-push.mjs:437-445`): the session id is used **server-side** for filtering and never
  leaves in the payload.
- **Coalescing:** at most one hint per device per 60 s, trailing-edge (the last change always produces
  a hint). The `collapse_key` keeps a single pending hint while the device is offline, and a TTL of 1 h
  drops hints that no longer matter.
- **Normal priority, on purpose:** FCM deprioritizes high-priority messages that do not produce a
  visible notification. Catch-up is opportunistic. Visible notifications keep their current path and
  priority.

**C. `removedQueueIds`** in `SessionProjection` (reducer `queued_message_removed`, bounded 50). This is
a change to `engines/events.mjs`, so the parity corpus is re-exported and T2.x ports it. It is
**separable**: if review rejects it, parts A and B ship alone and §5.5 keeps the client-only mitigation.
**Android owner: new task T13.3b** (§12), gated on S13.1-C being **deployed**. It covers the Kotlin
reducer port of `queued_message_removed`, the parity-corpus re-sync (`syncParityCorpus`,
`ReducerConformanceTest` 100 %), and `reconcileWithSnapshot` treating `removedQueueIds` as cleared. The
last one must come through the web port, `lib/pending-input.mjs`, so it is part of S13.1-C too, followed
by a helper-corpus re-sync. T13.3 does not depend on T13.3b.

**Protocol classification (for the changelog comment):**

| Item | Wire change? | Native-breaking? |
|---|---|---|
| A `lastSeq` (server→client field) | yes: `lib/protocol.ts` + validator | **No.** Additive and tolerant-decodable (D4). |
| B FCM `sync` + `syncHints` (HTTP/FCM, not WS) | documented in the same entry | **No.** Old apps ignore title-less messages (`TetherFcmService.kt:39-42`) and never opt in. |
| C `removedQueueIds` (projection field) | yes | **No.** Additive, and the Android typed adapter ignores unknown keys. |

→ **One `PROTOCOL_VERSION` bump. `NATIVE_PROTOCOL_FLOOR` unchanged.** Web impact: a web client still
does a strict-equality reload, as for any bump. The twins in `lib/protocol-validate.mjs` move in the
same commit.

### 6.2 T13.4: the client

- **Receive:** `TetherFcmService.onMessageReceived` routes `data.kind == "sync"` **before** the title
  check (`TetherFcmService.kt:39`). It enqueues unique work `"tether-catch-up"` (`ExistingWorkPolicy.KEEP`)
  and **posts no notification**.
- **Work request:** `OneTimeWorkRequest`, `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`,
  constraint `NetworkType.CONNECTED`, plus `UNMETERED` when "Wi-Fi only" is set. It is skipped when the
  app is in the foreground (the live socket already does the work), and skipped when a catch-up
  finished less than 2 minutes ago.
- **Execution** (`CatchUpWorker`, a thin shell over `TetherClient.catchUp(budget)`):
  1. Open a **catch-up epoch** on the singleton client, even when it is `backgroundSuspended`
     (`RealTetherClient.kt:671-686`). In this epoch **`drainPending` and every outbound frame except
     `hello`/`attach`/`ping`/`fetch-turns` are suppressed** (I4). This is enforced in `sendFrame` by an
     epoch flag, not by caller discipline. In this epoch the client also **does not promote the staged
     outbox into PendingStore, and does not check, send or discard held decisions** (I3/I5). Snapshots
     still reconcile PendingStore (removing acknowledged records is server evidence and loses nothing),
     but staging and held state are untouched. Promotion and held checks run at the first foreground
     handshake, or at the R7 handover when the app comes to the foreground during a run.
     **Handover rule:** the socket is kept, so `onReady` does not run again. At the handover the client
     therefore attaches, via `afterSeqFor` (§3.1 rule 1a), every session with pending, staged or held
     input that was **not attached on this connection**. On each reply it runs promotion, then drain,
     then the held-decision check, exactly as `onReady` plus `onSnapshot` would. Until that session's
     attach reply arrives (carrying `state` wherever rule 1a requires it), no frame goes out for its items.
  2. From `ready`: sessions where `lastSeq > cursor` (or all targets if `lastSeq` is absent, which means
     an older server), intersected with the **targets**: pinned, sessions with a mirror opened in the
     last 7 days, and sessions in the push scope. The cap is **8 per run**, lowered from 20. Each attach
     costs a native-transcript tail read on the server (§3.1 rule 5), and 8 behind sessions already fill
     the 5 MB metered budget. Sessions left over wait for the next run or for the foreground.
  3. Attach each with its cursor, persist the replies, and close with 1000 once all have been answered
     or a **45 s** deadline passes (well inside WorkManager limits and expedited quotas).
  4. **Unmetered only:** prefetch `fetch-turns` for trimmed turns of **pinned** sessions, within the
     byte budget.
- **Budgets:** each run is capped at **5 MB metered / 25 MB unmetered**, counting received frame bytes.
  At about 1 MB per behind session (`tether:server.mjs:8679-8682`), 5 MB covers the common case.
  Exceeding the budget stops attaching new sessions. The rest wait for foreground.
- **Doze, standby and Android 14+:** expedited jobs are quota-limited per standby bucket, and
  normal-priority FCM may wait for a Doze maintenance window. The design treats both as acceptable:
  catch-up is **best effort**, and opening the app always catches up in the foreground. No foreground
  service is used, so none of the Android 14 FGS-type rules apply. WorkManager needs no
  `getForegroundInfo` for expedited work at minSdk 34 (it matters only below API 31).
- **Periodic fallback** (so offline reading works without push): `PeriodicWorkRequest` every 12 h,
  constrained to `UNMETERED` + `BATTERY_NOT_LOW`, pinned sessions only. Same worker.
- **Settings** (in T10.1 settings, "Sync" group): "Keep sessions up to date in the background" (default
  **on**; controls hints, periodic work and `syncHints` registration), "Only on Wi-Fi" (default off),
  cache size (§7), and "Clear cache".
- **Auth:** FCM registration needs a paired-device credential (`tether:server.mjs:6984-6989`). A
  password (cookie) login gets the periodic fallback only. This must be stated in the settings copy.

### 6.3 Invariant: no content in push, ever

The client never reads anything from a push except `kind`. A future server that put content in `data`
would be ignored. A test feeds a `sync` message carrying extra fields and asserts that nothing beyond
enqueueing work happens.

---

## 7. Cache policy (T13.5)

| Parameter | Default | Notes |
|---|---|---|
| Mirror budget | **200 MB** (setting: 100 / 200 / 500 MB) | Measured as `page_count × page_size`. Per-session `sync_state.bytes` is maintained on write. |
| Tail before a local checkpoint | 2,000 events (500 at `turn_end`) | §2.4 |
| Sessions re-attached on `ready` | open + pending + pinned + 10 most recent | §3.1 |
| Background targets | ≤ 8 per run, opened in the last 7 days, pinned, or in push scope | §6.2 |

**Eviction** runs after each catch-up, on cold start, and in a daily idle `PeriodicWorkRequest`
(`DEVICE_IDLE`, `BATTERY_NOT_LOW`). It stops once usage is ≤ 90 % of the budget:

1. `turn_detail` rows of unpinned sessions, least-recently-opened first. They can be re-fetched and show
   as "Older turns not downloaded".
2. Whole `gone_from_server` sessions.
3. Whole unpinned sessions, LRU by `last_opened_at`. Each drops to `level=list` (the row stays; base,
   tail, details and cursor go).
4. **Never evicted:** pinned sessions (server `pinned`, `tether:lib/protocol.ts:1745`, and sessions
   under v128 owner-pinned workspaces), the open session, and sessions with staged, pending or held
   input. If these alone exceed the budget, show "Pinned sessions exceed the cache limit" and do not
   evict.

**Clear cache** closes and deletes the mirror DB file (plus `-wal`/`-shm`), resets in-memory stores and
cursors, rotates the data key (§8.1), and re-attaches the open session in full. It **never** touches DataStore
unsent input. A test
asserts this.

---

## 8. Security, encryption, backup (T13.1, T13.5, reviewed in T14.3)

### 8.1 Is the mirror encrypted at rest? Yes (SD9)

- Transcripts include tool output that routinely contains secrets (env files, keys in diffs). The
  server itself treats the journal as secret-bearing: 0700/0600 permissions
  (`tether:engines/journal.mjs:13-21`). The mirror is the largest sensitive artifact on the phone.
- **Mechanism: envelope encryption.** A random 256-bit **data key** is generated in software. It is
  sealed with a new Keystore key `tether.mirror.aesgcm.v1`, built with the same spec as
  `KeystoreCredentialKeySource` (not user-auth-bound, so the background worker can use it after first
  unlock, `KeystoreCredentialKeySource.kt:15-19`). The sealed key is stored in `noBackupFilesDir`. It is
  unwrapped once per process and used by `javax.crypto` AES-256-GCM per blob, with the
  `CredentialCipher` framing (`CredentialCipher.kt:77-133`).
- **AAD** = `table ‖ session_id ‖ seq-or-turn_id`, so a blob cannot be moved to another row or session
  undetected.
- **In clear:** ids, seq, event `type`, timestamps and flags, which are needed for keys, ordering and
  eviction. This leaks activity metadata only. There is no local full-text index; search is
  server-side (v71).

**Alternatives rejected**

- *SQLCipher:* a native library (+APK size, per-ABI), and **untestable under Robolectric/JVM**.
  Emulators are deferred (T0.2, owner decision 2026-09-27), so the encryption could not be verified in
  CI.
- *Per-row Keystore operations:* each one is an IPC into keystore2, far too slow at streaming rates.
- *Rely on file-based encryption plus the sandbox only:* this protects only before first unlock and is
  inconsistent with T1.4's stance.

**Nonces and key rotation.** Each blob gets a fresh random 96-bit IV (the `CredentialCipher` framing).
The NIST SP 800-38D guidance is ≤ 2³² random-IV encryptions per key. To stay far below that and to
bound exposure, the data key **rotates** on: **Clear cache**, logout/revoke (§8.3, where it is
destroyed), and whenever a per-key write counter in `meta` passes 2²⁸. The mirror is a cache, so
rotation is simply: delete the DB, mint and wrap a new key, and rebuild from the server. There is no
re-encryption pass.

### 8.2 Key loss

If the data key or Keystore key is gone or unusable (restore to a new device, Keystore reset), the
**whole mirror DB is deleted** and rebuilt from the server. The mirror is a cache, so key loss can
never lose user data. Unsent input is in DataStore, which is unaffected.

### 8.3 Wipe triggers

| Event | Mirror | Data key |
|---|---|---|
| User logout | delete DB | destroy |
| 4001/4002 revocation (`RealTetherClient.kt:58-61,1045`) | delete DB | destroy. *A revoked device must not keep transcripts.* |
| Server origin changed | delete the other origin's DB (one origin at a time; `SettingsStore` holds a single base URL) | keep |
| Clear cache | delete DB | **rotate** (§8.1) |

### 8.4 Backups and migrations

- Add `<exclude domain="database" path="." />` to **both** `cloud-backup` and `device-transfer` in
  `data_extraction_rules.xml`, and to `backup_rules.xml`. The sealed data key lives in
  `noBackupFilesDir`, which is never backed up. A unit test parses both XML files and asserts the
  exclusions, so they cannot silently regress.
- **Migrations:** Room `exportSchema = true`, with schemas checked in under `core/data/schemas/`. Every
  version step gets a `MigrationTestHelper` test. `fallbackToDestructiveMigration` is allowed **only**
  for the mirror DB, and it logs a one-line notice ("Saved sessions were refreshed after an update").
  Unsent input is never in Room, so it can never be destroyed by this.
- Logs never contain blob contents, notice texts with user content, or keys (T14.3 checklist).

---

## 9. Multi-host (T1.5)

v109 nodes are a **server-side peer registry**: `nodes`/`node-result` frames and a `NodeSummary` with
`nodeId`, `baseUrl` and `status` (`tether:lib/protocol.ts:637-639,3198-3209`). `AgentSession` has no
`nodeId` (`tether:lib/protocol.ts:1705-1760`), and the app speaks to exactly one home server. Session
ids are unique per home server.

**Decision: mirror per server origin (the DB file name, §2.2) and key rows by `(sessionId, seq)`.**
Nothing is keyed per node. If a later protocol surfaces peer sessions through the home server with a
`nodeId`, a migration adds a nullable column, and the key becomes `(nodeId, sessionId, seq)` only if
ids can then collide. *Rejected:* a per-node key now. It would be speculative, and v109 gives the
client no node attribution to key on.

---

## 10. Conflict rules (T13.6)

| # | Rule |
|---|---|
| C1 | **The server is authoritative.** A `state` snapshot replaces the local base wholesale, even if the local tree has "more". |
| C2 | **The projection is a pure re-fold.** `tree == fold(splice(base, turn_details), tail)`. There are no local edits to projections, and optimistic UI lives only in outbox rows, never in the tree. |
| C3 | **Dedupe by seq.** `seq ≤ cursor` is dropped, a gap is never folded, and one resync goes out per gap (`CursorTracker.kt:57-68`). |
| C4 | **Reset lowers the cursor.** `reset` + state replaces everything, and the tail is deleted. |
| C5 | **The reducer is the arbiter.** A fold exception, a decrypt failure or a decode failure drops the base and triggers a full attach (`RealTetherClient.kt:1257-1267`). A `local` base is invalidated by a reducer version change (§2.4). |
| C6 | **Acknowledgement is only by server evidence**: a live ack by key, or snapshot reconcile (`RealTetherClient.kt:1205-1255`). Local state never marks input accepted. |
| C7 | **Consent never outlives its request** (I3, fingerprint check). |
| C8 | **Presentation ops are last-writer-wins** (mark-seen, dismiss-notice). Stale ones are dropped silently. |
| C9 | **Sessions list:** `ready` is the full truth. A missing row becomes `gone_from_server`, never a silent delete. `session-update` upserts. |

**Until T13.3b lands**, the probe ignores `removedQueueIds` on both sides: the field is stripped
before comparison when the local reducer does not know it. It is additive projection state, and an
older port's fold legitimately lacks it. Once T13.3b is in, the strip is removed and the probe demands
exact equality. A test covers both modes.

A **debug-only divergence probe** (DEBUG builds, a Settings → Diagnostics action) sends a full attach
(no afterSeq) for the open session and compares the canonical JSON of the server state against the
local fold at the same `throughSeq`. It is a development aid, not shipped behaviour.

---

## 11. Test strategy and acceptance gates

All tests run on the JVM (Robolectric + Room in-memory or temp-file DB), since emulators are deferred.
Every task's gate includes the PLAN §4 baseline: `./gradlew assembleDebug lint testDebugUnitTest` green.

| Task | Tests | Acceptance gate |
|---|---|---|
| **T13.1** | `MirrorDaoTest` (in-memory Room: upsert, conflict-ignore, same-transaction cursor). **`JournalMirrorConformanceTest`**: every reducer corpus fixture is driven through a fake socket, `RealTetherClient`, `MirrorWriter` and a temp-file DB, with random split points where the client and DB are **re-instantiated** (process death). At every step, assert the canonical projection == corpus `expectedProjectionAfterEachStep` **and** == `fold(DB.base, DB.tail)`, and that local checkpoint promotion at random points yields the same tree. `CursorPersistenceTest`: a crash between event insert and cursor write never persists cursor > coverage, and a restart attaches from the persisted cursor. `AttachReplyTest` covers stateless, state, reset (cursor down), bounded + turns-detail splice, unsolicited snapshot, and fold-throw → full attach. `MirrorCipherTest`: a plaintext marker is absent from DB bytes, AAD swap is rejected, data-key loss wipes the DB. `BackupRulesTest` (XML). Existing T1.2/T1.3/T2.3 suites unchanged. `RestoredRecordAtHeadTest` (BLOCKER-1): a record restored with `tries=1`, a mirror at head, a cold start, and a server that would answer stateless. Assert the attach for that session carries **no afterSeq**, the `state` reply reconciles, and the record is re-sent **exactly once**, well within 10 min (the fake clock advances < 30 s). A companion case with only `tries=0` records asserts the cursor **is** sent. | All green twice. The conformance test covers **100 %** of corpus fixtures. Evidence includes a cold-start attach frame showing `afterSeq` = the persisted cursor and a stateless reply. |
| **T13.2** | `FreshnessTest` (the state machine over connection, attach and verify). `OfflineReadTest`: a DB seeded, then the network refused, then the client started; the projection and list flows emit the saved copy, and freshness is `Saved` with the right age. Roborazzi: banner, sidebar glyph, chat header and qualified badges in **all 6 skins**, phone + expanded, at 1.3× font. TalkBack labels asserted. | Screens committed under `docs/parity/screens/sync/`. **Native-only surface: there is no web reference**, and the evidence says so. The no-colour-only rule is checked in review. |
| **T13.3** | `OutboxPolicyTest` (the pure classification and promotion rules). `OutboxAtomicityTest` (a crash between staging removal and PendingStore add is impossible: a single edit, fault-injected store). **`ExactlyOnceProperty`**: random interleavings of disconnect, process death (including **restore with tries>0 while the mirror is at head**), snapshot, live ack and server restart (empty dedupe slot). Assert no key is transmitted twice without an intervening reconcile showing it absent, and every record is either acked or surfaced. `HeldDecisionTest` (fingerprint match → one frame; mismatch, resolved, TTL or process death → zero frames plus a notice). `MentionPersistenceTest`. `QueueRemovedElsewhereTest` (live event → tombstone; without S13.1-C → the documented residual). The "with S13.1-C → no resend" case is **conditional and belongs to T13.3b**. | All green. A frame log proves invariants I1-I5. The T1.3 corpus (`HelperConformanceTest` pending-input tables) is still 100 %. |
| **S13.1** | tether `node --test`: `lastSeq` present and correct on ready/created/session-update. The sync payload has **no** session id, seq, title, body or tag (extend the pins in `tests/push-notifications.test.mjs`). Coalescing window, scope filter, `syncHints` opt-in/opt-out, validator twin bump, native-window test. Part C: reducer tests plus a re-exported corpus. | A PR on `android-parity/S13.1` from a `~/git/tether-wt/` worktree, with the changelog classifying the bump as **not native-breaking**. The owner merges and deploys. No Android build that depends on it ships before deploy (the same rule as S1.1). |
| **T13.4** | `FcmRoutingTest` (`sync` → unique work enqueued, no notification; extra fields ignored). `CatchUpWorkerTest` (`TestListenableWorkerBuilder` + Robolectric): only `hello`/`attach`/`ping`/`fetch-turns` frames appear in a catch-up epoch, **even with pending and staged records present**. **`CatchUpHeldAndStagedTest`**: a held decision and a staged offline message, with a snapshot whose fingerprint and session match both. Assert **zero** outbound frames beyond hello/attach/ping, and that the held decision and the staged record are byte-identical after the run. A follow-up foreground handshake then runs promotion and the held check once. **Handover case:** a `tries=1` record and a held decision sit on a session that is **not** a catch-up target, and the app comes to the foreground mid-run. Assert exactly one attach for that session with **no afterSeq**, **no** frame for either item before its `state` snapshot, and each item checked or sent **at most once** after it. Targets chosen by `lastSeq`, falling back to all targets without it. Byte budget and 45 s deadline stop the run. Socket closed at the end. Foreground skip, 2-minute debounce, periodic constraints. | All green. Evidence: the frame log of a background epoch. An on-device check (a real FCM hint on the owner's phone) is **deferred to T14** while emulators are off, and is logged. |
| **T13.5** | `EvictionPolicyTest` (pure order, never-evict set, the "pinned exceed budget" notice). `CacheSizeTest`. `ClearCacheTest` (DataStore unsent input byte-identical afterwards). `MirrorMigrationTest` (`MigrationTestHelper` for every version; destructive fallback for the mirror only). | All green. Schema JSONs are committed. |
| **T13.3b** | `ReducerConformanceTest` + `HelperConformanceTest` 100 % on the re-synced corpus. `QueueRemovedElsewhereTest` "with S13.1-C": a queue-add is accepted, its ack is lost, and it is removed while this device is offline; the reconnect snapshot carries `removedQueueIds`, and **no** resend happens. | All green. Runs only after S13.1-C is deployed. |
| **T13.6** | `ConflictRulesTest`: one test per rule C1-C9, plus the §2.1 invariant as a property test over random corpus prefixes. Debug-probe equality test in both modes (§10). | All green. §10 of this doc is the rule text, and any change to it goes with tests. |

---

## 12. Per-task breakdown and order

| Task | Files and modules | Depends on |
|---|---|---|
| **T13.1** | `gradle/libs.versions.toml` (Room, KSP, WorkManager later). `core/data`: `mirror/{MirrorDatabase, entities, daos, MirrorWriter, MirrorCipher, MirrorKeySource}`, `schemas/`. `core/net`: `client/sync/SessionStore.kt`, `CursorTracker.seed`, `RealTetherClient` (write-through, hydration, capped re-attach, wipe on logout/revoke). `app`: backup XMLs, `ClientLocator` wiring. | T13.0 verified. T1.2, T1.3, T2.3, T1.4 (all verified). |
| **T13.2** | `core/net`: `syncStates` derivation. `feature/shell` banner, `feature/sidebar` row glyph, `feature/chat` header/badges/"not downloaded" rows, `core/designsystem` indicator primitive (uses T3.3/T3.5). | T13.1. T3.3 + T3.5 for visuals. |
| **T13.3** | `core/net`: `client/sync/Outbox.kt` (pure policy), `PendingInput` (mention), `RealTetherClient` (filing, promotion, held decisions, frame gating). `core/data`: `SettingsStore.writeInput`. `feature/chat`: pending/held rows, disabled-offline controls. | T13.1 (mirror tombstones). S13.1-C is optional. T6.3/T7.1 own the card and composer visuals (coordinate). |
| **S13.1** | tether: `lib/protocol.ts` + `lib/protocol-validate.mjs` (bump), `server.mjs` (AgentSession serialization, fcm-register `syncHints`), `lib/fcm-push.mjs` + `lib/push-notifications.mjs` (sync kind, coalescing), `engines/events.mjs` (part C), tests, corpus exporter. | T13.0 verified. Can run **in parallel** with T13.1. |
| **T13.3b** | `core/reducer` fold for `queued_message_removed` (`removedQueueIds`), vendored `parity-corpus/` re-sync, the `PendingInput` web-port refresh (`reconcileWithSnapshot`). | S13.1-C **deployed**, T13.3. |
| **T13.4** | `app/push/TetherFcmService.kt` (routing), `app/sync/CatchUpWorker.kt`, `PushRegistrar` (`syncHints`), `core/net` `catchUp()` epoch + frame gate, settings toggles (T10.1 hosts the UI). | T13.1, S13.1 **deployed**, T12.1 (FCM `register()` migration) preferred first. |
| **T13.5** | `core/data/mirror/Eviction.kt`, the idle maintenance worker, clear-cache action, migration tests. | T13.1, T13.2 ("not downloaded" states). |
| **T13.6** | `core/net` test suite `ConflictRulesTest` and the debug divergence probe. | T13.1, T13.3. |

**Order:** T13.0 → (T13.1 ∥ S13.1) → (T13.2 ∥ T13.3) → T13.5 → T13.6 → T13.4 and T13.3b (after S13.1 is
deployed). T13.1 is the only large task. Split it into the three steps of §2.6, each a separate
commit with its own green gate.

---

## 13. Risks and open questions

### 13.1 Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | KSP (Room codegen) may be incompatible with AGP 9 built-in Kotlin 2.4.x. | Check at the start of T13.1. If it breaks, **stop and report**. The fallback is `androidx.sqlite` + hand-written DAOs (6 tables), with the same schema and tests. |
| R2 | The write-behind batch loses ≤ 100 ms on a crash. | By design. The cursor is persisted with its rows, so the next attach refetches (§2.3). |
| R3 | A reducer-port bug corrupts a `local` checkpoint silently. | Local checkpoints are only created by the corpus-proven fold. They are invalidated on reducer version change. The fold-throw path triggers a full attach. The debug divergence probe exists. |
| R4 | Normal-priority hints are delayed hours in Doze or the rare bucket. | Accepted: best effort. Foreground catch-up on open. The periodic fallback. |
| R5 | A 200 MB default is heavy on small phones. | A setting, eviction, pinned-only protection, and an "exceeds limit" notice. |
| R6 | Control-API subscribers that fold `readSince` from a mid-turn cursor can diverge after compaction (§3.2). This is a server concern, not ours. | **Flag upstream** as a tether issue, the same way T2.1's prototype-key issue was flagged. |
| R7 | The catch-up epoch shares the singleton client with a foreground that starts mid-run. | A foreground transition ends the catch-up epoch's suppression and keeps the socket. Because `onReady` does not run again, the handover attaches (via `afterSeqFor`) every session with pending, staged or held input that was not attached on this connection, then runs promotion, drain and the held-decision check on those replies (§6.2 step 1). Tested in T13.4 (`CatchUpHeldAndStagedTest`, handover case). |
| R8 | Holding decisions in memory loses them on process death, which is annoying. | Intentional (§5.4). The card reappears from the mirror. |
| R9 | `turn_detail` rows for old, done turns are treated as immutable until a new base supersedes them. | Compaction is projection-preserving (`tether:engines/journal.mjs:222-236`). Any new server base drops the rows it covers. The debug probe (§10) would expose drift. |
| R10 | The web has the same stateless-reconnect gap within one tab (§3.1 rule 1a). | Flag upstream to tether. Android is unaffected by rule 1a. |

### 13.2 Open questions

| # | Question | Status |
|---|---|---|
| OQ1 | **(owner-only, at PR review)** S13.1 part C changes the web's `SessionProjection` shape (`removedQueueIds`). Accept it in the same PR, or split it out? | Recommended: accept (it closes a documented residual). Separable either way. |
| OQ2 | **(owner-only, optional)** May background work ever *send* staged input (like "send when back online" with the app closed)? | Decided **no** for now (I4). Only the owner can widen this, because it means sending the operator's words with no app open. |
| OQ3 | *(engineering deferral)* Is a real `sessions-changed-since` query needed? | Deferred. Decide from a measured `ready` size on the owner's server during T13.4. |
| OQ4 | *(engineering deferral)* A compaction-aware event delta (serve events only when no compacted turn straddles `afterSeq`). | Future `S13.x` candidate. Needs server tests on compaction boundaries. Not needed for correctness. |
| OQ5 | *(engineering deferral)* WS permessage-deflate (OkHttp client + `ws` server) to cut catch-up bytes about 5×. | Candidate for T14.1. It is a server config change and not a PROTOCOL change. |
| OQ6 | *(engineering deferral)* Persist offline attachments (sealed files under `noBackupFilesDir`)? | Deferred. Parity keeps them in memory only. Revisit after T7.4. |
| OQ7 | *(engineering deferral)* Compose visible notification text on-device from the mirror after a catch-up (a richer notification with no content in the push)? | Idea for T12.x. It would need data-only messages for asks, which is a server change. Not in this phase. |

### 13.3 Decisions for the TRACKER decision log (main session to transfer)

1. Outbox stays in DataStore next to PendingStore, not in Room (deviation from D8, §2.2).
2. No WS event deltas. Catch-up = bounded snapshot. PLAN T13.1 "true deltas" wording to be amended (§3.2).
3. The FCM sync hint is session-free and seq-free. `AgentSession.lastSeq` replaces "sessions-changed-since" (§6.1).
4. S13.1 = one PROTOCOL bump, not native-breaking, floor unchanged (§6.1).
5. Mirror encryption uses row-level AES-GCM with a Keystore-wrapped data key, not SQLCipher (§8.1). Wipe on logout and revoke (§8.3).
6. Held approvals and answers are in memory only and fingerprint-verified. The background never sends (§5.4, I4).
7. Offline prompts older than 10 minutes need an explicit "Send now" (§5.3).
8. The `ready` re-attach is **capped** to open + pending + pinned + the 10 most recently opened sessions.
   The web re-attaches every subscribed session (`use-tether.ts:795-799`), so this differs from the web
   (§3.1 rule 5).
9. Approval and question cards rendered from a **saved copy are disabled** ("Connect to answer"),
   except for held decisions on cards seen Live in this process. The web has no saved copies, so this is
   new behaviour (§4.2, §5.4).
10. A session with unsent input (`tries > 0`) attaches without afterSeq (§3.1 rule 1a, option (a) over
    (b)).
11. New task **T13.3b** (the Android port of S13.1-C), gated on S13.1-C being deployed (§6.1).
12. The background cap is 8 attaches per run (§6.2). The data key rotates on Clear cache (§8.1).
