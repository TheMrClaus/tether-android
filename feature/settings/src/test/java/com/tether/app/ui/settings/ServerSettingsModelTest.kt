package com.tether.app.ui.settings

import com.tether.app.client.ServerSetting
import com.tether.app.protocol.ClaudeCliVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-t7l: the Metadata provider list, the Claude CLI picker's words and options, and the binding's send guard. */
class ServerSettingsModelTest {
    @Test fun theProviderListParsesAsTheWebParsesIt() {
        assertEquals(
            listOf(MetadataRows.ProviderEntry("anthropic", "claude-3-5-haiku-latest"), MetadataRows.ProviderEntry("ollama", "")),
            MetadataRows.parseProviders("""[{"provider":"anthropic","model":"claude-3-5-haiku-latest","thinkingOptionId":"x"},{"provider":"ollama","model":""}]"""),
        )
        // Any shape error: null (the free-text row).
        for (bad in listOf("", "{not json", "{}", "[]", "[1]", """[{"provider":""}]""", """[{"provider":"a"}]""", """[{"provider":"a","model":1}]""")) {
            assertNull(bad, MetadataRows.parseProviders(bad))
        }
    }

    @Test fun theProviderOptionsAddACustomRowOnlyForAnUnlistedValue() {
        val list = MetadataRows.parseProviders(ServerFixtures.PROVIDERS)!!
        val listed = MetadataRows.providerOptions(ServerFixtures.view(), list)
        assertEquals(listOf("", "anthropic:claude-3-5-haiku-latest", "openai:gpt-4o-mini", "ollama"), listed.map { it.value })
        assertEquals(listOf("Default order", "anthropic / claude-3-5-haiku-latest", "openai / gpt-4o-mini", "ollama / (default)"), listed.map { it.label })
        val custom = MetadataRows.providerOptions(ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("metadataGenerationProvider" to "groq:llama"))), list)
        assertEquals(ServerChoice("groq:llama", "Custom: groq:llama"), custom[1])
    }

    @Test fun theCliPickerNamesWhatAutoResolvesTo() {
        assertEquals("Auto resolves to 2.1.225 (newest installed)", ClaudeCliCopy.caption(ServerFixtures.ADVANCED))
        assertEquals("No host versions installed — using the bundled CLI", ClaudeCliCopy.caption(ServerFixtures.ADVANCED.copy(effectiveSource = "bundled", effectiveVersion = "bundled")))
        assertEquals("Applies to sessions started after the change", ClaudeCliCopy.caption(null))
        assertEquals("Applies to sessions started after the change", ClaudeCliCopy.caption(ServerFixtures.ADVANCED.copy(effectiveSource = "picker")))
        assertEquals(
            listOf("" to "Auto — newest installed (2.1.225)", "bundled" to "Bundled (SDK)", "2.1.225" to "2.1.225", "2.1.220" to "2.1.220"),
            ClaudeCliCopy.options(ServerFixtures.ADVANCED).map { it.value to it.label },
        )
        assertEquals(listOf("" to "Auto — newest installed", "bundled" to "Bundled (SDK)"), ClaudeCliCopy.options(null).map { it.value to it.label })
    }

    @Test fun aHostileVersionIsSpelledOutNotDrawn() {
        val adv = ServerFixtures.ADVANCED.copy(discovered = listOf(ClaudeCliVersion("2.1.0‮x")))
        val label = ClaudeCliCopy.options(adv).last().label
        assertFalse(label.contains("‮"))
        assertEquals("2.1.0\\u{202E}x", label)
        // The value sent back is the server's own string, untouched.
        assertEquals("2.1.0‮x", ClaudeCliCopy.options(adv).last().value)
    }

    @Test fun aBindingWithoutAServerSendsNothing() {
        val writer = RecordingWriter()
        assertFalse(ServerFixtures.binding(origin = null, writer = writer).send(ServerFixtures.json("""{"host":"x"}""")))
        assertFalse(ServerFixtures.binding(writer = writer).send(null))
        assertTrue(ServerFixtures.binding(writer = writer).send(ServerFixtures.json("""{"host":"x"}""")))
        assertEquals(listOf(ServerFixtures.json("""{"host":"x"}""") to ServerFixtures.ORIGIN), writer.patches)
    }

    @Test fun everyAdvancedAndMetadataKeyHasItsWebLabel() {
        val rows = listOf(
            AdvancedRows.host, AdvancedRows.port, AdvancedRows.password, AdvancedRows.proxyToken, AdvancedRows.stateDir, AdvancedRows.workspaceRoot,
            AdvancedRows.claudePersistent, AdvancedRows.claudeTaskTelemetry, AdvancedRows.warmMaxSessions, AdvancedRows.maxConcurrentTurns,
            AdvancedRows.warmIdleEvictionMs, AdvancedRows.warmBgHardCapMs, AdvancedRows.warmSweepMs, AdvancedRows.shutdownDrainMs,
            AdvancedRows.messageInterruptMode, AdvancedRows.claudeModelFallback, AdvancedRows.archiveOnMerge, AdvancedRows.defaultPermissionMode,
            AdvancedRows.defaultSandboxPolicy, AdvancedRows.defaultUseWorktree, AdvancedRows.preferSpawnAgent, MetadataRows.enabled, MetadataRows.mode,
        ).map { it.setting } + listOf(AdvancedRows.allowedRoots.setting, AdvancedRows.spawnExtraWritableRoots.setting, ServerSetting.MetadataGenerationProvider, ServerSetting.MetadataGenerationProviders)
        assertEquals(ServerSetting.entries.toSet(), rows.toSet())
    }
}
