package com.tether.app.client

import com.tether.app.protocol.DelegateMention
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.ProviderCapabilities
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T7.3: [CommandGuard] and [LabelText.output], as pure checks. */
class CommandGuardTest {

    private val session = AgentSession(id = "s1", provider = "claude", name = "n", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)
    private fun providers(runner: Boolean) = listOf(ProviderInfo("claude", "Claude", "C", true, ProviderCapabilities(commandRunner = runner)))

    @Test
    fun commandModeIsOfferedOnlyWhenTheServerSaysSo() {
        assertTrue(CommandGuard.commandModeOffered(session, providers(true)))
        assertFalse(CommandGuard.commandModeOffered(session, providers(false)))
        assertFalse(CommandGuard.commandModeOffered(session, emptyList()))
        assertFalse(CommandGuard.commandModeOffered(session.copy(provider = "codex"), providers(true)))
        assertEquals(RunCommandResult.NotOffered, CommandGuard.checkRun(session, providers(false), null, "ls", false))
    }

    @Test
    fun theCommandIsTheOperatorsLineCheckedForShapeOnly() {
        val p = providers(true)
        assertNull(CommandGuard.checkRun(session, p, freshTree(), "rm -rf build && npm test", false))
        assertEquals(RunCommandResult.Invalid, CommandGuard.checkRun(session, p, freshTree(), " \n\t", false))
        assertEquals(RunCommandResult.Invalid, CommandGuard.checkRun(session, p, freshTree(), "x".repeat(CommandGuard.COMMAND_MAX_BYTES + 1), true))
        assertNull(CommandGuard.checkRun(session, p, freshTree(), "x".repeat(CommandGuard.COMMAND_MAX_BYTES), true))
        assertEquals(RunCommandResult.Locked, CommandGuard.checkRun(session.copy(runtimeArchived = true), p, freshTree(), "ls", true))
    }

    @Test
    fun onlyAnOpenCommandTurnIsAForegroundCommand() {
        val command = foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1) {
            put("commandRun", buildJsonObject { put("command", "ls"); put("cwd", "/w"); put("logFile", "/w/l") })
        })
        assertEquals("t1", CommandGuard.foregroundCommandTurn(command))
        assertNull(CommandGuard.checkBackground(command, "t1"))
        assertEquals(BackgroundCommandResult.NotRunning, CommandGuard.checkBackground(command, "t0"))
        val ordinary = foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1))
        assertNull(CommandGuard.foregroundCommandTurn(ordinary))
        assertEquals(RunCommandResult.Busy, CommandGuard.checkRun(session, providers(true), ordinary, "ls", false))
        assertNull(CommandGuard.checkRun(session, providers(true), ordinary, "ls", true))
        val ended = foldTree(command, ev("turn_end", "t1", seq = 2, ts = 2) { put("outcome", "ok") })
        assertNull(CommandGuard.foregroundCommandTurn(ended))
        // A commandRun without its cwd / logFile is not a command run (normalizeCommandRunMeta).
        val malformed = foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1) { put("commandRun", buildJsonObject { put("command", "ls") }) })
        assertNull(CommandGuard.foregroundCommandTurn(malformed))
    }

    private val claude = ProviderCatalogEntry(
        "claude", "claude", "ready",
        listOf(SessionModelOption("m1", "Model 1", variants = listOf(ModelVariantOption("high", "High")))),
        defaultModel = "m1", label = "Claude",
    )

    @Test
    fun aMentionMustBeOneTheCatalogOffers() {
        val catalog = listOf(claude, claude.copy(key = "acp", provider = "acp"), claude.copy(key = "p", profileId = "p"))
        assertTrue(CommandGuard.mentionOffered(session, DelegateMention("claude", "review"), catalog))
        assertTrue(CommandGuard.mentionOffered(session, DelegateMention("claude", "build", "m1", "high"), catalog))
        assertFalse(CommandGuard.mentionOffered(session, DelegateMention("claude", "review", "m2"), catalog))
        assertFalse(CommandGuard.mentionOffered(session, DelegateMention("claude", "review", "m1", "max"), catalog))
        assertFalse(CommandGuard.mentionOffered(session, DelegateMention("acp", "review"), catalog))
        assertFalse(CommandGuard.mentionOffered(session, DelegateMention("claude", "Review"), catalog))
        assertFalse(CommandGuard.mentionOffered(session.copy(parentSessionId = "p"), DelegateMention("claude", "review"), catalog))
        val long = claude.copy(models = listOf(SessionModelOption("m".repeat(201))))
        assertFalse(CommandGuard.mentionOffered(session, DelegateMention("claude", "review", "m".repeat(201)), listOf(long)))
    }

    @Test
    fun theCatalogParsesLenientlyAndBounded() {
        val entries = (0 until 300).map { i ->
            buildJsonObject { put("key", "k$i"); put("provider", "claude"); put("status", "ready") }
        } + buildJsonObject { put("provider", "claude") }
        val parsed = ProviderCatalogEntry.parse(entries)
        assertEquals(LabelText.MAX_ITEMS, parsed.size)
        assertEquals(emptyList<ProviderCatalogEntry>(), ProviderCatalogEntry.parse(listOf(buildJsonObject { put("key", 1) })))
    }

    // --- LabelText.output ----------------------------------------------------------------------

    @Test
    fun outputKeepsLinesAndDropsWhatCouldDisguiseIt() {
        assertEquals("a\nb\tc d", LabelText.output("a\r\nb\tc d"))
        assertEquals("50%\n100%", LabelText.output("50%\r100%"))
        // ANSI colour, cursor and OSC title sequences go whole.
        assertEquals("red plain title-less", LabelText.output("\u001B[31mred\u001B[0m plain\u001B]0;evil title\u0007 title-less"))
        assertEquals("ok", LabelText.output("\u001B]8;;https://x\u001B\\ok"))
        // Bidi overrides / isolates / marks: never reorder the text around them.
        assertEquals("Approve  gnp.exe", LabelText.output("Approve \u202E \u2066gnp.exe\u2069\u200F"))
        // C0 / C1 controls, DEL, zero-width and blank-looking code points.
        assertEquals("abc", LabelText.output("a\u0000b\u0085\u007Fc\u200B\u2800"))
        assertEquals("x y", LabelText.output("x\u00A0y"))
        assertEquals("line\nnext", LabelText.output("line\u2028next"))
        assertEquals("", LabelText.output(null))
    }
}
