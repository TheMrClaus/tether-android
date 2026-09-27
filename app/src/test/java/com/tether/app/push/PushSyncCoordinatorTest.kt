package com.tether.app.push

import com.tether.app.client.Credential
import com.tether.app.client.InMemorySettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PushSyncCoordinator]: when the server's push row is POSTed (full, replaces
 * the row) or PATCHed, and that `syncHints` is always what the device stored.
 *
 * The regression this guards: before T12.1 a token rotation (`onNewToken`)
 * only set flags. The prefs combine never re-emits on its own, so the new token
 * did not reach the server. The fix must also resend the opt-in, because the
 * POST replaces the row and an absent `syncHints` is stored as false (T13.4
 * note, S13.1 review).
 */
class PushSyncCoordinatorTest {

    private class RecordingRegistrar(
        var syncResult: PushRegistrarResult = PushRegistrarResult.Success,
        var updateResult: PushRegistrarResult = PushRegistrarResult.Success,
    ) : PushRegistrar(InMemorySettings(), OkHttpClient(), FirebaseTokenProvider { "fake-fcm-token-not-a-credential" }) {
        val calls = mutableListOf<String>()

        override suspend fun sync(scope: PushScope, attached: Set<String>, pinned: Set<String>, syncHints: Boolean): PushRegistrarResult {
            calls += "POST ${scope.wire} ${attached.sorted()} ${pinned.sorted()} syncHints=$syncHints"
            return syncResult
        }

        override suspend fun update(scope: PushScope, attached: Set<String>, pinned: Set<String>, syncHints: Boolean): PushRegistrarResult {
            calls += "PATCH ${scope.wire} ${attached.sorted()} ${pinned.sorted()} syncHints=$syncHints"
            return updateResult
        }

        override suspend fun unregister(): PushRegistrarResult {
            calls += "DELETE"
            return PushRegistrarResult.Success
        }

        override suspend fun unregister(baseUrl: String, credential: Credential): PushRegistrarResult {
            calls += "DELETE logout"
            return PushRegistrarResult.Success
        }
    }

    private val serverA = PushServerIdentity("https://a.tether.invalid/", Credential.DeviceToken("fake-device-token-a"))
    private val serverB = PushServerIdentity("https://b.tether.invalid/", Credential.DeviceToken("fake-device-token-b"))

    private fun request(
        enabled: Boolean = true,
        scope: PushScope = PushScope.Attached,
        attached: Set<String> = setOf("s1"),
        pinned: Set<String> = emptySet(),
        syncHints: Boolean = true,
        server: PushServerIdentity? = serverA,
    ) = PushSyncRequest(enabled, scope, attached, pinned, syncHints, server)

    @Test
    fun firstRequestPostsTheWholeRowIncludingTheOptIn() = runBlocking {
        val registrar = RecordingRegistrar()
        PushSyncCoordinator(registrar).onRequest(request())
        assertEquals(listOf("POST attached [s1] [] syncHints=true"), registrar.calls)
    }

    @Test
    fun laterChangesPatchWithTheOptIn() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request())
        coordinator.onRequest(request(attached = setOf("s1", "s2")))
        coordinator.onRequest(request(attached = setOf("s1", "s2"), syncHints = false))
        assertEquals(
            listOf(
                "POST attached [s1] [] syncHints=true",
                "PATCH attached [s1, s2] [] syncHints=true",
                "PATCH attached [s1, s2] [] syncHints=false",
            ),
            registrar.calls,
        )
    }

    @Test
    fun tokenRotationRePostsAtOnceAndResendsTheStoredOptIn() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request(scope = PushScope.Pinned, pinned = setOf("p1"), syncHints = true))
        registrar.calls.clear()

        coordinator.onTokenRotated()

        assertEquals(listOf("POST pinned [s1] [p1] syncHints=true"), registrar.calls)
    }

    @Test
    fun tokenRotationResendsAnOptOutExplicitly() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request(syncHints = false))
        registrar.calls.clear()
        coordinator.onTokenRotated()
        assertEquals(listOf("POST attached [s1] [] syncHints=false"), registrar.calls)
    }

    @Test
    fun rotationBeforeAnyPrefsWaitsForTheFirstEmission() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onTokenRotated()
        assertEquals(emptyList<String>(), registrar.calls)
        coordinator.onRequest(request())
        assertEquals(listOf("POST attached [s1] [] syncHints=true"), registrar.calls)
    }

    @Test
    fun rotationWhileDisabledRegistersNothing() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request(enabled = false))
        coordinator.onTokenRotated()
        assertEquals(emptyList<String>(), registrar.calls)
    }

    @Test
    fun aFailedRotationRetriesTheFullPostOnTheNextChange() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request())
        registrar.syncResult = PushRegistrarResult.Error("Push register request failed.")
        coordinator.onTokenRotated()
        registrar.syncResult = PushRegistrarResult.Success
        registrar.calls.clear()
        coordinator.onRequest(request(attached = setOf("s9")))
        assertEquals(listOf("POST attached [s9] [] syncHints=true"), registrar.calls)
    }

    @Test
    fun aVanishedRowFallsBackToTheFullPost() = runBlocking {
        val registrar = RecordingRegistrar(updateResult = PushRegistrarResult.Error("Not registered yet."))
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request())
        coordinator.onRequest(request(attached = setOf("s2")))
        assertEquals(
            listOf(
                "POST attached [s1] [] syncHints=true",
                "PATCH attached [s2] [] syncHints=true",
                "POST attached [s2] [] syncHints=true",
            ),
            registrar.calls,
        )
    }

    @Test
    fun disableUnregistersAndReEnablePostsAgain() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request())
        coordinator.onRequest(request(enabled = false))
        coordinator.onRequest(request())
        assertEquals(
            listOf("POST attached [s1] [] syncHints=true", "DELETE", "POST attached [s1] [] syncHints=true"),
            registrar.calls,
        )
    }

    @Test
    fun logoutUnregistersAndTheNextSignInPostsAgain() = runBlocking {
        // The identity sequence production emits: signed in, logout (the hook,
        // then the credential flow goes null), signed in again.
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request())
        coordinator.onLoggedOut("https://a.tether.invalid/", Credential.DeviceToken("fake-device-token-a"))
        coordinator.onRequest(request(server = null))
        coordinator.onRequest(request())
        assertEquals(
            listOf("POST attached [s1] [] syncHints=true", "DELETE logout", "POST attached [s1] [] syncHints=true"),
            registrar.calls,
        )
    }

    @Test
    fun withoutAPairedDeviceNothingIsCalled() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request(server = null))
        coordinator.onRequest(request(server = null, attached = setOf("s2")))
        coordinator.onTokenRotated()
        coordinator.onRequest(request(server = null, enabled = false))
        assertEquals(emptyList<String>(), registrar.calls)
    }

    @Test
    fun aSignInWithUnchangedPrefsPostsOnce() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request(server = null))
        coordinator.onRequest(request(server = serverA))
        coordinator.onRequest(request(server = serverA))
        assertEquals(listOf("POST attached [s1] [] syncHints=true"), registrar.calls)
    }

    @Test
    fun aServerSwitchPostsTheWholeRowToTheNewServer() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request(server = serverA))
        coordinator.onRequest(request(server = null))
        coordinator.onRequest(request(server = serverB))
        assertEquals(
            listOf("POST attached [s1] [] syncHints=true", "POST attached [s1] [] syncHints=true"),
            registrar.calls,
        )
    }

    @Test
    fun aDisableRightAfterASwitchDeletesNothingOnTheNewServer() = runBlocking {
        val registrar = RecordingRegistrar()
        val coordinator = PushSyncCoordinator(registrar)
        coordinator.onRequest(request(server = serverA))
        coordinator.onRequest(request(server = serverB, enabled = false))
        assertEquals(listOf("POST attached [s1] [] syncHints=true"), registrar.calls)
    }

    @Test
    fun theDefaultOptInSourceIsOffUntilT134() = runBlocking {
        var value: Boolean? = null
        SyncHintsSource.Off.optedIn().collect { value = it }
        assertEquals(false, value)
    }
}
