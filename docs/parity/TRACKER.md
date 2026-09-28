# Tether Android — Native Parity TRACKER

> Live state of the program described in [`PLAN.md`](./PLAN.md).
> **Rules: PLAN.md §3 (Tracker Protocol).** Claim → checkpoint → evidence → commit + push.
> This file on `main` of `TheMrClaus/tether-android` is canonical.

## Header

| Field | Value |
|---|---|
| Program status | IN PROGRESS |
| Current phase | Phases 0-3 CLOSED; Phase 4 in progress (T4.1-T4.3 merged); Phases 5/6/12 landing |
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

**Resume point (2026-09-28 ~18:05 CEST, taken over from the clean handover):** `main` @ `4a41c8a`.
**Merged + verified since the overnight run began (17):** T6.1 chat/markdown, T4.5 log dialog, T5.1 sidebar, ta-cdh, T11.1 file
browser, ta-ouu push security, T5.2 resume, ta-u2n, T4.4 deep links, T5.3 search, ta-g04 (file-browser launch race - fixed the red
CI), T7.1 composer, T13.1 encrypted journal mirror (3 verify + 2 security rounds), ta-s4r sign-in (P0), **T6.2 tool cards** (6 rounds;
security clear: per-card tile/diff/step budgets, bounded parsing, deep-frame rewrite, media hash/magic-byte checks).
**CI:** green on `4a41c8a` (T6.2 included).
**Drafts (unpublished, same cert as 0.6.0):** 0.7.0 (`b60b0d4`), 0.7.1 (`dc9200d`, +composer +mirror), 0.7.2 (`391de70`, +sign-in fix),
**0.7.3 (code 20, `4a41c8a`, +T6.2 tool cards)** - built 2026-09-28 18:03 CEST; apksigner v2 cert SHA-256 `4f8c22de...b74d` = 0.6.0's; versionCode 20.
**In flight (coordinator takeover 2026-09-28 ~18:00):** T6.3 (security-executor, `parity/T6.3-approvals`), ta-hra (security-executor,
`parity/ta-hra-wipe-residuals`), S10.1 assetlinks (executor, tether PR `android-parity/S10.1`) - lanes under the `-wt` worktree dirs.
**ta-s4r (owner sign-in):** owner sees the app fallback "That password is not correct." (a 401 without Tether JSON); the most likely
cause is an SSO/auth gateway in front of `/api/auth/login` (Tether's README advises exactly that). 0.7.2 now names such a refusal
and points to pairing. **Waiting on the owner's result with 0.7.2.**
**Owner queue (report, not act):** push is blocked on Firebase provisioning (production has no FCM config at all: create the
Firebase project + register `com.tether.app`, service-account key, set all six `TETHER_FCM_*` values in a root-owned
EnvironmentFile, rebuild + restart, restrict the API key). tether#204 is merged. Publishing any draft is the owner's call.
Optional tether private-history scrub.
**Next frontier (`bd ready`):** T6.3 approvals/questions, T6.4 subagents/background, T6.5-T6.7, T7.2-T7.4, T8.x, T10.x settings,
T13.2+ offline/outbox, plus the filed follow-ups (ta-hra wipe residuals before T14.3, ta-js0 Conscrypt classifier, ta-epo mirror
flake, ta-dhu/ta-cqf T6.2 lows, ta-854 login lows, ta-705, ta-w6z, ta-55u, ta-0lv, ta-3pf, ta-6z4, ta-hcj, ta-5wx).
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
| T6.3 | Approvals, questions, permission denials/paths | IN-PROGRESS | TheMrClaus @ 2026-09-28 16:02 |  |  |
| T6.4 | Subagents, spawned runs, background tasks/commands, todo bar, turn activity | TODO |  |  |  |
| T6.5 | Conversation timeline refresh | TODO |  |  | From T2.2: helpers.ConversationStoryPoints.storyPointsFromSession(state, promptMax=220, replyMax=260) is the faithful port; the timeline sh… |
| T6.6 | Notices/dismiss, rate limit, model fallback, handoff/read-only, MCP health | TODO |  |  |  |
| T6.7 | Interrupt/kill/errors; selection & copy | TODO |  |  |  |

### Phase 7 — Composer
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T7.1 | Draft composer, persisted drafts, queue UI | VERIFIED | TheMrClaus @ 2026-09-28 04:59 |  |  |
| T7.2 | Model/Effort/Mode row, fast mode, model browser, codex/opencode controls | TODO |  |  |  |
| T7.3 | Slash commands, run/background command, mentions | TODO |  |  |  |
| T7.4 | Attach sheet (camera/photos/files/clipboard) + limits | TODO |  |  |  |

### Phase 8 — New session, workspaces, worktrees, GitHub
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T8.1 | Studio welcome + new-session catalog + providers | TODO |  |  | Owns M.cmp.draft-composer, M.lib.hooks-use-draft-composer-ts and M.lib.hooks-use-keyboard-inset-ts (reassigned from T7.1: the web's draft-c… |
| T8.2 | Folder picker, workspaces | TODO |  |  |  |
| T8.3 | Worktree modes/scripts/logs/diff/services/open, repository panel, change request | TODO |  |  |  |
| T8.4 | GitHub work dialog | TODO |  |  |  |
| T8.5 | Metadata draft panel, handoff brief + claim | TODO |  |  |  |
| T8.6 | Browser pane (native frame stream) — scope per T0.5 | TODO |  |  |  |

### Phase 9 — Inspector, usage, scheduled actions
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T9.1 | Inspector + telemetry | TODO |  |  |  |
| T9.2 | Usage page, accounts, reset credits/grants, deepseek peak | TODO |  |  | From the T4.1 verifier: the web workspace header shows the DeepSeek peak-hours badge (workspace-header.tsx:111). The phone shell (T4.1) has… |
| T9.3 | Scheduled actions | TODO |  |  |  |

### Phase 10 — Settings & first run
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T10.1 | Settings dialog, all tabs | TODO |  |  |  |
| T10.2 | Session settings sheet | TODO |  |  |  |
| T10.3 | Nodes settings | TODO |  |  |  |
| T10.4 | Paired devices + sign-in security (device-token view) | TODO |  |  | OWNER DECISION 2026-09-27 (ta-xax): a paired phone is fully trusted; only owner-grade actions (device management, passkeys, claude-accounts… |
| S10.1 | Server `/.well-known/assetlinks.json` — PR | IN-PROGRESS | TheMrClaus @ 2026-09-28 16:02 |  |  |
| T10.5 | Passkeys via Credential Manager | TODO |  |  |  |
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
| T13.2 | Offline mode + stale indicators | TODO |  |  | design refinement (SYNC_DESIGN §4): freshness Live/CatchingUp/Saved/NotDownloaded, icon + text, no violet or red. Saved-copy run badges rea… |
| T13.3 | Outbox (dedupe-safe, no turn auto-retry, stale approvals dropped) | TODO |  |  | design refinement r2: ExactlyOnceProperty includes restore with tries>0 while the mirror is at head. The 'with S13.1-C' QueueRemovedElsewhe… |
| S13.1 | Server content-free FCM "advanced" hint + sessions-changed cursor — PR | VERIFIED | claude-main @ 2026-09-27 09:38 |  |  |
| T13.4 | FCM hint → WorkManager catch-up | TODO |  |  | Gate update: S13.1 deployed (v130: AgentSession.lastSeq + {kind:sync} FCM hint + syncHints opt-in). Remaining gates: T13.1 (and T12.1 prefe… |
| T13.5 | Cache policy, eviction, migrations | TODO |  |  | From the T13.1 security re-review (R2, Low): the interim per-origin caps count only sync_state rows/bytes - turn_detail (no count/byte cap)… |
| T13.6 | Conflict rules doc + tests | TODO |  |  | design refinement r2: the debug probe strips removedQueueIds until T13.3b lands, then demands exact equality; test both modes. |

### Phase 14 — Hardening & release 1.0.0
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T14.1 | Performance + Baseline Profiles | TODO |  |  | DECISION: FoldAdapterStressTest p99<2ms bar is now OPT-IN (-Pparity.perfAssert=true) — wall-clock assertions flake under load in the defaul… |
| T14.2 | Accessibility pass | TODO |  |  | From T3.2: CSS text-transform:uppercase keeps the ORIGINAL words as the accessible name; native uppercase labels must set contentDescriptio… |
| T14.3 | Security review | TODO |  |  | From security review of T0.6 (508198c), none release-blocking: (1) LOW/UX: on Android 17, a LAN server the classifier misses (IPv6 global, … |
| T14.4 | Full parity audit (fresh verifier) | TODO |  |  | From the T4.3 r2 verifier (fidelity detail): UsageTrack's colour transition uses Compose's default tween easing; the web uses CSS 'ease' (c… |
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
