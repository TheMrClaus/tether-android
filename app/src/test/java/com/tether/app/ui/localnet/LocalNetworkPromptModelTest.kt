package com.tether.app.ui.localnet

import org.junit.Assert.assertEquals
import org.junit.Test

/** The permission flow's states: explain -> request -> granted/denied, persistent denial, no loops. */
class LocalNetworkPromptModelTest {

    private val model = LocalNetworkPromptModel()
    private var retries = 0
    private val retry: () -> Unit = { retries++ }

    @Test
    fun `first block explains before the system request, grant retries exactly once`() {
        model.onBlocked(LocalNetworkSource.Connection, retry, canRequest = true)
        assertEquals(LocalNetworkPhase.Explaining, model.phase)
        model.onExplainContinue()
        assertEquals(LocalNetworkPhase.Requesting, model.phase)
        model.onPermissionResult(granted = true, canRequest = false)
        assertEquals(LocalNetworkPhase.Idle, model.phase)
        assertEquals(1, retries)
        // A later resume must not replay the retry.
        model.onResumed(granted = true)
        assertEquals(1, retries)
    }

    @Test
    fun `denial is a persistent state, and nothing retries while denied`() {
        model.onBlocked(LocalNetworkSource.Login, retry, canRequest = true)
        model.onExplainContinue()
        model.onPermissionResult(granted = false, canRequest = true)
        assertEquals(LocalNetworkPhase.Denied(canRequest = true), model.phase)
        // The client reports the block again (resume / network change): the
        // notice stays, with no second explanation and no retry.
        model.onBlocked(LocalNetworkSource.Login, retry, canRequest = true)
        model.onResumed(granted = false)
        assertEquals(LocalNetworkPhase.Denied(canRequest = true), model.phase)
        assertEquals(0, retries)
    }

    @Test
    fun `denied for good - Allow means app settings, and a grant made there is picked up on resume`() {
        model.onBlocked(LocalNetworkSource.Connection, retry, canRequest = true)
        model.onExplainContinue()
        model.onPermissionResult(granted = false, canRequest = false)
        assertEquals(LocalNetworkPhase.Denied(canRequest = false), model.phase)
        model.onResumed(granted = true)
        assertEquals(LocalNetworkPhase.Idle, model.phase)
        assertEquals(1, retries)
    }

    @Test
    fun `Not now goes straight to the persistent notice`() {
        model.onBlocked(LocalNetworkSource.Login, retry, canRequest = true)
        model.onExplainDismissed(canRequest = true)
        assertEquals(LocalNetworkPhase.Denied(canRequest = true), model.phase)
        model.onRequestLaunched()
        assertEquals(LocalNetworkPhase.Requesting, model.phase)
        model.onPermissionResult(granted = true, canRequest = false)
        assertEquals(1, retries)
    }

    @Test
    fun `when the system will not ask again, the explanation is skipped`() {
        model.onBlocked(LocalNetworkSource.Connection, retry, canRequest = false)
        assertEquals(LocalNetworkPhase.Denied(canRequest = false), model.phase)
    }

    @Test
    fun `a source clears only its own prompt`() {
        model.onBlocked(LocalNetworkSource.Login, retry, canRequest = true)
        model.clear(LocalNetworkSource.Connection)
        assertEquals(LocalNetworkPhase.Explaining, model.phase)
        model.clear(LocalNetworkSource.Login)
        assertEquals(LocalNetworkPhase.Idle, model.phase)
        model.onResumed(granted = true)
        assertEquals(0, retries)
    }
}
