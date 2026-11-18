package com.tether.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWorkflowProjectionTest {
    @Test fun destinationsAndAgentSettingsUseTheFinalGroupsAndTaskOrder() {
        assertEquals(
            listOf(
                "preferences", "access-devices", "engines", "accounts", "sessions-routing",
                "hosts-nodes", "server-integrations",
            ),
            RuntimeDeskDestinations.items.map { it.id },
        )
        assertEquals(
            listOf("Set up Tether", "Set up Tether", "Run agents", "Run agents", "Run agents", "Manage infrastructure", "Manage infrastructure"),
            RuntimeDeskDestinations.items.map { it.group },
        )
        assertEquals(
            listOf("claude", "codex", "opencode", "reasonix", "pi", "dsh", "custom-profiles", "host-config", "claude-cli"),
            RuntimeDeskRoute.ENGINE_IDS,
        )
        RuntimeDeskRoute.ENGINE_TASK_IDS.forEach { task ->
            val route = RuntimeDeskRoute.Root.selectDestination(RuntimeDeskDestinations.Engines).selectEntity(task)
            assertEquals(RuntimeDeskLevel.Detail, route.level)
            assertEquals(task, route.entity)
            assertEquals(RuntimeDeskLevel.Entities, route.back().level)
        }
    }

    @Test fun credentialsUsageAliasesAndProfilesProjectToOneCanonicalAccountEach() {
        val credentials = AccountsFixtures.decode(
            """{"accounts":[
              {"id":"claude-default","label":"Claude Code (default)","hasConfigDir":true,"managed":false,"imported":true,"syncEligible":true},
              {"id":"work","label":"Same name","hasConfigDir":true,"managed":true,"imported":false,"syncEligible":true},
              {"id":"ordinary","label":"Same name","hasConfigDir":false,"managed":false,"imported":false,"syncEligible":false}
            ]}""",
        )
        val providers = ProfileFixtures.list(
            ProfileFixtures.profiles(
                """{"id":"work","extends":"claude","label":"Same name","env":{"CLAUDE_CONFIG_DIR":"/fake/work"},"homeDir":"/fake/home","enabled":true,"order":2,"models":[{"id":"opus","isDefault":true}]}""",
                """{"id":"offline","extends":"claude","label":"Offline account","env":{"CLAUDE_CONFIG_DIR":"/fake/offline"},"enabled":true}""",
                """{"id":"ordinary","extends":"claude","label":"Same name","homeDir":"/fake/ordinary","enabled":true}""",
                """{"id":"unknown","extends":"future-engine","label":"Keep me","future":{"x":1}}""",
            ),
        )
        val usage = listOf(
            usage("claude", "default", "Default"),
            usage("claude", "work", "Same name"),
            usage("claude", "ordinary", "Same name"),
            usage("codex", "codex-a", "Same name"),
            usage("deepseek", "deep-a", "ignored"),
        )

        val result = SettingsAccountProjector.project(credentials, usage, providers)

        assertEquals(listOf("claude-default", "work"), result.credentialAccounts.map { it.id })
        assertEquals(listOf("work", "offline"), result.accountBackedProfiles.map { it.id })
        assertEquals(setOf("ordinary", "unknown"), result.customProfiles.map { it.id }.toSet())
        assertEquals(setOf("default", "work"), result.visibleClaudeUsageIds)
        assertEquals("default", result.usageIdByAccountId["claude-default"])
        assertEquals(
            RuntimeDeskEntities.account("claude", "claude-default"),
            result.canonicalEntity(RuntimeDeskEntities.usage("claude", "default")),
        )
        assertEquals(
            RuntimeDeskEntities.account("claude", "work"),
            result.canonicalEntity(RuntimeDeskEntities.credential("work")),
        )
        assertEquals(
            RuntimeDeskEntities.usage("claude", "offline"),
            result.usageEntity(RuntimeDeskEntities.account("claude", "offline")),
        )
        assertFalse(result.entities.any { it.id.endsWith(":ordinary") })
        assertEquals(result.entities.map { it.id }.distinct(), result.entities.map { it.id })
        assertTrue(result.entities.filter { it.label == "Same name" }.all { " · " in it.supporting.orEmpty() })
    }

    @Test fun persistedImportedDefaultOwnsTheDefaultUsageAliasWithoutADuplicate() {
        val credentials = AccountsFixtures.decode(
            """{"accounts":[
              {"id":"host-login","label":"Host login","hasConfigDir":true,"managed":false,"imported":true,"syncEligible":true}
            ]}""",
        )
        val result = SettingsAccountProjector.project(
            credentials,
            listOf(usage("claude", "default", "Default"), usage("claude", "host-login", "Host login")),
            providers = null,
        )

        assertEquals("host-login", result.hostDefaultAccountId)
        assertEquals(listOf("default"), result.usageAccounts.filter { it.provider == "claude" }.map { it.id })
        assertEquals("default", result.usageIdByAccountId["host-login"])
        assertEquals(
            RuntimeDeskEntities.account("claude", "host-login"),
            result.canonicalEntity(RuntimeDeskEntities.account("claude", "default")),
        )
        assertEquals(1, result.entities.count { it.id == RuntimeDeskEntities.account("claude", "host-login") })
    }

    private fun usage(provider: String, id: String, label: String) =
        RuntimeDeskNavItem(RuntimeDeskEntities.usage(provider, id), label, "Usage only")
}
