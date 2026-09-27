# Tether Android — Native Parity Program (PLAN)

> **Companion file:** [`TRACKER.md`](./TRACKER.md) is the live state of this program.
> This PLAN is the stable *what/why/how*. The TRACKER is the *where are we right now*.
> **Always read both. Always update the TRACKER (see §3 Tracker Protocol).**

Written 2026-09-26. Author: planning session in `~/git/tether`.

---

## 0. Goal, in one paragraph

Bring the native Android app (`TheMrClaus/tether-android`, currently **v0.5.1, speaking
PROTOCOL_VERSION 40**) to **full look-and-behave parity with the Tether web console as
used in Chrome** (`TheMrClaus/tether`, currently **PROTOCOL_VERSION 128**, commit
`7d65611`), as a **truly native app: Kotlin + Jetpack Compose**. Then build on it a proper
**server↔app sync layer** (local journal mirror, offline reading, durable outbox, push-triggered
background catch-up) so the app behaves *better* than a browser tab on a phone.

### Hard constraints (non-negotiable)

1. **Native only.** No WebView-rendered UI, no PWA/TWA, no Capacitor/Cordova/React Native/
   Flutter, no "web wrapper" of any kind. Every screen is Jetpack Compose. (Opening an
   *external* URL — e.g. a worktree service preview — in a Chrome Custom Tab is allowed,
   because that is Android's native "open link" behavior, not our UI.)
2. **Same look.** The web's theme system — **3 families × light/dark/system → 6 skins**
   (`tactile`/`night`, `precision`/`machine`, `studio`/`studio-dark`; see
   `hooks/use-preferences.ts`; Studio also restyles the material layer via `app/studio.css`) —
   *(T0.5 correction: this line originally said "four themes")*, the same tokens, typography (Manrope + JetBrains Mono),
   the "Quiet Instrument" rules (`DESIGN.md`, `PRODUCT.md` in the tether repo): violet only
   for focus/selected/waiting, no gradients/glass, status never by color alone, 44dp targets.
3. **Same behavior.** Same event folding (the `engines/events.mjs` reducer semantics), same
   wire protocol, same auth rules, same invariants: never auto-retry a turn, an agent's
   message is never consent, approvals are resolved only by the operator, etc.
4. **Server invariants from `~/git/tether/CLAUDE.md` bind this program too** — especially:
   bump `PROTOCOL_VERSION` on any wire change and keep `lib/protocol.ts` ↔
   `lib/protocol-validate.mjs` in sync by hand; never run tests/dogfood against the
   production service; never `pkill -f server.mjs`; context hygiene (no whole-file reads of
   huge files, grep to the region).

### Non-goals (for this program)

- iOS / KMP multiplatform. (Keep `:core:*` modules free of Android APIs where cheap, so
  KMP stays possible later, but do not pay for it now.)
- De-Googled devices: FCM stays a hard dependency for push (prior owner decision).
- Play Store distribution. Distribution stays APK via GitHub Releases / Obtainium
  (existing `.github/workflows/android-release.yml`).

---

## 1. Where things stand (baseline facts, verified 2026-09-26)

### 1.1 Android repo (`~/git/tether-android`, remote `TheMrClaus/tether-android`, branch `main`)

- 27 commits, last `84a9c66 Add FCM push notifications (0.5.1)` on 2026-08-04.
- Stack: AGP 9.3.1 (built-in Kotlin), Kotlin 2.4.10, Compose BOM 2026.06.01, compileSdk/
  targetSdk 36, minSdk 26, OkHttp 5.4 (WS + HTTP), kotlinx.serialization, DataStore,
  Navigation-Compose, Lucide icons, Firebase Messaging (no google-services plugin; options
  injected at runtime), Robolectric + JUnit4 tests. ~13.6k lines of Kotlin.
- Packages: `protocol/` (Wire, AgentEvent, Client/ServerMessage), `protocol/reduce/`
  (Kotlin port of the reducer + golden tests), `client/` (RealTetherClient, CursorTracker,
  PendingInput, SettingsStore), `push/` (FCM), `ui/` (LoginScreen, MainShell, SessionDrawer,
  chat/{ChatScreen, Bubbles, Cards, ToolCard, Markdown, Composer, ChatControls,
  SubagentRuns, ConversationTimeline, PermissionModes, Notices}, components/{Keys, Wells,
  Indicators, Haptics, Brand, Dialogs, Layout}, theme/{Tokens, Theme, Type}).
- Specs already written against the v40 era: `specs/protocol-spec.md`, `specs/reducer-spec.md`,
  `specs/visual-spec.md`, `specs/2026-08-03-chat-controls-*.md`,
  `docs/superpowers/specs/2026-08-04-push-notifications-design.md`. **They are good
  structure but stale content** — v40, and they reference the old repo name `aidash`
  (= today's `tether`). Refresh them; do not trust their numbers blindly.
- Release: manual workflow_dispatch `dry_run | draft | publish`, signed from repo secrets.

### 1.2 Web/server repo (`~/git/tether`, `TheMrClaus/tether`, private, branch `main`)

- `PROTOCOL_VERSION = 128` (`lib/protocol.ts:758`); per-version changelog as inline comments
  at `lib/protocol.ts:~179–751`. 648 commits since the Android app last moved.
- Features added since v40 that the app lacks entirely (non-exhaustive — Phase 0 builds the
  exhaustive matrix): unified Model/Effort/Mode row + applied defaults (v40–49), background
  command live output + native task progress (v55–56), setup wizard defaults (v57),
  server-side seen/unread (v63), opencode-serve (v64–65), server-synced sidebar order (v67),
  global search (v71), providers pi/acp/dsh (v72–74, v83), new-session composer catalog
  (v75–76), scheduled actions (v87), limits/reset credits/account identity (v88–92), tool
  media (v94), fast mode (v95), worktree modes (v98), handoff/brief/mention (v101–106), ping
  (v105), multi-host node registry (v109), git diff + change request (v110–113), bounded
  snapshots (v115), background tasks + spawn links (v117–118), dismissable notices (v119),
  spawned-run media (v122), model fallback (v124), reset grants + wrap-up allowance
  (v126–127), owner-level pinned workspaces (v128).
- Web surfaces (72 components): shell (`dashboard`, `topbar`, `workspace-header`,
  `panel-resize-handle`, `session-sidebar`, `inspector`, `session-statusline`, `session-dial`,
  `telemetry-sheet`, `telemetry-readings`, `context-gauge`); chat (`chat-view` 4.5k lines,
  `markdown`, `chat-tool-render`, `codex-rich-renderers`, `opencode-rich-renderers`,
  `expandable-block`, `subagent-runs`, `conversation-timeline`, `turn-activity`, `todo-bar`,
  `notice-dismiss-button`); composer/new session (`draft-composer`, `attach-sheet`,
  `model-browser`, `codex-controls`, `opencode-serve-controls`, `tether-select`,
  `metadata-draft-panel`, `studio-welcome`, `folder-picker-dialog`); settings
  (`settings-dialog` 2.5k lines, `session-settings-sheet`, `nodes-settings`, `paired-devices`,
  `sign-in-security`); git/worktree (`git-changes-card`, `repository-panel`,
  `worktree-services-card`, `github-work-dialog`, `mcp-health-card`); usage
  (`usage-dashboard`, `usage-accounts-dialog`, `codex-reset-credit-dialog`,
  `claude-reset-grant-dialog`, `deepseek-peak`); other (`global-search`,
  `workspace-file-browser`, `browser-pane`, `log-dialog`, `scheduled-actions-view`,
  `provider-logo`); login (`login/{instrument,retro,studio}-login.tsx`, `use-login-flow.ts`).
  Pages: `/` dashboard, `/web`, `/login`, `/setup` (first-run wizard), `/usage`.
- HTTP API (all in `server.mjs`): `/healthz`; `/api/auth/{session,login,logout,passkey/*,
  passkeys*,sessions*}`; `/api/devices/{claim,pair,pairings}` + list/delete; `/api/push/
  {config,subscriptions,fcm-config,fcm-register}`; `/api/{stats,usage,usage/accounts}`;
  `/api/codex/reset-credits/consume`; `/api/usage/claude-reset-grants/claim`; `/api/files/
  {list,mkdir,touch,rename,move,copy,upload}` + GET/HEAD/DELETE; `/api/github/{issues,
  pull-requests,connection/*}`; `/api/claude-accounts*`; `/api/worktree/open`;
  `/api/tool-media/`; WS `/ws` and `/ws-browser`; Control API `/api/control/v1` (not for the
  app — it is the machine-facing plane).
- Native auth already exists server-side: paired-device bearer tokens
  (`lib/device-tokens.mjs`, `server.mjs` `bearerToken()`/`authenticate()` ~2959–2994,
  claim at ~6691), FCM relay (`lib/fcm-push.mjs`, `/api/push/fcm-*` ~6965–7029).
  A device token answers **403** on device-management routes (by design).
- Reference client: `hooks/use-tether.ts` (connect ~687, per-session last-applied `seq` as
  dedupe + reconnect cursor ~142, `attach {sessionId, afterSeq}` on reconnect, visibility
  re-check). Local state keys: `tether:pendingInput`, `tether.preferences.v1`,
  `tether:draft:<…>`.
- **Version gate today:** `server.mjs:~8035` — a `hello` whose `protocolVersion !==
  PROTOCOL_VERSION` gets `version_mismatch` ("reload to reconnect"). Fine for a browser,
  **fatal for a native app** (every server deploy with a bump would lock the phone out
  until a new APK ships). Fixed in Phase 1 (task S1.x).

### 1.3 Local machine

- Android SDK at `~/Android/Sdk` (build-tools, platforms, platform-tools, cmdline-tools);
  **no emulator package or system image yet**; `/dev/kvm` present; JDK 17; 20 cores / 62 GB.
- Production Tether runs as `tether.service` (systemd user unit). **Never point tests,
  emulators or dogfood at it.** Use an isolated instance (see §6.3).

---

## 2. Key decisions (defaults chosen; owner may overturn — log changes in TRACKER "Decision log")

| ID | Decision | Default | Why |
|---|---|---|---|
| D1 | Where the app lives | **Evolve `TheMrClaus/tether-android`** in place (keep history, CI, signing, FCM). Version → `1.0.0` at parity. | Reuses tested protocol/client/push/theme code and the release pipeline. |
| D2 | UI toolkit | **Jetpack Compose + Material3 as a primitive layer only** (all visuals are custom Tether components; no stock Material look). | Native; pixel control needed to match the web. |
| D3 | Reducer strategy | **Hand-maintained Kotlin port of `engines/events.mjs`, proven by a cross-language conformance corpus generated from the JS reducer** (§5). | Stays 100% native; the corpus makes drift a red CI build, not a silent bug. *Fallback (only if the corpus shows the port cannot keep up): run the unmodified `events.mjs` in an embedded QuickJS behind a Kotlin interface — the UI would stay native. Needs owner sign-off.* |
| D4 | Protocol model | Hand-written Kotlin types for every `ClientMessage`/`ServerMessage`/`AgentEvent`, **tolerant decoding** (unknown types/fields logged + ignored, never fatal) + a **wire corpus** of real frames captured from a fake-engine server (§5.2). | The web can hard-reload on a mismatch; a phone can't. |
| D5 | Version compatibility | **Server change: native compatibility window** (task S1.1): the `hello` gains `client: "android"`, and `/healthz` + `ready` gain `nativeProtocolFloor`. The server accepts a native client with `floor ≤ v ≤ current`; the app shows an "Update available" banner instead of locking out. `nativeProtocolFloor` rises **only** on changes that break native clients; add that rule to tether `CLAUDE.md`. | Server deploys must not brick the phone. |
| D6 | Parity reference | **`PARITY_BASE = 7d65611` (tether, v128).** Parity is measured against this SHA; later tether changes go through the Catch-up Loop (§9). | You can't hit a moving target; freeze it, then roll forward in batches. |
| D7 | Architecture | Single activity, unidirectional data flow (ViewModel → immutable UiState via StateFlow), coroutines. Gradle modules: `:app`, `:core:protocol`, `:core:reducer`, `:core:net`, `:core:data` (Room + DataStore), `:core:designsystem`, `:feature:*` (shell, sidebar, chat, composer, newsession, inspector, settings, files, usage, search, scheduled, auth). DI: keep the existing manual `ClientLocator` unless it becomes a pain (then Hilt, as a logged decision). | Modules let parallel agents work without merge fights and keep the core pure/testable. |
| D8 | Local storage | **Room** for the journal mirror/snapshots (sync, Phase 13; *T13.0: the outbox stays in DataStore beside T1.3's PendingStore, see `SYNC_DESIGN.md` §2.2*), **DataStore** for prefs, **Android Keystore-backed encryption** for credentials (cookie, `tthr_` device token). | Sync needs an indexed store; credentials need hardware-backed protection. |
| D9 | Design tokens | **Generated, not hand-copied:** a tether script exports the CSS custom properties for all 6 skins from `app/globals.css` + `app/studio.css` to JSON → a Gradle task / checked-in generator writes `Tokens.kt`. A CI check fails on drift. | 88 versions of drift happened by hand-copying. |
| D10 | Layout classes | **Phone = web mobile layout** (the web below its mobile breakpoint). **Tablet/foldable/landscape ≥ expanded width = web desktop layout** (sidebar + chat + inspector, resizable). Use `WindowSizeClass`. | Same app on every Android form factor. |
| D11 | Markdown & code | Native Kotlin markdown rendered to Compose, matching `components/markdown.tsx` feature by feature. *(T6.1: the web uses its own regex parser and has NO syntax highlighting, strikethrough or task-list checkboxes, so the app ports that parser line for line, with no library and none of those extras.)* | No WebView for markdown. |
| D12 | Login surface | Implement the **login variant(s) the web serves by default** (check `app/login/page.tsx` + `components/login/*`); pairing code + password + (later) passkeys via Credential Manager. *T0.5: the web picks `InstrumentLogin` for the tactile/precision families, `StudioLogin` for Studio, `RetroLogin` as an opt-in — all three are in T1.4.* | Parity with what the owner actually sees. |
| D13 | Distribution | Keep APK via GitHub Releases (+ Obtainium). Add an in-app "update available" check against the GitHub Releases API (ties into D5). | Existing pipeline; D5 banner needs a way to update. |

---

## 3. Tracker Protocol (MANDATORY — this is what makes the program resumable)

The TRACKER must let **any** agent, with zero context, resume within minutes after a crash,
quota exhaustion, or handoff. Treat these rules as part of every task's Definition of Done.

### 3.0 Machine layer — beads (`bd`)

Row state, claims, dependencies and evidence live in this repo's **beads store** (`.beads/`, issue
prefix `ta`), seeded from `TRACKER.md` with the program's own task IDs. `TRACKER.md` stays the human
digest and is refreshed from the store — never edit the same row in both. The rule-by-rule command
mapping, the status vocabulary, CAS guards and the worktree/sync rules are in
[`BEADS.md`](./BEADS.md). Read it before your first claim.

1. **Start of every session:** `git -C ~/git/tether-android pull --rebase`, read PLAN.md
   (skim) and TRACKER.md (fully), then do what "▶ RESUME HERE" says. If a task is
   `IN-PROGRESS` with a claim older than **2 hours** and no newer session-log entry, treat the
   claimant as dead: inspect the working tree/branch it names, salvage or reset, re-claim it,
   and log the takeover.
2. **Claim before work:** set the task to `IN-PROGRESS`, fill `Claimed by` (session/agent
   id + UTC timestamp), update "▶ RESUME HERE", **commit + push the tracker immediately**
   (`tracker: claim T2.3`).
3. **Checkpoint often:** at every meaningful sub-step (and at least every ~30 minutes of
   work), update the task's `Notes` with: what's done, what's half-done, exact files
   touched, the exact next command/step. Commit code **and** tracker together; push.
   Never leave important state only in your head or only in an uncommitted working tree —
   WIP commits on the task branch are fine and encouraged (`wip(T2.3): …`).
4. **Finish:** mark `DONE` **only with evidence** in the `Evidence` column (commit SHA(s),
   test names/counts, screenshot paths, command output summary). A reviewer/verifier pass
   promotes `DONE → VERIFIED` (see §4 acceptance). Update "▶ RESUME HERE" to the next task.
5. **Blocked:** set `BLOCKED`, write the blocker in the Blockers table (what, why, what's
   needed, who can unblock — e.g. "owner: FCM secrets"), move on to the next unblocked task,
   update RESUME HERE.
6. **Session log:** append one line per session at the end of the TRACKER
   (`UTC | agent/model | tasks touched | outcome | next`). Append-only.
7. **Decisions:** any deviation from this PLAN goes into the TRACKER Decision log (date,
   decision, reason, who). If it changes the plan materially, edit PLAN.md in the same
   commit.
8. **Status vocabulary (exact):** `TODO` · `IN-PROGRESS` · `BLOCKED` · `DONE` · `VERIFIED`
   · `DROPPED` (with reason).
9. **Branching:** Android work on short-lived branches `parity/<task-id>-<slug>` merged to
   `main` when the task is DONE and CI is green (fast-forward or squash). The TRACKER on
   `main` is canonical. Server-side (tether repo) tasks go on `android-parity/<task-id>` in
   `~/git/tether` and ship as a PR — **never commit straight to tether `main`**, never
   restart the production service; the owner merges/deploys.
10. **Parity matrix is part of the tracker:** when a web feature is ported, flip its matrix
    row and link the task.

---

## 4. Definition of Done (applies to every task unless the task says otherwise)

- `./gradlew assembleDebug lint testDebugUnitTest` green (and the relevant `:module:test`).
- New logic has unit tests; reducer/protocol changes pass the conformance corpora (§5).
- UI tasks: a **screenshot test** (Roborazzi, JVM) per visual state in all 6 skins at
  phone size (and expanded size if the surface differs), **plus a side-by-side comparison**
  against the web reference screenshot of the same seeded state (§6.2) saved under
  `docs/parity/screens/<surface>/` and linked in Evidence. Differences must be explained
  (font rasterization, platform idioms) or fixed.
- Behavior tasks: an instrumented test or a scripted emulator run against an **isolated
  fake-engine server** (§6.3) proving the flow end-to-end.
- Accessibility: TalkBack labels, 44dp targets, font scale 1.3× doesn't break layout,
  status is never color-only.
- Tracker updated per §3. For a phase's last task: a fresh-context verifier pass (a
  `verifier` agent or `code-reviewer`) over the phase, recorded in the tracker.

---

## 5. Parity engineering: making "exactly the same" checkable

### 5.1 Reducer conformance corpus (task S0.2, tether side)

- New script in tether: `scripts/export-parity-corpus.mjs` (no wire change, no server
  change). It imports `engines/events.mjs` and, for every fixture in `tests/fixtures/`
  (claude/codex/acp/… JSON) **and** a set of synthetic event sequences (every `AgentEvent`
  type, every guard/edge case the JS unit tests cover — mine `tests/events*.test.mjs`),
  writes `{ name, inputEvents, expectedProjectionAfterEachStep }` to
  `parity-corpus/reducer/*.json`, plus `corpus-manifest.json` with the tether SHA and
  PROTOCOL_VERSION.
- Also export the **pure helpers the UI relies on** (e.g. `lib/format.ts` formatters,
  `lib/model-picker.mjs` catalog/labels, `lib/pending-input.mjs` constants, session
  grouping/ordering helpers, anything in `hooks/` that is pure fold logic): input → output
  tables in `parity-corpus/helpers/*.json`.
- Android: `:core:reducer` test `ReducerConformanceTest` loads every corpus file and asserts
  canonical-JSON equality of the Kotlin projection at every step. The corpus is vendored
  into the Android repo under `parity-corpus/` with the manifest SHA; a Gradle task
  `syncParityCorpus` refreshes it from `~/git/tether`.

### 5.2 Wire corpus (task S0.3)

- Script `scripts/capture-wire-corpus.mjs` in tether: boot an isolated server
  (`TETHER_HEADLESS=fake`), drive the fake engine through scripted scenarios over `/ws`,
  and record every server→client frame type (and a validated example of every
  client→server type, checked with `lib/protocol-validate.mjs`) into
  `parity-corpus/wire/*.jsonl`.
- Android `WireConformanceTest`: every server frame decodes to a known Kotlin type (no
  `Unknown`), and every client message the app can send re-validates byte-for-byte against
  the recorded valid example shape (run `protocol-validate.mjs` in the test via `node` when
  it's available, or compare against the captured canonical JSON).

### 5.3 Visual reference (task S0.4)

- Script `scripts/parity-seed.mjs` in tether: seeds an isolated fake-engine server with
  **deterministic named scenarios** (empty state, idle session, streaming, long markdown,
  every tool-card kind incl. diffs + tool media, approval pending, question pending,
  subagent runs, timeline, notices, rate limit, model fallback, wrap-up, handoff/read-only,
  worktree services, scheduled actions, usage, settings tabs, file browser…).
- Script `scripts/parity-screens.mjs` (Playwright, already a tether dev dependency):
  screenshots each scenario × skin (all 6) at **Pixel-class viewport 412×915 @DPR 2.625** and at
  **tablet 1280×800**, into `parity-corpus/screens/web/<scenario>/<theme>-<size>.png`.
- Android side: Roborazzi screenshots of the same scenario rendered from the same
  captured wire frames (fed through the fake client), into `docs/parity/screens/`. A small
  `tools/compare-screens` (image diff + side-by-side montage) produces the evidence image.
  Perceptual diff is a review aid, not a hard gate (fonts rasterize differently).

### 5.4 Token export (task S0.5)

- Script `scripts/export-design-tokens.mjs` in tether: parse `app/globals.css`
  `:root,[data-theme]` (≈line 55) and each theme block (`machine` ≈119, `precision` ≈237,
  `tactile` ≈352, `night` ≈468 — and the later "MATERIAL LAYER" overrides) **plus
  `app/studio.css` (`studio`, `studio-dark`)** → resolved `design-tokens.json` (6 skins).
  Note: `machine` is written as `:root, [data-theme="machine"]`, so its values are the base
  every other skin overrides. Android generator → `core/designsystem/.../GeneratedTokens.kt`.
  CI check: regenerate + `git diff --exit-code`.

---

## 6. Environments & tooling

### 6.1 Android toolchain (task T0.2)
`sdkmanager "emulator" "system-images;android-36;google_apis;x86_64" "platforms;android-36"`;
create AVD `tether-parity` (Pixel 8 profile) and `tether-tablet` (Pixel Tablet). Run headless:
`emulator -avd tether-parity -no-window -no-audio -gpu swiftshader_indirect &` — via the
Tether `run_in_background` / spawn tools, never a hand-rolled orphaned `nohup`. Kill by the
PID you started. Confirm AGP 9.3 works on the installed JDK 17 (upgrade to the JDK AGP
requires if not, and log it).

### 6.2 Reference screenshots — see §5.3.

### 6.3 Isolated Tether server for app testing
```bash
cd ~/git/tether
TETHER_HEADLESS=fake TETHER_PASSWORD=verify-pass TETHER_HOST=127.0.0.1 TETHER_PORT=4290 \
TETHER_STATE_DIR=$(mktemp -d) TETHER_WORKSPACE_ROOT=$(mktemp -d) \
NEXT_DIST_DIR=.next-parity NODE_ENV=development node server.mjs
```
(`TETHER_HOST`/`TETHER_PORT` per `.env.example` ~line 133; also check the network-scope
setting near ~line 150 in case loopback-only blocks the emulator. Never use 4173 (the
production default) and never the production state dir.) The emulator's `10.0.2.2` is the
host's loopback, so it reaches this server at `http://10.0.2.2:4290`;
debug builds need a `network_security_config` allowing cleartext **only** for `10.0.2.2`/
`localhost`. Remember the WS upgrade must send an `Origin` matching `Host` (OkHttp does not).
Pair via `POST /api/devices/pair` from a password session → claim in the app.

### 6.4 CI (task T0.4)
GitHub Actions in tether-android: build + lint + unit tests + Roborazzi verify + corpus
conformance on every push/PR. Keep the release workflow as is.

---

## 7. Phases & tasks

Task IDs are stable; the TRACKER mirrors them. `S*` = server/tether-repo tasks,
`T*` = Android tasks. Order within a phase is a suggestion unless a dependency is listed.
Phases 3–12 can partially overlap once Phases 0–2 are VERIFIED.

### Phase 0 — Bootstrap, baseline, parity matrix
- **T0.1** Repo hygiene: build `main` as-is on this machine; record what breaks. Refresh
  `specs/*.md` headers: `aidash` → `tether`, mark them "v40 historical" pending refresh.
- **T0.2** Toolchain + emulators (§6.1). Evidence: `adb devices` shows both AVDs booted;
  current 0.5.1 debug APK installs and launches.
- **T0.3** Modularize into D7 modules (move, don't rewrite; keep tests green).
- **T0.4** CI workflow (§6.4).
- **T0.5** **Build the Parity Matrix** in TRACKER: go through every web component, page,
  HTTP route, ClientMessage, ServerMessage and AgentEvent type at `PARITY_BASE`; one row
  each → `Web artifact | Behavior (1 line) | Android status (MISSING/PARTIAL/DONE) |
  Task`. Add a `T*` task for anything not already covered below. This is the source of
  truth for "are we done".
- **S0.1** In tether, create branch `android-parity/S0` for the scripts below.
- **S0.2** Reducer + helper conformance corpus exporter (§5.1).
- **S0.3** Wire corpus capture (§5.2).
- **S0.4** Scenario seeder + web reference screenshots (§5.3).
- **S0.5** Design token exporter (§5.4).
- **S0.6** PR the scripts to tether (no runtime/wire change → no PROTOCOL bump). Owner merges.

### Phase 1 — Protocol v128, connection, auth, compatibility
- **S1.1** Native compatibility window (D5): `hello` accepts optional `client`
  (`"android"`), server accepts native `floor ≤ v ≤ PROTOCOL_VERSION`; `nativeProtocolFloor`
  in `/healthz` and `ready`; bump PROTOCOL_VERSION; update `protocol.ts` +
  `protocol-validate.mjs` + tests; add the "classify every bump as native-breaking or not;
  raise the floor only when breaking" rule to tether `CLAUDE.md`. PR.
- **T1.1** Kotlin protocol types for **all** v128 messages/events (+ S1.1 fields), tolerant
  decoder, `WireConformanceTest` green on the corpus.
- **T1.2** Connection manager: connect → `ready` → `hello` → attach/snapshot+cursor
  (`afterSeq`, `reset`, bounded snapshots v115, optional `state` when at head), `ping`
  (v105), reconnect with backoff, lifecycle-aware (app foreground/background ≈ web
  `visibilitychange`), 4001 revocation = terminal, compat banner (D5/D13).
- **T1.3** Durable send: port `lib/pending-input.mjs` semantics (clientMessageId, queue
  cap 50, limits table), survive process death (Room/DataStore). **Never auto-retry a turn:**
  resend only what the server's dedupe makes idempotent, exactly like `use-tether.ts`.
- **T1.4** Auth: password login (cookie), device pairing (claim), logout, session-expiry →
  re-login, credential storage via Keystore encryption; `/api/auth/sessions` view if the
  web shows it to the owner. Passkeys → T10.x (needs server `assetlinks.json`).
- **T1.5** Multi-host node registry awareness (v109: `nodes`, node-add/remove/probe) as the
  web does.

### Phase 2 — Reducer at v128
- **T2.1** Port every reducer change v40 → v128 in `:core:reducer` until
  `ReducerConformanceTest` is 100% green on the corpus (all steps, all fixtures).
- **T2.2** Port the pure helpers corpus (formatters, model-picker catalog incl.
  `CLAUDE_MODEL_CATALOG` + `/model <id>` passthrough rule `looksLikeModelId`, ordering,
  grouping, seen/unread fold) until `HelperConformanceTest` is green.
- **T2.3** Client-side state parity with `use-tether.ts`: per-session seq dedupe, cursor,
  pending input, drafts (`tether:draft:*` → per-session draft store), preferences
  (`tether.preferences.v1` → DataStore, same keys/semantics).

### Phase 3 — Design system
- **T3.1** Generated tokens (D9) for all 6 skins; family × mode switcher (+ follow system);
  status/nav bar colors (`--graphite` per skin, as the PWA manifest fix did; `color-scheme`
  per skin drives light/dark bar icons). Studio's material-layer restyle lands in T3.3.
- **T3.2** Typography: Manrope + JetBrains Mono variable fonts (already in `res/font`),
  exact weights/tracking/uppercase micro-labels.
- **T3.3** Primitives matching the web's material layer: keys (face/side/press-travel/slit),
  wells, seams, pills, chips, `tether-select`, dialogs/sheets, expandable block, spinners,
  2s radar ping for waiting dots, reduced-motion honoring, haptics (web vibrates 7ms on
  timeline scrub; the app's richer haptics stay, but map them to the same moments).
- **T3.4** In-app **Component Gallery** (debug-only screen) showing every primitive in every
  theme — the screenshot-test anchor.
- **T3.5** Icons: Lucide (same glyphs the web uses), provider logos (`provider-logo`), app
  icon/adaptive icon from `public/icon*.svg`.

### Phase 4 — App shell & layout
- **T4.1** Phone shell = web mobile layout: `topbar`, `workspace-header`, drawer sidebar,
  chat, bottom sheets for inspector/telemetry (`telemetry-sheet`).
- **T4.2** Expanded shell = web desktop layout: sidebar | chat | inspector with
  `panel-resize-handle` behavior, persisted widths.
- **T4.3** `session-statusline`, `session-dial`, `context-gauge`, `telemetry-readings`,
  wrap-up badge (v127).
- **T4.4** Navigation + deep links (`tether://session/<id>` and the web's URL shapes) so
  notifications and shared links open the right session.
- **T4.5** Global log dialog (`log-dialog`, server `log` messages).

### Phase 5 — Sidebar & sessions
- **T5.1** Session list: grouping, workspaces, owner-level pinned workspaces (v128),
  server-synced order + drag reorder (`set-session-order`, v67), pin/rename/archive/kill,
  seen/unread (`mark-seen`, v63), running/waiting indicators (icon + text).
- **T5.2** History/resume picker (`discover`/`histories`/`resume`).
- **T5.3** Global search (`global-search`, v71) and in-session search (`search`).
- **T5.4** Away digests — **confirmed on the web in T0.5** ("changed while away" digest in
  `session-sidebar.tsx`, `lastSeen` fold) → in scope.

### Phase 6 — Chat view (largest phase; split further in T0.5 if needed)
- **T6.1** Turn/block rendering: user/agent bubbles, streaming text, thinking blocks,
  markdown parity (D11), code blocks + copy, long-transcript performance (lazy list,
  stable keys, `fetch-turns`/`turns-detail` paging for bounded snapshots).
- **T6.2** Tool cards: `chat-tool-render`, `codex-rich-renderers`, `opencode-rich-renderers`,
  diffs, `git-changes-card`, `git-diff-file`, tool media (v94, `/api/tool-media/`),
  spawned-run media (v122), expandable blocks.
- **T6.3** Approvals & questions cards (attention/question tokens), permission denials,
  permission paths. Operator-only consent semantics.
- **T6.4** Subagent runs + spawned runs/spawn links (v117–118), background tasks/commands
  with live output (v55–56, `stop-command`), todo bar, turn activity.
- **T6.5** Conversation timeline rail (existing, refresh to current web behavior).
- **T6.6** Notices incl. dismiss (v119), rate-limit + resume (`rate-limit-resume`),
  auto-continue-on-limit, model fallback (v124), external advancement, outcome_unknown,
  handoff/read-only lock (composer locked, reason shown), MCP health card.
- **T6.7** Interrupt, kill, error surfaces; selection/copy behavior (the web fixed runaway
  selection — don't reintroduce it).

### Phase 7 — Composer
- **T7.1** `draft-composer` parity: multiline, send/queue, per-session persisted drafts,
  queue add/edit/remove UI.
- **T7.2** Unified Model/Effort/Mode row, fast mode (v95), `model-browser`, applied
  defaults, `/model <id>` passthrough, `codex-controls`, `opencode-serve-controls`,
  `session-controls`, `set-*` messages.
- **T7.3** Slash commands menu + `run-command`/`background-command`; mentions (v106).
- **T7.4** `attach-sheet`: camera, photos, files (Android pickers), clipboard images;
  attachment limits from the protocol; image attachments per `IMAGE_ATTACHMENTS.md`.

### Phase 8 — New session, workspaces, worktrees, GitHub
- **T8.1** `studio-welcome` + new-session composer catalog (v75–76), provider choice
  (claude/codex/opencode/reasonix/pi/acp/dsh as the server reports via `providers`).
- **T8.2** `folder-picker-dialog` (`browse`, `create-folder`), workspaces.
- **T8.3** Worktree modes (v98), `worktree-inspect/scripts/script/logs/diff`,
  `worktree-services-card`, `/api/worktree/open` (open service URL in a Custom Tab),
  `repository-panel`, `change-request` (v110–113).
- **T8.4** `github-work-dialog` (`/api/github/*` issues/PRs/connection device-login).
- **T8.5** `metadata-draft-panel`, handoff brief + claim (v101–106).
- **T8.6** `browser-pane` (`/ws-browser`) rendered natively (image/frame stream + input),
  **Decided in T0.5: in scope on phone** — the web shows it as a full-screen sheet on phones
  (`dashboard.tsx` #162), so the app does too (frames as images, never a WebView).

### Phase 9 — Inspector, usage, scheduled actions
- **T9.1** `inspector` (all sections), `telemetry-*`.
- **T9.2** `/usage` page + `usage-dashboard`, `usage-accounts-dialog`, account identity,
  `codex-reset-credit-dialog`, `claude-reset-grant-dialog` (v126), `deepseek-peak`.
- **T9.3** `scheduled-actions-view` (v87: `scheduled-actions`, `schedule-create/update/
  control`).

### Phase 10 — Settings & first run
- **T10.1** `settings-dialog` — every tab, including advanced/server settings, providers,
  ACP agents, detect engines, Claude accounts + sync, preferences.
- **T10.2** `session-settings-sheet`.
- **T10.3** `nodes-settings` (multi-host).
- **T10.4** `paired-devices` + `sign-in-security`: show what a device token may see; hide
  or explain owner-grade actions (server returns 403 to device tokens — by design; do not
  "fix" that).
- **T10.5** Passkeys via Credential Manager (needs **S10.1**: server serves
  `/.well-known/assetlinks.json` for the app's signing cert; PR).
- **T10.6** `/setup` first-run wizard parity. **Decided in T0.5: keep the default (yes)** —
  `/setup` is a separate unauthenticated setup-server mode (`lib/setup-server.mjs`); the app
  detects a server in setup mode and runs the wizard natively. Lowest priority of Phase 10.

### Phase 11 — Files
- **T11.1** `workspace-file-browser` on `/api/files/*`: list, view (text/code/images),
  mkdir/touch/rename/move/copy/delete, upload (Android picker), download/share out.
- **T11.2** Android share target: "Share to Tether" from other apps → pick session →
  attachment/draft (native bonus that fits the parity model).

### Phase 12 — Notifications
- **T12.1** FCM refresh against current `lib/fcm-push.mjs` payloads; channels (waiting
  approval, question, turn done); tap → deep link to session; Android 13+ permission flow.
- **T12.2** Parity with web push triggers and settings toggles.
- **(Enhancement, owner opt-in)** approve/deny actions in the notification — only if the
  owner wants them; it is operator consent, so it must send the exact same `approval`
  message after an explicit tap (+ biometric confirm option). Never auto-resolve.

### Phase 13 — Proper sync (the "works better than the browser" layer)
Design first (**T13.0**: `docs/parity/SYNC_DESIGN.md`, **approved by `plan-verifier` 2026-09-27**; it is
binding for T13.1–T13.6, T13.3b and S13.1), then:
- **T13.1** Room **journal mirror**: `(sessionId, seq)`-keyed events + latest projection
  snapshot per session; the UI reads from Room (single source of truth), the network
  writes into it. Reconnect = `attach {afterSeq = max local seq}` → the empty at-head reply when
  current, otherwise a bounded snapshot. *(T13.0: the server never sends event deltas on attach, and
  journal compaction re-stamps seqs, so "true deltas" means the at-head reply; see `SYNC_DESIGN.md` §3.)*
- **T13.2** Offline mode: every mirrored session readable with no network; clear
  offline/stale indicators (icon + text).
- **T13.3** Outbox: durable sends/approvals queued while offline, flushed on reconnect
  with server-side dedupe; approvals older than their request are dropped with a notice,
  never replayed blindly. Never auto-retry a turn.
- **T13.4** Background catch-up: FCM data hint (*T13.0: a session-free `{kind:"sync"}`, since push data
  never names a session; see `SYNC_DESIGN.md` §6*)
  → WorkManager expedited job attaches and pulls the delta, so opening the app is instant.
  Needs **S13.1** (server: content-free sync hint on the FCM relay + `AgentSession.lastSeq` in place of a
  `sessions-changed-since` cursor; one PROTOCOL bump, not native-breaking).
- **T13.5** Cache policy: size caps, per-session eviction, pinned sessions kept, "clear
  cache" in settings, schema migrations tested.
- **T13.6** Conflict rules documented + tested: server is authoritative; local
  projection is always a pure re-fold of mirrored events (the reducer is the arbiter).

### Phase 14 — Hardening & release 1.0.0
- **T14.1** Performance: cold start, 5k-block transcript scroll at 60fps, streaming
  jank, memory; Baseline Profiles.
- **T14.2** Accessibility pass (TalkBack, font scale, contrast per theme).
- **T14.3** Security pass (`security-reviewer`): credential storage, no content in logs,
  no content in push, cleartext only in debug, WS Origin, 4001 handling, file intents.
- **T14.4** Full parity audit: every matrix row DONE, screenshot set complete, a
  fresh-context `verifier` walk-through of the whole app vs the web on the seeded server.
- **T14.5** Release `1.0.0` through the existing workflow (dry_run → draft → owner publishes).

---

## 8. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Reducer port drifts / is wrong in edge cases | Conformance corpus at every step (§5.1); D3 fallback. |
| Server deploys lock the app out | S1.1 compatibility window + tolerant decoding + update banner. |
| Web keeps moving during the program | Frozen `PARITY_BASE`; Catch-up Loop (§9) after each phase. |
| "Looks the same" is subjective | Generated tokens, seeded scenarios, side-by-side screenshots per state/theme. |
| Agent dies mid-task | Tracker Protocol (§3): claims, checkpoints, WIP commits, 2h stale rule. |
| Tests touching production | Isolated server only (§6.3); never port 4173 prod / real state dir. |
| Huge files blow context (chat-view 4.5k, events.mjs 6.4k, server.mjs, globals.css) | Grep to regions; use the corpora instead of reading the code wholesale. |
| FCM/signing secrets not available to agents | Mark BLOCKED with "owner" in Blockers; continue other tasks. |

---

## 9. Catch-up Loop (after parity at PARITY_BASE, and between phases if drift is large)

1. `git -C ~/git/tether log --oneline <PARITY_BASE>..origin/main` and the protocol changelog
   comments above `PROTOCOL_VERSION`.
2. For each change: add/adjust Parity Matrix rows + new `T*` tasks in the TRACKER.
3. Re-run S0.2–S0.5 exporters at the new SHA; vendor the corpora; failing conformance
   tests enumerate the exact reducer/wire work.
4. Port, verify, then bump `PARITY_BASE` in the TRACKER header (Decision log entry).

---

## 10. Owner inputs that may be needed (log as BLOCKED when hit, keep working elsewhere)

- Release signing + FCM secrets (already repo secrets; agents shouldn't need them for debug).
- Merging/deploying tether PRs (S0.6, S1.1, S10.1, S13.1) — production restart via
  `npm run safe-restart` by the owner.
- ~~Decisions flagged "decide in T0.5"~~ — settled in T0.5 (browser pane: yes on phone; setup
  wizard: yes; notification quick actions: **not in parity scope**, parked as owner opt-in
  bead `T12.3` (`deferred`) — the owner turns it on or it stays off).
