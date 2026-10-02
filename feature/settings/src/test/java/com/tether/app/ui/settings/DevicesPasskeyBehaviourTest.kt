package com.tether.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.AppSignIn
import com.tether.app.client.PasskeyCeremony
import com.tether.app.client.PasskeysView
import com.tether.app.client.SecurityResult
import com.tether.app.protocol.TetherJson
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.settings.DevicesFixtures.LAPTOP_KEY
import com.tether.app.ui.settings.DevicesFixtures.ORIGIN
import com.tether.app.ui.settings.DevicesFixtures.OTHER_ORIGIN
import com.tether.app.ui.settings.DevicesFixtures.YUBIKEY
import com.tether.app.ui.settings.DevicesFixtures.ok
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * T10.5: Settings > Devices > Add a passkey through the semantics tree, on the phone and the expanded
 * layout, with a source and a Credential Manager that each wait for the test's answer. The web's
 * registerPasskey (use-sign-in-security.ts 887c222): options, the ceremony, verify with the label
 * (`label.trim() || "Passkey"`), "Passkey added." and a re-read, the label cleared; its words for a
 * dismissed prompt and a duplicate; the server's own refusals. And the app's own guards: the prompt is
 * asked only for this server's rpId, an answer about another server is dropped, one ceremony at a
 * time, nothing of the answer in semantics, saved state, logs or preferences.
 */
abstract class DevicesPasskeyBehaviourBase(private val layout: TetherLayoutClass) {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Devices)
    private val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
    private val source = RecordingSecuritySource()
    private val passkeys = WaitingPasskeys()

    /** console.example.test: the host of [ORIGIN], the only rpId the prompt may be asked for. */
    private val ownRpId = "console.example.test"

    private fun opened(view: PasskeysView = DevicesFixtures.PASSKEYS, authenticator: WaitingPasskeys = passkeys) {
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry, LocalConfirmArmMs provides 0L) {
                val controller = rememberDevicesController(source, ORIGIN, now = { DevicesFixtures.NOW }, authenticator = authenticator)
                SettingsUnderTest(store.prefs, state, layout = layout, devices = DevicesBinding(controller, now = { DevicesFixtures.NOW }))
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitUntil(5_000) { source.calls.size == 3 }
        source.answerReads(AppSignIn.DeviceToken, passkeys = view)
        compose.waitUntil(5_000) { exists(DevicesTags.SignOutOthers) && !exists(DevicesTags.PasskeysChecking) }
    }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun enabled(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
        ?.config?.contains(SemanticsProperties.Disabled) == false

    private fun tap(t: String) {
        compose.waitUntil(5_000) { enabled(t) }
        tag(t).performScrollTo().performClick()
    }

    private fun label(text: String) {
        compose.waitUntil(5_000) { enabled(DevicesTags.PasskeyLabel) }
        tag(DevicesTags.PasskeyLabel).performScrollTo().performTextReplacement(text)
    }

    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun allSemantics(): String {
        val out = StringBuilder()
        fun walk(node: SemanticsNode) {
            for ((key, value) in node.config) out.append(key.name).append('=').append(value).append('\n')
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out.toString()
    }

    private fun waitText(text: String) = try {
        compose.waitUntil(5_000) { texts().any { it == text } }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("never shown: $text; shown: ${texts()}", e)
    }

    private fun waitFor(predicate: () -> Boolean) = compose.waitUntil(5_000, predicate)

    private fun lineText(): String? = compose.onAllNodesWithTag(DevicesTags.line(DevicesArea.Security), useUnmergedTree = true)
        .fetchSemanticsNodes().singleOrNull()?.let { node ->
            val parts = mutableListOf<String>()
            fun walk(n: SemanticsNode) {
                n.config.getOrNull(SemanticsProperties.Text)?.forEach { parts += it.text }
                n.children.forEach(::walk)
            }
            walk(node)
            parts.joinToString(" ")
        }

    private fun obj(text: String) = TetherJson.parseToJsonElement(text) as JsonObject

    /** Up to the ceremony: the options call answered with [rpId]'s challenge. */
    private fun upToThePrompt(typed: String? = "Pixel", rpId: String = ownRpId) {
        typed?.let(::label)
        tap(DevicesTags.AddPasskey)
        waitFor { source.pending("registerOptions") }
        source.answer("registerOptions", ok(PasskeyShapes.challenge(rpId), AppSignIn.DeviceToken))
    }

    // ---- the web's path ------------------------------------------------------------------------

    @Test fun addingAPasskeyIsOptionsThePromptThenVerifyWithTheLabelThenAReRead() {
        opened()
        upToThePrompt("  Pixel  ")
        // While the ceremony runs the key says so and nothing else may start in this area.
        waitFor { passkeys.pending() }
        waitText(DevicesCopy.ADDING_PASSKEY)
        assertFalse(enabled(DevicesTags.AddPasskey))
        assertFalse(enabled(DevicesTags.PasskeyLabel))
        assertFalse(enabled(DevicesTags.rename(LAPTOP_KEY.id)))
        // The prompt was handed the server's options exactly.
        assertEquals(listOf(obj(PasskeyShapes.challenge(ownRpId).optionsJson())), passkeys.requests.map(::obj))

        passkeys.answer(PasskeyCeremony.Done(PasskeyShapes.ANSWER))
        waitFor { source.pending("registerVerify") }
        val (challengeId, response, sentLabel) = source.verified.single()
        assertEquals(PasskeyShapes.CHALLENGE_ID, challengeId)
        assertEquals(obj(PasskeyShapes.ANSWER), response)
        // The web trims; the server cuts at 64 and normalises the rest.
        assertEquals("Pixel", sentLabel)
        source.answer("registerVerify", ok(PasskeyShapes.NEW_KEY, AppSignIn.DeviceToken))

        waitText(DevicesCopy.PASSKEY_ADDED)
        assertEquals(DevicesCopy.PASSKEY_ADDED, lineText())
        // Re-read (passkeys + sessions), and the label field is empty again.
        waitFor { source.pending("passkeys") && source.pending("sessions") }
        source.answerReads(AppSignIn.DeviceToken, passkeys = DevicesFixtures.PASSKEYS.copy(passkeys = listOf(PasskeyShapes.NEW_KEY, LAPTOP_KEY, YUBIKEY)))
        waitFor { exists(DevicesTags.passkey(PasskeyShapes.NEW_KEY.id)) }
        assertEquals("", tag(DevicesTags.PasskeyLabel).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertEquals(listOf("devices", "passkeys", "sessions", "registerOptions", "registerVerify", "passkeys", "sessions"), source.names())
    }

    @Test fun aBlankLabelIsSentAsTheWebsDefault() {
        opened()
        upToThePrompt(typed = "   ")
        waitFor { passkeys.pending() }
        passkeys.answer(PasskeyCeremony.Done(PasskeyShapes.ANSWER))
        waitFor { source.pending("registerVerify") }
        assertEquals(DevicesCopy.DEFAULT_PASSKEY_LABEL, source.verified.single().third)
    }

    @Test fun whatThePromptAnsweredIsTheWebsWordsAndNothingMoreIsSent() {
        val cases = listOf(
            PasskeyCeremony.Dismissed to DevicesCopy.PASSKEY_DISMISSED,
            PasskeyCeremony.Duplicate to DevicesCopy.PASSKEY_DUPLICATE,
            PasskeyCeremony.Unsupported to DevicesCopy.PASSKEY_UNSUPPORTED,
            PasskeyCeremony.Failed to DevicesCopy.ADD_PASSKEY_FAILED,
            PasskeyCeremony.Done("""{"id":"x","type":"password","response":{}}""") to DevicesCopy.ADD_PASSKEY_FAILED,
        )
        opened()
        for ((answer, words) in cases) {
            val before = source.calls.size
            upToThePrompt()
            waitFor { passkeys.pending() }
            passkeys.answer(answer)
            waitText(words)
            assertEquals(words, lineText())
            compose.waitForIdle()
            // No verify, no re-read: the challenge simply expires on the server.
            assertEquals("after $answer: ${source.names()}", before + 1, source.calls.size)
            // The key is usable again (the label stays, as on the web).
            waitFor { enabled(DevicesTags.AddPasskey) }
        }
        assertTrue(source.verified.isEmpty())
    }

    // ---- the app's own guards ------------------------------------------------------------------

    @Test fun optionsForAnotherRelyingPartyNeverOpenThePrompt() {
        opened()
        // The server this panel talks to asks for ANOTHER console's passkey (a relay): refused.
        upToThePrompt(rpId = "other-console.example.test")
        waitText(DevicesCopy.PASSKEY_WRONG_RP)
        compose.waitForIdle()
        assertTrue("no prompt", passkeys.requests.isEmpty())
        assertEquals(listOf("devices", "passkeys", "sessions", "registerOptions"), source.names())
        // A parent domain is refused too (a browser would allow it; the app fails closed).
        upToThePrompt(rpId = "example.test")
        waitText(DevicesCopy.PASSKEY_WRONG_RP)
        compose.waitForIdle()
        assertTrue(passkeys.requests.isEmpty())
    }

    @Test fun anAnswerAboutAnotherServerIsDropped() {
        opened()
        label("Pixel")
        tap(DevicesTags.AddPasskey)
        waitFor { source.pending("registerOptions") }
        source.answer("registerOptions", SecurityResult.Ok(PasskeyShapes.challenge("other-console.example.test"), OTHER_ORIGIN, AppSignIn.DeviceToken))
        compose.waitForIdle()
        waitFor { enabled(DevicesTags.AddPasskey) }
        assertTrue(passkeys.requests.isEmpty())
        assertEquals(4, source.calls.size)
    }

    @Test fun theServersRefusalsAreShownAsItWordsThem() {
        opened()
        tap(DevicesTags.AddPasskey)
        waitFor { source.pending("registerOptions") }
        source.answer("registerOptions", SecurityResult.Refused(409, "This console already has 16 passkeys. Remove one first.", ORIGIN))
        waitText("This console already has 16 passkeys. Remove one first.")
        assertTrue(passkeys.requests.isEmpty())

        upToThePrompt()
        waitFor { passkeys.pending() }
        passkeys.answer(PasskeyCeremony.Done(PasskeyShapes.ANSWER))
        waitFor { source.pending("registerVerify") }
        source.answer("registerVerify", SecurityResult.Refused(400, "That passkey could not be registered: Unexpected registration response origin", ORIGIN))
        waitText("That passkey could not be registered: Unexpected registration response origin")

        tap(DevicesTags.AddPasskey)
        waitFor { source.pending("registerOptions") }
        source.answer("registerOptions", SecurityResult.OwnerSignInNeeded(ORIGIN))
        waitFor { exists(DevicesTags.ownerNote(DevicesArea.Security)) }
        assertFalse("an owner-grade refusal holds every write", enabled(DevicesTags.AddPasskey))
    }

    @Test fun drawnOffWhenTheConsoleCannotUsePasskeysOrThePhoneHasNoPrompt() {
        opened(view = DevicesFixtures.PASSKEYS.copy(passkeysUsable = false))
        assertTrue(exists(DevicesTags.PasskeysHttps))
        assertFalse(enabled(DevicesTags.AddPasskey))
        assertFalse(enabled(DevicesTags.PasskeyLabel))
        assertEquals(3, source.calls.size)
    }

    @Test fun oneCeremonyAtATime() {
        opened()
        upToThePrompt()
        waitFor { passkeys.pending() }
        // A second tap, an Enter on the label, a rename: none starts anything.
        tag(DevicesTags.AddPasskey).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, passkeys.requests.size)
        assertEquals(4, source.calls.size)
    }

    @Test fun nothingOfTheAnswerIsKeptOrPrinted() {
        opened()
        upToThePrompt()
        waitFor { passkeys.pending() }
        passkeys.answer(PasskeyCeremony.Done(PasskeyShapes.ANSWER))
        waitFor { source.pending("registerVerify") }
        // Control: the answer did go to the server.
        assertTrue(source.verified.single().second.toString().contains(PasskeyShapes.SIGNATURE))
        source.answer("registerVerify", ok(PasskeyShapes.NEW_KEY, AppSignIn.DeviceToken))
        waitText(DevicesCopy.PASSKEY_ADDED)
        val leak = PasskeyShapes.SIGNATURE
        assertFalse("semantics", allSemantics().contains(leak))
        assertFalse("saved state", registry.performSave().toString().contains(leak))
        assertFalse("a log line", ShadowLog.getLogs().any { "${it.tag} ${it.msg} ${it.throwable}".contains(leak) })
        assertFalse("the preference store", store.stored().toString().contains(leak))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DevicesPasskeyPhoneBehaviourTest : DevicesPasskeyBehaviourBase(TetherLayoutClass.Phone)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class DevicesPasskeyExpandedBehaviourTest : DevicesPasskeyBehaviourBase(TetherLayoutClass.Expanded)
