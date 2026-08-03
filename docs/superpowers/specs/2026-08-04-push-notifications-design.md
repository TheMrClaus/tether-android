# Push notifications — FCM relay

**Date:** 2026-08-04
**Status:** Approved (brainstorming complete, awaiting implementation plan)
**Scope:** `tether-android` (app) + `aidash` (server, sibling repo at `../aidash`)

## Goal

Send a push notification to the Android app when an agent session needs the
owner or finishes a turn — even when the app is killed. Reuse the trigger
logic and the per-event payload shape the server already has for browser Web
Push; add an FCM dispatch path alongside it.

## Non-goals

- No foreground-service fallback. FCM (and Google Play Services) is a hard
  dependency; de-Googled devices are out of scope (user decision).
- No background task / rate-limit / MCP-health pushes. The three existing
  triggers (`approval_request`, `question_request`, `turn_end`) are the
  entire payload set for v1.
- No push of session content. Titles and bodies stay generic, exactly like
  the existing Web Push payloads (`"A Claude session is waiting for
  approval."`, never the user's prompt).

## Context

The server already has a Web Push subsystem (`aidash/lib/push-notifications.mjs`,
`aidash/server.mjs` lines 79-88, 671-680, 2161-2196; `aidash/public/sw.js`;
`aidash/hooks/use-push-notifications.ts`). It fires on three event types via
`createPushEventObserver`:

- `approval_request` — `"Tether needs you" / "A <provider> session is waiting for approval."`
- `question_request` — `"Tether needs you" / "A <provider> session has a question."`
- `turn_end` (when the active turn goes to null) — `"Tether turn complete" / "A <provider> session finished its turn."`

Each payload is `{ title, body, tag, url }` with a `tag` of the form
`tether-<kind>-<sha24>` so duplicate events collapse. This spec adds a second
dispatcher (FCM) that consumes the **same** payload, so the trigger logic and
payload construction stay in one place.

The app today keeps the WebSocket alive only in the foreground
(`UiRoot.kt` `LifecycleResumeEffect`). It has no notification infrastructure
(`AndroidManifest.xml` carries only INTERNET, ACCESS_NETWORK_STATE, VIBRATE).
Auth is a single `Credential.Cookie | Credential.DeviceToken`
(`SettingsStore.kt`); a paired device has a stable `deviceId` (from the
`/api/devices/claim` response) — that id is the natural key for an FCM
registration row.

## Architecture

Two new subsystems, one on each side of the wire, sharing the existing
trigger logic and payload shape.

### Server (`aidash/`)

A new FCM sender runs **alongside** the existing Web Push sender. Both are
driven by the same event observer; the dispatch step fans out to both.

- **`aidash/lib/fcm-push.mjs`** (new, ~140 lines, mirrors
  `push-notifications.mjs`):
  - `createFcmSender({ env, implementation })` — reads
    `AIDASH_FCM_PROJECT_ID`, `AIDASH_FCM_CLIENT_EMAIL`,
    `AIDASH_FCM_PRIVATE_KEY` (the private key may be a path to a PEM file or
    the raw PEM; both forms are accepted). Mints a service-account JWT
    (RS256, 1 h TTL, cached with refresh). Sends via
    `POST https://fcm.googleapis.com/v1/projects/{projectId}/messages:send`
    with `Authorization: Bearer <jwt>`. Returns
    `{ configured, reason, send(registration, payload) }`. When unconfigured,
    `send` is a no-op and `configured === false` — same shape as
    `createWebPushAdapter`.
  - `class FcmRegistrationStore` — on-disk JSON at
    `<stateDir>/fcm-registrations.json`, same atomic write/`chmod 0o600`
    pattern as `DeviceTokenStore`. One row per paired device:
    `{ deviceId, fcmToken, scope, attachedSessions: string[], pinnedSessions: string[], updatedAt }`.
    `MAX_REGISTRATIONS = 16`. Methods: `list()`,
    `upsert({ deviceId, fcmToken, scope, attachedSessions?, pinnedSessions? })`,
    `remove(deviceId)`, `setSessions(deviceId, attachedSessions, pinnedSessions)`.
    Keyed by `deviceId`, not by fcm token, so FCM token rotation replaces the
    row in place rather than orphaning it.
  - Validators `normalizeFcmToken`, `normalizeScope`, `normalizeSessionIds` —
    defensive, bounded sizes, never log secrets. Match the style of
    `normalizePushSubscription`.

- **`aidash/lib/push-notifications.mjs`** (refactor, not rewrite):
  - `createPushEventObserver` takes both a `webPushDispatcher` and an
    `fcmDispatcher`. The `payload` construction (current lines 307-348) is
    unchanged; only the final dispatch fans out to both. The dispatcher
    interface each side implements is `notify(tetherSessionId, payload)` —
    the FCM dispatcher needs `tetherSessionId` for scope filtering, so the
    observer passes it through (the Web Push dispatcher ignores it).
  - `pushTag`, `providerLabel`, `hasPending` stay in `push-notifications.mjs`
    and are reused by `fcm-push.mjs` (export them, no behavior change).

- **`aidash/server.mjs`** wiring:
  - Construct `fcmRegistrationStore` and `fcmSender` next to the existing
    `pushSubscriptionStore`/`webPushAdapter` (around lines 79-88).
  - Build an `FcmDispatcher` (new class in `fcm-push.mjs`) and pass it to
    `createPushEventObserver` alongside the existing `PushDispatcher`
    (around line 676).
  - `FcmDispatcher.notify(tetherSessionId, payload)`:
    1. `if (!fcmSender.configured) return { sent:0, failed:0, pruned:0 }`.
    2. Load `fcmRegistrationStore.list()`. For each row, filter by `scope`:
       - `all` → always pass.
       - `attached` → pass iff `tetherSessionId ∈ row.attachedSessions`.
       - `pinned` → pass iff `tetherSessionId ∈ row.pinnedSessions`.
    3. For each pass, `fcmSender.send(row, payload)`. Aggregate
       `sent/failed/pruned`; on FCM 404/410, `fcmRegistrationStore.remove(row.deviceId)`.
    4. `obs.warn?.("fcm.delivery_failed", { attempted, failed, pruned })`
       on any failure (no tokens, no payloads in the log — mirror the Web
       Push path).
  - **Endpoints** (all behind the existing `/api/` auth gate at line 2109,
    so a device bearer token or browser cookie works):
    - `GET /api/push/fcm-config` → `{ configured: bool, reason: string|null }`.
      No VAPID key — FCM does not use one.
    - `POST /api/push/fcm-register` body
      `{ fcmToken: string, scope: "all"|"attached"|"pinned", attachedSessions?: string[], pinnedSessions?: string[] }`.
      The authenticated device's `deviceId` (derived from the bearer token)
      is the row key. 503 if `!fcmSender.configured`. 400 on validation
      error. 201 on create, 200 on update.
    - `PATCH /api/push/fcm-register` body
      `{ scope?, attachedSessions?, pinnedSessions? }` — partial update of
      the authed device's row. Used when the user changes scope or when the
      app refreshes the attached/pinned sets. 404 if the row does not exist
      (caller must POST first).
    - `DELETE /api/push/fcm-register` → removes the authed device's row.
      200 `{ ok, removed }`.
  - **`DELETE /api/devices/<id>`** (existing, line 2148) is extended: after
    `disconnectDeviceSockets(id)`, also `fcmRegistrationStore.remove(id)`.
    A revoked device cannot call `/api/push/fcm-register` (its bearer token
    is dead), so this server-side cleanup is the belt-and-braces path.

- **Env contract**: three new vars, added to the repo's existing
  `.env.example`/README pattern:
  - `AIDASH_FCM_PROJECT_ID`
  - `AIDASH_FCM_CLIENT_EMAIL`
  - `AIDASH_FCM_PRIVATE_KEY` (path to PEM, or the raw PEM with literal `\n`)

  When absent, `fcmSender.configured === false`; `/api/push/fcm-config`
  reports it; the app's settings row shows "Server push not configured".

### App (`tether-android`)

- **Build / manifest**:
  - `gradle/libs.versions.toml` — add `firebase-bom` and `firebase-messaging`.
  - `app/build.gradle.kts` — apply `com.google.gms.google-services`, add
    `implementation(platform(libs.firebase.bom))` and
    `implementation(libs.firebase.messaging)`.
  - `AndroidManifest.xml` — add
    `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`
    (API 33+) and register `TetherFcmService` with `android:exported="false"`.
  - **Firebase config without checking secrets into the repo** — the same
    pattern the release keystore uses: `TETHER_FIREBASE_APP_ID`,
    `TETHER_FIREBASE_API_KEY`, `TETHER_FIREBASE_PROJECT_ID` (and the
    `gms` plugin's required `TETHER_FIREBASE_PROJECT_NUMBER` /
    `TETHER_FIREBASE_CLIENT_ID` if the plugin needs them) are read from the
    environment at build time by a small Gradle task that writes
    `app/google-services.json` (gitignored). CI decodes a base64 secret,
    mirroring `TETHER_RELEASE_STORE_FILE`. The task is a no-op if the vars
    are absent, so local builds without Firebase still compile (the app
    falls back to "push not configured" at runtime).
  - Notification channel registration in `TetherApp.onCreate` (the existing
    `Application` subclass that hosts `ClientLocator`):
    - `tether-events` — `IMPORTANCE_HIGH` (approval, question).
    - `tether-complete` — `IMPORTANCE_DEFAULT` (turn end).

- **New package `com.tether.app.push`**:
  - `PushScope.kt` — `enum class PushScope { All, Attached, Pinned }` with
    `wire: String` and `fromWire(s): PushScope?`.
  - `PushRegistrar.kt` — constructed with `SettingsStore`, `OkHttpClient`,
    the base URL, and a `FirebaseTokenProvider` seam (so tests stub the
    Firebase call). Methods:
    - `suspend fun sync(scope, attached: Set<String>, pinned: Set<String>)` —
      fetch `/api/push/fcm-config`; if `configured`, get the FCM token,
      `POST /api/push/fcm-register` with
      `{ fcmToken, scope, attachedSessions: attached.toList(), pinnedSessions: pinned.toList() }`.
      If `!configured`, no-op; the caller surfaces "Server push not
      configured".
    - `suspend fun update(scope, attached, pinned)` —
      `PATCH /api/push/fcm-register`. Used when only the scope or sets
      change (no token re-fetch).
    - `suspend fun unregister()` — `DELETE /api/push/fcm-register`. Called
      on logout and when the user disables notifications.
  - `TetherFcmService.kt` — `class TetherFcmService : FirebaseMessagingService()`.
    - `onMessageReceived(remoteMessage)` reads `remoteMessage.notification.title/body`
      and `remoteMessage.data["url"]`. The server sends both the `notification`
      field (for the system UI) and `data.url` (deep link). Builds a
      `NotificationCompat` notification on the right channel (`tether-events`
      for approval/question, `tether-complete` for turn end — the server
      carries a `kind` in the `data` payload so the app picks the channel
      without parsing the title). Posts with the server-supplied `tag` so
      duplicate events collapse. `contentIntent` is a `PendingIntent` to
      `MainActivity` with an extra `EXTRA_SESSION_ID` that `UiRoot` reads to
      auto-select the session.
    - `onNewToken(token)` → hand off to `PushRegistrar.sync` with the
      current prefs-derived scope and sets.
    - **Foreground suppression**: if the app is in the foreground AND the
      referenced session is currently selected in `TetherViewModel`, do not
      post — the user is already looking at it. Implementation: a
      `ProcessLifecycleOwner`-style foreground signal plus a
      `@Volatile var activeSessionId` set by `TetherViewModel` and read by
      the service. This mirrors what the web service worker gets for free
      via `clients.matchAll`.
  - `PushController.kt` — the glue. Observes `UiPrefs.pushEnabled` +
    `pushScope` + the attached-session set + the pinned-session set;
    debounces 500 ms; calls `PushRegistrar.sync` (on enable / scope change)
    or `PushRegistrar.update` (on set-only changes). Observes
    `client.configured`; on logout calls `PushRegistrar.unregister`. Wired
    from `TetherApp.onCreate` alongside `ClientLocator`.

- **`com.tether.app.ui.prefs.UiPrefs` additions**:
  - `pushEnabled: Flow<Boolean>` (default `true`).
  - `pushScope: Flow<PushScope>` (default `All`).
  - `pushPermissionAsked: Flow<Boolean>` (so the master toggle prompts for
    `POST_NOTIFICATIONS` at most once per user action).

- **UI** (`com.tether.app.ui.SessionDrawer` settings section):
  - One new "Notifications" entry that opens a sheet with:
    - A master toggle bound to `pushEnabled`. On first enable, requests
      `POST_NOTIFICATIONS` runtime permission; if denied, the toggle
      reverts and a "Permission required" line shows.
    - A scope selector (radio): All / Sessions opened on this phone /
      Pinned. Disabled while the master toggle is off.
    - A status line: "Server push not configured" (when
      `/api/push/fcm-config` says so) or "Notifications on" / "Off".

## Data flow (approval_request; the other two are identical)

1. Engine emits `approval_request`. `HeadlessSessionManager` persists the
   event, calls `observePushEvent(tetherSessionId, event, prev, next)`.
2. The observer builds the shared payload
   `{ title: "Tether needs you", body: "A Claude session is waiting for approval.", tag: "tether-approval-<sha24>", url: "/", kind: "approval" }`
   (a new `kind` field is added for the app's channel selection).
3. Dispatch fans out in parallel:
   - `webPushDispatcher.notify(payload)` — existing browser path, unchanged.
   - `fcmDispatcher.notify(tetherSessionId, payload)` — new.
4. `FcmDispatcher` loads registrations, filters each row by `scope`, and
   for each pass calls `fcmSender.send(row, payload)` →
   `POST https://fcm.googleapis.com/v1/projects/{id}/messages:send`.
5. FCM delivers. `TetherFcmService.onMessageReceived` builds a
   `NotificationCompat` on the channel selected by `payload.kind` (approval
   and question → `tether-events`/HIGH; turn_end → `tether-complete`/DEFAULT).
   Posts with `tag` so duplicates collapse. Tap → `MainActivity` extra
   `EXTRA_SESSION_ID` → `UiRoot` auto-selects the session.

## Edge cases

- **FCM token rotation**: `FirebaseMessagingService.onNewToken` →
  `PushRegistrar.sync`. Server upserts on `deviceId`; the old row is
  replaced in place. No orphan rows.
- **Device revoked** (WS close 4001, protocol-spec §1.4): the app clears its
  credential AND calls `PushRegistrar.unregister` (DELETE). Server-side,
  `DELETE /api/devices/<id>` also calls `fcmRegistrationStore.remove(id)` —
  a revoked device's dead token cannot call `/api/push/fcm-register`, so
  this is the cleanup path. A lingering row is harmless (FCM 404 → prune on
  next send).
- **Multiple devices**: each paired device has its own row, its own scope,
  its own attached/pinned sets. A second phone is unaffected by the first's
  settings.
- **Server unconfigured**: `/api/push/fcm-config` →
  `{ configured: false, reason }`. The app's settings row shows the reason
  and disables the master toggle. The existing Web Push path is unaffected.
- **Offline / network churn**: `PushRegistrar.sync` is best-effort; failures
  surface as a toast via `client.errors` (same as other client failures).
  The next foreground or settings toggle retries. No queue.
- **Duplicate pushes** (app in foreground when the push arrives):
  `TetherFcmService.onMessageReceived` checks a foreground signal; if the
  app is foregrounded AND the referenced session is the currently-selected
  one, suppress (the user is already looking at it). Otherwise post.
- **`POST_NOTIFICATIONS` denied**: settings toggle reverts, `pushEnabled`
  stays false, no foreground-service fallback. The status line reads
  "Permission required."

## Security

- FCM registration endpoints sit behind the existing `/api/` auth gate —
  only an authenticated device (bearer token) or browser (cookie) can
  register. A device can only register/unregister its own row (the row key
  is the `deviceId` derived from the authed credential).
- The FCM service-account JSON never leaves the server; the app only holds
  the per-device FCM token (per-device and rotatable).
- Push payloads contain NO session content. Title/body are generic —
  exactly the existing Web Push behavior. The `kind` and `url` fields are
  not user-supplied.
- `obs` logs `fcm.delivery_failed` with counts only (no tokens, no
  payloads), mirroring `push.delivery_failed`.

## Testing

- **`aidash/tests/fcm-push.test.mjs`** — unit tests for:
  - `FcmRegistrationStore`: upsert (create + update), `setSessions`,
    `remove`, rotation replaces-in-place, validation rejects bad
    tokens/scope/ids, `MAX_REGISTRATIONS` cap.
  - `createFcmSender`: configured/unconfigured branches, JWT shape, send
    body matches the FCM HTTP v1 schema.
  - `FcmDispatcher.notify`: scope filtering matrix —
    `all→notify`, `attached∈→notify`, `attached∉→skip`, `pinned∈→notify`,
    `pinned∉→skip`, FCM 404/410 → prune.
- **`aidash/tests/fcm-endpoints.test.mjs`** — HTTP tests: auth required
  (401), 503 when unconfigured, 400 on invalid body, 201 on create, 200 on
  update, PATCH partial update, 404 PATCH on missing row, 200 DELETE.
  Pattern matches `aidash/tests/push-endpoints.test.mjs`.
- **App** — `PushRegistrar` is tested via a `FakePushRegistrar` seam (the
  existing `FakeTetherClient` pattern in `ui/fake/`); tests cover scope
  serialization, attached-set changes, unregister on logout.
  `TetherFcmService.onMessageReceived` is tested with Robolectric:
  notification posted on the right channel, correct tag, content intent
  extra set, foreground suppression. The Firebase token call is stubbed via
  the `FirebaseTokenProvider` seam — no Firebase calls in unit tests.
- **No integration test that hits FCM** — no fake FCM server; unit tests
  stop at the `fcmSender.send` boundary.

## Open questions for implementation

These are small enough to resolve during implementation, not now:

1. Whether the `google-services` Gradle plugin accepts a generated
   `google-services.json` written by a build task, or whether the app
   should use the Firebase Admin SDK's "no-plugin" init path
   (`FirebaseOptions.Builder`) — the latter is one less Gradle plugin and
   one less file on disk, at the cost of initializing Firebase in
   `TetherApp.onCreate` from env-supplied values. Either way the env-var
   pattern above is the source of truth.
2. Exact wire field name for the `kind` discriminator the app uses to pick
   the notification channel. `data.kind` is the obvious choice; the
   implementation will pick one and document it in `fcm-push.mjs`.