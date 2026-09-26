# Tether Android — Native Parity TRACKER

> Live state of the program described in [`PLAN.md`](./PLAN.md).
> **Rules: PLAN.md §3 (Tracker Protocol).** Claim → checkpoint → evidence → commit + push.
> This file on `main` of `TheMrClaus/tether-android` is canonical.

## Header

| Field | Value |
|---|---|
| Program status | NOT STARTED |
| Current phase | Phase 0 — Bootstrap, baseline, parity matrix |
| PARITY_BASE (tether SHA) | `7d65611` (PROTOCOL_VERSION 128) |
| App version on `main` | 0.5.1 (code 15), speaks protocol 40 |
| Android repo | `~/git/tether-android` (`TheMrClaus/tether-android`, `main`) |
| Server repo | `~/git/tether` (`TheMrClaus/tether`, server tasks on `android-parity/<task>` branches → PR) |
| Last updated (UTC) | 2026-09-26 (plan written; nothing executed yet) |
| Last agent | planning session (Opus 5.5) |

> **Machine layer:** the live state of this program is the repo's **beads store** (`.beads/`,
> prefix `ta`) — rows, claims, dependencies and evidence. Use `bd` (see
> [`BEADS.md`](./BEADS.md)); this file is the human digest, refreshed from the store. Verified
> 2026-09-26: `bd ready` lists exactly `T0.1`–`T0.5` + `S0.1`.

## ▶ RESUME HERE

**Next action:** Start **T0.1** — claim it first (`bd update T0.1 --claim`, §3.2 / `BEADS.md`).
PLAN.md + TRACKER.md + the beads machine layer are committed and pushed; the store is seeded and
mirrors this board.

**In-flight state:** none. (When working, write here: branch name, uncommitted files, the
last command run, and the exact next step.)

---

## Task board

Status: `TODO` · `IN-PROGRESS` · `BLOCKED` · `DONE` · `VERIFIED` · `DROPPED`

### Phase 0 — Bootstrap, baseline, parity matrix
| ID | Task | Status | Claimed by (agent @ UTC) | Evidence | Notes |
|---|---|---|---|---|---|
| T0.1 | Build `main` as-is; record breakage; mark `specs/*.md` as v40-historical, `aidash`→`tether` | TODO | | | |
| T0.2 | Toolchain: emulator pkg + system image, AVDs `tether-parity` + `tether-tablet`, 0.5.1 installs | TODO | | | |
| T0.3 | Modularize into D7 modules (move, keep tests green) | TODO | | | |
| T0.4 | CI workflow: build + lint + unit + Roborazzi + conformance | TODO | | | |
| T0.5 | Build the full Parity Matrix (below) at PARITY_BASE; add missing T-tasks; settle "decide in T0.5" items | TODO | | | |
| S0.1 | tether branch `android-parity/S0` | TODO | | | |
| S0.2 | `scripts/export-parity-corpus.mjs` (reducer + pure helpers) | TODO | | | |
| S0.3 | `scripts/capture-wire-corpus.mjs` (all frame types, fake engine) | TODO | | | |
| S0.4 | `scripts/parity-seed.mjs` + `scripts/parity-screens.mjs` (web reference screenshots) | TODO | | | |
| S0.5 | `scripts/export-design-tokens.mjs` (4 themes → JSON) | TODO | | | |
| S0.6 | PR S0 scripts to tether (no PROTOCOL bump) | TODO | | | |

### Phase 1 — Protocol v128, connection, auth, compatibility
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| S1.1 | Server native compatibility window (`client`, `nativeProtocolFloor`, bump, CLAUDE.md rule) — PR | TODO | | | |
| T1.1 | Kotlin types for all v128 messages/events, tolerant decoder, WireConformanceTest green | TODO | | | |
| T1.2 | Connection manager (ready/hello/attach afterSeq/reset/bounded snapshots/ping/reconnect/lifecycle/4001/compat banner) | TODO | | | |
| T1.3 | Durable send (pending-input semantics, process-death safe, no auto-retry) | TODO | | | |
| T1.4 | Auth: password, pairing, logout, expiry, Keystore-encrypted credentials | TODO | | | |
| T1.5 | Multi-host node registry awareness (v109) | TODO | | | |

### Phase 2 — Reducer at v128
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T2.1 | Reducer v40→v128; ReducerConformanceTest 100% | TODO | | | |
| T2.2 | Pure helpers (format, model-picker, ordering, seen) ; HelperConformanceTest 100% | TODO | | | |
| T2.3 | Client-state parity with use-tether.ts (seq dedupe, cursor, drafts, prefs) | TODO | | | |

### Phase 3 — Design system
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T3.1 | Generated tokens, 4 themes + system, system bars | TODO | | | |
| T3.2 | Typography (Manrope, JetBrains Mono) | TODO | | | |
| T3.3 | Primitives (keys, wells, seams, pills, select, sheets, expandable, spinners, ping, haptics, reduced motion) | TODO | | | |
| T3.4 | Debug Component Gallery + screenshot tests | TODO | | | |
| T3.5 | Icons, provider logos, adaptive app icon | TODO | | | |

### Phase 4 — App shell & layout
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T4.1 | Phone shell (web mobile layout) | TODO | | | |
| T4.2 | Expanded shell (web desktop layout, resizable panels) | TODO | | | |
| T4.3 | Statusline, dial, context gauge, telemetry readings, wrap-up badge | TODO | | | |
| T4.4 | Navigation + deep links | TODO | | | |
| T4.5 | Log dialog | TODO | | | |

### Phase 5 — Sidebar & sessions
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T5.1 | Session list: groups, pinned workspaces, synced order, pin/rename/archive/kill, seen/unread | TODO | | | |
| T5.2 | History/resume picker | TODO | | | |
| T5.3 | Global + in-session search | TODO | | | |
| T5.4 | Away digests (if on web) | TODO | | | |

### Phase 6 — Chat view
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T6.1 | Turns/blocks, streaming, thinking, markdown, code, paging, perf | TODO | | | |
| T6.2 | Tool cards, rich renderers, diffs, git changes, tool/spawned media | TODO | | | |
| T6.3 | Approvals, questions, permission denials/paths | TODO | | | |
| T6.4 | Subagents, spawned runs, background tasks/commands, todo bar, turn activity | TODO | | | |
| T6.5 | Conversation timeline refresh | TODO | | | |
| T6.6 | Notices/dismiss, rate limit, model fallback, handoff/read-only, MCP health | TODO | | | |
| T6.7 | Interrupt/kill/errors; selection & copy | TODO | | | |

### Phase 7 — Composer
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T7.1 | Draft composer, persisted drafts, queue UI | TODO | | | |
| T7.2 | Model/Effort/Mode row, fast mode, model browser, codex/opencode controls | TODO | | | |
| T7.3 | Slash commands, run/background command, mentions | TODO | | | |
| T7.4 | Attach sheet (camera/photos/files/clipboard) + limits | TODO | | | |

### Phase 8 — New session, workspaces, worktrees, GitHub
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T8.1 | Studio welcome + new-session catalog + providers | TODO | | | |
| T8.2 | Folder picker, workspaces | TODO | | | |
| T8.3 | Worktree modes/scripts/logs/diff/services/open, repository panel, change request | TODO | | | |
| T8.4 | GitHub work dialog | TODO | | | |
| T8.5 | Metadata draft panel, handoff brief + claim | TODO | | | |
| T8.6 | Browser pane (native frame stream) — scope per T0.5 | TODO | | | |

### Phase 9 — Inspector, usage, scheduled actions
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T9.1 | Inspector + telemetry | TODO | | | |
| T9.2 | Usage page, accounts, reset credits/grants, deepseek peak | TODO | | | |
| T9.3 | Scheduled actions | TODO | | | |

### Phase 10 — Settings & first run
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T10.1 | Settings dialog, all tabs | TODO | | | |
| T10.2 | Session settings sheet | TODO | | | |
| T10.3 | Nodes settings | TODO | | | |
| T10.4 | Paired devices + sign-in security (device-token view) | TODO | | | |
| S10.1 | Server `/.well-known/assetlinks.json` — PR | TODO | | | |
| T10.5 | Passkeys via Credential Manager | TODO | | | |
| T10.6 | `/setup` wizard parity (scope per T0.5) | TODO | | | |

### Phase 11 — Files
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T11.1 | Workspace file browser (all /api/files ops) | TODO | | | |
| T11.2 | Android share target → session | TODO | | | |

### Phase 12 — Notifications
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T12.1 | FCM refresh, channels, deep link, Android 13+ permission | TODO | | | |
| T12.2 | Web-push trigger/settings parity | TODO | | | |

### Phase 13 — Proper sync
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T13.0 | `SYNC_DESIGN.md` + plan-verifier review | TODO | | | |
| T13.1 | Room journal mirror; UI reads Room; delta attach | TODO | | | |
| T13.2 | Offline mode + stale indicators | TODO | | | |
| T13.3 | Outbox (dedupe-safe, no turn auto-retry, stale approvals dropped) | TODO | | | |
| S13.1 | Server content-free FCM "advanced" hint + sessions-changed cursor — PR | TODO | | | |
| T13.4 | FCM hint → WorkManager catch-up | TODO | | | |
| T13.5 | Cache policy, eviction, migrations | TODO | | | |
| T13.6 | Conflict rules doc + tests | TODO | | | |

### Phase 14 — Hardening & release 1.0.0
| ID | Task | Status | Claimed by | Evidence | Notes |
|---|---|---|---|---|---|
| T14.1 | Performance + Baseline Profiles | TODO | | | |
| T14.2 | Accessibility pass | TODO | | | |
| T14.3 | Security review | TODO | | | |
| T14.4 | Full parity audit (fresh verifier) | TODO | | | |
| T14.5 | Release 1.0.0 (dry_run → draft; owner publishes) | TODO | | | |

---

## Parity Matrix (filled by T0.5 — the definition of "done")

Seed rows below; T0.5 replaces/extends them with one row per web component, page, HTTP
route, ClientMessage, ServerMessage and AgentEvent type at PARITY_BASE.

| Web artifact | Behavior (1 line) | Android status | Task |
|---|---|---|---|
| `components/dashboard.tsx` | Shell, layout switching | PARTIAL (MainShell) | T4.1/T4.2 |
| `components/session-sidebar.tsx` | Session list | PARTIAL (SessionDrawer, v40) | T5.1 |
| `components/chat-view.tsx` | Transcript | PARTIAL (ChatScreen, v40) | T6.* |
| `components/draft-composer.tsx` | Composer | PARTIAL (Composer, v40) | T7.1 |
| `components/conversation-timeline.tsx` | Timeline rail | PARTIAL | T6.5 |
| `components/subagent-runs.tsx` | Subagent runs | PARTIAL | T6.4 |
| `components/inspector.tsx` | Inspector | MISSING | T9.1 |
| `components/settings-dialog.tsx` | Settings | MISSING | T10.1 |
| `components/workspace-file-browser.tsx` | File browser | MISSING | T11.1 |
| `components/scheduled-actions-view.tsx` | Scheduled actions | MISSING | T9.3 |
| `components/usage-dashboard.tsx` | Usage | MISSING | T9.2 |
| `components/global-search.tsx` | Global search | MISSING | T5.3 |
| … (T0.5 completes) | | | |

---

## Blockers

| Since (UTC) | Task | Blocker | Needed from | Status |
|---|---|---|---|---|
| | | | | |

## Decision log

| Date | Decision | Reason | By |
|---|---|---|---|
| 2026-09-26 | Adopt PLAN.md D1–D13 defaults; PARITY_BASE = tether `7d65611` (v128) | Initial plan | planning session |

## Session log (append-only)

| UTC | Agent / model | Tasks | Outcome | Next |
|---|---|---|---|---|
| 2026-09-26 | planning session / Opus 5.5 | — | Wrote PLAN.md + TRACKER.md (uncommitted in `~/git/tether-android/docs/parity/`) | T0.1 |
