package com.tether.app.ui.settings

import com.tether.app.client.ServerSecretOutcome
import com.tether.app.client.ServerSetting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v144: the credential dialog's state machine (components/server-secret-row.tsx) without any UI. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerSecretsModelTest {
    private val view = ServerFixtures.view()

    private fun fill(d: SecretDialogState, current: String = "cur", next: String = "", confirm: String = "") {
        d.current = current
        d.next = next
        d.confirm = confirm
    }

    // ---- enablement (server-secret-row.tsx:82-86), the whole table ------------------------------------

    @Test fun saveIsEnabledExactlyAsTheWebsCanSubmit() {
        val pw = SecretDialogState(SecretMode.PasswordSet)
        assertFalse(pw.canSubmit)
        fill(pw, current = "", next = "a", confirm = "a")
        assertFalse("no current password", pw.canSubmit)
        fill(pw, next = "", confirm = "")
        assertFalse(pw.canSubmit)
        fill(pw, next = "a", confirm = "")
        assertFalse("no confirmation", pw.canSubmit)
        fill(pw, next = "a", confirm = "b")
        assertFalse("mismatch", pw.canSubmit)
        fill(pw, next = "a", confirm = "a")
        assertTrue("no client-side length or strength check", pw.canSubmit)
        pw.sending = true
        assertFalse("not while sending", pw.canSubmit)

        val tok = SecretDialogState(SecretMode.TokenSet)
        fill(tok, next = "")
        assertFalse(tok.canSubmit)
        fill(tok, next = "x")
        assertTrue("a 1-character token: the server judges", tok.canSubmit)
        fill(tok, current = "", next = "x")
        assertFalse(tok.canSubmit)

        val clear = SecretDialogState(SecretMode.TokenClear)
        assertFalse(clear.canSubmit)
        fill(clear)
        assertTrue("clear needs only the current password", clear.canSubmit)
    }

    @Test fun theMismatchNoteShowsOnlyForAPasswordWithATypedConfirmationThatDiffers() {
        val pw = SecretDialogState(SecretMode.PasswordSet)
        fill(pw, next = "abc", confirm = "")
        assertFalse(pw.mismatch)
        fill(pw, next = "abc", confirm = "ab")
        assertTrue(pw.mismatch)
        fill(pw, next = "abc", confirm = "abc")
        assertFalse(pw.mismatch)
        val tok = SecretDialogState(SecretMode.TokenSet)
        fill(tok, next = "abc", confirm = "zzz")
        assertFalse(tok.mismatch)
    }

    // ---- rows ---------------------------------------------------------------------------------------------

    @Test fun theRowFactsFollowTheWebsCanActAndCaptionPrecedence() {
        val pwOnly = ServerFixtures.view(secretsSet = mapOf("password" to true, "proxyToken" to false))
        assertTrue(SecretRowFacts.of(pwOnly, ServerSetting.Password).canAct)
        assertTrue(SecretRowFacts.of(pwOnly, ServerSetting.ProxyToken).canAct)
        val none = ServerFixtures.view(secretsSet = mapOf("password" to false, "proxyToken" to true))
        assertFalse("P-unset: no key", SecretRowFacts.of(none, ServerSetting.Password).canAct)
        assertFalse("T-nopw: no keys", SecretRowFacts.of(none, ServerSetting.ProxyToken).canAct)
        val forced = ServerFixtures.view(envForced = mapOf("password" to true, "proxyToken" to true))
        assertFalse(SecretRowFacts.of(forced, ServerSetting.Password).canAct)
        assertFalse(SecretRowFacts.of(forced, ServerSetting.ProxyToken).canAct)

        val t = ServerSetting.ProxyToken
        assertEquals("Set by TETHER_PROXY_TOKEN in the environment — change it there and restart.", ServerSecretCopy.caption(t, true, true, SecretSaved.TokenSaved))
        assertEquals("Saved — takes effect after a restart.", ServerSecretCopy.caption(t, false, true, SecretSaved.TokenSaved))
        assertEquals("SSO-header sign-in is on. Changes apply after a restart.", ServerSecretCopy.caption(t, false, true, SecretSaved.TokenCleared))
        assertEquals("SSO-header sign-in is off.", ServerSecretCopy.caption(t, false, false, null))
        assertEquals("No login password is set. Set TETHER_PASSWORD and restart to manage it here.", ServerSecretCopy.caption(ServerSetting.Password, false, false, null))
        assertEquals("Set by environment", ServerSecretCopy.tag(true, true))
        assertEquals("Configured", ServerSecretCopy.tag(false, true))
        assertEquals("Not set", ServerSecretCopy.tag(false, false))
    }

    // ---- submit ---------------------------------------------------------------------------------------------

    private class Harness(val reply: CompletableDeferred<ServerSecretOutcome> = CompletableDeferred()) {
        val sent = mutableListOf<Pair<JsonObject, String>>()
        var sessionsRefreshed = 0
        val change: suspend (JsonObject, String) -> ServerSecretOutcome = { patch, current ->
            sent += patch to current
            reply.await()
        }
    }

    private fun TestScope.submit(s: ServerSecretsState, h: Harness) {
        s.submit(this, ServerFixtures.view(), h.change) { h.sessionsRefreshed++ }
        testScheduler.runCurrent()
    }

    @Test fun savingAPasswordSendsItAndOnSuccessClosesRecordsTheLineAndRefreshesSessions() = runTest(StandardTestDispatcher()) {
        val s = ServerSecretsState()
        val h = Harness()
        s.open(SecretMode.PasswordSet)
        fill(s.dialog!!, current = "cur", next = "new-pass", confirm = "new-pass")
        submit(s, h)
        assertTrue(s.dialog!!.sending)
        assertEquals(listOf(com.tether.app.ui.settings.ServerFixtures.json("""{"password":"new-pass"}""") to "cur"), h.sent)
        h.reply.complete(ServerSecretOutcome.Saved)
        testScheduler.runCurrent()
        assertNull("success closes the dialog", s.dialog)
        assertEquals(SecretSaved.PasswordChanged, s.saved[ServerSetting.Password])
        assertEquals("the Devices sessions are read again", 1, h.sessionsRefreshed)
    }

    @Test fun aTokenSetAndClearAreOneKeyEachAndDoNotRefreshSessions() = runTest(StandardTestDispatcher()) {
        val s = ServerSecretsState()
        val set = Harness().also { it.reply.complete(ServerSecretOutcome.Saved) }
        s.open(SecretMode.TokenSet)
        fill(s.dialog!!, next = "tok")
        submit(s, set)
        assertEquals(listOf(ServerFixtures.json("""{"proxyToken":"tok"}""")), set.sent.map { it.first })
        assertEquals(SecretSaved.TokenSaved, s.saved[ServerSetting.ProxyToken])
        val clear = Harness().also { it.reply.complete(ServerSecretOutcome.Saved) }
        s.open(SecretMode.TokenClear)
        fill(s.dialog!!)
        submit(s, clear)
        assertEquals(listOf(ServerFixtures.json("""{"proxyToken":""}""")), clear.sent.map { it.first })
        assertEquals(SecretSaved.TokenCleared, s.saved[ServerSetting.ProxyToken])
        assertEquals(0, set.sessionsRefreshed + clear.sessionsRefreshed)
    }

    @Test fun aBadCurrentPasswordShowsTheServersWordsClearsCurrentAndFocusesIt() = runTest(StandardTestDispatcher()) {
        val s = ServerSecretsState()
        val h = Harness()
        s.open(SecretMode.TokenSet)
        val d = s.dialog!!
        fill(d, current = "wrong", next = "tok")
        val before = d.focus!!.nonce
        submit(s, h)
        h.reply.complete(ServerSecretOutcome.Refused("bad_current_password", "That isn't the current password. Nothing was changed."))
        testScheduler.runCurrent()
        assertEquals("That isn't the current password. Nothing was changed.", d.error)
        assertEquals("", d.current)
        assertEquals("New is kept", "tok", d.next)
        assertEquals(SecretField.Current, d.focus!!.field)
        assertTrue(d.focus!!.nonce > before)
        assertFalse(d.sending)
        assertFalse("Save disables until Current is typed again", d.canSubmit)
        assertNotNull("the dialog stays open", s.dialog)
    }

    @Test fun aWeakValueFocusesNewAndKeepsEverythingTyped() = runTest(StandardTestDispatcher()) {
        val s = ServerSecretsState()
        val h = Harness()
        s.open(SecretMode.PasswordSet)
        val d = s.dialog!!
        fill(d, current = "cur", next = "short", confirm = "short")
        submit(s, h)
        h.reply.complete(ServerSecretOutcome.Refused("weak", "The new password must be at least 12 characters in production (got 5)."))
        testScheduler.runCurrent()
        assertEquals("The new password must be at least 12 characters in production (got 5).", d.error)
        assertEquals(SecretField.New, d.focus!!.field)
        assertEquals(Triple("cur", "short", "short"), Triple(d.current, d.next, d.confirm))
        assertTrue("Save re-enables", d.canSubmit)
    }

    @Test fun otherRefusalsOnlyShowTheServersWordsAndANewSubmitClearsThem() = runTest(StandardTestDispatcher()) {
        val s = ServerSecretsState()
        s.open(SecretMode.TokenSet)
        val d = s.dialog!!
        fill(d, next = "tok")
        val throttled = Harness()
        submit(s, throttled)
        throttled.reply.complete(ServerSecretOutcome.Refused("throttled", "Too many wrong attempts from this address. Wait a few minutes, then try again."))
        testScheduler.runCurrent()
        assertEquals("Too many wrong attempts from this address. Wait a few minutes, then try again.", d.error)
        val focusAfter = d.focus
        // Typing does not clear it; a new submit does, at once.
        d.next = "tok2"
        assertNotNull(d.error)
        val again = Harness()
        submit(s, again)
        assertNull(d.error)
        assertTrue(d.sending)
        assertEquals("no focus move for throttled", focusAfter, d.focus)
        again.reply.complete(ServerSecretOutcome.NoReply)
        testScheduler.runCurrent()
        assertEquals(ServerSecretOutcome.NO_REPLY_MESSAGE, d.error)
        assertFalse(d.sending)
    }

    @Test fun closingWipesEverythingAndALaterRefusalIsDropped() = runTest(StandardTestDispatcher()) {
        val s = ServerSecretsState()
        val h = Harness()
        s.open(SecretMode.PasswordSet)
        val d = s.dialog!!
        fill(d, current = "cur", next = "new-pass", confirm = "new-pass")
        submit(s, h)
        s.close()
        assertNull(s.dialog)
        assertEquals(Triple("", "", ""), Triple(d.current, d.next, d.confirm))
        h.reply.complete(ServerSecretOutcome.Refused("weak", "x"))
        testScheduler.runCurrent()
        assertNull("the refusal is dropped", s.dialog)
        assertEquals(emptyMap<ServerSetting, SecretSaved>(), s.saved.toMap())
        s.open(SecretMode.PasswordSet)
        assertNull("the next open starts clean", s.dialog!!.error)
        assertEquals("", s.dialog!!.current)
    }

    @Test fun aSuccessAfterTheDialogWasClosedStillAppliesItsEffects() = runTest(StandardTestDispatcher()) {
        val s = ServerSecretsState()
        val h = Harness()
        s.open(SecretMode.PasswordSet)
        fill(s.dialog!!, next = "new-pass", confirm = "new-pass")
        submit(s, h)
        s.close()
        h.reply.complete(ServerSecretOutcome.Saved)
        testScheduler.runCurrent()
        assertEquals(SecretSaved.PasswordChanged, s.saved[ServerSetting.Password])
        assertEquals(1, h.sessionsRefreshed)
    }

    @Test fun submitSendsNothingWhileDisabledOrForced() = runTest(StandardTestDispatcher()) {
        val s = ServerSecretsState()
        val h = Harness()
        s.open(SecretMode.TokenSet)
        submit(s, h)
        assertTrue("Current and New empty: nothing sent", h.sent.isEmpty())
        fill(s.dialog!!, next = "tok")
        s.submit(this, ServerFixtures.view(envForced = mapOf("proxyToken" to true)), h.change)
        testScheduler.runCurrent()
        assertTrue("the environment forces it: nothing sent", h.sent.isEmpty())
    }
}
