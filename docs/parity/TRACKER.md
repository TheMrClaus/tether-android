# Tether Android — Native Parity TRACKER

> Live state of the program described in [`PLAN.md`](./PLAN.md).
> **Rules: PLAN.md §3 (Tracker Protocol).** Claim → checkpoint → evidence → commit + push.
> This file on `main` of `TheMrClaus/tether-android` is canonical.

## Header

| Field | Value |
|---|---|
| Program status | IN PROGRESS |
| Current phase | Phases 0-3 CLOSED; Phase 4 in progress (T4.1-T4.3 merged); Phases 5/6/12 landing |
| PARITY_BASE (tether SHA) | `887c222` (PROTOCOL_VERSION 137, floor 129) for the protocol corpora (reducer+helpers, wire) and the Parity Matrix (T15.8). **UI base stays older:** tokens = tether `356b456` export reduced to Studio (T15.5), until ta-ccu; web screenshots = `3f69e4f` (v128), until ta-lx3. See `parity-corpus/VENDORED.md` |
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

**Resume point (2026-10-01 06:30 CEST):** `main` @ `fe5e86dd` (gate green 4188), **v0.9.0 published** (code 27, same cert). Nothing in flight.
**Merged + verified overnight:** T15.4 top bar, ta-ylh (v133-v135 decode; hello stays 132), T7.4 attachments + thumbnails, T15.6 operator-only queue,
T15.3 Overview host & usage, ta-vmg/ta-x9c flakes, T15.5 Studio-only, T15.7 service Open links, ta-dl4 inspector leftovers, T15.8 re-baseline
(PARITY_BASE 887c222 / v137 for protocol corpora + matrix), ta-exi (store-read failures fail closed). **Owner:** confirm two coordinator
defaults in the Decision log (hello stays 132 until the server is deployed at >= the new version; T15.7 Open via the pinned console link),
try a service Open on the device (the browser may not send the console cookie on an app-started navigation), exempt the app's routes at the
gateway (#230). **Next (`bd ready`):** ta-ceo (#229 deferred-message UI), ta-ebc (#231 plan names), ta-ccu (tokens re-baseline), ta-lx3 (tether
screens exporter), then the P3 follow-ups.
**Tether (merged by the coordinator; deploys are the owner's):** #208, #209, #212, #216 deployed (server protocol 133 then);
**#224 merged (`81aa352`), NOT deployed** - see tether#220 for deploy notes (service hostnames change; new settings in docs/worktrees.md;
protocol 134, native floor 129).
**Releases (same cert as 0.6.0 `4f8c22de...b74d`):** v0.7.8 (code 25) published by the owner 2026-09-30; **v0.8.0 (code 26, `6c5e474`,
+ta-28i +ta-fz3 +T6.8 +ta-8lg +T9.1 +T15.1/T15.2) PUBLISHED by the coordinator 2026-09-30** (cert checked). **v0.9.0 (code 27, `fe5e86dd`, everything merged overnight) PUBLISHED by the coordinator 2026-10-01** (cert checked). Drafts 0.7.4-0.7.7 superseded. **Owner rule 2026-09-30: the coordinator PUBLISHES releases itself (no drafts left for the owner), and every new
release bumps the MINOR version** (0.8.0, 0.9.0, ...; versionCode +1 each). Flow: `android-release.yml` draft -> download the APK,
check the signing cert against 0.7.8's and the versionName/versionCode -> publish; never publish a mismatched cert.
**Security follow-ups live in private tether issues** (public beads carry pointers only): #221, #222, #223, #225.
**Owner queue:** deploy tether #224 (later today); review/redact the private tether issues and PRs before the visibility flip (owner handles it).
**Next frontier (`bd ready`), owner-ordered 2026-09-30:** after ta-28i/ta-fz3 merge, in parallel: **T6.8** (P1, tool screenshots
not showing on device), **Overview** (T15.1 feed -> T15.2 screen / T15.3 host+usage -> T15.4 top bar), **T9.1** full telemetry
(the web's whole inspector), **T7.4** attachments + image thumbnails; then T15.5 Studio-only, ta-ylh (v135 wire) -> T15.6/T15.7, T15.8
re-baseline. Server main is protocol **135** (not deployed past 133). Note: `bd ready` on v1.3.0 still lists T15.6/T15.7 despite
their ta-ylh blocks-edge; check `bd show` before dispatch.
Tether S* work happens only in `~/git/tether-wt/` worktrees; **never** switch branches in `~/git/tether` (production runs
from it). Refresh this board's rows with `python3 tools/parity/refresh-tracker.py` (reads `bd list --all --json`).

**Machine notes for this host:** `local.properties` needs `sdk.dir=<home>/Android/Sdk`
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
| S0.4 | `scripts/parity-seed.mjs` + `scripts/parity-screens.mjs` (web reference screenshots) | VERIFIED | claude-main @ 2026-09-26 23:01 |  |  |
| S0.5 | `scripts/export-design-tokens.mjs` (6 skins → JSON) | VERIFIED | executor-S0.5 @ 2026-09-26 21:12 |  |  |
| S0.6 | PR S0 scripts to tether (no PROTOCOL bump) | VERIFIED | claude-main @ 2026-09-27 08:54 |  |  |

### Phase 1 — Protocol v128, connection, auth, compatibility
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| S1.1 | Server native compatibility window (`client`, `nativeProtocolFloor`, bump, CLAUDE.md rule) — PR | VERIFIED | claude-main @ 2026-09-27 00:09 |  |  |
| T1.1 | Kotlin types for all v128 messages/events, tolerant decoder, WireConformanceTest green | VERIFIED | claude-main @ 2026-09-27 00:35 |  |  |
| T1.2 | Connection manager (ready/hello/attach afterSeq/reset/bounded snapshots/ping/reconnect/lifecycle/4001/compat banner) | VERIFIED | claude-main @ 2026-09-27 00:57 |  |  |
| T1.3 | Durable send (pending-input semantics, process-death safe, no auto-retry) | VERIFIED | claude-main @ 2026-09-27 03:55 |  |  |
| T1.4 | Auth: password, pairing, logout, expiry, Keystore-encrypted credentials | VERIFIED | claude-main @ 2026-09-27 02:41 |  |  |
| T1.5 | Multi-host node registry awareness (v109) | VERIFIED | claude-main @ 2026-09-27 08:53 |  |  |

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
| T3.3 | Primitives (keys, wells, seams, pills, select, sheets, expandable, spinners, ping, haptics, reduced motion) | VERIFIED | claude-main @ 2026-09-27 04:16 |  |  |
| T3.4 | Debug Component Gallery + screenshot tests | VERIFIED | claude-main @ 2026-09-27 11:53 |  |  |
| T3.5 | Icons, provider logos, adaptive app icon | VERIFIED | claude-main @ 2026-09-27 04:16 |  |  |

### Phase 4 — App shell & layout
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T4.1 | Phone shell (web mobile layout) | VERIFIED | claude-main @ 2026-09-27 11:53 |  |  |
| T4.2 | Expanded shell (web desktop layout, resizable panels) | VERIFIED | claude-main @ 2026-09-27 13:32 |  |  |
| T4.3 | Statusline, dial, context gauge, telemetry readings, wrap-up badge | VERIFIED | claude-main @ 2026-09-27 11:53 |  |  |
| T4.4 | Navigation + deep links | VERIFIED | TheMrClaus @ 2026-09-27 23:46 |  |  |
| T4.5 | Log dialog | VERIFIED | TheMrClaus @ 2026-09-27 21:58 |  |  |

### Phase 5 — Sidebar & sessions
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T5.1 | Session list: groups, pinned workspaces, synced order, pin/rename/archive/kill, seen/unread | VERIFIED | claude-main @ 2026-09-27 13:53 |  |  |
| T5.2 | History/resume picker | VERIFIED | TheMrClaus @ 2026-09-28 00:37 |  |  |
| T5.3 | Global + in-session search | VERIFIED | TheMrClaus @ 2026-09-28 03:33 |  |  |
| T5.4 | Away digests (if on web) | TODO |  |  |  |

### Phase 6 — Chat view
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T6.1 | Turns/blocks, streaming, thinking, markdown, code, paging, perf | VERIFIED | claude-main @ 2026-09-27 16:58 |  |  |
| T6.2 | Tool cards, rich renderers, diffs, git changes, tool/spawned media | VERIFIED | TheMrClaus @ 2026-09-28 04:21 |  |  |
| T6.3 | Approvals, questions, permission denials/paths | VERIFIED | TheMrClaus @ 2026-09-28 16:02 |  |  |
| T6.4 | Subagents, spawned runs, background tasks/commands, todo bar, turn activity | VERIFIED | TheMrClaus @ 2026-09-29 01:02 |  |  |
| T6.5 | Conversation timeline refresh | VERIFIED | TheMrClaus @ 2026-09-30 06:13 |  |  |
| T6.6 | Notices/dismiss, rate limit, model fallback, handoff/read-only, MCP health | VERIFIED | TheMrClaus @ 2026-09-29 10:10 |  |  |
| T6.7 | Interrupt/kill/errors; selection & copy | VERIFIED | TheMrClaus @ 2026-09-29 15:38 |  |  |
| T6.8 | Tool screenshots do not show in the conversation (owner report) | VERIFIED | executor-T6.8 @ 2026-09-30 15:46 | `bd show` |  |

### Phase 7 — Composer
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T7.1 | Draft composer, persisted drafts, queue UI | VERIFIED | TheMrClaus @ 2026-09-28 04:59 |  |  |
| T7.2 | Model/Effort/Mode row, fast mode, model browser, codex/opencode controls | VERIFIED | TheMrClaus @ 2026-09-29 07:09 |  |  |
| T7.3 | Slash commands, run/background command, mentions | VERIFIED | TheMrClaus @ 2026-09-30 06:50 |  |  |
| T7.4 | Attach sheet (camera/photos/files/clipboard) + limits | VERIFIED | security-executor-T7.4 @ 2026-09-30 16:… |  |  |

### Phase 8 — New session, workspaces, worktrees, GitHub
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T8.1 | Studio welcome + new-session catalog + providers | TODO |  |  | from T7.3 (coordinator): reuse T7.3's providerCatalog / requestProviderCatalog; the providers-snapshot matrix rows were not flipped by T7.3. |
| T8.2 | Folder picker, workspaces | TODO |  |  |  |
| T8.3 | Worktree modes/scripts/logs/diff/services/open, repository panel, change request | TODO |  |  |  |
| T8.4 | GitHub work dialog | TODO |  |  |  |
| T8.5 | Metadata draft panel, handoff brief + claim | TODO |  |  | from T7.3 (coordinator): add the @ picker's 'Sessions on this project' section (takeover); the T7.3 mention picker is ready for it. |
| T8.6 | Browser pane (native frame stream) — scope per T0.5 | TODO |  |  |  |

### Phase 9 — Inspector, usage, scheduled actions
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T9.1 | Inspector + telemetry | VERIFIED | executor-T9.1 @ 2026-09-30 16:58 |  |  |
| T9.2 | Usage page, accounts, reset credits/grants, deepseek peak | TODO |  |  | From the T4.1 verifier: the web workspace header shows the DeepSeek peak-hours badge (workspace-header.tsx:111). The phone shell (T4.1) has… |
| T9.3 | Scheduled actions | TODO |  |  |  |

### Phase 10 — Settings & first run
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T10.1 | Settings dialog, all tabs | TODO |  |  |  |
| T10.2 | Session settings sheet | TODO |  |  |  |
| T10.3 | Nodes settings | TODO |  |  | from T6.7 (coordinator): show NodeRequestOutcome.ServerError text cleaned through LabelText in Nodes settings. |
| T10.4 | Paired devices + sign-in security (device-token view) | TODO |  |  | OWNER DECISION 2026-09-27 (ta-xax): a paired phone is fully trusted; only owner-grade actions (device management, passkeys, claude-accounts… |
| S10.1 | Server `/.well-known/assetlinks.json` — PR | VERIFIED | TheMrClaus @ 2026-09-28 16:02 |  |  |
| T10.5 | Passkeys via Credential Manager | TODO |  |  | coordinator (from the tether#216 review): once #216 lands, every cookie-authenticated POST from the app (incl. an app-passkey session) must… |
| T10.6 | `/setup` wizard parity (scope per T0.5) | TODO |  |  |  |

### Phase 11 — Files
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T11.1 | Workspace file browser (all /api/files ops) | VERIFIED | TheMrClaus @ 2026-09-27 22:25 |  |  |
| T11.2 | Android share target → session | TODO |  |  |  |

### Phase 12 — Notifications
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T12.1 | FCM refresh, channels, deep link, Android 13+ permission | VERIFIED | claude-main @ 2026-09-27 12:49 |  |  |
| T12.2 | Web-push trigger/settings parity | TODO |  |  |  |
| T12.3 | (owner opt-in) Approve/deny actions in the notification | BLOCKED (deferred) |  |  |  |

### Phase 13 — Proper sync
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T13.0 | `SYNC_DESIGN.md` + plan-verifier review | VERIFIED | claude-main @ 2026-09-27 09:10 |  |  |
| T13.1 | Room journal mirror; UI reads Room; delta attach | VERIFIED | TheMrClaus @ 2026-09-27 23:48 |  |  |
| T13.2 | Offline mode + stale indicators | VERIFIED | TheMrClaus @ 2026-09-29 10:10 |  |  |
| T13.3 | Outbox (dedupe-safe, no turn auto-retry, stale approvals dropped) | TODO |  |  | design refinement r2: ExactlyOnceProperty includes restore with tries>0 while the mirror is at head. The 'with S13.1-C' QueueRemovedElsewhe… |
| S13.1 | Server content-free FCM "advanced" hint + sessions-changed cursor — PR | VERIFIED | claude-main @ 2026-09-27 09:38 |  |  |
| T13.4 | FCM hint → WorkManager catch-up | TODO |  |  | un-parked (coordinator, 2026-09-29): owner clarified push is BOTH bring-your-own-Firebase per instance AND a Firebase-free path; FCM work c… |
| T13.5 | Cache policy, eviction, migrations | TODO |  |  | From the T13.1 security re-review (R2, Low): the interim per-origin caps count only sync_state rows/bytes - turn_detail (no count/byte cap)… |
| T13.6 | Conflict rules doc + tests | TODO |  |  | design refinement r2: the debug probe strips removedQueueIds until T13.3b lands, then demands exact equality; test both modes. |

### Phase 14 — Hardening & release 1.0.0
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T14.1 | Performance + Baseline Profiles | TODO |  |  | DECISION: FoldAdapterStressTest p99<2ms bar is now OPT-IN (-Pparity.perfAssert=true) — wall-clock assertions flake under load in the defaul… |
| T14.2 | Accessibility pass | TODO |  |  | From T3.2: CSS text-transform:uppercase keeps the ORIGINAL words as the accessible name; native uppercase labels must set contentDescriptio… |
| T14.3 | Security review | TODO |  |  | From security review of T0.6 (508198c), none release-blocking: (1) LOW/UX: on Android 17, a LAN server the classifier misses (IPv6 global, … |
| T14.4 | Full parity audit (fresh verifier) | TODO |  |  | From the T4.3 r2 verifier (fidelity detail): UsageTrack's colour transition uses Compose's default tween easing; the web uses CSS 'ease' (c… |
| T14.5 | Release 1.0.0 (dry_run → draft; owner publishes) | TODO |  | `bd show` | owner rule 2026-09-30: the coordinator publishes releases itself (no drafts left for the owner) and bumps the minor version each release (0… |

### Phase 15 — Catch-up to web protocol v135 (Overview, Studio-only, v133-v135)
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| ta-ylh | Speak protocol v135 (wire only) | TODO |  |  |  |
| T15.1 | Overview feed client (v131) | VERIFIED | executor-T15.1 @ 2026-09-30 15:46 | `bd show` |  |
| T15.2 | Overview screen | VERIFIED | executor-T15.1 @ 2026-09-30 16:19 |  |  |
| T15.3 | Overview host + daily usage panels | VERIFIED | security-executor-T15.3 @ 2026-09-30 20… |  |  |
| T15.4 | Top-bar navigation | VERIFIED | executor-T15.4 @ 2026-09-30 20:30 |  |  |
| T15.5 | Studio-only appearance + theme migration | VERIFIED | executor-T15.5 @ 2026-09-30 23:01 | `bd show` |  |
| T15.6 | Queue origin labels (v133) + hidden session count (v135) | VERIFIED | executor-T15.6 @ 2026-09-30 23:41 |  |  |
| T15.7 | Worktree service links after v134 | VERIFIED | security-executor-T15.7 @ 2026-10-01 00… |  |  |
| T15.8 | Re-baseline: exporters, corpora, matrix rows, PARITY_BASE bump | VERIFIED | executor-T15.8 @ 2026-10-01 00:43 |  |  |
| ta-ceo | v136 (#229): deferred message waits/choice + Stop confirms its cost (UI) | TODO |  |  |  |
| ta-ebc | v137 (#231): Claude account plan names in Settings (with T10.1) | TODO |  |  |  |
| ta-ccu | Token re-baseline to 887c222 (drop 21 retired material tokens) | TODO |  |  |  |
| ta-lx3 | Upstream (tether): parity-screens exporter after the top-bar redesign | TODO |  |  |  |

---

## Parity Matrix (built by T0.5 — the definition of "done")

**Full matrix: [`MATRIX.md`](./MATRIX.md)** (one row per web component, page, HTTP route, hook/pure
helper, ClientMessage, ServerMessage and AgentEvent; built at tether `7d65611`, v128, re-based by T15.8 at
`887c222`, v137: +20 rows, 1 retired, 310 rows),
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

T15.8 re-baseline (`887c222`, v137): +20 rows (Overview components, theme-sync, `/api/overview/host|usage`,
`/.well-known/assetlinks.json`, 8 helpers, the 4 v131 overview message types); 17 entered as DONE, delivered
by T15.1-T15.6 (their beads `done` with that evidence); open: assetlinks (T10.5), queue-wait (ta-ceo, #229),
claude-account-plan (ta-ebc, #231). Retired: `components/login/instrument-login.tsx` (bead dropped).

---

## Blockers

| Since (UTC) | Task | Blocker | Needed from | Status |
|---|---|---|---|---|
| 2026-09-27 | S1.1 → T1.x release | ~~PR [tether#197] waiting for the owner~~ **MERGED `bde3cfa` + DEPLOYED 10:10 CEST** (v129 live, floor 129). Android work against v129 proceeds; **no Android release that speaks 129 may ship before the server is deployed** | owner: review + merge + `npm run safe-restart` | OPEN |
| 2026-09-26 21:55 | S0.3 (→ S0.4, and every DoD "isolated fake-engine server" run) | An isolated server can't boot from an agent session: agents run inside `tether.service`'s cgroup, and `server.mjs`'s `findLiveUnitMainPeer()` guard (issue #155 — a second in-cgroup server once process-group-killed prod) refuses to start. Escaping via `systemd-run --user --scope` would bypass that production-safety guard. Capture script + tests are committed (`fdecbe9`, never run live). | owner: either OK agents running isolated servers in their own transient scope (`systemd-run --user --scope …`, separate port/state dir), or run the capture from a shell outside `tether.service` | RESOLVED 2026-09-27: owner OK'd `systemd-run --user --scope` |
| 2026-09-26 21:10 | T0.2 | Emulator can't boot: the operator account is not in group `kvm` (`/dev/kvm` root:kvm 0660) → no hardware acceleration | owner: `sudo usermod -aG kvm <operator>` + re-login (or OK the agent to run it) | RESOLVED 2026-09-27: owner chose *skip emulators for now* → T0.2 deferred |

## Decision log

| Date | Decision | Reason | By |
|---|---|---|---|
| 2026-09-28 | Owner asleep overnight: the coordinator works autonomously, decides on the owner's behalf (logged here), merges verified work, and delivers a morning report plus a live-deployment test list. Production deploys, releases and history rewrites stay owner calls | Owner message | owner |
| 2026-09-28 | A security reviewer without a shell reads a **`git archive` snapshot** of the committed candidate (read-only, outside every worktree) when a verifier is mutating in parallel | The reviewer agent has no git; the archive is byte-identical to the commit and immune to in-flight mutations | claude-main (owner delegation) |
| 2026-09-28 | The coordinator resolves **keep-both** rebase conflicts itself (additive blocks from two merged lanes at the same spot), re-runs the full gate, and has the task's verifier review the resolution before merge | T5.1 vs T4.5/T6.1 collided only on appended interface members; a new executor round would add nothing | claude-main (owner delegation) |
| 2026-09-28 | T4.5: the unseen-warnings mark is scoped to a **sign-in generation** carried by `EventLog`, not reset by observing an empty log | StateFlow conflation can skip the empty state (reproduced by the verifier) | claude-main (owner delegation) |
| 2026-09-28 | T13.1: SYNC_DESIGN rule 1a (a tries>0 unsent record forces a full attach, no afterSeq) applies **with the mirror off too**; the mirror is wiped on **any** credential rejection (incl. an expired cookie), not only on revocation | Rule 1a closes the same in-process gap either way; a rejected credential can't prove the same owner, so dropping the cache is the conservative choice (it refills on the next sign-in) | claude-main (owner delegation) |
| 2026-09-28 | T4.4: MainActivity uses **singleTop**, not singleTask | A launcher relaunch of a singleTask root clears activities above it (Custom Tabs, permission dialogs, SAF pickers) - documented platform behaviour, decided without a device while emulators are owner-deferred | claude-main (owner delegation) |
| 2026-09-28 | tether#204 (S12.2) merged by the owner's ops agent (tether `2287777`); deploy deferred (a restart would orphan in-flight agent work and buys nothing until Firebase is provisioned). FCM secrets belong in a root-owned EnvironmentFile, not a repo .env.local | Operator-side status report | owner (via ops agent) |
| 2026-09-28 | T7.1: Escape on a queue row **reverts** the edit. The web intends a revert but actually saves (its blur reads a stale value, chat-view.tsx:1464-1467 at 7d65611; reproduced under React 19 + jsdom by the verifier) | Same rule as T2.1: port the web's intent, not its JS bugs; flag upstream | claude-main (owner delegation) |
| 2026-09-28 | T7.1: the web's `draft-composer.tsx` / `use-draft-composer.ts` / `use-keyboard-inset.ts` are the **new-session** composer - their matrix rows move to T8.1; T7.1 is the in-session composer from chat-view.tsx | Verified by reading the web at 7d65611 | claude-main (owner delegation) |
| 2026-09-28 | Built a **0.7.0 DRAFT** release (workflow `draft` mode, version stamped 0.7.0/17 at dispatch, not committed) from green `main` `b60b0d4` so the owner can test overnight work on a device; not published | Owner asked for a live-deployment test list; nothing on main was installable otherwise (a debug APK cannot upgrade the signed 0.6.0) | claude-main (owner delegation) |
| 2026-09-28 | ta-s4r: a 401 without Tether's JSON `error` (or with `WWW-Authenticate`) is reported as **"refused before Tether checked the password"** (`LoginResult.GatewayRefused`, pointing to pairing), never as a wrong password. Sign-in never submits with unknown requirements (a "Checking sign-in" re-probe first), and after a failed probe the username field is shown as **optional** (the web hides it) | Owner saw the app's fallback "That password is not correct." - only a gateway in front of `/api/auth/login` (as Tether's README advises for SSO setups) can produce it on a server that asks for a username; pairing is the way in behind such a gateway | claude-main (owner delegation) |
| 2026-09-26 | Adopt PLAN.md D1–D13 defaults; PARITY_BASE = tether `7d65611` (v128) | Initial plan | planning session |
| 2026-09-26 | All tether-side (S*) work happens in git worktrees under `~/git/tether-wt/<branch>`, never by switching branches in `~/git/tether` | `tether.service` (production) runs with `WorkingDirectory=~/git/tether`; a checkout there changes what prod runs on restart | claude-main |
| 2026-09-27 | **Emulators skipped for now**: T0.2 deferred (SDK emulator + both AVDs stay installed); behavior checks run on JVM/Robolectric; Phase 0 closes without T0.2 | Owner answer (question card) | owner |
| 2026-09-27 | **Isolated test servers run in their own transient cgroup** via `systemd-run --user --scope` (ports 4290–4299, throwaway state dirs); the #155 guard is never modified or bypassed in code | Agents run inside `tether.service`'s cgroup, where `server.mjs` rightly refuses a second server; a scope is a separate cgroup (verified) | owner |
| 2026-09-27 | Pushed commits with AI `Co-authored-by` trailers stay as they are (no force-push); all new commits are trailer-free | Owner answer | owner |
| 2026-09-27 | Fix `android-release.yml` now (`setup-android` → `packages: platform-tools`), proven with a `dry_run` dispatch — overrides "keep the release workflow as is" for this one line | Owner answer | owner |
| 2026-09-27 | **Owner asked: merge tether#197 + redeploy.** Merged `bde3cfa`; production restart delegated to a watcher OUTSIDE `tether.service` that waits for Tether to be idle (no active turns, no background work) and runs `safe-restart --abort-if-busy` (never `--force`) | This session and its agents run inside `tether.service`; a forced restart would kill them mid-work | owner request / claude-main |
| 2026-09-27 | **Gate exception:** T1.5 (nodes) claimed while P0 is still open. It depends only on T1.1/T1.2 (verified). Routed to `security-executor` + security review, because `node-add` carries a peer credential bundle | Keep ≤4 lanes busy while S0.4/S0.6 close Phase 0 | claude-main (owner delegation) |
| 2026-09-27 | S0.6 merge keeps only S0.2's anchored `/parity-corpus/` ignore rule and drops S0.5's unanchored one (`fa6807f`); `git check-ignore` confirms `scripts/parity-corpus/*.mjs` stays tracked | The unanchored rule hid S0.2's exporter modules | claude-main (owner delegation) |
| 2026-09-27 | The 360 web reference PNGs (55 MB) are **not committed** to this repo. `tools/parity/sync-corpus.sh` copies them into a gitignored `parity-corpus/screens/web/`; the committed `manifest.json` + SHA256 list pins them. Per-surface montages (the DoD evidence) are committed under `docs/parity/screens/` | 55 MB per refresh would bloat history every catch-up; montages are a review aid, not a CI gate (PLAN §5.3) | claude-main (owner delegation) |
| 2026-09-27 | **Closed epics P0 and P2** (`bd close --force`: the v1.3.0 guard counts `verified` children as open). P0 = 11/12 verified + T0.2 owner-deferred; P2 = 4/4 verified | Every child was verified by a separate actor (S0.4 and S0.6 today) | claude-main (owner delegation) |
| 2026-09-27 | **Gate exception:** T13.0 (SYNC_DESIGN.md, doc only) started ahead of Phases 3–12 | It writes no code and touches no file another lane owns, so it fills the 4th slot without merge risk. T13.1+ stay gated on its plan-verifier review | claude-main (owner delegation) |
| 2026-09-27 | Verifier finding F1 on T3.5 (logo path text not asserted) fixed by the coordinator before merge: a fixture test plus a mutation proof (edited path → red). It was a test-only hardening of already-verified, byte-identical paths | Keeps the merge clean without another verifier round for a test-only change | claude-main (owner delegation) |
| 2026-09-27 | A security reviewer never reads a worktree while a verifier mutates it. When they overlap, reviewers read committed objects only (`git show <sha>:path`) | T1.5's review flagged a HIGH that was the verifier's in-flight mutation (f); the committed code was correct | claude-main (owner delegation) |
| 2026-09-27 | **ta-fsp** (tether `/ws` lets paired-device sockets manage nodes) is fixed as a coordinator-initiated tether PR: a fail-closed owner-grade guard in front of node-add/remove/probe; wire shape unchanged | Security review rated it MEDIUM (blind SSRF oracle, peer re-point or removal, rows that persist past revocation), rising to HIGH with N1. The owner merges and deploys | claude-main (owner delegation) |
| 2026-09-27 | **SYNC_DESIGN.md approved** (plan-verifier, 3 rounds: 1 BLOCKER + 3 MAJOR fixed). It is binding for Phase 13. PLAN D8, T13.1, T13.4 and S13.1 wording amended in the same commit | Design gate for T13.1+ | plan-verifier / claude-main |
| 2026-09-27 | Sync: the outbox stays in **DataStore** beside PendingStore, not Room (deviation from D8) | One atomic write with PendingStore; no second store to reconcile (SYNC_DESIGN §2.2) | claude-main (owner delegation) |
| 2026-09-27 | Sync: **no WS event deltas**. Catch-up = the at-head empty reply or a bounded snapshot | The server never sends events on attach, and journal compaction re-stamps seqs (journal.mjs:533-547) (§3.2) | claude-main (owner delegation) |
| 2026-09-27 | Sync: the FCM hint is a session-free, seq-free `{kind:"sync"}`. `AgentSession.lastSeq` replaces sessions-changed-since. S13.1 = one PROTOCOL bump, not native-breaking | Push data must never name a session (push-notifications.mjs:360-366) (§6.1) | claude-main (owner delegation) |
| 2026-09-27 | Sync: the mirror is encrypted per row (AES-GCM, random 96-bit nonces, Keystore-wrapped data key rotated on Clear cache/logout/2^28 writes), not SQLCipher; wiped on logout/revoke; excluded from backup | JVM-testable while emulators are off; transcripts are sensitive (§8) | claude-main (owner delegation) |
| 2026-09-27 | Sync: held approvals/answers stay in memory only, fingerprint-checked against a fresh snapshot; the background never sends, checks or discards them; saved-copy cards are disabled ("Connect to answer") | Approvals are operator-only; never replay stale consent (§4.2, §5.4) | claude-main (owner delegation) |
| 2026-09-27 | Sync: offline prompts older than 10 min need an explicit "Send now"; a session with `tries>0` unsent input always attaches without afterSeq (rule 1a), including at the background→foreground handover | Never auto-retry a turn; only a snapshot with state authorises redelivery, as on the web (§3.1, §5.3) | claude-main (owner delegation) |
| 2026-09-27 | Sync: the `ready` re-attach is capped (open + pending + pinned + 10 most recent); background runs attach at most 8 sessions; new task **T13.3b** (`ta-srn`) ports S13.1-C after it deploys | Server cost per attach and the 5 MB mobile-data budget; S13.1-C's port needed an owner (§3.1, §6) | claude-main (owner delegation) |
| 2026-09-27 | Layout class cutoff = `WindowSizeClass` expanded width (**840dp**), per PLAN D10, not the web's 768px breakpoint (`LayoutClass.kt`, T3.3). 768–839dp windows get the phone layout | D10 names WindowSizeClass; it keeps foldables/split-screen on the phone layout until they are truly wide | claude-main (owner delegation) |
| 2026-09-27 | S13.1 part C (`removedQueueIds`, changes the web's SessionProjection) ships in the **same PR as separate trailing commits**, flagged separable for the owner's OQ1 call at review | One PROTOCOL bump either way; the owner can drop part C without a re-bump | claude-main (owner delegation) |
| 2026-09-27 | `ta-s8q` (cross-server replay of unsent turns) is **release-blocking** for the next APK | It leaks prompt content to a different server | claude-main (owner delegation) |
| 2026-09-27 | After the owner's history rewrite, unmerged local branches are **transplanted** with `git rebase --onto <rewritten main> <old base>` (their own commits only, identical diffstat, zero identifier hits verified), never merged with the old history. The remote task branch `parity/T3.3-primitives` was replaced with `--force-with-lease` pinned to the owner's rewritten tip | Pushing old-history commits would undo a privacy scrub on a public repo | claude-main (owner delegation) |
| 2026-09-27 | T3.3 keys: pressed = `:active` only. Chromium's touch-emulated `:hover` (Studio hover rules win on a held key) is not modelled | Native Android has no hover on touch; `:active` is the operator's actual "pressed" moment | claude-main (owner delegation) |
| 2026-09-27 | After any interruption (usage limit, server restart), the coordinator checkpoints each worktree's uncommitted work as a WIP commit (identifier-scanned) before resuming the agent in context | Two interruptions today; resuming in context plus a checkpoint loses nothing | claude-main (owner delegation) |
| 2026-09-27 | OQ1 resolved by the owner: #200 merged **with** S13.1 part C (`removedQueueIds`); T13.3b (`ta-srn`) now waits only on T13.3 | Owner merge | owner |
| 2026-09-27 | **Owner decision:** a paired phone is **fully trusted** (operator-equivalent). The only owner-grade exceptions are device management, passkeys and claude-accounts (HTTP) and node-registry frames (#199). `ta-xax` closed | Owner answer | owner |
| 2026-09-27 | **Owner decision:** remove the operator account name from git history. Done: this repo's `main` + tag v0.6.0 rewritten (filter-repo replace-text, identical tip tree) and force-pushed by the owner (the jev-gate hook blocks agent force-pushes to main); the beads Dolt history flattened (`bd flatten`) and the remote ref deleted and re-pushed fresh, so raw objects have 0 hits. Per-issue `bd history` before this point is gone; all current notes remain | Owner answer; the Dolt git-blobstore kept old table files until the ref was recreated | owner / claude-main |
| 2026-09-27 | Executors run mutations only in a **scratch worktree**. A coordinator checkpoint after a restart once captured an in-flight mutation (ta-s8q M10) | Keep checkpoints and verifier reads of the real worktree trustworthy | claude-main (owner delegation) |
| 2026-09-27 | T12.1 split: the `register()`/`onRegistered()` migration moved to `ta-gxp` (T12.1b). With the required manifest flag it yields a bare Firebase Installation ID, not proven to be a drop-in FCM v1 `message.token`. Background-notification channel ids went to server task `ta-yhu` (S12.1). Robolectric end-to-end tests stand in for PLAN §4's instrumented test while emulators are owner-deferred | Don't break push on an unverified API change | claude-main (owner delegation) |
| 2026-09-27 | T4.1: Lock keeps the app's sign-out confirmation (the web logs out immediately); controls drawn smaller than 44dp keep the web's size with 48dp touch areas (tested) | Signing out forgets the paired credential; Android touch guidelines | claude-main (owner delegation) |
| 2026-09-27 | **Owner standing instruction: "merge everything to main".** The coordinator merges VERIFIED work itself (rebase then `--ff-only`, gate green, normal push) and never parks verified work waiting on the owner. Cheap Low findings are fixed before merge; otherwise the work merges and the findings are filed on the task. Releases and production deploys stay owner calls | Owner, via Tether ops | owner |
| 2026-09-27 | T6.1: markdown is a line-for-line Kotlin port of the web's regex parser (no CommonMark library). No syntax highlighting, strikethrough or task lists, because the web has none (PLAN D11 amended). Paging uses the web's "Load N earlier turns" button (fetchTurns(id, 0, trimmedCount)), not scroll-to-top. The chat keeps the 54dp timeline-rail column (the web overlays the rail); T6.5 owns the rail | Parity with the web beats the plan's assumptions; a CommonMark parser would render real replies differently | claude-main (owner delegation) |
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
| 2026-09-28 | S10.1 (tether#208): a passkey sign-in from the **Android app origin** (`android:apk-key-hash:`) yields a **device-grade** session (refused by owner-only routes: pairing mint, passkey register, sign-in methods, session revoke, nodes); web/browser passkey sessions unchanged. Owner may overturn | Security review M1: an owner-grade cookie stored in app data would break tether's documented "phones are device-grade" invariant | claude-main (coordinator default) |
| 2026-09-28 | T6.3: **every permission-granting choice needs an unsaved confirmation** (full request or subset), reset whenever the card's state is recreated or lost - stricter than the web (web asks only for the full expansion) | Four review rounds found the same class: any loss of card state resets to all-ticked and a further untick made a partial grant that skipped confirmation; this closes the class structurally | claude-main (coordinator default) |
| 2026-09-29 | Owner authorises the coordinator to **merge verified tether PRs** itself (pinned to the verified head, merge commit); deploying/restarting production remains the owner's. PLAN §3.9 edited | Owner: "From now on you can merge yourself" | owner |
| 2026-09-29 | **Security detail stays out of beads.** The beads store syncs to `refs/dolt/data` on this PUBLIC repo, so an unpatched tether finding is written as a one-line neutral bead ("see tether#NNN / private PR") with the detail in the private tether PR; earlier security beads already public - owner asked whether to scrub them | Disclosure of unpatched server vulnerabilities via a public ref | claude-main (coordinator default; owner may decide the scrub) |
| 2026-09-29 | **Incident + rule: host-credential isolation for test servers.** A #216 verifier's ad-hoc route sweep ran a test server with the operator's real HOME and POSTed the GitHub-connection logout route, which runs `gh auth logout` and cleared the machine's gh login (06:22 CEST). Committed tests were already isolated (fake gh + temp GH_CONFIG_DIR). Rule added to PLAN §6.3: every agent-started test/probe server gets a throwaway HOME/GH_CONFIG_DIR/XDG_CONFIG_HOME + fake gh on PATH, and sweeps never hit credential routes | Protect the operator's host logins; owner must re-run gh auth login | claude-main (coordinator) |
| 2026-09-29 | **Service-page isolation (tether#214): hostname-only + strip credentials.** Each service is served only on its own per-service hostname (its own origin) and the proxy strips console credentials; the fix must not assume an SSO gateway or any one DNS/TLS setup | Tether goes public and self-hostable soon; only a minority of self-hosters will run SSO | owner |
| 2026-09-29 | **Scrub the historical security detail from the public beads history now** (rewrite to neutral pointers, force-push `refs/dolt/data`) | Unpatched detail was published before the disclosure rule | owner |
| 2026-09-29 | **Next draft is 0.7.6, built after T6.6 merges** (T13.2 + T6.6); 0.7.4/0.7.5 stay unpublished drafts | Owner choice | owner |
| 2026-09-29 | **Owner deploying tether #209, #212, #216** (then reopen each running service once from the Services panel) | Owner action | owner |
| 2026-09-29 | **No SSO bypass here: the owner disables the SSO gateway on this box.** The app must still work for self-hosters behind their OWN SSO/reverse-proxy gateway (design task filed), including passkeys, which need `/.well-known/assetlinks.json` reachable | Self-hosting; gateway-agnostic | owner |
| 2026-09-29 | **Push supports BOTH bring-your-own-Firebase per instance AND a Firebase-free path** (corrects an earlier "skip Firebase entirely" reading; tether `docs/self-hosting-push.md` covers BYO-Firebase). FCM work continues; ta-nrq designs the Firebase-free path (e.g. UnifiedPush with a self-hosted distributor) alongside it | Self-hosters choose; nobody is forced onto a Google project | owner |
| 2026-09-29 | **Service pages (see tether#220): path-form URLs kept only for loopback consoles; credential stripping configurable, default by name** (Tether credentials + gateway identity headers stripped, the app's own cookies pass; per-instance strip-everything option) | Dev apps with their own logins keep working; loopback use stays convenient | owner |
| 2026-09-29 | **Beads scrub: proceed at the next quiet window** (fresh store from a neutralised export; the event timeline is dropped, notes and evidence kept); no cache purge request; git history left as is, TRACKER wording neutralised in a normal commit | Owner choice | owner |
| 2026-09-30 | **Service-proxy strip list: keep the wider gateway list** (oauth2-proxy, Pomerium, Authentik, Authelia, Google IAP, Cloudflare Access variants) | Safer for self-hosters on any gateway | owner |
| 2026-09-30 | **tether #224 deploy later; the owner redacts the private tether issues/PRs before the visibility flip; beads-scrub backup deleted** | Owner choice | owner |
| 2026-09-30 | **Transcript prose: every explicit bidi formatting character (embeddings, overrides, isolates) is a visible token; only LRM/RLM/ALM directly next to a real RTL letter stay raw; prose paragraph direction comes from content.** Copy: dangerous characters copy in visible form with a notice and an explicit Copy raw (no long-press raw copy) | Three review rounds kept finding isolate-based word swaps; implicit bidi already orders real Hebrew/Arabic. Cost: rare legitimate isolate use shows tokens | coordinator |
| 2026-09-30 | **Catch-up batch 1 = Phase 15** (web v129-v135 since PARITY_BASE `7d65611`): Overview feed/screen/host+usage/top bar (T15.1-T15.4), Studio-only appearance (T15.5), v133-v135 wire (ta-ylh widened) + UI (T15.6/T15.7), re-baseline and PARITY_BASE bump (T15.8). P14 (1.0.0) now depends on P15 | The owner found Overview and other web features missing; they postdate the frozen base | owner request, coordinator scoping |
| 2026-09-30 | Overview, full telemetry (T9.1, rescoped to the web's whole inspector) and attachments with image thumbnails (T7.4) run **in parallel** after ta-28i/ta-fz3; tool screenshots not showing on device is a P1 bug (T6.8) | Owner answers (card) | owner |
| 2026-09-30 | The app **matches the web's Studio-only appearance** (Light / Dark / Follow system); retired theme families migrate to Studio (T15.5). Supersedes the 2026-09-26 six-skin entry once T15.5 lands | Owner answer (card) | owner |
| 2026-09-30 | T6.8 root cause: the owner's sign-in gateway still fronts `/api`, and the app (correctly) never follows its login redirect, so tool images show "Image unavailable". Fix on the gateway + tether README exempt list (ta-p5l); the app only explains the failure better | Owner answers (card): tile shows "Image unavailable", gateway still there | owner |
| 2026-09-30 | Chat links keep the current direct-open rule (exact short ASCII label==href, no `@`, settled, not clamped); everything else asks first. No "always ask" | Owner answer (card) | owner |
| 2026-09-30 | T7.4: attachments go **inline on the WebSocket send, like the web** (no upload route). Port the web's image shrinking (long edge 1568, JPEG 0.8), keep the web's limits, and refuse any send frame over a safe cap below OkHttp's 16 MiB queue limit, before sending (a logged divergence for large non-image sets). Sheet rows = the web's four (no camera or text-clipboard rows). Offline sends with attachments are refused; the in-memory queue is T13.3's. The oversized-frame socket drop on main is fixed in T7.4 | The maker found the brief assumed an upload route the web doesn't have, and OkHttp closes the socket on a >16 MiB frame | claude-main (coordinator default) |
| 2026-09-30 | Releases: the coordinator publishes each APK release itself (no drafts for the owner to publish), and every new release bumps the minor version (0.7.8 -> 0.8.0 -> 0.9.0; versionCode +1). Replaces "publishing is the owner's call" | Owner instruction (chat) | owner |
| 2026-10-01 | ta-ylh decodes the v133-v135 fields but the app keeps ADVERTISING protocol 132: the server serves a native hello only when floor <= client <= its own version (lib/hello-compat.mjs), and the owner's deployed server was last known at 133, so advertising 135 would lock the app out until the owner deploys main. Raising the advertised version is owner-gated (server deployed at >= the new version) | Server hello-compat rule; deploy state in RESUME HERE | claude-main (coordinator default, owner to confirm) |
| 2026-10-01 | **PARITY_BASE -> tether `887c222` (v137) for the protocol corpora and the Parity Matrix; the UI base stays split.** Reducer+helpers and wire re-exported there (each twice, byte-identical) and vendored; v136 `queuedAt` and #222 (over-bound requested permissions are not grantable) ported; the wire types model 137 while the advertised hello stays 132 (owner gate unchanged). Tokens not vendored: the fresh export retires 21 material-layer tokens the app still references (ta-ccu). Screens not regenerated: the exporter fails after the top-bar redesign (ta-lx3, tether-side). #229 UI -> ta-ceo, #231 plan names -> ta-ebc | PLAN §9 catch-up loop (T15.8); a split base is recorded honestly rather than claimed whole | executor-T15.8 (coordinator to confirm) |
| 2026-10-01 | T15.7 service links: "Open" goes to the web's own console-side link (`proxyAuthUrl` = relative `/api/worktree/open?session&script`, owner-grade, mints a 60 s single-use token then redirects to the service host), pinned to exactly that path with session = this session and script = this row, resolved only against the paired origin, confirm first, opened in the EXTERNAL browser with no app credential (the browser's own console sign-in authenticates; otherwise a 401 page). "On this machine" (`proxyPath`, loopback consoles only) omitted in the app. Service address stays plain text (T9.1) | The maker found the brief's 'capability URL on a service host' premise wrong; this is the web's behaviour with tight pinning | claude-main (coordinator default, owner to confirm) |

## Session log (append-only)

| UTC | Agent / model | Tasks | Outcome | Next |
|---|---|---|---|---|
| 2026-09-26 | planning session / Opus 5.5 | — | Wrote PLAN.md + TRACKER.md (uncommitted in `~/git/tether-android/docs/parity/`) | T0.1 |
| 2026-09-26 21:15 | claude-main / Opus 5.5 | T0.1, S0.1, T0.2 | T0.1 DONE (`63eca63`, gate green 131 tests); S0.1 DONE (worktree branch); T0.2 BLOCKED (KVM) | verifier for T0.1/S0.1; T0.5, T0.3 |
| 2026-09-26 21:40 | claude-main / Opus 5.5 (+ executor-S0.5, verifier) | T0.1, S0.1, T0.5, S0.5, S0.2, S0.3 | T0.1+S0.1 VERIFIED (verifier caught AIDASH_ env names → fixed `fed0ab1`); T0.5 DONE (291-row matrix, 6-skin correction); S0.5 DONE (`356b456`); S0.2/S0.3 running | T0.3; verify T0.5/S0.5 |
| 2026-09-26 23:00 | claude-main / Opus 5.5 (+ executors S0.2, S0.3, T0.3; verifier) | S0.2, S0.3, T0.3, T0.4 | S0.2 VERIFIED (`157b87d`); S0.3 BLOCKED (cgroup guard; script `fdecbe9` untested live); T0.3 DONE (`cbd6042`) — verifier found a real duplicate-attach race, fixed `76b0431` + deterministic regression test; T0.4 DONE (CI green); T0.5 re-verified after a matrix regex fix | owner decisions: KVM, isolated-server scope |
| 2026-09-27 08:55 | claude-main / Opus 5.5 (+ executors, verifiers, security reviewers) | T1.1–T1.4, T2.1, T2.1D, T2.2, T2.3, T3.1–T3.3, T3.5, S0.3, S0.4, S1.1 | v0.6.0 published; VERIFIED+merged T1.1, T1.2, T1.4 (security-blocked once, fixed), T2.1 (70/70), T2.1D, T2.2 (807/807), T3.1, T3.2; S1.1 PR tether#197 open; two outages (5 agents each) recovered by resuming agents in context | owner: merge/deploy tether#197; next: T1.3/T2.3 verify → merge, S0.4 → S0.6, T3.3 → T3.4 |
| 2026-09-27 09:25 | claude-main / Opus 5.5 | S1.1 deploy, T1.3, T2.3, T3.2, S0.4, T3.3, T3.5 | Owner: merge + redeploy tether → tether#197 merged `bde3cfa` (8 port tests 44/44 first), idle-waiting safe-restart watcher launched; T1.3 (real OkHttp onOpen race fixed), T2.3, T3.2 VERIFIED+merged; S0.4/T3.3/T3.5 checkpointed + paused for the restart | read deploy log; resume S0.4, T3.3, T3.5, T1.5 |
| 2026-09-27 19:15 | claude-main / Opus 5.5 (new coordinator session) | T5.1, T6.1 | Took over after the previous coordinator hit its 5-hour usage limit; RESUME HERE rewritten from disk (T12.1 r3 merged `98bf8ac`; T5.1 DONE unverified; T6.1 blocked P1) | T5.1 verifier; T6.1 allowlist fix → re-verify → merge |
| 2026-09-28 04:55 | claude-main / Opus 5.5 | T6.1, T4.5, T5.1, ta-cdh, T11.1, ta-ouu; T4.4, T13.1, ta-u2n, T5.2 | Overnight: 6 verified + merged (T6.1 `19abfbc`, T4.5 `61f868a`, T5.1 `a41178a`, ta-cdh `fc3f820`, T11.1 `d39d5d5`, ta-ouu `80e9651`). All 4 lanes interrupted by an account usage limit ~04:00; T13.1 checkpointed as WIP `bf42bc4`, all 4 agents resumed in context | verify T4.4, T13.1, ta-u2n, T5.2 as they finish |
| 2026-09-28 18:00 | claude-main / Opus 5.5 | T6.2, T7.1, T13.1, ta-g04, ta-s4r | Daytime: CI red→green (ta-g04), T7.1, T13.1, ta-s4r (P0 sign-in), T6.2 merged; drafts 0.7.0-0.7.2 built; three usage-limit interruptions and one server restart recovered by WIP checkpoints | build 0.7.3 draft; owner sign-in result; next frontier |
| 2026-09-29 16:30 | claude-main / Opus 5.5 (new coordinator session) | T13.2, T6.6 | Took over mid-flight (old session stopped by the owner; both makers had checkpointed). T13.2: verify + security review, 2 fix rounds (interrupt/End locked unless Live, origin-bound kill, fail-closed freshness), merged ff-only `9219a2d`, VERIFIED. T6.6: verify REFUTED on unrecorded matrix rows (coordinator brief error), security follow-ups fixed in r2; r3 rebases onto T13.2 and applies the live-copy lock to every notice key. Follow-ups filed: ta-tgs; notes on T6.7 (interrupt turn binding), T8.5 (limit-card take-over key). | (superseded below) |
| 2026-09-29 19:00 | claude-main / Opus 5.5 | T6.6, ta-cyy | T6.6 merged ff-only `ccdb1a8`, VERIFIED (+23 matrix rows; M.ev.warning with --force past the verified blocker). Owner deployed tether #209/#212/#216 (server protocol 133, floor 129; ta-ylh filed). Owner decisions logged (push = BYO-Firebase + Firebase-free design ta-nrq; gateway-agnostic sign-in ta-31i; service pages see tether#220). tether#220 PR in progress (ta-cyy). | build 0.7.6 draft; beads scrub (ta-l8k); T6.5/T6.7 |
| 2026-09-29 21:00 | claude-main / Opus 5.5 | ta-cyy, T6.7 | tether PR #224 (service pages, see tether#220) merged `81aa352` after 3 rounds (verify CONFIRMED; security follow-ups fixed) - **not deployed** (owner); deferred item tether#225. Android follow-up ta-96z (accept the __Host- cookie name). T6.7 verified-pending: verify CONFIRMED, security follow-ups in its fix round. | owner deploy of #224; T6.7 merge; beads scrub |
| 2026-09-29 21:30 | claude-main / Opus 5.5 | T6.7 | T6.7 merged ff-only `b65fb48`, VERIFIED (+7 matrix rows, --force past the verified blocker). 3 rounds: verify CONFIRMED r1/r2; security follow-ups (toast X under touch slop, lock every interrupt while cancelling, re-arm on toast shrink) fixed in r3 and checked by the coordinator. RESUME HERE rewritten (neutral security wording). | beads scrub (ta-l8k); T6.5/T7.3 |
| 2026-09-29 22:00 | claude-main / Opus 5.5 | ta-l8k | Beads scrub done in a quiet window: store rebuilt from a neutralised export (461/461 ids, 37 field changes across 17 ids, 0 unexpected; ready/blocked sets identical), history flattened, `refs/dolt/data` deleted and re-pushed as a 2-commit orphan; a fresh clone of the published ref verifies. Event timeline dropped (owner-accepted); offline backup kept outside the repo. Merged worktrees removed. | T6.5 / T7.3 / ta-96z next |
| 2026-09-30 | claude-main / Opus 5.5 | ta-96z | The app accepts the `__Host-tether_session` name (stored with the credential, sent back under it; legacy blobs unchanged, nobody signed out; `__Host-` accepted only with its prefix rules). Merged ff-only, VERIFIED (verify CONFIRMED, security PASS-WITH-FOLLOWUPS, hardening checked by the coordinator). 0.7.7 draft (code 24) built and checked. Follow-up ta-1yx. | T6.5 in progress |
| 2026-09-30 | claude-main / Opus 5.5 | T6.5, T7.3, ta-blf | T6.5 conversation timeline merged ff-only `3dd391c`, VERIFIED (+2 rows; verify REFUTED r1 on the needle while reading a long reply, CONFIRMED r2; off-screen needle divergence recorded in the README). Filed ta-blf (transcript renders bidi controls raw). T7.3 in progress. | T7.3 review; ta-blf |
| 2026-09-30 | claude-main / Opus 5.5 | ta-blf | Transcript text spoofing fix merged ff-only, VERIFIED after 5 rounds: risky characters drawn as visible tokens (code: every bidi/invisible char; prose: all explicit bidi formatting chars, marks only beside real RTL letters, ALM only beside Arabic); copy carries the visible form with a notice and an explicit Copy raw; shared rules moved to core/designsystem. Follow-ups ta-28i (other surfaces), ta-fz3 (links). | T7.3 r3 (terminal rule) |
| 2026-09-30 | claude-main / Opus 5.5 | T7.3 | T7.3 slash commands / `!` run + background commands / command output / @mentions merged ff-only `07ba88f`, VERIFIED (+8 rows). 3 rounds: command output shown through the shared terminal rule (nothing hidden), delegated sends origin- and lock-bound, durable mentions origin-keyed. Follow-ups ta-4dm, ta-10h. No agents running. | 0.7.8 draft; ta-28i, ta-fz3, T7.4 |
| 2026-09-30 | claude-main / Opus 5.5 (new coordinator session) | ta-28i, ta-fz3 | Took over from disk (0.7.8 draft already checked: code 25, same cert). Dispatched two makers in parallel worktrees: ta-28i (text rules on the remaining surfaces, file preview first) and ta-fz3 (link safety). | verifier + security review for each |
| 2026-09-30 | claude-main / Opus 5.5 | catch-up | Owner published v0.7.8. Owner reports gaps (screenshots not visible, telemetry thin, no Overview page); the web is 729 commits past PARITY_BASE `7d65611` (v128 -> 133), so a PLAN §9 Catch-up delta analysis was started (read-only). | new matrix rows + tasks from the delta |
| 2026-09-30 | claude-main / Opus 5.5 | ta-28i, ta-fz3 | Host restart interrupted the four reviewers (verifier + security review for ta-28i and ta-fz3). Checked worktrees: maker branches clean at 1602901 / ce416a4, nothing to checkpoint; all four resumed in context. | verdicts -> fix rounds or merge |
| 2026-09-30 | claude-main / Opus 5.5 | ta-28i, ta-fz3, T6.8, T15.1 | Review r1 done for both (see RESUME HERE); r2 fix rounds sent to the makers. Follow-ups filed: ta-w58, ta-4mm, ta-j8r, ta-3xc, ta-td0 (from ta-28i), ta-08y (from ta-fz3). Started T6.8 and T15.1->T15.2. | r2 re-review; T9.1, T7.4 |
| 2026-09-30 | claude-main / Opus 5.5 | ta-28i, ta-fz3, T6.8, T15.1 | Second host restart. ta-fz3 r2 landed (`3ba3d25`), re-verify was in flight; ta-28i r2 had 5 commits (to `3a680f8`), gate pending; T6.8 uncommitted tests checkpointed as a WIP commit; T15.1 at 2 commits. All four resumed in context. | r2 verdicts; security re-check of ta-fz3 r2 |
| 2026-09-30 | claude-main / Opus 5.5 | ta-28i, ta-fz3, T6.8 | ta-28i r2 gate run by the coordinator: green on `3a680f8` (4532 tests); re-verify + security re-check running. ta-fz3 r2: security PASS-WITH-FOLLOW-UPS, verify REFUTED on streamed links (settle timer) -> small r3 (settle on content change, table-cell links ask, no `/` in mailto local parts). T6.8: no app bug; owner confirmed the gateway -> ta-p5l (README exempt list). | ta-28i verdicts; ta-fz3 r3; T6.8 tile copy |
| 2026-09-30 | claude-main / Opus 5.5 | ta-28i, ta-fz3, T6.8, ta-p5l, T9.1, T7.4 | ta-28i merged ff-only `27fd169` VERIFIED; ta-fz3 merged ff-only `7569d54` VERIFIED (3 rounds). T6.8 done (gateway tile copy + wire replay tests), verifier running with ta-p5l. Started T9.1 and T7.4 (T7.4 re-scoped: inline attachments). Follow-up ta-zih. | T6.8/ta-p5l verdicts; Overview, T9.1, T7.4 reviews |
| 2026-09-30 | claude-main / Opus 5.5 | T15.1, T15.2 | Overview feed client + screen built (branch T15.1 @ `edefad3`, gates green; sidebar entry, phone + tablet shells; T15.3 host/usage slot left). Mark-seen clarified against the web plan (display never marks seen; opening follows the normal rule). Verifier running; a security review (text rules, send gating) follows. | Overview verdict; T9.1, T7.4 |
| 2026-09-30 21:30 | claude-main / Opus 5.5 (resumed session) | T15.1, T9.1, T7.4, ta-p5l, ta-8lg | Previous coordinator stopped on its 5-hour limit mid-turn; its Overview and T9.1 verifiers died before verdicts (relaunched from their scratch state). main gate re-run green on `8f6f15a` (4604). tether #230 squash-merged `cac6a5a`, ta-p5l VERIFIED. T7.4 gate failure traced to a pre-existing stop() bug -> ta-8lg (own branch). | Overview + T9.1 verdicts; ta-8lg; T7.4 rebase + gate |
| 2026-09-30 23:00 | claude-main / Opus 5.5 | ta-8lg, T9.1, T15.1, T15.2, T7.4, T15.3, T15.4 | ta-8lg merged `1293f0b` VERIFIED (coordinator red/green recheck). T9.1 merged `e2ae7c8` VERIFIED (follow-up ta-dl4). Overview: T15.1 CONFIRMED, T15.2 REFUTED on text rules -> r2 (+ security L1 caps) -> coordinator recheck -> merged `1c5af15`, both VERIFIED (follow-ups ta-fhl, ta-hoo). T7.4: security FAIL (clipboard file:// URIs), verify REFUTED (staged-set race), gate red from a T7.4 test leak -> r2 running (follow-up ta-ec1). Started T15.3 and T15.4. Also filed ta-exi. | T7.4 r2 recheck; T15.3/T15.4 reviews |
| 2026-09-30 23:40 | claude-main / Opus 5.5 | release | Owner rule: the coordinator publishes releases, minor bump each. v0.8.0 (code 26, `6c5e474`) built by android-release.yml (draft), APK checked (same cert `4f8c22de...b74d` as 0.7.8, versionName 0.8.0 / code 26), asset renamed tether-0.8.0.apk, PUBLISHED as Latest with highlights. | T7.4 r2; T15.3/T15.4 |
| 2026-10-01 00:55 | claude-main / Opus 5.5 | T7.4, T15.3, T15.4, ta-ylh, ta-vmg, ta-x9c | All five overnight lanes stopped (usage limit 00:50 + connection errors); resumed each from its transcript with its uncommitted work intact (T7.4 r3 WIP, T15.3 r2 WIP rebased, ta-ylh WIP in 12 files, T15.4 r2 re-verify mid-mutants, flakes not started); makers told to checkpoint-commit first. T15.3 verify CONFIRMED earlier (r2 = origin check + JSON content type). | T7.4 r3; T15.3 r2; T15.4 verdict; ta-ylh; flakes |
| 2026-10-01 01:30 | claude-main / Opus 5.5 | T15.4, T15.5 | T15.4 r2 re-verify CONFIRMED -> merged ff-only `cee8ea80`, VERIFIED (gate 4741; follow-up ta-2qv). Started T15.5 (Studio-only appearance; rebases over T7.4 at the end). | T7.4 r3; T15.3 r2; ta-ylh; flakes; T15.5 |
| 2026-10-01 02:20 | claude-main / Opus 5.5 | ta-ylh, T15.6 | ta-ylh: coordinator ran the gate (maker hit its command limit): green 4757 on `f1b3b639`; verify CONFIRMED -> merged ff-only `f1b3b639`, VERIFIED (advertised hello stays 132). Started T15.6 (queue labels, hidden count, createdVia; + the two ta-ylh lows). | T7.4 r3; T15.3 r2; T15.5; T15.6; flakes |
| 2026-10-01 03:00 | claude-main / Opus 5.5 | T7.4, T15.3, T15.5, T15.6, ta-vmg, ta-x9c | T7.4 r3 CONFIRMED + security PASS-WITH-FOLLOW-UPS -> merged `cfa7ce90` VERIFIED (+2 matrix rows). T15.6 CONFIRMED -> merged `71abae3c` VERIFIED (follow-up ta-e7j). T15.3 r2 rechecked -> merged with the flake fixes (ta-vmg: v2 compose rule + off-main write check; ta-x9c: await the typed projection) at `4c925b3a`, all VERIFIED (gate 4968). T15.5 verify REFUTED (Machine window background) -> r2 done -> final rebase. | T15.5 final gate + recheck; v0.9.0 |
| 2026-10-01 02:50 | claude-main / Opus 5.5 | T15.5, T15.7, T15.8 | T15.5 r2 rechecked (golden audit: exactly the 18 expected Studio re-records, 898 retired removed, none left) -> merged ff-only `fe71970d`, VERIFIED (gate 4133). T15.7 re-briefed after the maker found proxyAuthUrl is the console-side /api/worktree/open link (decision logged). Started T15.8 re-baseline at tether `887c222` (protocol 137: #229, #231 since the snapshot). | T15.7; T15.8; v0.9.0 in the morning |
| 2026-10-01 03:10 | claude-main / Opus 5.5 | T15.7, T15.8, ta-exi, ta-dl4 | T15.7 verify CONFIRMED + security PASS-WITH-FOLLOW-UPS -> merged ff-only `fdffd947`, VERIFIED (follow-up ta-e9l; owner to try Open on a device: the browser may not send the console cookie on an app-initiated navigation, fails closed). T15.8 running. Started follow-ups ta-exi (settings read/collector failures never crash the app) and ta-dl4 (inspector parity leftovers). | T15.8; ta-exi; ta-dl4; v0.9.0 in the morning |
| 2026-10-01 03:20 | executor-T15.8 / Opus 5.5 | T15.8 | Protocol corpora + matrix re-based at tether 887c222 (v137); v136 queuedAt + #222 ported; tokens/screens kept (ta-ccu, ta-lx3); filed ta-ceo, ta-ebc; gate green on the branch, not merged | coordinator: verify, merge |
| 2026-10-01 06:10 | claude-main / Opus 5.5 | ta-dl4, T15.8, ta-exi | Second usage-limit stop (03:50-05:50): three verifiers resumed from their scratch state. ta-dl4 CONFIRMED and T15.8 CONFIRMED (corpora regenerated byte-identical from a clean 887c222; #222/queuedAt matched events.mjs over a 186-step node differential; security PASS-WITH-FOLLOW-UPS ta-bh7) -> stacked, one gate green 4178 -> merged `20c3afcf`, both VERIFIED. Untracked a committed .pyc. ta-exi verifying. | ta-exi; v0.9.0 |
| 2026-10-01 06:30 | claude-main / Opus 5.5 | ta-exi, release | ta-exi CONFIRMED -> merged ff-only `fe5e86dd`, VERIFIED (gate 4188; follow-up ta-bm1). v0.9.0 (code 27, `fe5e86dd`) built by android-release.yml, APK checked (same cert `4f8c22de...b74d`, 0.9.0/27), asset renamed tether-0.9.0.apk, PUBLISHED as Latest with highlights. Nothing in flight. | owner review; ta-ceo, ta-ebc, ta-ccu, ta-lx3 |
