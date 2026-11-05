# Tether Android — Native Parity TRACKER (digest)

> **Authority: the beads store.** Task state, claims, dependencies and evidence live in `.beads/`
> (prefix `ta`): use `bd ready`, `bd show <id>`, `bd list`. **This file is a human digest and may lag**
> the store and the code (it was last refreshed in full on 2026-10-18; where it disagrees with `bd`
> or `git`, those win). Rules: [`PLAN.md`](./PLAN.md) §3 Tracker Protocol; machine layer:
> [`BEADS.md`](./BEADS.md).
>
> **Full history** — the complete pre-condensation tracker (every row's claim/evidence/notes, all resume
> points, the whole Decision log and Session log, ~187 KB) — is archived verbatim at
> [`archive/TRACKER-2026-11-05-before-ta-y3dw.md`](./archive/TRACKER-2026-11-05-before-ta-y3dw.md).
> Pointers below like `archive L258` are line numbers in that file; section names: `## Header`,
> `## ▶ RESUME HERE`, `## Task board`, `## Parity Matrix`, `## Blockers`, `## Decision log`, `## Session log`.

## Header

| Field | Value |
|---|---|
| Program status | IN PROGRESS |
| Current phase | Phases 0-3 CLOSED; Phase 4+ shipped through the 1.5.x releases; see `bd` for live rows |
| PARITY_BASE (tether SHA) | Protocol corpora at `19c56e25` (PROTOCOL 152, floor 129); tokens and web screenshots at `887c222`; Parity Matrix re-baselined at `29537e0` (PROTOCOL 143, T14.4). Detail: `parity-corpus/VENDORED.md`, archive L13 |
| App version | Published: **1.5.6 (code 61)** at `0fc21a72`; this branch prepares **1.5.7 (code 62)**. The archived resume point predates these releases. |
| Android repo | `~/git/tether-android` (`TheMrClaus/tether-android`, `main`) |
| Server repo | `~/git/tether` (`TheMrClaus/tether`, server tasks on `android-parity/<task>` branches -> PR); S* work only in standalone clones/worktrees under `~/git/tether-wt/`, never the production checkout |
| Digest condensed | 2026-11-05 (previous tracker header: last updated 2026-10-17 by claude-main) |

## ▶ RESUME HERE

**Current work (2026-11-05): ta-y3dw (new-session sheet, branch `parity/ta-y3dw-new-session`) — IN PROGRESS, pending gates. NOT DONE.**

- Current implementation and screenshot baselines: `13308ea5`; full release gate running on that commit.
- Focused draft tests: **23/0/0** on `15a7cb60`, including pre-IME dispatch through the dialog host.
- Native key checks: PASS (10 checks).
- Pending: the full gate and the release.
- Server docs PR #285 merged as `3913483e`.
- Live state: `bd show ta-y3dw`. This digest carried no ta-y3dw row before 2026-11-05.

**Last full resume point in the archive (2026-10-18 morning, v1.5.4; kept verbatim; later releases are in `bd`/git):**

**Resume point (2026-10-18 morning, clean):** `main` @ `235e168e`+tracker. **v1.5.4 published** (code 59, run 39108016084, tag at `235e168e`; served APK verified 59/1.5.4, signer 4f8c22de… unchanged, sha c611fbd0…) = ta-d3tj.10 R1 (reducer+helpers re-vendor at 19c56e25: sessionErrors/errorAt/endedAt folds + lastError clear, task reopen/dedupe, withdrawn/held/peer queue fields, pi-dialog notice replace, spawned-run summary, model catalog +5 rows, draft-form toolApproval reset rules; fixes the 1.5.3 'repeated identical error not shown' gap, E153), ta-hkk7.7 (one pixel-snapped spine column + the constant-14 stub golden), ta-hkk7.8 slice 3b (the tablet rail = the phone list inside the web SB rail chrome; coloured tiles, no N chip on coarse, 48 dp floor, no tablet swipe, rail 320 at ≥ 1440 dp; tablet header chevron-first). Gates on `235e168e`: 9262/0/5 cached; uncached green except flake SetupAccountModelTest (ta-hkch/ta-sw5n, re-run 172/0). Every lane: refute 0 blocking (W52 and W53 after one fix round each); design-director APPROVE W52, W53b (W53 1st REJECT fixed). R8 pre-release smoke PASS 7/7. Next: H154 served-APK smoke + VERIFY-154; ta-d3tj .2 ‖ .4 ‖ .5 ‖ .6, then .3, then .7 (hello bump, LAST); small: ta-mpkx, ta-d3tj.11, ta-5a1w (keyboard shortcuts), ta-coik.78, flakes.

Older resume points (v1.2.4 … v1.5.3, 2026-10-10 … 10-18) and the per-wave "Merged …" notes are superseded: archive L28-L39 and L43.

**Upstream:** tether #262 (ta-8noa) merged, not deployed. tether #264 filed: the same four owner features for desktop + mobile web, plus the server change for task-notification rows (ta-xwzv blocked on it).

**Follow-ups:** ta-9irz parked (the web probes auth before every connect), ta-lg5l (early panel drag overwrites stored prefs), ta-60fo, ta-mxi9, ta-vobj, ta-vjuj, ta-0t1g (short-extension file links), ta-w7nt (crash-record stack wrap on phones); ta-wj3l TalkBack on a device; ta-ml74 needs owner. ta-qm8b: the owner's v1.2.0-only crash cause is not proven; the crash record is the way to get a stack.

**Releases** (cert SHA-256 `4f8c22de...b74d`): v1.5.4 (code 59, `235e168e`) is the last one this digest recorded; the chain back to v1.1.2 (code 45) with per-release contents is at archive L42. Owner rule 2026-10-09: a named third-party product never appears in this repo (ForbiddenNameScanTest guards it).

**Next (`bd ready`, as of 2026-10-18):** flaky-under-load tests (MainShellEndSessionTest class, ToolMediaVideoBehaviourTest, MainShellOverviewTest, SetupWizardBehaviourTest, ta-dx0p, ta-ez0c, ta-sw5n); W3-C follow-ups (B3 rotation stall P2, O1-O3, ta-x5m8); approval-card follow-ups; ta-smj8 / ta-7njx; ta-izzw (needs a non-destructive migration plan), ta-oe6u, ta-002k, ta-nrq, P13.

**Lessons:** seed every golden synchronously and run the full gate twice for UI slices; behaviour tests use the v2 compose rule and waitUntil; a verifier probe must assert and have a positive control; rebase docs-only and check `git diff <gated> <head> -- . ':!docs'` is empty before merging on an earlier gate; stale test-result XML hides a compile failure: check the log and timestamps.

**Machine notes for this host:** `local.properties` needs `sdk.dir=<home>/Android/Sdk` (gitignored). JVM network tools (sdkmanager) need the sandbox proxy CA: build a temp truststore from JDK `cacerts` + `~/.config/jean-claude/ca/bundle.pem` and pass `-Djavax.net.ssl.trustStore=…` via `JAVA_OPTS`, plus `--proxy=http --proxy_host=127.0.0.1 --proxy_port=8000`.

## Blockers (archive `## Blockers`, L250)

| Since (UTC) | Task | Blocker | Status |
|---|---|---|---|
| 2026-09-27 | S1.1 -> T1.x release | tether#197 (server compat window) | RESOLVED: merged `bde3cfa` + deployed (v129, floor 129). Rule kept: no Android release speaking a protocol may ship before the server speaking it is deployed |
| 2026-09-26 | S0.3 (-> S0.4) | An isolated server cannot boot inside `tether.service`'s cgroup (`findLiveUnitMainPeer()` guard, issue #155) | RESOLVED 2026-09-27: owner OK'd `systemd-run --user --scope` (separate port/state dir) |
| 2026-09-26 | T0.2 | Emulator needs `kvm` group membership (👤 owner) | RESOLVED 2026-09-27: owner chose skip emulators; T0.2 deferred (BLOCKED-deferred) |

**Live 👤 owner gates / blocked rows:** T0.2 (BLOCKED, deferred by owner), T12.3 (BLOCKED, deferred: owner opt-in notification approve/deny actions), ta-xwzv (blocked on tether #264), ta-wj3l (needs a physical device), ta-ml74 (needs owner). Authoritative list: `bd list -s blocked`.

## Task board (ID · status · title). Per-row claim/evidence/notes: archive `## Task board`, L55-L216

Status values: TODO, IN-PROGRESS, BLOCKED, DONE, VERIFIED, DROPPED.

**Phase 0 — Bootstrap, baseline, parity matrix:** all VERIFIED except T0.2 BLOCKED (deferred, owner 2026-09-27).
T0.1 build main/mark specs historical · T0.2 emulator + AVDs (BLOCKED, deferred) · T0.3 modularize into D7 modules · T0.4 CI workflow · T0.5 full Parity Matrix · T0.6 dependency refresh · S0.1 tether branch `android-parity/S0` · S0.2 export-parity-corpus.mjs · S0.3 capture-wire-corpus.mjs · S0.4 parity-seed + parity-screens · S0.5 export-design-tokens.mjs · S0.6 PR S0 scripts.

**Phase 1 — Protocol v128, connection, auth** (all VERIFIED): S1.1 server native compat window · T1.1 Kotlin wire types + tolerant decoder · T1.2 connection manager · T1.3 durable send · T1.4 auth (password, pairing, Keystore) · T1.5 multi-host node registry.

**Phase 2 — Reducer:** T2.1 reducer v40->v128 (VERIFIED) · **T2.1D reducer cutover: typed views + legacy adapter (TODO)** · T2.2 pure helpers (VERIFIED) · T2.3 client-state parity (VERIFIED).

**Phase 3 — Design system** (all VERIFIED): T3.1 tokens/6 skins · T3.2 typography · T3.3 primitives · T3.4 component gallery + screenshot tests · T3.5 icons/logos/adaptive icon.

**Phase 4 — App shell** (all VERIFIED): T4.1 phone shell · T4.2 expanded shell · T4.3 statusline/dial/gauge · T4.4 navigation + deep links (singleTop) · T4.5 log dialog.

**Phase 5 — Sidebar & sessions** (all VERIFIED): T5.1 session list · T5.2 history/resume picker · T5.3 global + in-session search · T5.4 away digests.

**Phase 6 — Chat view** (all VERIFIED): T6.1 turns/blocks/streaming · T6.2 tool cards/diffs/media · T6.3 approvals/questions · T6.4 subagents/background tasks · T6.5 timeline refresh · T6.6 notices/handoff/MCP health · T6.7 interrupt/kill/errors, selection · T6.8 tool screenshots in conversation.

**Phase 7 — Composer** (all VERIFIED): T7.1 draft composer + queue UI · T7.2 model/effort/mode row · T7.3 slash commands/mentions · T7.4 attach sheet.

**Phase 8 — New session, workspaces, worktrees, GitHub:** T8.1 Studio welcome + new-session catalog (TODO; six slices ta-8cv, ta-abm, ta-2uq, ta-xki, ta-23f, ta-3e7 merged/verified 2026-10-02, open only for the remainder noted in the archive L142; **ta-y3dw is the current new-session sheet work**) · T8.2 folder picker (VERIFIED) · **T8.3 worktree modes/scripts/logs/diff/services (TODO)** · T8.4 GitHub work dialog (VERIFIED) · T8.5 metadata draft panel/handoff brief (VERIFIED) · T8.6 browser pane (VERIFIED).

**Phase 9** (all VERIFIED): T9.1 inspector + telemetry · T9.2 usage page · T9.3 scheduled actions.

**Phase 10 — Settings & first run** (all VERIFIED): T10.1 settings dialog · T10.2 session settings sheet · T10.3 nodes settings · T10.4 paired devices · S10.1 server assetlinks.json · T10.5 passkeys · T10.6 /setup wizard.

**Phase 11 — Files** (all VERIFIED): T11.1 file browser · T11.2 Android share target.

**Phase 12 — Notifications:** T12.1 FCM (VERIFIED) · T12.2 web-push parity (VERIFIED) · T12.3 owner opt-in approve/deny in notification (BLOCKED, deferred).

**Phase 13 — Proper sync:** T13.0 SYNC_DESIGN, T13.1 Room journal mirror, T13.2 offline mode, S13.1 server FCM hint (all VERIFIED) · **T13.3 outbox, T13.4 FCM hint -> WorkManager, T13.5 cache policy, T13.6 conflict rules (all TODO: OWNER DECISION 2026-10-06 — defer to after 1.0.0; no web counterpart, no user-visible bug; archive L186-L190)**.

**Phase 14 — Hardening & release 1.0.0** (all VERIFIED): T14.1 performance + baseline profiles · T14.2 accessibility · T14.3 security review · T14.4 full parity audit · T14.5 release 1.0.0 (owner publishes).

**Phase 15 — Catch-up to web protocol v135:** T15.1 Overview feed client, T15.2 Overview screen, T15.3 host + daily usage panels, T15.4 top-bar navigation, T15.5 Studio-only appearance + theme migration, T15.6 queue origin labels + hidden session count, T15.7 worktree service links, T15.8 re-baseline (all VERIFIED) · **TODO as of 2026-10-18 (check `bd`): ta-ylh speak protocol v135 (wire), ta-ceo v136 deferred-message waits (UI), ta-ebc v137 Claude plan names in Settings, ta-ccu token re-baseline to 887c222, ta-lx3 upstream parity-screens exporter** (the parity-corpus section above records ta-ccu/ta-lx3 as since done; `bd` wins).

## Parity Matrix

Full matrix: [`MATRIX.md`](./MATRIX.md), data in [`matrix.json`](./matrix.json), generated by `tools/parity/build-matrix.py`. Every row is also a bead (`bd list -l matrix --all`); "are we done" = every non-dropped matrix bead `verified`. T0.5 baseline counts and the T15.8 re-baseline notes: archive L220-L246.

## Owner decisions (condensed; full log: archive `## Decision log`, L258-L502)

- 2026-09-27 owner: skip emulators for now; T0.2 deferred (JVM/Robolectric behaviour checks) — archive L63, L256.
- 2026-09-28 owner: overnight autonomy for the coordinator (decisions logged, merges of verified work, report in the morning); production deploys, releases and history rewrites stay owner calls — archive L262.
- 2026-10-06 owner: T13.3-T13.6 deferred to after 1.0.0 — archive L186-L190.
- 2026-10-09 owner rule: a named third-party product never appears in this repo (ForbiddenNameScanTest).
- 2026-10-17 owner: "release everything in 1.5.2" (combined releases); 2026-10-15 "release one with both combined" (1.3.0 + W38 -> 1.5.0) — archive L29-L31.
- 2026-10-31 owner: Jev retired from the fleet policy (git `77e3e523`).
- Standing (CLAUDE.md, binding): the app is exactly as capable and trusted as the web console; never add app-only restrictions; never touch the production Tether service.
- Everything else (coordinator decisions on T4.4 singleTop, T4.5 sign-in generation, T7.1 Escape revert, T13.1 mirror wipe, etc.) is in the archive Decision log by date and task ID.

## Session log

Append-only coordinator session log (UTC | agent | tasks | outcome | next): archive `## Session log`, L504-L578. Not reproduced here.

## Task ID coverage

Every task ID on the board above is kept. IDs that appear only in the archive's resume points, Decision log and Session log (hundreds of `ta-*` beads, e.g. ta-d3tj.x, ta-hkk7.x, ta-coik.x, ta-hmzy, ta-kfrh, ta-q2t6) are not re-listed: use `bd show <id>`, or `grep -n '<id>' docs/parity/archive/TRACKER-2026-11-05-before-ta-y3dw.md`.
