package com.tether.app.protocol

/**
 * The v143 TS unions' discriminator sets, checked in so CI (no tether checkout)
 * can verify type coverage. They are derived from docs/parity/matrix.json (kind
 * `server-msg` / `client-msg`, 43 + 69 rows), which tools/parity/build-matrix.py
 * generates from tether lib/protocol.ts at PARITY_BASE (T15.8: 887c222, v137; the
 * v131 Overview frames joined the matrix then). v132-v140 added no message types; v141 (#244) added
 * `archive-stale` / `archive-stale-result` and v143 (#241) `archive-inspect` / `archive-preview`
 * ([SINCE_PARITY_BASE_SERVER], [SINCE_PARITY_BASE_CLIENT]).
 * WireConformanceTest re-checks these against matrix.json, and against
 * lib/protocol.ts itself when TETHER_PROTOCOL_TS points at one.
 */
object WireTypeLists {
    val SERVER_TYPES: Set<String> = setOf(
        "acp-agents", "advanced-settings", "approval", "approval_resolved", "change-request",
        "codex-control-result", "codex-controls", "created", "directories", "error", "event",
        "git-diff-file", "global-search-results", "handoff-brief", "histories", "interrupt_result",
        "log", "metadata-draft-error", "metadata-draft-result", "node-result", "nodes",
        "opencode-control-result", "opencode-controls", "pong", "providers", "providers-snapshot",
        "ready", "scheduled-actions", "search-results", "seen", "server-settings", "session",
        "session-controls", "session-order", "snapshot", "turns-detail", "version_mismatch",
        "worktree-diff", "worktree-logs", "worktree-scripts", "worktree-source",
        // v141 / v143 (after PARITY_BASE)
        "archive-preview", "archive-stale-result",
        // v131 (opt-in Overview feed)
        "overview-delta", "overview-snapshot",
    )

    val CLIENT_TYPES: Set<String> = setOf(
        "acp-agents", "advanced-settings", "approval", "archive", "archive-inspect", "archive-stale", "attach", "background-command",
        "browse", "change-request", "codex-control-action", "codex-controls", "create",
        "create-folder", "detect-engines", "discover", "dismiss-notice", "fetch-turns",
        "git-diff-file", "global-search", "handoff", "handoff-brief", "hello", "interrupt", "kill",
        "mark-seen", "metadata-draft-request", "node-add", "node-probe", "node-remove",
        "opencode-control-action", "opencode-controls", "pin", "ping", "providers",
        "providers-snapshot", "question", "queue-add", "queue-edit", "queue-remove",
        "rate-limit-resume", "refresh-providers", "rename", "resume", "run-command",
        "schedule-control", "schedule-create", "schedule-update", "scheduled-actions", "search",
        "send", "server-settings", "session-controls", "set-acp-agents", "set-advanced-settings",
        "set-auto-continue-on-limit", "set-fast-mode", "set-mode", "set-model", "set-providers",
        "set-reasoning-effort", "set-server-settings", "set-session-order", "stop-command",
        "worktree-diff", "worktree-inspect", "worktree-logs", "worktree-script", "worktree-scripts",
        // v131 (opt-in Overview feed)
        "overview-subscribe", "overview-unsubscribe",
    )

    /** Types added after PARITY_BASE (887c222): not in matrix.json until the matrix is rebuilt at a newer base. */
    val SINCE_PARITY_BASE_SERVER: Set<String> = setOf("archive-preview", "archive-stale-result")
    val SINCE_PARITY_BASE_CLIENT: Set<String> = setOf("archive-inspect", "archive-stale")
}
