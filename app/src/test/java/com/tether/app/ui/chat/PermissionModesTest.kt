package com.tether.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionModesTest {

    @Test
    fun valuesMatchTheServerAllowSetExactly() {
        // The wire is strict (protocol-validate.mjs + engines/claude.mjs allow-set);
        // a typo here is a silently rejected frame. Keep in sync with
        // PERMISSION_MODE_OPTIONS in aidash/lib/protocol.ts.
        assertEquals(
            listOf("default", "acceptEdits", "plan", "dontAsk", "bypassPermissions"),
            PERMISSION_MODE_OPTIONS.map { it.value },
        )
        assertEquals(
            listOf("Manual", "Accept Edits", "Plan", "Locked", "Auto"),
            PERMISSION_MODE_OPTIONS.map { it.label },
        )
        assertEquals(listOf(false, false, false, false, true), PERMISSION_MODE_OPTIONS.map { it.danger })
        assertEquals(
            listOf(
                "Prompts before every gated tool (Bash, Write, Edit…)",
                "Auto-accepts file edits; still prompts for other tools",
                "Researches and proposes a plan without making changes",
                "Denies tools that are not pre-approved; agent questions are denied instead of shown",
                "Runs everything without asking — including destructive commands",
            ),
            PERMISSION_MODE_OPTIONS.map { it.hint },
        )
    }

    @Test
    fun lookupDefaultsToManual() {
        assertEquals("Manual", permissionModeOption(null).label)
        assertEquals("Manual", permissionModeOption("default").label)
        assertEquals("Auto", permissionModeOption("bypassPermissions").label)
        assertEquals("Manual", permissionModeOption("bogus-from-the-future").label)
    }
}
