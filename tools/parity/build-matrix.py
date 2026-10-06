#!/usr/bin/env python3
"""Build the Parity Matrix (T0.5) — one row per web component, page, HTTP route, hook/helper,
ClientMessage, ServerMessage and AgentEvent type at PARITY_BASE.

    python3 tools/parity/build-matrix.py --tether ~/git/tether-wt/android-parity-S0

Writes docs/parity/matrix.json (data) and docs/parity/MATRIX.md (human digest).
Protocol rows are EXTRACTED from lib/protocol.ts; everything else is classified by hand
below (client-facing artifacts only: server-only lib/ modules are not rows). Android status is
judged against the app at T0.1 (v0.5.1, protocol 40):
  MISSING  — no equivalent in the app
  PARTIAL  — an equivalent exists but predates v128 / differs from the web
  DONE     — at parity (none yet: nothing has been checked against the corpora)
  N/A      — deliberately out of scope for the native app (reason in behavior)

Re-baseline (PLAN §9, T15.8): a row already in matrix.json keeps its recorded status (the
baseline it entered at; progress lives in the row's bead). A NEW row takes its status from
NEW_ROW_STATUS (DONE when merged work already delivered it), else from the app's wire types.
An artifact the web removed moves to RETIRED (its bead is dropped with the reason).
"""
import argparse
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# ---- components: file -> (behavior, android status, task) --------------------------------
COMPONENTS = {
    "dashboard": ("App shell: layout switching phone/desktop, panels, dialogs, browser pane host", "PARTIAL", "T4.1"),
    "topbar": ("Top bar: brand, session title, connection state, menu entry points", "PARTIAL", "T4.1"),
    "workspace-header": ("Workspace header above the chat (cwd, branch, worktree, actions)", "MISSING", "T4.1"),
    "panel-resize-handle": ("Drag handles for sidebar/inspector widths, persisted", "MISSING", "T4.2"),
    "session-sidebar": ("Session list: workspaces, pins, order, unread, away digest, actions", "PARTIAL", "T5.1"),
    "inspector": ("Session details column/sheet: telemetry, context, git, services, MCP", "MISSING", "T9.1"),
    "session-statusline": ("Statusline under composer: model, effort, tokens, cost, wrap-up badge", "MISSING", "T4.3"),
    "session-dial": ("Session dial control (quick session state + actions)", "MISSING", "T4.3"),
    "telemetry-sheet": ("Mobile bottom sheet with telemetry readings", "MISSING", "T4.1"),
    "telemetry-readings": ("Token/cost/elapsed readings formatting and layout", "PARTIAL", "T4.3"),
    "context-gauge": ("Context-window usage gauge", "MISSING", "T4.3"),
    "chat-view": ("Transcript: turns, blocks, streaming, cards, notices, paging", "PARTIAL", "T6.1"),
    "markdown": ("Markdown renderer (GFM, code, copy)", "PARTIAL", "T6.1"),
    "chat-tool-render": ("Tool call cards: per-tool rendering, output, diffs", "PARTIAL", "T6.2"),
    "codex-rich-renderers": ("Codex-specific rich tool renderers", "MISSING", "T6.2"),
    "opencode-rich-renderers": ("OpenCode-specific rich tool renderers", "MISSING", "T6.2"),
    "expandable-block": ("Collapsible block primitive for long output", "MISSING", "T3.3"),
    "subagent-runs": ("Subagent run tabs/cards with nested transcript", "PARTIAL", "T6.4"),
    "conversation-timeline": ("Timeline rail with story points + scrub", "PARTIAL", "T6.5"),
    "turn-activity": ("Live turn activity line (spinner words, elapsed)", "PARTIAL", "T6.4"),
    "todo-bar": ("Todo/plan progress bar above composer", "MISSING", "T6.4"),
    "notice-dismiss-button": ("Dismiss control for dismissable notices (v119)", "MISSING", "T6.6"),
    "draft-composer": ("Composer: multiline, send/queue, drafts, attachments, controls row", "PARTIAL", "T7.1"),
    "attach-sheet": ("Attachment picker sheet (camera/photos/files/paste)", "MISSING", "T7.4"),
    "model-browser": ("Model browser popover with catalog, search, passthrough", "PARTIAL", "T7.2"),
    "codex-controls": ("Codex session controls (sandbox/approval presets, actions)", "MISSING", "T7.2"),
    "opencode-serve-controls": ("OpenCode serve controls", "MISSING", "T7.2"),
    "tether-select": ("Custom select primitive", "MISSING", "T3.3"),
    "metadata-draft-panel": ("AI-drafted title/metadata panel", "MISSING", "T8.5"),
    "studio-welcome": ("Empty-state / new session welcome with catalog", "MISSING", "T8.1"),
    "folder-picker-dialog": ("Folder picker (browse, create folder)", "PARTIAL", "T8.2"),
    "settings-dialog": ("Settings: all tabs (appearance, providers, ACP, advanced, server, accounts)", "MISSING", "T10.1"),
    "session-settings-sheet": ("Per-session settings sheet", "MISSING", "T10.2"),
    "nodes-settings": ("Multi-host node registry settings", "MISSING", "T10.3"),
    "paired-devices": ("Paired devices list/pair/revoke", "MISSING", "T10.4"),
    "sign-in-security": ("Sign-in & security: passkeys, sessions", "MISSING", "T10.4"),
    "git-changes-card": ("Git changes card with per-file diffs", "MISSING", "T6.2"),
    "repository-panel": ("Repository panel: branch, changes, change request", "MISSING", "T8.3"),
    "worktree-services-card": ("Worktree services (ports, open in browser)", "MISSING", "T8.3"),
    "github-work-dialog": ("GitHub issues/PRs picker + device login", "MISSING", "T8.4"),
    "mcp-health-card": ("MCP server health card", "MISSING", "T6.6"),
    "usage-dashboard": ("Usage dashboard (/usage)", "MISSING", "T9.2"),
    "usage-accounts-dialog": ("Usage accounts dialog / account identity", "MISSING", "T9.2"),
    "codex-reset-credit-dialog": ("Codex reset-credit consume dialog", "MISSING", "T9.2"),
    "claude-reset-grant-dialog": ("Claude reset-grant claim dialog (v126)", "MISSING", "T9.2"),
    "deepseek-peak": ("DeepSeek peak-hours indicator", "MISSING", "T9.2"),
    "global-search": ("Global search across sessions (v71)", "MISSING", "T5.3"),
    "workspace-file-browser": ("Workspace file browser over /api/files", "MISSING", "T11.1"),
    "browser-pane": ("In-console browser pane: frames as images over /ws-browser; full-screen sheet on phones", "MISSING", "T8.6"),
    "archive-stale-dialog": ("Bulk archive of long-idle sessions: preview then bounded batches (#244, v142)", "MISSING", "ta-06yt"),
    "setup-commands": ("Repo-controlled commands for approval: numbered, hidden characters made visible (v143)", "MISSING", "ta-m7ef"),
    "log-dialog": ("Server log dialog (log messages)", "MISSING", "T4.5"),
    "scheduled-actions-view": ("Scheduled actions list/create/control (v87)", "MISSING", "T9.3"),
    "provider-logo": ("Provider logos", "MISSING", "T3.5"),
    "login/studio-login": ("Default sign-in (Studio): password + pairing + passkey", "MISSING", "T1.4"),
    "login/retro-login": ("Retro sign-in (per-browser opt-in variant)", "MISSING", "T1.4"),
    "landing/landing-page": ("Public marketing landing (/web) — not part of the console", "N/A", "—"),
    "landing/demo-console": ("Landing-page demo console — not part of the console", "N/A", "—"),
    # T15.8 re-baseline (tether 887c222): the Overview (v131/v132) and Studio-only theme sync.
    "overview/overview": ("Overview: every session at a glance, counts, filters, attention first (opt-in feed)", "MISSING", "T15.2"),
    "overview/overview-card": ("Overview session card: status text + shape, current-state age, excerpt", "MISSING", "T15.2"),
    "overview/overview-host": ("Overview host resources (polled /api/overview/host while visible, stale label)", "MISSING", "T15.3"),
    "overview/overview-panels": ("Overview panels: needs your attention (pending requests), recent activity", "MISSING", "T15.2"),
    "theme-sync": ("Applies the Studio skin for the stored mode (light/dark/system) and follows the OS", "MISSING", "T15.5"),
}

# Artifacts the web removed since the row was added: artifact -> reason (bead dropped with it).
RETIRED = {
    "components/login/instrument-login.tsx": "removed from the web at the T15.8 re-baseline (tether 887c222): Studio-only appearance; Studio's sign-in is the only one (T15.5)",
}

PAGES = {
    "/": ("Dashboard (console)", "PARTIAL", "T4.1"),
    "/login": ("Sign-in: Studio default or the Retro opt-in (Studio-only since T15.5)", "PARTIAL", "T1.4"),
    "/setup": ("First-run wizard (separate setup-server mode, unauthenticated)", "MISSING", "T10.6"),
    "/usage": ("Usage page", "MISSING", "T9.2"),
    "/web": ("Marketing landing page — not the console", "N/A", "—"),
    "app/layout.tsx boot script": ("Pre-paint theme resolution mode→Studio skin (studio / studio-dark)", "PARTIAL", "T3.1"),
}

ROUTES = {
    "/healthz": ("Health + protocol version probe", "PARTIAL", "T1.2"),
    "/ws": ("Main WebSocket (Origin must match Host)", "PARTIAL", "T1.2"),
    "/ws-browser": ("Browser-pane frame/input stream", "MISSING", "T8.6"),
    "/api/auth/login": ("Password login → cookie", "PARTIAL", "T1.4"),
    "/api/auth/logout": ("Logout", "MISSING", "T1.4"),
    "/api/auth/session": ("Who am I / session probe", "PARTIAL", "T1.4"),
    "/api/auth/sessions": ("List/revoke sign-in sessions", "MISSING", "T10.4"),
    "/api/auth/passkey/login/*": ("Passkey login options/verify", "MISSING", "T10.5"),
    "/api/auth/passkeys (+/policy, /register/*)": ("Passkey management", "MISSING", "T10.4"),
    "/api/devices (list/delete)": ("Paired device list/revoke (403 for device tokens by design)", "MISSING", "T10.4"),
    "/api/devices/claim": ("Claim pairing code → device bearer token", "PARTIAL", "T1.4"),
    "/api/devices/pair, /api/devices/pairings": ("Create/list pairing codes", "MISSING", "T10.4"),
    "/api/push/fcm-config, /api/push/fcm-register": ("FCM relay config + token registration", "PARTIAL", "T12.1"),
    "/api/push/config, /api/push/subscriptions": ("Web Push (VAPID) — browser only; native uses FCM", "N/A", "T12.2"),
    "/api/stats": ("Server stats", "MISSING", "T9.1"),
    "/api/usage, /api/usage/accounts": ("Usage + account identity", "MISSING", "T9.2"),
    "/api/usage/claude-reset-grants/claim": ("Claim Claude reset grant (v126)", "MISSING", "T9.2"),
    "/api/codex/reset-credits/consume": ("Consume Codex reset credit", "MISSING", "T9.2"),
    "/api/claude-accounts (+/sync, /sync/run)": ("Claude accounts + sync", "MISSING", "T10.1"),
    "/api/files/* (list, mkdir, touch, rename, move, copy, upload, GET/HEAD/DELETE)": ("Workspace file ops", "MISSING", "T11.1"),
    "/api/github/* (issues, pull-requests, connection/*)": ("GitHub work + device login", "MISSING", "T8.4"),
    "/api/worktree/open": ("Open a worktree service URL (Custom Tab on Android)", "MISSING", "T8.3"),
    "/api/tool-media/": ("Tool media blobs (v94)", "MISSING", "T6.2"),
    "/api/setup/*": ("First-run wizard API (setup-server mode)", "MISSING", "T10.6"),
    "/api/overview/host": ("Overview host resources (CPU, memory, disk) sampled while viewed (T15.8)", "MISSING", "T15.3"),
    "/api/overview/usage": ("Overview daily token usage summary (T15.8)", "MISSING", "T15.3"),
    "/.well-known/assetlinks.json": ("Digital Asset Links for the Android app: verified App Links + this host's passkeys (S10.1, T15.8)", "MISSING", "T10.5"),
    "/api/control/v1": ("Control API — machine-facing plane, not for the app", "N/A", "—"),
}

HOOKS_HELPERS = {
    "hooks/use-tether.ts": ("Reference client: connect, seq dedupe, cursor, reconnect, visibility", "PARTIAL", "T2.3"),
    "hooks/use-preferences.ts": ("Preferences (tether.preferences.v1), theme family×mode, panel widths", "PARTIAL", "T2.3"),
    "hooks/use-draft-composer.ts": ("Per-session drafts (tether:draft:*)", "MISSING", "T7.1"),
    "hooks/use-keyboard-inset.ts": ("Soft-keyboard inset handling", "PARTIAL", "T7.1"),
    "hooks/use-menu-placement.ts": ("Popover placement", "MISSING", "T3.3"),
    "hooks/use-paired-devices.ts": ("Paired devices data", "MISSING", "T10.4"),
    "hooks/use-push-notifications.ts": ("Web push subscription (browser) — native equivalent is FCM", "PARTIAL", "T12.2"),
    "hooks/use-sign-in-security.ts": ("Passkeys/sessions data", "MISSING", "T10.4"),
    "hooks/use-browser.ts": ("Browser pane client", "MISSING", "T8.6"),
    "lib/format.ts": ("Formatters (tokens, cost, durations, paths)", "PARTIAL", "T2.2"),
    "lib/model-picker.mjs (+model-id, model-browser-view)": ("Model catalog/labels, looksLikeModelId, /model passthrough", "PARTIAL", "T2.2"),
    "lib/pending-input.mjs": ("Pending-input queue semantics + limits", "PARTIAL", "T1.3"),
    "lib/conversation-story-points.ts": ("Timeline story points (web limits 220/260)", "PARTIAL", "T6.5"),
    "lib/sidebar-order.mjs, sidebar-workspaces.mjs, session-order.mjs, session-seen.mjs": ("Sidebar grouping/order/seen fold", "PARTIAL", "T2.2"),
    "lib/panel-widths.mjs": ("Panel width parse/clamp", "MISSING", "T4.2"),
    "lib/elapsed.mjs, message-time.mjs, spinner-words.mjs": ("Elapsed/time/spinner-word helpers", "PARTIAL", "T2.2"),
    "lib/mode-cycle.mjs, codex-mode-presets.mjs, draft-preferences.mjs": ("Mode cycling, presets, applied defaults", "PARTIAL", "T2.2"),
    "lib/interrupt-notice.mjs, history-origin.mjs, pending-workspace.mjs": ("Interrupt notice copy, history origin, pending workspace", "MISSING", "T2.2"),
    "lib/claude-reset-grants-view.mjs, deepseek-peak.mjs": ("Usage view helpers", "MISSING", "T9.2"),
    "lib/attachment-draft.ts, draft-form.ts": ("Attachment/draft form model", "PARTIAL", "T7.4"),
    "app/globals.css + app/studio.css tokens": ("6 skin token maps + material layer", "PARTIAL", "T3.1"),
    # T15.8 re-baseline (tether 887c222)
    "lib/overview-client.mjs (+overview-model.mjs limits)": ("Overview feed fold (snapshot/delta) and its bounds (v131/v132)", "MISSING", "T15.1"),
    "components/overview/overview-format.ts": ("Overview display helpers (ages, counts; no clock reads)", "MISSING", "T15.2"),
    "lib/dashboard-view.mjs": ("Top-level views and URLs: Overview / Sessions / Scheduled, last view remembered", "MISSING", "T15.4"),
    "lib/theme-mode.mjs": ("Studio appearance: stored mode -> skin, the one shared resolver", "MISSING", "T15.5"),
    "lib/queued-message.mjs": ("Queued-message provenance: origin user/system, notice kinds, the operator's own drafts (v133)", "MISSING", "T15.6"),
    "lib/session-list-visibility.mjs": ("Creator rule: agent-made sessions kept off the list server-side; the app shows the hidden count (v135)", "MISSING", "T15.6"),
    "lib/queue-wait.mjs (+events.mjs runningToolIds, liveBackgroundTaskCount)": ("Deferred message: what it waits for, for how long, the choice after 60s; Stop's cost confirmation (v136, #229)", "MISSING", "ta-ceo"),
    "lib/claude-account-plan.mjs": ("Claude account plan by its popular name on /api/claude-accounts rows; label + organization only (v137, #231)", "MISSING", "ta-ebc"),
}

# Status of a row NEW at a re-baseline, when merged work already delivered it (evidence in the
# row's bead). Keyed by (kind, artifact).
NEW_ROW_STATUS = {
    ("component", "components/overview/overview.tsx"): "DONE",
    ("component", "components/overview/overview-card.tsx"): "DONE",
    ("component", "components/overview/overview-host.tsx"): "DONE",
    ("component", "components/overview/overview-panels.tsx"): "DONE",
    ("component", "components/theme-sync.tsx"): "DONE",
    ("route", "/api/overview/host"): "DONE",
    ("route", "/api/overview/usage"): "DONE",
    ("hook/helper", "lib/overview-client.mjs (+overview-model.mjs limits)"): "DONE",
    ("hook/helper", "components/overview/overview-format.ts"): "DONE",
    ("hook/helper", "lib/dashboard-view.mjs"): "DONE",
    ("hook/helper", "lib/theme-mode.mjs"): "DONE",
    ("hook/helper", "lib/queued-message.mjs"): "DONE",
    ("hook/helper", "lib/session-list-visibility.mjs"): "DONE",
    ("client-msg", "overview-subscribe"): "DONE",
    ("client-msg", "overview-unsubscribe"): "DONE",
    ("server-msg", "overview-snapshot"): "DONE",
    ("server-msg", "overview-delta"): "DONE",
    # re-baseline at tether 29537e0 (protocol 143); evidence in each task's bead
    # ta-06yt (#244): feature/sidebar/.../ArchiveStaleDialog.kt:75 (dialog)
    ("component", "components/archive-stale-dialog.tsx"): "DONE",
    # ta-m7ef (#241): core/designsystem/.../ConsentViews.kt:155 CommandList, core/data/.../HiddenCharacters.kt:12
    ("component", "components/setup-commands.tsx"): "DONE",
    # ta-m7ef: ClientMessage.kt:340 ArchiveInspect (wire 1081); RealTetherClient.kt:773 sendArchiveInspect
    ("client-msg", "archive-inspect"): "DONE",
    # ta-06yt: ClientMessage.kt:359 ArchiveStale (wire 1084); RealTetherClient.kt:5248 requestArchiveStale
    ("client-msg", "archive-stale"): "DONE",
    # ta-m7ef: ServerMessage.kt:304 ArchivePreview (decode 893); RealTetherClient.kt:3263 -> EndSessionFlow.kt:99
    ("server-msg", "archive-preview"): "DONE",
    # ta-06yt: ServerMessage.kt:315 ArchiveStaleResult (decode 903); SidebarSync.kt:90 -> ArchiveStaleDialog
    ("server-msg", "archive-stale-result"): "DONE",
}

# ---- protocol type -> task -----------------------------------------------------------------
CLIENT_TASK = {
    "hello": "T1.2", "ping": "T1.2", "attach": "T1.2", "node-add": "T10.3", "node-remove": "T10.3",
    "node-probe": "T10.3", "create": "T8.1", "resume": "T5.2", "discover": "T5.2",
    "worktree-inspect": "T8.3", "worktree-scripts": "T8.3", "worktree-script": "T8.3",
    "worktree-logs": "T8.3", "worktree-diff": "T8.3", "change-request": "T8.3", "git-diff-file": "T6.2",
    "mark-seen": "T5.1", "set-session-order": "T5.1", "pin": "T5.1", "rename": "T5.1", "archive": "T5.1",
    "kill": "T5.1", "search": "T5.3", "global-search": "T5.3", "browse": "T8.2", "create-folder": "T8.2",
    "fetch-turns": "T6.1", "send": "T1.3", "run-command": "T7.3", "background-command": "T7.3",
    "stop-command": "T6.4", "queue-add": "T7.1", "queue-edit": "T7.1", "queue-remove": "T7.1",
    "interrupt": "T6.7", "dismiss-notice": "T6.6", "rate-limit-resume": "T6.6",
    "set-auto-continue-on-limit": "T6.6", "scheduled-actions": "T9.3", "schedule-create": "T9.3",
    "schedule-update": "T9.3", "schedule-control": "T9.3", "approval": "T6.3", "question": "T6.3",
    "metadata-draft-request": "T8.5", "handoff-brief": "T8.5", "handoff": "T8.5",
    "providers-snapshot": "T8.1", "refresh-providers": "T8.1",
    "overview-subscribe": "T15.1", "overview-unsubscribe": "T15.1",
}
SERVER_TASK = {
    "ready": "T1.2", "pong": "T1.2", "version_mismatch": "T1.2", "snapshot": "T1.2", "log": "T4.5",
    "nodes": "T1.5", "node-result": "T10.3", "created": "T8.1", "providers": "T8.1",
    "providers-snapshot": "T8.1", "session": "T5.1", "session-order": "T5.1", "seen": "T5.1",
    "histories": "T5.2", "scheduled-actions": "T9.3", "search-results": "T5.3",
    "global-search-results": "T5.3", "directories": "T8.2", "event": "T2.1", "turns-detail": "T6.1",
    "approval": "T6.3", "approval_resolved": "T6.3", "interrupt_result": "T6.7", "error": "T6.7",
    "metadata-draft-result": "T8.5", "metadata-draft-error": "T8.5", "handoff-brief": "T8.5",
    "git-diff-file": "T6.2", "change-request": "T8.3",
    "overview-snapshot": "T15.1", "overview-delta": "T15.1",
}
EVENT_TASK_PREFIX = [
    ("native_session_id", "T7.3"), ("cli_", "T7.3"), ("command_output_", "T7.3"),
    ("background_command_", "T6.4"), ("spawned_run_", "T6.4"), ("turn_started", "T6.1"),
    ("user_message_accepted", "T6.1"), ("message_", "T6.1"), ("thinking_", "T6.1"),
    ("turn_end", "T6.1"), ("tool_", "T6.2"), ("diff_updated", "T6.2"), ("review_", "T6.2"),
    ("approval_", "T6.3"), ("question_", "T6.3"), ("permission_denied", "T6.3"),
    ("cancel", "T6.7"), ("process_exit", "T6.7"), ("error", "T6.7"), ("usage", "T4.3"),
    ("token_progress", "T4.3"), ("turn_activity", "T6.4"), ("fast_mode", "T7.2"),
    ("plan_updated", "T6.4"), ("todo_", "T6.4"), ("task_", "T6.4"), ("background_tasks_changed", "T6.4"),
    ("subagent_message", "T6.4"), ("queued_message_", "T7.1"),
]


def client_task(t):
    if t in CLIENT_TASK:
        return CLIENT_TASK[t]
    if t.startswith(("set-mode", "set-model", "set-reasoning", "set-fast", "session-controls", "codex-", "opencode-")):
        return "T7.2"
    return "T10.1"  # settings/providers/acp/detect-engines family


def server_task(t):
    if t in SERVER_TASK:
        return SERVER_TASK[t]
    if t.startswith(("session-controls", "codex-", "opencode-")):
        return "T7.2"
    if t.startswith("worktree-"):
        return "T8.3"
    return "T10.1"


def event_task(t):
    for p, task in EVENT_TASK_PREFIX:
        if t.startswith(p):
            return task
    return "T6.6"  # notices, limits, fallback, compaction, external advancement, interrupts…


def extract_unions(protocol_ts: str):
    src = protocol_ts.split("\n")
    aliases = {}
    for k, l in enumerate(src):
        m = re.match(r"export (?:type|interface) (\w+)\s*=?\s*\{?", l)
        if m:
            for j in range(k, min(k + 6, len(src))):
                t = re.search(r'\btype:\s*"([^"]+)"', src[j])
                if t:
                    aliases[m[1]] = t[1]
                    break

    def union(start):
        i = next(k for k, l in enumerate(src) if l.startswith(start))
        out, j, depth = [], i + 1, 0
        while j < len(src) and not src[j].startswith("export "):
            l = src[j]
            if depth == 0 and re.match(r"\s*\|", l):
                m = re.match(r"\s*\|\s*([A-Z]\w+)\s*;?\s*(//.*)?$", l)
                if m:
                    out.append(aliases.get(m[1], "@" + m[1]))
                else:
                    t = re.search(r'type:\s*"([^"]+)"', l)
                    if not t:
                        for q in range(j + 1, j + 5):
                            t = re.search(r'^\s*type:\s*"([^"]+)"', src[q])
                            if t:
                                break
                    if t:
                        out.append(t[1])
            s = re.sub(r'"[^"]*"', "", re.sub(r"//.*", "", l))
            depth += s.count("{") - s.count("}")
            j += 1
        return list(dict.fromkeys(out))

    return (union("export type ClientMessage ="), union("export type ServerMessage ="),
            union("export type AgentEvent ="))


def android_known():
    p = ROOT / "core/protocol/src/main/java/com/tether/app/protocol"
    client = set(re.findall(r'"type"\s*(?:,|to)\s*"([a-z_-]+)"', (p / "ClientMessage.kt").read_text()))
    server = when_labels((p / "ServerMessage.kt").read_text())
    events = set()
    for f in (ROOT / "core/reducer/src/main/java/com/tether/app/protocol/fold").glob("*.kt"):
        events |= when_labels(f.read_text())
    return client, server, events


def when_labels(src):
    """String labels of Kotlin `when` branches, incl. multi-label `"a", "b" ->` and a trailing `->`."""
    out = set()
    for m in re.finditer(r'^\s*((?:"[a-z_-]+"\s*,\s*)*"[a-z_-]+")\s*->', src, re.M):
        out |= set(re.findall(r'"([a-z_-]+)"', m.group(1)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tether", default=str(Path.home() / "git/tether-wt/android-parity-S0"))
    ap.add_argument("--beads", action="store_true", help="emit beads JSONL to stdout instead")
    a = ap.parse_args()
    if a.beads:
        return
    tether = Path(a.tether)
    sha = subprocess.run(["git", "-C", str(tether), "rev-parse", "--short", "HEAD"], capture_output=True,
                         text=True, check=True).stdout.strip()
    proto = (tether / "lib/protocol.ts").read_text()
    pv = re.search(r"export const PROTOCOL_VERSION = (\d+)", proto)[1]
    client, server, events = extract_unions(proto)
    a_client, a_server, a_events = android_known()
    matrix = ROOT / "docs/parity/matrix.json"
    previous = {(r["kind"], r["artifact"]): r["android"] for r in json.loads(matrix.read_text())["rows"]} if matrix.exists() else {}

    rows = []

    def add(kind, artifact, behavior, status, task):
        status = previous.get((kind, artifact)) or NEW_ROW_STATUS.get((kind, artifact)) or status
        rows.append({"kind": kind, "artifact": artifact, "behavior": behavior, "android": status, "task": task})

    found = sorted(str(p.relative_to(tether / "components"))[:-4] for p in (tether / "components").rglob("*.tsx"))
    missing_class = [c for c in found if c not in COMPONENTS]
    stale_class = [c for c in COMPONENTS if c not in found]
    if missing_class or stale_class:
        raise SystemExit(f"component classification drift: unclassified={missing_class} stale={stale_class}")
    for c in found:
        add("component", f"components/{c}.tsx", *COMPONENTS[c])
    for k, v in PAGES.items():
        add("page", k, *v)
    for k, v in ROUTES.items():
        add("route", k, *v)
    for k, v in HOOKS_HELPERS.items():
        add("hook/helper", k, *v)
    for t in client:
        add("client-msg", t, "ClientMessage (lib/protocol.ts); validated by protocol-validate.mjs",
            "PARTIAL" if t in a_client else "MISSING", client_task(t))
    for t in server:
        add("server-msg", t, "ServerMessage (lib/protocol.ts); tolerant decode + UI",
            "PARTIAL" if t in a_server else "MISSING", server_task(t))
    for t in events:
        add("event", t, "AgentEvent: reducer fold (T2.1) + surface",
            "PARTIAL" if t in a_events else "MISSING", event_task(t))

    gone = [r for r in RETIRED if r.startswith("components/") and (tether / r).exists()]
    if gone:
        raise SystemExit(f"retired artifacts still in the web tree: {gone}")
    data = {"parityBase": sha, "protocolVersion": int(pv), "rows": rows,
            "retired": [{"artifact": k, "reason": v} for k, v in RETIRED.items()]}
    matrix.write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n")

    counts = {}
    for r in rows:
        counts.setdefault(r["kind"], {}).setdefault(r["android"], 0)
        counts[r["kind"]][r["android"]] += 1
    md = [
        "# Parity Matrix — the definition of \"done\"",
        "",
        f"> Generated by `tools/parity/build-matrix.py` from tether `{sha}` (PROTOCOL_VERSION {pv}) — T0.5.",
        "> Data: [`matrix.json`](./matrix.json). Each row is also a bead (`bd list -l matrix`), closed when",
        "> its task ports it and a verifier confirms. Android status is as of app 0.5.1 (protocol 40) for",
        "> the T0.5 rows (tether 7d65611); rows added at a re-baseline (T15.8) carry their status then.",
        "> `PARTIAL` = an equivalent exists but predates v128 or differs; every protocol row also needs",
        "> T1.1 (types + tolerant decode) and, for events, T2.1 (reducer conformance).",
        "",
        "## Summary",
        "",
        "| Kind | Rows | MISSING | PARTIAL | DONE | N/A |",
        "|---|---|---|---|---|---|",
    ]
    for k, c in counts.items():
        md.append(f"| {k} | {sum(c.values())} | {c.get('MISSING', 0)} | {c.get('PARTIAL', 0)} | {c.get('DONE', 0)} | {c.get('N/A', 0)} |")
    md.append(f"| **total** | **{len(rows)}** | | | | |")
    for kind in counts:
        md += ["", f"## {kind}", "", "| Web artifact | Behavior (1 line) | Android status | Task |", "|---|---|---|---|"]
        for r in rows:
            if r["kind"] == kind:
                md.append(f"| `{r['artifact']}` | {r['behavior']} | {r['android']} | {r['task']} |")
    md += ["", "## Retired", "", "| Web artifact | Why |", "|---|---|"]
    md += [f"| `{k}` | {v} |" for k, v in RETIRED.items()]
    (ROOT / "docs/parity/MATRIX.md").write_text("\n".join(md) + "\n")
    print(json.dumps(counts), len(rows))


if __name__ == "__main__":
    main()


# ---- beads rows (bd import upserts; IDs are deterministic so a re-run updates, never duplicates) ----
KIND_ID = {"component": "cmp", "page": "page", "route": "route", "hook/helper": "lib",
           "client-msg": "c2s", "server-msg": "s2c", "event": "ev"}


def bead_id(r):
    slug = re.sub(r"[^A-Za-z0-9]+", "-", r["artifact"].replace("components/", "").replace(".tsx", "")).strip("-")
    return f"M.{KIND_ID[r['kind']]}.{slug[:60].strip('-')}"


def beads_jsonl(data):
    out = [{
        "_type": "issue", "id": "MATRIX", "issue_type": "epic", "status": "open", "priority": 1,
        "title": "Parity Matrix — one bead per web artifact at PARITY_BASE",
        "description": "Rows from docs/parity/matrix.json (tools/parity/build-matrix.py, T0.5). Rows are PARKED as "
                       "`deferred` (they are acceptance rows, not work items, so they stay out of bd ready). Each "
                       "carries a blocks-edge + task:<id> label to the task that ports it. When that task is done its "
                       "maker moves the rows to `done` with evidence; a different actor promotes them to `verified` "
                       "after checking the behavior against the web. N/A rows are dropped with the reason.",
        "labels": ["matrix"],
        "dependencies": [{"issue_id": "MATRIX", "depends_on_id": "PROG", "type": "parent-child"}],
    }]
    for r in data["rows"]:
        bid = bead_id(r)
        deps = [{"issue_id": bid, "depends_on_id": "MATRIX", "type": "parent-child"}]
        if r["task"] != "—":
            deps.append({"issue_id": bid, "depends_on_id": r["task"], "type": "blocks"})
        out.append({
            "_type": "issue", "id": bid, "issue_type": "task", "priority": 3,
            "status": "dropped" if r["android"] == "N/A" else "deferred",  # parked; not a work item (see BEADS.md)
            "title": f"[{r['kind']}] {r['artifact']}"[:150],
            "description": f"{r['behavior']}. Android at T0.5: {r['android']}. Ported by {r['task']}. "
                           f"Web ref: tether {data['parityBase']} (v{data['protocolVersion']}).",
            "labels": ["matrix", f"kind:{KIND_ID[r['kind']]}", f"android:{r['android'].lower().replace('/', '')}",
                       f"task:{r['task']}"],
            "dependencies": deps,
        })
    return out


if __name__ == "__main__" and "--beads" in __import__("sys").argv:
    for row in beads_jsonl(json.loads((ROOT / "docs/parity/matrix.json").read_text())):
        print(json.dumps(row, ensure_ascii=False))
