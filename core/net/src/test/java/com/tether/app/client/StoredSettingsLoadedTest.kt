package com.tether.app.client

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T4.4: a cold-start deep link waits for [RealTetherClient.storedSettingsLoaded] and then reads
 * [RealTetherClient.serverUrl] and [RealTetherClient.configured]. Whoever sees it flip to true
 * must already see the stored server and sign-in state, or the link binds to no server.
 */
class StoredSettingsLoadedTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    /** What an observer reads at the instant the flag turns true, captured on the writer's thread. */
    private fun seenWhenLoaded(settings: SettingsStore): Pair<String?, Boolean> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val seen = CopyOnWriteArrayList<Pair<String?, Boolean>>()
        lateinit var client: RealTetherClient
        val observer = CoroutineScope(Dispatchers.Unconfined).also { scopes += it }
        client = RealTetherClient(
            settings = settings,
            httpClient = OkHttpClient(),
            scope = scope,
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = ManualScheduler(),
        )
        observer.launch(start = CoroutineStart.UNDISPATCHED) {
            client.storedSettingsLoaded.first { it }
            seen += client.serverUrl.value to client.configured.value
        }
        withTimeout(5_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(5) }
        seen.first()
    }

    @Test
    fun aStoredSignInIsVisibleTheMomentTheFlagFlips() {
        val (url, configured) = seenWhenLoaded(InMemorySettings(initialBaseUrl = "https://tether.example.com", initialCookie = "c"))
        assertEquals("https://tether.example.com", url)
        assertEquals(true, configured)
    }

    @Test
    fun aSignedOutStoreStillKeepsItsServer() {
        val (url, configured) = seenWhenLoaded(InMemorySettings(initialBaseUrl = "https://tether.example.com"))
        assertEquals("https://tether.example.com", url)
        assertFalse(configured)
    }

    @Test
    fun anEmptyStoreLoadsAsNoServer() {
        val (url, configured) = seenWhenLoaded(InMemorySettings())
        assertNull(url)
        assertFalse(configured)
    }
}
