package com.tether.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * ta-ouu item 1: only push fails quietly. A job on the app scope (the settings
 * store, the protocol client) that throws must stay uncaught: it goes down the
 * platform's uncaught-exception path (the ServiceLoader handlers, then the
 * thread's uncaught-exception handler, which on Android crashes the process).
 * A push job that throws is contained, and only its class name is logged.
 *
 * The uncaught path is observed through runTest, whose collector sits first
 * on that path and fails the test with anything uncaught. (Observing the
 * thread handler directly would leave the exception queued in that collector,
 * to fail some later, unrelated test.) Both scopes run on the production
 * dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppScopesTest {

    @Test
    fun anExceptionInANonPushJobStaysUncaught() {
        val appScope = AppScopes.app()
        // Push being wired alongside must not change that.
        AppScopes.push(appScope)
        try {
            runTest {
                appScope.launch { throw IllegalStateException("fake-configured-collector-died") }.join()
            }
            fail("the app scope contained the exception; it must crash")
        } catch (e: IllegalStateException) {
            assertEquals("fake-configured-collector-died", e.message)
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun anExceptionInAPushJobIsContainedAndLoggedByClassNameOnly() = runTest {
        ShadowLog.clear()
        val appScope = AppScopes.app()
        val pushScope = AppScopes.push(appScope)

        // runTest fails on anything uncaught, so returning normally means contained.
        pushScope.launch { throw IllegalStateException("fake-server-content-must-not-be-logged") }.join()
        // The push scope and the app scope carry on, and so does a sibling push job.
        assertTrue(appScope.isActive)
        assertTrue(pushScope.isActive)
        val sibling = CompletableDeferred<Unit>()
        pushScope.launch { sibling.complete(Unit) }
        sibling.await()

        val logs = ShadowLog.getLogsForTag(AppScopes.TAG).map { it.msg }
        assertEquals(listOf("Background push job failed: IllegalStateException"), logs)
        assertFalse(logs.any { it.contains("fake-server-content") })

        // Push is a child: cancelling the app scope cancels it.
        appScope.cancel()
        assertFalse(pushScope.isActive)
    }
}
