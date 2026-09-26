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
| App version on `main` | 0.5.1 (code 15), speaks protocol 40 |
| Android repo | `~/git/tether-android` (`TheMrClaus/tether-android`, `main`) |
| Server repo | `~/git/tether` (`TheMrClaus/tether`, server tasks on `android-parity/<task>` branches → PR) |
| Last updated (UTC) | 2026-09-26 23:10 |
| Last agent | claude-main (Opus 5.5, Tether session) |

> **Machine layer:** the live state of this program is the repo's **beads store** (`.beads/`,
> prefix `ta`) — rows, claims, dependencies and evidence. Use `bd` (see
> [`BEADS.md`](./BEADS.md)); this file is the human digest, refreshed from the store. Verified
> 2026-09-26: `bd ready` lists exactly `T0.1`–`T0.5` + `S0.1`.

## ▶ RESUME HERE

**Next action:** ⏸ **Waiting on the owner.** VERIFIED: T0.1, T0.3, T0.4, T0.5, S0.1, S0.2, S0.5.
Every remaining Phase 0 task is blocked on an owner decision — **T0.2** (KVM group for the emulator)
and **S0.3** (isolated test server vs. the production cgroup guard), which also gates **S0.4**
(web reference screenshots) and **S0.6** (the tether PR). Phase 1+ is gated on Phase 0 in the store.
When unblocked: S0.3 run → S0.4 → S0.6 (merge `android-parity/S0.{2,3,4,5}` into
`android-parity/S0`, keep the anchored `/parity-corpus/` ignore, PR) and T0.2 (boot AVDs, install APK).

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
| T0.2 | Toolchain: emulator pkg + system image, AVDs `tether-parity` + `tether-tablet`, 0.5.1 installs | BLOCKED | claude-main @ 2026-09-26 21:05 |  | BLOCKED: emulator refuses to boot — 'x86_64 emulation requires hardware acceleration; user doesn't have permissions to use KVM' (/dev/kvm i… |
| T0.3 | Modularize into D7 modules (move, keep tests green) | VERIFIED | executor-T0.3 @ 2026-09-26 21:22 | `cbd6042`, `76b0431` · `bd show` |  |
| T0.4 | CI workflow: build + lint + unit + Roborazzi + conformance | VERIFIED | claude-main @ 2026-09-26 21:35 | `1865da1` · `bd show` |  |
| T0.5 | Build the full Parity Matrix (below) at PARITY_BASE; add missing T-tasks; settle "decide in T0.5" items | VERIFIED | claude-main @ 2026-09-26 21:09 | `bd show` |  |
| S0.1 | tether branch `android-parity/S0` | VERIFIED | claude-main @ 2026-09-26 21:05 | `bd show` |  |
| S0.2 | `scripts/export-parity-corpus.mjs` (reducer + pure helpers) | VERIFIED | executor-S0.2 @ 2026-09-26 21:12 |  |  |
| S0.3 | `scripts/capture-wire-corpus.mjs` (all frame types, fake engine) | BLOCKED | executor-S0.3 @ 2026-09-26 21:12 |  | done: fdecbe9 on android-parity/S0.3 (script + tests/capture-wire-corpus.test.mjs 5 pass/2 skip + .gitignore parity-corpus/ .next-parity/; … |
| S0.4 | `scripts/parity-seed.mjs` + `scripts/parity-screens.mjs` (web reference screenshots) | TODO |  |  |  |
| S0.5 | `scripts/export-design-tokens.mjs` (6 skins → JSON) | VERIFIED | executor-S0.5 @ 2026-09-26 21:12 |  |  |
| S0.6 | PR S0 scripts to tether (no PROTOCOL bump) | TODO |  |  | Merge note: S0.5 (356b456) adds unanchored 'parity-corpus/' to .gitignore, which would ALSO hide S0.2's scripts/parity-corpus/*.mjs — keep … |

### Phase 1 — Protocol v128, connection, auth, compatibility
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| S1.1 | Server native compatibility window (`client`, `nativeProtocolFloor`, bump, CLAUDE.md rule) — PR | TODO |  |  |  |
| T1.1 | Kotlin types for all v128 messages/events, tolerant decoder, WireConformanceTest green | TODO |  |  |  |
| T1.2 | Connection manager (ready/hello/attach afterSeq/reset/bounded snapshots/ping/reconnect/lifecycle/4001/compat banner) | TODO |  |  | From T0.3 verify: after 76b0431 a narrower pre-existing window remains — an attach() from another thread that lands just BEFORE onReady's r… |
| T1.3 | Durable send (pending-input semantics, process-death safe, no auto-retry) | TODO |  |  |  |
| T1.4 | Auth: password, pairing, logout, expiry, Keystore-encrypted credentials | TODO |  |  |  |
| T1.5 | Multi-host node registry awareness (v109) | TODO |  |  |  |

### Phase 2 — Reducer at v128
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T2.1 | Reducer v40→v128; ReducerConformanceTest 100% | TODO |  |  | From S0.2 verify: corpus-manifest tetherSha records the GENERATING commit (157b87d = scripts-only diff atop PARITY_BASE 7d65611). When vend… |
| T2.2 | Pure helpers (format, model-picker, ordering, seen) ; HelperConformanceTest 100% | TODO |  |  | From S0.2: sidebar unread/grouping (hasUnseenWork etc.) is inside components/session-sidebar.tsx, not in lib/, so it has no helper corpus t… |
| T2.3 | Client-state parity with use-tether.ts (seq dedupe, cursor, drafts, prefs) | TODO |  |  |  |

### Phase 3 — Design system
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T3.1 | Generated tokens, 6 skins (3 families × light/dark/system), system bars | TODO |  |  |  |
| T3.2 | Typography (Manrope, JetBrains Mono) | TODO |  |  |  |
| T3.3 | Primitives (keys, wells, seams, pills, select, sheets, expandable, spinners, ping, haptics, reduced motion) | TODO |  |  |  |
| T3.4 | Debug Component Gallery + screenshot tests | TODO |  |  |  |
| T3.5 | Icons, provider logos, adaptive app icon | TODO |  |  |  |

### Phase 4 — App shell & layout
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T4.1 | Phone shell (web mobile layout) | TODO |  |  |  |
| T4.2 | Expanded shell (web desktop layout, resizable panels) | TODO |  |  |  |
| T4.3 | Statusline, dial, context gauge, telemetry readings, wrap-up badge | TODO |  |  |  |
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
| T6.5 | Conversation timeline refresh | TODO |  |  | From T0.1: Android story-point limits PROMPT_MAX/REPLY_MAX = 270/320 (0.5.0.1 owner bump, wider bubble); web lib/conversation-story-points.… |
| T6.6 | Notices/dismiss, rate limit, model fallback, handoff/read-only, MCP health | TODO |  |  |  |
| T6.7 | Interrupt/kill/errors; selection & copy | TODO |  |  |  |

### Phase 7 — Composer
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T7.1 | Draft composer, persisted drafts, queue UI | TODO |  |  |  |
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
| T12.1 | FCM refresh, channels, deep link, Android 13+ permission | TODO |  |  |  |
| T12.2 | Web-push trigger/settings parity | TODO |  |  |  |
| T12.3 | (owner opt-in) Approve/deny actions in the notification | BLOCKED (deferred) |  |  |  |

### Phase 13 — Proper sync
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T13.0 | `SYNC_DESIGN.md` + plan-verifier review | TODO |  |  |  |
| T13.1 | Room journal mirror; UI reads Room; delta attach | TODO |  |  |  |
| T13.2 | Offline mode + stale indicators | TODO |  |  |  |
| T13.3 | Outbox (dedupe-safe, no turn auto-retry, stale approvals dropped) | TODO |  |  |  |
| S13.1 | Server content-free FCM "advanced" hint + sessions-changed cursor — PR | TODO |  |  |  |
| T13.4 | FCM hint → WorkManager catch-up | TODO |  |  |  |
| T13.5 | Cache policy, eviction, migrations | TODO |  |  |  |
| T13.6 | Conflict rules doc + tests | TODO |  |  |  |

### Phase 14 — Hardening & release 1.0.0
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T14.1 | Performance + Baseline Profiles | TODO |  |  |  |
| T14.2 | Accessibility pass | TODO |  |  |  |
| T14.3 | Security review | TODO |  |  |  |
| T14.4 | Full parity audit (fresh verifier) | TODO |  |  |  |
| T14.5 | Release 1.0.0 (dry_run → draft; owner publishes) | TODO |  |  | From T0.4: android-release.yml uses android-actions/setup-android@v3 with default packages, which include the retired 'tools' package -> sd… |

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
| 2026-09-26 21:55 | S0.3 (→ S0.4, and every DoD "isolated fake-engine server" run) | An isolated server can't boot from an agent session: agents run inside `tether.service`'s cgroup, and `server.mjs`'s `findLiveUnitMainPeer()` guard (issue #155 — a second in-cgroup server once process-group-killed prod) refuses to start. Escaping via `systemd-run --user --scope` would bypass that production-safety guard. Capture script + tests are committed (`fdecbe9`, never run live). | owner: either OK agents running isolated servers in their own transient scope (`systemd-run --user --scope …`, separate port/state dir), or run `TETHER_CAPTURE_WIRE_E2E=1 node --test tests/capture-wire-corpus.test.mjs` in `~/git/tether-wt/android-parity-S0.3` from a shell outside `tether.service` | OPEN |
| 2026-09-26 21:10 | T0.2 | Emulator can't boot: user `operator` is not in group `kvm` (`/dev/kvm` root:kvm 0660) → no hardware acceleration | owner: `sudo usermod -aG kvm operator` + re-login (or OK the agent to run it) | OPEN |

## Decision log

| Date | Decision | Reason | By |
|---|---|---|---|
| 2026-09-26 | Adopt PLAN.md D1–D13 defaults; PARITY_BASE = tether `7d65611` (v128) | Initial plan | planning session |
| 2026-09-26 | All tether-side (S*) work happens in git worktrees under `~/git/tether-wt/<branch>`, never by switching branches in `~/git/tether` | `tether.service` (production) runs with `WorkingDirectory=~/git/tether`; a checkout there changes what prod runs on restart | claude-main |
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
