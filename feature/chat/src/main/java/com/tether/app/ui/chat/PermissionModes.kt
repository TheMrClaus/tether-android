package com.tether.app.ui.chat

/**
 * UI-facing metadata for the permission-mode selector. Hand-synced with
 * PERMISSION_MODE_OPTIONS in aidash/lib/protocol.ts — `default` (Manual) is
 * the safe default: every gated tool prompts via the approval chips.
 */
data class PermissionModeOption(
    val value: String,
    val label: String,
    val hint: String,
    val danger: Boolean = false,
)

val PERMISSION_MODE_OPTIONS: List<PermissionModeOption> = listOf(
    PermissionModeOption("default", "Manual", "Prompts before every gated tool (Bash, Write, Edit…)"),
    PermissionModeOption("acceptEdits", "Accept Edits", "Auto-accepts file edits; still prompts for other tools"),
    PermissionModeOption("plan", "Plan", "Researches and proposes a plan without making changes"),
    PermissionModeOption("dontAsk", "Locked", "Denies tools that are not pre-approved; agent questions are denied instead of shown"),
    PermissionModeOption("bypassPermissions", "Auto", "Runs everything without asking — including destructive commands", danger = true),
)

/** The current option for a session row; null/bogus wires down to Manual. */
fun permissionModeOption(value: String?): PermissionModeOption =
    PERMISSION_MODE_OPTIONS.firstOrNull { it.value == (value ?: "default") } ?: PERMISSION_MODE_OPTIONS.first()
