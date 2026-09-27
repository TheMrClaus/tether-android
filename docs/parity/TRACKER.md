# Tether Android — Native Parity TRACKER

> Live state of the program described in [`PLAN.md`](./PLAN.md).
> **Rules: PLAN.md §3 (Tracker Protocol).** Claim → checkpoint → evidence → commit + push.
> This file on `main` of `TheMrClaus/tether-android` is canonical.

## Header

| Field | Value |
|---|---|
| Program status | IN PROGRESS |
| Current phase | Phase 0 — Bootstrap, baseline, parity matrix |
| PARITY_BASE (tether SHA) | `7d65611` (PROTOCOL_VERSION 128) |
| App version on `main` | **0.6.0 (code 16), released 2026-09-27** ([v0.6.0](https://github.com/TheMrClaus/tether-android/releases/tag/v0.6.0)); minSdk 34 / targetSdk 37; still speaks protocol 40 |
| Android repo | `~/git/tether-android` (`TheMrClaus/tether-android`, `main`) |
| Server repo | `~/git/tether` (`TheMrClaus/tether`, server tasks on `android-parity/<task>` branches → PR) |
| Last updated (UTC) | 2026-09-27 |
| Last agent | claude-main (Opus 5.5, Tether session) |

> **Machine layer:** the live state of this program is the repo's **beads store** (`.beads/`,
> prefix `ta`) — rows, claims, dependencies and evidence. Use `bd` (see
> [`BEADS.md`](./BEADS.md)); this file is the human digest, refreshed from the store. Verified
> 2026-09-26: `bd ready` lists exactly `T0.1`–`T0.5` + `S0.1`.

## ▶ RESUME HERE

**Next action:** VERIFIED + merged: T1.1, T1.2, T1.4, T2.1, T2.1D, T2.2, T2.3 (`30340e2`), T3.1, T3.2. Owner action
pending: merge + deploy **tether#197** (S1.1, v129) — `main` speaks 129, so **no release until it's
deployed**; next release notes must mention pre-T1.4 backups may hold the old plaintext credential file.
IN FLIGHT (≤4 agents): **T1.3** durable send (refuted on an atomicity test gap → fixing, plus the
reconnect-handshake test flake in `reconnectAfterDrop`); **S0.4** sidebar-order determinism; **T3.3**
primitives + Roborazzi; **T3.5** icons. Next: T1.5 (after T1.3), S0.6 PR, T3.4 gallery, Phase 4 shell.

**In-flight state:** none uncommitted. Unpushed tether branches (worktrees under `~/git/tether-wt/`): `android-parity/S0.2` (`157b87d`), `android-parity/S0.3` (`fdecbe9`), `android-parity/S0.5` (`356b456`). Tether S* work happens in the worktree
`~/git/tether-wt/android-parity-S0` (branch `android-parity/S0`) — **never** switch branches in
`~/git/tether` (production `tether.service` runs from that checkout). Refresh this board's rows
with `python3 tools/parity/refresh-tracker.py` (reads `bd list --all --json`).

**Machine notes for this host:** `local.properties` needs `sdk.dir=/home/op/Android/Sdk`
(gitignored). JVM network tools (sdkmanager) need the sandbox proxy CA: build a temp truststore
from JDK `cacerts` + `~/.config/jean-claude/ca/bundle.pem` and pass
`-Djavax.net.ssl.trustStore=…` via `JAVA_OPTS`, plus `--proxy=http --proxy_host=127.0.0.1 --proxy_port=8000`.

---

## Task board

Status: `TODO` · `IN-PROGRESS` · `BLOCKED` · `DONE` · `VERIFIED` · `DROPPED`

### Phase 0 — Bootstrap, baseline, parity matrix
| ID | Task | Status | Claimed by (agent @ UTC) | Evidence | Notes |
|---|---|---|---|---|---|
| T0.1 | Build `main` as-is; record breakage; mark `specs/*.md` as v40-historical, `aidash`→`tether` | VERIFIED | claude-main @ 2026-09-26 20:59 | `63eca63`, `fed0ab1` · `bd show` |  |
| T0.2 | Toolchain: emulator pkg + system image, AVDs `tether-parity` + `tether-tablet`, 0.5.1 installs | BLOCKED (deferred) | claude-main @ 2026-09-26 21:05 |  | OWNER DECISION 2026-09-27: skip emulators for now. T0.2 deferred (emulator + AVDs stay installed). Behavior checks use JVM/Robolectric unti… |
| T0.3 | Modularize into D7 modules (move, keep tests green) | VERIFIED | executor-T0.3 @ 2026-09-26 21:22 | `cbd6042`, `76b0431` · `bd show` |  |
| T0.4 | CI workflow: build + lint + unit + Roborazzi + conformance | VERIFIED | claude-main @ 2026-09-26 21:35 | `1865da1` · `bd show` |  |
| T0.5 | Build the full Parity Matrix (below) at PARITY_BASE; add missing T-tasks; settle "decide in T0.5" items | VERIFIED | claude-main @ 2026-09-26 21:09 | `bd show` |  |
| T0.6 | Dependency refresh: every toolchain, plugin, library and CI action to its latest stable (owner request) | VERIFIED | executor-T0.6 @ 2026-09-26 22:17 | `3541b4b`, `508198c` · `bd show` |  |
| S0.1 | tether branch `android-parity/S0` | VERIFIED | claude-main @ 2026-09-26 21:05 | `bd show` |  |
| S0.2 | `scripts/export-parity-corpus.mjs` (reducer + pure helpers) | VERIFIED | executor-S0.2 @ 2026-09-26 21:12 |  |  |
| S0.3 | `scripts/capture-wire-corpus.mjs` (all frame types, fake engine) | VERIFIED | executor-S0.3 @ 2026-09-26 21:12 |  |  |
| S0.4 | `scripts/parity-seed.mjs` + `scripts/parity-screens.mjs` (web reference screenshots) | IN-PROGRESS | claude-main @ 2026-09-26 23:01 |  | Reopened after VERIFY-FAIL: cross-sitting determinism 198/360 — session sidebar order timing-dependent (two '10m' sessions swap; parity-clo… |
| S0.5 | `scripts/export-design-tokens.mjs` (6 skins → JSON) | VERIFIED | executor-S0.5 @ 2026-09-26 21:12 |  |  |
| S0.6 | PR S0 scripts to tether (no PROTOCOL bump) | TODO |  |  | Merge note: S0.5 (356b456) adds unanchored 'parity-corpus/' to .gitignore, which would ALSO hide S0.2's scripts/parity-corpus/*.mjs — keep … |

### Phase 1 — Protocol v128, connection, auth, compatibility
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| S1.1 | Server native compatibility window (`client`, `nativeProtocolFloor`, bump, CLAUDE.md rule) — PR | VERIFIED | claude-main @ 2026-09-27 00:09 |  |  |
| T1.1 | Kotlin types for all v128 messages/events, tolerant decoder, WireConformanceTest green | VERIFIED | claude-main @ 2026-09-27 00:35 |  |  |
| T1.2 | Connection manager (ready/hello/attach afterSeq/reset/bounded snapshots/ping/reconnect/lifecycle/4001/compat banner) | VERIFIED | claude-main @ 2026-09-27 00:57 |  |  |
| T1.3 | Durable send (pending-input semantics, process-death safe, no auto-retry) | IN-PROGRESS | claude-main @ 2026-09-27 03:55 |  | VERIFY-FAIL (test gap): persisted-write atomicity untested (half-then-full write and empty-then-real write both green). Invariant confirmed… |
| T1.4 | Auth: password, pairing, logout, expiry, Keystore-encrypted credentials | VERIFIED | claude-main @ 2026-09-27 02:41 |  |  |
| T1.5 | Multi-host node registry awareness (v109) | TODO |  |  |  |

### Phase 2 — Reducer at v128
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T2.1 | Reducer v40→v128; ReducerConformanceTest 100% | VERIFIED | claude-main @ 2026-09-27 00:44 | `af24229` · `bd show` |  |
| T2.1D | Reducer cutover: typed views + legacy adapter, delete v40 reducer (T2.1 unit D) | TODO |  |  |  |
| T2.2 | Pure helpers (format, model-picker, ordering, seen) ; HelperConformanceTest 100% | VERIFIED | claude-main @ 2026-09-27 02:17 |  |  |
| T2.3 | Client-state parity with use-tether.ts (seq dedupe, cursor, drafts, prefs) | VERIFIED | claude-main @ 2026-09-27 03:55 |  |  |

### Phase 3 — Design system
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T3.1 | Generated tokens, 6 skins (3 families × light/dark/system), system bars | VERIFIED | claude-main @ 2026-09-27 03:14 |  |  |
| T3.2 | Typography (Manrope, JetBrains Mono) | VERIFIED | claude-main @ 2026-09-27 03:55 |  |  |
| T3.3 | Primitives (keys, wells, seams, pills, select, sheets, expandable, spinners, ping, haptics, reduced motion) | IN-PROGRESS | claude-main @ 2026-09-27 04:16 |  | executor-T3.3 resumed in ~/git/tether-android-wt/T3.3 (branch parity/T3.3-primitives @823d7d8). Plan: Roborazzi 1.75.0 (latest on Maven Cen… |
| T3.4 | Debug Component Gallery + screenshot tests | TODO |  |  |  |
| T3.5 | Icons, provider logos, adaptive app icon | IN-PROGRESS | claude-main @ 2026-09-27 04:16 |  | Executor died at session limit before any change (worktree clean at 823d7d8). Deferred to reduce concurrent agents; restart after T3.3. |

### Phase 4 — App shell & layout
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T4.1 | Phone shell (web mobile layout) | TODO |  |  |  |
| T4.2 | Expanded shell (web desktop layout, resizable panels) | TODO |  |  |  |
| T4.3 | Statusline, dial, context gauge, telemetry readings, wrap-up badge | TODO |  |  | From T2.2: current designsystem ui/util/Format.kt diverges from web lib/format.ts — compactNumber floors (1250->'1.2K' vs web '1.3K'; 99995… |
| T4.4 | Navigation + deep links | TODO |  |  |  |
| T4.5 | Log dialog | TODO |  |  |  |

### Phase 5 — Sidebar & sessions
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T5.1 | Session list: groups, pinned workspaces, synced order, pin/rename/archive/kill, seen/unread | TODO |  |  |  |
| T5.2 | History/resume picker | TODO |  |  |  |
| T5.3 | Global + in-session search | TODO |  |  |  |
| T5.4 | Away digests (if on web) | TODO |  |  |  |

### Phase 6 — Chat view
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T6.1 | Turns/blocks, streaming, thinking, markdown, code, paging, perf | TODO |  |  |  |
| T6.2 | Tool cards, rich renderers, diffs, git changes, tool/spawned media | TODO |  |  |  |
| T6.3 | Approvals, questions, permission denials/paths | TODO |  |  |  |
| T6.4 | Subagents, spawned runs, background tasks/commands, todo bar, turn activity | TODO |  |  |  |
| T6.5 | Conversation timeline refresh | TODO |  |  | From T2.2: helpers.ConversationStoryPoints.storyPointsFromSession(state, promptMax=220, replyMax=260) is the faithful port; the timeline sh… |
| T6.6 | Notices/dismiss, rate limit, model fallback, handoff/read-only, MCP health | TODO |  |  |  |
| T6.7 | Interrupt/kill/errors; selection & copy | TODO |  |  |  |

### Phase 7 — Composer
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T7.1 | Draft composer, persisted drafts, queue UI | TODO |  |  | From T1.3: pending-status rows via helpers.PendingInput.describePending(PendingStore.tree); no failed-send list/retract yet (abandoned reco… |
| T7.2 | Model/Effort/Mode row, fast mode, model browser, codex/opencode controls | TODO |  |  |  |
| T7.3 | Slash commands, run/background command, mentions | TODO |  |  |  |
| T7.4 | Attach sheet (camera/photos/files/clipboard) + limits | TODO |  |  |  |

### Phase 8 — New session, workspaces, worktrees, GitHub
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T8.1 | Studio welcome + new-session catalog + providers | TODO |  |  |  |
| T8.2 | Folder picker, workspaces | TODO |  |  |  |
| T8.3 | Worktree modes/scripts/logs/diff/services/open, repository panel, change request | TODO |  |  |  |
| T8.4 | GitHub work dialog | TODO |  |  |  |
| T8.5 | Metadata draft panel, handoff brief + claim | TODO |  |  |  |
| T8.6 | Browser pane (native frame stream) — scope per T0.5 | TODO |  |  |  |

### Phase 9 — Inspector, usage, scheduled actions
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T9.1 | Inspector + telemetry | TODO |  |  |  |
| T9.2 | Usage page, accounts, reset credits/grants, deepseek peak | TODO |  |  |  |
| T9.3 | Scheduled actions | TODO |  |  |  |

### Phase 10 — Settings & first run
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T10.1 | Settings dialog, all tabs | TODO |  |  |  |
| T10.2 | Session settings sheet | TODO |  |  |  |
| T10.3 | Nodes settings | TODO |  |  |  |
| T10.4 | Paired devices + sign-in security (device-token view) | TODO |  |  |  |
| S10.1 | Server `/.well-known/assetlinks.json` — PR | TODO |  |  |  |
| T10.5 | Passkeys via Credential Manager | TODO |  |  |  |
| T10.6 | `/setup` wizard parity (scope per T0.5) | TODO |  |  |  |

### Phase 11 — Files
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T11.1 | Workspace file browser (all /api/files ops) | TODO |  |  |  |
| T11.2 | Android share target → session | TODO |  |  |  |

### Phase 12 — Notifications
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T12.1 | FCM refresh, channels, deep link, Android 13+ permission | TODO |  |  | OWNER DECISION 2026-09-27: migrate FCM from deprecated getToken()/onNewToken() to register()/onRegistered() here (different identifier -> m… |
| T12.2 | Web-push trigger/settings parity | TODO |  |  |  |
| T12.3 | (owner opt-in) Approve/deny actions in the notification | BLOCKED (deferred) |  |  |  |

### Phase 13 — Proper sync
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T13.0 | `SYNC_DESIGN.md` + plan-verifier review | TODO |  |  |  |
| T13.1 | Room journal mirror; UI reads Room; delta attach | TODO |  |  |  |
| T13.2 | Offline mode + stale indicators | TODO |  |  |  |
| T13.3 | Outbox (dedupe-safe, no turn auto-retry, stale approvals dropped) | TODO |  |  | From T1.3: PendingInput facade has no mention parameter; a queue item removed on another device can still be resent (web has the same gap);… |
| S13.1 | Server content-free FCM "advanced" hint + sessions-changed cursor — PR | TODO |  |  |  |
| T13.4 | FCM hint → WorkManager catch-up | TODO |  |  |  |
| T13.5 | Cache policy, eviction, migrations | TODO |  |  |  |
| T13.6 | Conflict rules doc + tests | TODO |  |  |  |

### Phase 14 — Hardening & release 1.0.0
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T14.1 | Performance + Baseline Profiles | TODO |  |  | DECISION: FoldAdapterStressTest p99<2ms bar is now OPT-IN (-Pparity.perfAssert=true) — wall-clock assertions flake under load in the defaul… |
| T14.2 | Accessibility pass | TODO |  |  | From T3.2: CSS text-transform:uppercase keeps the ORIGINAL words as the accessible name; native uppercase labels must set contentDescriptio… |
| T14.3 | Security review | TODO |  |  | From security review of T0.6 (508198c), none release-blocking: (1) LOW/UX: on Android 17, a LAN server the classifier misses (IPv6 global, … |
| T14.4 | Full parity audit (fresh verifier) | TODO |  |  |  |
| T14.5 | Release 1.0.0 (dry_run → draft; owner publishes) | TODO |  | `bd show` | RESOLVED early (owner decision 2026-09-27): android-release.yml setup-android -> packages: platform-tools, sha c08d9fa on main. EVIDENCE: d… |

---

## Parity Matrix (built by T0.5 — the definition of "done")

**Full matrix: [`MATRIX.md`](./MATRIX.md)** (one row per web component, page, HTTP route, hook/pure
helper, ClientMessage, ServerMessage and AgentEvent at tether `7d65611`, v128),
data in [`matrix.json`](./matrix.json), generated by `tools/parity/build-matrix.py`. Every row is also a
bead (`bd list -l matrix --all`), parked as `deferred` with a `task:<id>` label; when its task is done
the maker moves the task's rows to `done`, and a different actor promotes them to `verified`.
"Are we done" = every non-dropped matrix bead `verified`.

Android status at T0.5 (app 0.5.1 / protocol 40):

| Kind | Rows | MISSING | PARTIAL | DONE | N/A |
|---|---|---|---|---|---|
| component | 57 | 41 | 14 | 0 | 2 |
| page | 6 | 2 | 3 | 0 | 1 |
| route | 25 | 17 | 6 | 0 | 2 |
| hook/helper | 21 | 8 | 13 | 0 | 0 |
| client-msg | 67 | 47 | 20 | 0 | 0 |
| server-msg | 41 | 28 | 13 | 0 | 0 |
| event | 74 | 21 | 53 | 0 | 0 |
| **total** | **291** | 164 | 122 | 0 | 5 |

---

## Blockers

| Since (UTC) | Task | Blocker | Needed from | Status |
|---|---|---|---|---|
| 2026-09-27 | S1.1 → T1.x release | PR [tether#197](https://github.com/TheMrClaus/tether/pull/197) (protocol v129 native window) is VERIFIED and waiting for the owner to merge + deploy. Android work against v129 proceeds; **no Android release that speaks 129 may ship before the server is deployed** | owner: review + merge + `npm run safe-restart` | OPEN |
| 2026-09-26 21:55 | S0.3 (→ S0.4, and every DoD "isolated fake-engine server" run) | An isolated server can't boot from an agent session: agents run inside `tether.service`'s cgroup, and `server.mjs`'s `findLiveUnitMainPeer()` guard (issue #155 — a second in-cgroup server once process-group-killed prod) refuses to start. Escaping via `systemd-run --user --scope` would bypass that production-safety guard. Capture script + tests are committed (`fdecbe9`, never run live). | owner: either OK agents running isolated servers in their own transient scope (`systemd-run --user --scope …`, separate port/state dir), or run the capture from a shell outside `tether.service` | RESOLVED 2026-09-27: owner OK'd `systemd-run --user --scope` |
| 2026-09-26 21:10 | T0.2 | Emulator can't boot: user `operator` is not in group `kvm` (`/dev/kvm` root:kvm 0660) → no hardware acceleration | owner: `sudo usermod -aG kvm operator` + re-login (or OK the agent to run it) | RESOLVED 2026-09-27: owner chose *skip emulators for now* → T0.2 deferred |

## Decision log

| Date | Decision | Reason | By |
|---|---|---|---|
| 2026-09-26 | Adopt PLAN.md D1–D13 defaults; PARITY_BASE = tether `7d65611` (v128) | Initial plan | planning session |
| 2026-09-26 | All tether-side (S*) work happens in git worktrees under `~/git/tether-wt/<branch>`, never by switching branches in `~/git/tether` | `tether.service` (production) runs with `WorkingDirectory=~/git/tether`; a checkout there changes what prod runs on restart | claude-main |
| 2026-09-27 | **Emulators skipped for now**: T0.2 deferred (SDK emulator + both AVDs stay installed); behavior checks run on JVM/Robolectric; Phase 0 closes without T0.2 | Owner answer (question card) | owner |
| 2026-09-27 | **Isolated test servers run in their own transient cgroup** via `systemd-run --user --scope` (ports 4290–4299, throwaway state dirs); the #155 guard is never modified or bypassed in code | Agents run inside `tether.service`'s cgroup, where `server.mjs` rightly refuses a second server; a scope is a separate cgroup (verified) | owner |
| 2026-09-27 | Pushed commits with AI `Co-authored-by` trailers stay as they are (no force-push); all new commits are trailer-free | Owner answer | owner |
| 2026-09-27 | Fix `android-release.yml` now (`setup-android` → `packages: platform-tools`), proven with a `dry_run` dispatch — overrides "keep the release workflow as is" for this one line | Owner answer | owner |
| 2026-09-27 | Run **≤ 4 concurrent agents** (was 5–7) | Two API/session-limit outages killed 5 agents each; fewer concurrent agents keeps the program under the limit, and every executor now WIP-commits so an interruption loses nothing | claude-main |
| 2026-09-27 | T2.1 does **not** mimic 3 corpus-unexercised JS quirks (Object.prototype-named keys like `constructor` in mcpHealth/subagent maps; numeric `+` on non-string delta text) — flag the prototype-key issue upstream in tether | They're JS bugs / malformed-input artefacts, not intended behavior | claude-main (owner delegation) |
| 2026-09-27 | T2.2 keeps helpers **faithful to the web** (story points 220/260); the Android UI keeps the owner's 0.5.0.1 wider-bubble override (270/320) as an explicit, logged divergence passed in by the timeline UI | Helper corpus must match the web; the owner chose the wider Android bubble deliberately | claude-main (owner delegation) |
| 2026-09-27 | **Gate exception:** Phase 3 (T3.1 tokens) starts before P1/P2 close — depends only on S0.5 (verified), disjoint files | Keep the pipeline full while T1.3/T1.5/T2.3 queue behind T1.4 | claude-main (owner delegation) |
| 2026-09-27 | **Gate exception:** S1.1 (server native compat window) starts before Phase 0 closes | It needs no Phase-0 artifact; only S0.3-verify/S0.4/S0.6 remain in P0 | claude-main (owner delegation) |
| 2026-09-27 | **Published v0.6.0** (code 16) from `34b1c9b` after CI + verifier + security review; same signing cert as 0.5.1 (upgrade-installs). APK is 33 MB because dex is stored uncompressed at minSdk ≥ 28 (AGP default, not a regression); R8 minification (off since before the program) → T14.1 | Owner asked for a morning APK; decided on owner's behalf | claude-main |
| 2026-09-27 | Owner away overnight: agent works autonomously, decides on the owner's behalf (logged here), and publishes a new APK release when the SDK work lands | Owner message | owner |
| 2026-09-27 | **targetSdk 37** with an `ACCESS_LOCAL_NETWORK` permission flow (Android 17 blocks LAN traffic otherwise); **minSdk 26 → 34 (Android 14)**; compileSdk 37 | Owner: "everything latest", Android 14 floor. targetSdk doesn't limit installs; minSdk does | owner |
| 2026-09-27 | FCM `register()`/`onRegistered()` migration folded into **T12.1** (+ matching server PR); old API narrowly `@Suppress`ed until then | Owner answer | owner |
| 2026-09-27 | New task **T0.6**: update every toolchain/plugin/library/CI action to its latest stable, incl. compileSdk/targetSdk | Owner request: "update everything in that app to the very latest" | owner |
| 2026-09-26 | Themes are **3 families × light/dark/system = 6 skins** (tactile/night, precision/machine, studio/studio-dark), not "four themes"; PLAN constraint 2, D9, D12, §5.3, §5.4, T3.1 edited | Web moved to a family×mode model (`hooks/use-preferences.ts`) incl. the Studio family (`app/studio.css`) | claude-main (T0.5) |
| 2026-09-26 | T0.5 settled: browser pane **in scope on phone**; `/setup` wizard **yes** (lowest P10 priority); notification quick actions **out of parity scope** → `T12.3` deferred owner opt-in; away digest in scope (T5.4); login = instrument + studio + retro | Web behavior at `7d65611`; PLAN defaults | claude-main (T0.5) |
| 2026-09-26 | Full matrix lives in `MATRIX.md`/`matrix.json` (generated) + one bead per row under epic `MATRIX`, rows parked `deferred`; TRACKER keeps only the summary | 291 rows don't fit a hand-kept table; bd v1.3.0 leaked `open`+blocks rows into `bd ready` | claude-main (T0.5) |
| 2026-09-26 | Generated corpora (`parity-corpus/`) are **gitignored in tether** and vendored into the Android repo; the tether PR carries only the scripts + tests | Keeps the tether PR reviewable; Android pins the corpus by manifest SHA | claude-main |
| 2026-09-26 | T0.1 baseline keeps Android timeline story-point limits 270/320 (owner bump in 0.5.0.1) and fixes the stale test; the web's 220/260 is flagged on T6.5 | T0.1 is "build as-is"; parity decisions belong to the surface task | claude-main |

## Session log (append-only)

| UTC | Agent / model | Tasks | Outcome | Next |
|---|---|---|---|---|
| 2026-09-26 | planning session / Opus 5.5 | — | Wrote PLAN.md + TRACKER.md (uncommitted in `~/git/tether-android/docs/parity/`) | T0.1 |
| 2026-09-26 21:15 | claude-main / Opus 5.5 | T0.1, S0.1, T0.2 | T0.1 DONE (`63eca63`, gate green 131 tests); S0.1 DONE (worktree branch); T0.2 BLOCKED (KVM) | verifier for T0.1/S0.1; T0.5, T0.3 |
| 2026-09-26 21:40 | claude-main / Opus 5.5 (+ executor-S0.5, verifier) | T0.1, S0.1, T0.5, S0.5, S0.2, S0.3 | T0.1+S0.1 VERIFIED (verifier caught AIDASH_ env names → fixed `fed0ab1`); T0.5 DONE (291-row matrix, 6-skin correction); S0.5 DONE (`356b456`); S0.2/S0.3 running | T0.3; verify T0.5/S0.5 |
| 2026-09-26 23:00 | claude-main / Opus 5.5 (+ executors S0.2, S0.3, T0.3; verifier) | S0.2, S0.3, T0.3, T0.4 | S0.2 VERIFIED (`157b87d`); S0.3 BLOCKED (cgroup guard; script `fdecbe9` untested live); T0.3 DONE (`cbd6042`) — verifier found a real duplicate-attach race, fixed `76b0431` + deterministic regression test; T0.4 DONE (CI green); T0.5 re-verified after a matrix regex fix | owner decisions: KVM, isolated-server scope |
| 2026-09-27 08:55 | claude-main / Opus 5.5 (+ executors, verifiers, security reviewers) | T1.1–T1.4, T2.1, T2.1D, T2.2, T2.3, T3.1–T3.3, T3.5, S0.3, S0.4, S1.1 | v0.6.0 published; VERIFIED+merged T1.1, T1.2, T1.4 (security-blocked once, fixed), T2.1 (70/70), T2.1D, T2.2 (807/807), T3.1, T3.2; S1.1 PR tether#197 open; two outages (5 agents each) recovered by resuming agents in context | owner: merge/deploy tether#197; next: T1.3/T2.3 verify → merge, S0.4 → S0.6, T3.3 → T3.4 |
