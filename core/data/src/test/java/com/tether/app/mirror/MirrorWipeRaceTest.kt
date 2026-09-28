package com.tether.app.mirror

import com.tether.app.mirror.MirrorFixture.Companion.obj
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-hra (T13.1 round-2 security re-review, R1 / R3 / R4): a wipe that lands while the writer
 * is busy leaves no key minted after it, runs no op queued before it, never waits for a writer
 * stuck inside the Keystore, and still deletes the files when the writer dies around it.
 *
 * A "process death" at an instant is modelled by reading the disk AT that instant, on the
 * writer, with a fresh [MirrorKeyStore] over the same key file and KEK (what the next process
 * would find): a readable data key there is the finding.
 */
@RunWith(RobolectricTestRunner::class)
class MirrorWipeRaceTest {
    private val origin = "https://tether.example:443"
    private val fx = MirrorFixture()
    private val m get() = fx.mirror
    private val budgetS = 20L

    @After
    fun tearDown() = fx.close()

    private fun state(marker: String) = obj("""{"tetherSessionId":"s","marker":"$marker","turnsById":{},"turnOrder":[]}""")

    /** Process 1 leaves [origin] mirrored (s1, sealed under key K1); [fx] is then process 2. */
    private suspend fun mirroredThenRestarted() {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state("old-sign-in"), emptySet())
        m.flush()
        fx.restart()
    }

    /** What the next process would find: never a readable data key after a wipe. */
    private fun assertNothingReadableSurvives() {
        assertFalse("a key file survived the wipe", fx.keyFile.exists())
        assertTrue("a mirror DB survived the wipe", fx.factory.existing().isEmpty())
        fx.restart()
        runBlocking {
            val index = m.bind(origin)!!
            assertTrue(index.sessions.isEmpty() && index.cursors.isEmpty())
            for (id in listOf("s1", "s2", "s3")) assertEquals(Hydration.None, m.hydrate(origin, id))
        }
    }

    // ---- R1: a bind or rotation in flight / queued when the wipe lands ----

    @Test
    fun aBindInsideTheKeystoreWhenTheWipeLandsLeavesNoReadableKeyAtADeathRightAfterIt() = runBlocking {
        mirroredThenRestarted()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        // The next bind's unwrap of K1 is "in the Keystore" while the wipe lands.
        fx.beforeKekOpen = {
            fx.beforeKekOpen = null
            entered.countDown()
            gate.await(budgetS, TimeUnit.SECONDS)
        }
        val atDeath = AtomicReference<MirrorKeyStore.Loaded>()
        val deathThread = AtomicReference<Thread>()
        // Unconfined: the continuation runs on the writer, inside the bind's reply, i.e. at the
        // instant before the writer's next op (the Wipe). The process dies there.
        val bind = async(Dispatchers.Unconfined) {
            val index = m.bind(origin)
            deathThread.set(Thread.currentThread())
            atDeath.set(fx.keyStore().load())
            index
        }
        assertTrue(entered.await(budgetS, TimeUnit.SECONDS))
        m.recordState(origin, "s2", 5, null, state("queued-before-wipe"), emptySet())
        val keksBefore = fx.kekKeys.created
        val wiped = m.wipe()
        assertFalse("shredded on the calling thread", fx.keyFile.exists())
        gate.countDown()
        withTimeout(budgetS * 1_000) {
            assertNull("a bind the wipe overtook answers null", bind.await())
            wiped.await()
        }
        // L-1: a writer that already lost the race never even asks the Keystore for a key.
        assertEquals("the overtaken bind generated a Keystore key", keksBefore, fx.kekKeys.created)
        assertNotNull(deathThread.get())
        assertNotSame("the death point is on the writer", Thread.currentThread(), deathThread.get())
        assertFalse(
            "the bind minted a key after the shred and it outlived the wipe (${atDeath.get()})",
            atDeath.get() is MirrorKeyStore.Loaded.Present,
        )
        assertNothingReadableSurvives()
    }

    @Test
    fun theEpochMovesBeforeTheShredSoAKeyMintedWhileTheShredRunsIsNotKept() = runBlocking {
        mirroredThenRestarted()
        fx.keyFile.delete() // the next bind mints a key: Absent -> create()
        val sealing = CountDownLatch(1)
        val letSeal = CountDownLatch(1)
        fx.beforeKekSeal = {
            fx.beforeKekSeal = null
            sealing.countDown()
            letSeal.await(budgetS, TimeUnit.SECONDS)
        }
        val bindAnswered = CountDownLatch(1)
        val atDeath = AtomicReference<MirrorKeyStore.Loaded>()
        val bind = async(Dispatchers.Unconfined) {
            val index = m.bind(origin)
            atDeath.set(fx.keyStore().load()) // on the writer: a death right after the bind
            bindAnswered.countDown()
            index
        }
        assertTrue(sealing.await(budgetS, TimeUnit.SECONDS))
        // The wiping thread is paused inside its Keystore delete (the key file is already
        // gone) while the writer finishes minting and checks for a wipe.
        val pausedOnce = AtomicBoolean()
        fx.beforeKekDestroy = {
            if (pausedOnce.compareAndSet(false, true)) {
                letSeal.countDown()
                bindAnswered.await(5, TimeUnit.SECONDS)
            }
        }
        val wiped = AtomicReference<kotlinx.coroutines.CompletableDeferred<Unit>>()
        val caller = Thread { wiped.set(m.wipe()) }
        caller.start()
        caller.join(budgetS * 1_000)
        assertFalse(caller.isAlive)
        withTimeout(budgetS * 1_000) {
            assertNull("the bind saw no wipe: its epoch moved only after the shred", bind.await())
            wiped.get().await()
        }
        assertFalse("a key minted during the shred was kept (${atDeath.get()})", atDeath.get() is MirrorKeyStore.Loaded.Present)
        assertNothingReadableSurvives()
    }

    @Test
    fun opsQueuedBeforeTheWipeAreAnsweredAtOnceAndNeverRunSoNoRotationMintsAKeyForThem() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state("old-sign-in"), emptySet())
        m.flush()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        m.beforeHydrateRead = {
            m.beforeHydrateRead = null
            entered.countDown()
            gate.await(budgetS, TimeUnit.SECONDS)
        }
        val held = m.hydrateAsync(origin, "s1")
        assertTrue(entered.await(budgetS, TimeUnit.SECONDS))
        // Queued behind the held op, all before the wipe: a write, a rotation, a write, a read.
        m.recordState(origin, "s2", 5, null, state("queued-before-wipe"), emptySet())
        val rotate = async(Dispatchers.Unconfined) { m.clearAndRotate() } // enqueued before this returns
        m.recordState(origin, "s3", 5, null, state("queued-before-wipe"), emptySet())
        val read = m.hydrateAsync(origin, "s3")
        // If that read ever ran, it would run after the rotation and the s3 write: a death there.
        val keyAtDeath = AtomicReference<MirrorKeyStore.Loaded>()
        read.invokeOnCompletion { keyAtDeath.set(fx.keyStore().load()) }
        val wiped = m.wipe()
        withTimeout(2_000) {
            // Answered with their fallbacks while the writer is still held.
            rotate.await()
            assertEquals(Hydration.None, read.await())
        }
        assertFalse("the writer is still held", held.isCompleted)
        gate.countDown()
        withTimeout(budgetS * 1_000) {
            held.await()
            wiped.await()
        }
        assertFalse("a rotation queued before the wipe minted a key", keyAtDeath.get() is MirrorKeyStore.Loaded.Present)
        assertNothingReadableSurvives()
    }

    @Test
    fun aBindThatLoadedTheOldKeyBeforeTheWipeNeverHandsOutTheWipedIndex() = runBlocking {
        mirroredThenRestarted()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        // K1 is already unwrapped; the bind is opening the DB when the wipe lands.
        fx.beforeDbOpen = {
            fx.beforeDbOpen = null
            entered.countDown()
            gate.await(budgetS, TimeUnit.SECONDS)
        }
        val bind = async(Dispatchers.Default) { m.bind(origin) }
        assertTrue(entered.await(budgetS, TimeUnit.SECONDS))
        val wiped = m.wipe()
        gate.countDown()
        withTimeout(budgetS * 1_000) {
            assertNull("the old sign-in's list and cursors are never handed out", bind.await())
            wiped.await()
        }
        assertNothingReadableSurvives()
    }

    // ---- R3: the shred never waits for the writer ----

    @Test
    fun aWipeNeverWaitsForAWriterStuckInsideTheKeystore() = runBlocking {
        mirroredThenRestarted()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        fx.beforeKekOpen = {
            fx.beforeKekOpen = null
            entered.countDown()
            gate.await(budgetS, TimeUnit.SECONDS) // a Keystore call that does not come back
        }
        val bind = async(Dispatchers.Default) { m.bind(origin) }
        assertTrue(entered.await(budgetS, TimeUnit.SECONDS))
        val wiped = AtomicReference<kotlinx.coroutines.CompletableDeferred<Unit>>()
        val returned = CountDownLatch(1)
        val caller = Thread {
            wiped.set(m.wipe())
            returned.countDown()
        }
        try {
            caller.start()
            assertTrue("wipe() waited for the stuck writer", returned.await(5, TimeUnit.SECONDS))
            // Already unreadable, with the writer still stuck.
            assertFalse(fx.keyFile.exists())
            assertTrue(fx.kekKeys.destroyed >= 1)
            assertFalse("the writer is still stuck", bind.isCompleted)
        } finally {
            gate.countDown()
        }
        withTimeout(budgetS * 1_000) {
            assertNull(bind.await())
            wiped.get().await()
        }
        assertNothingReadableSurvives()
    }

    @Test
    fun theKeyFileGoesBeforeTheKeystoreCallSoADeathInsideItLeavesNothingReadable() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state("old-sign-in"), emptySet())
        m.flush()
        val fileAtKeystoreCall = AtomicReference<Boolean>()
        fx.beforeKekDestroy = {
            fx.beforeKekDestroy = null
            fileAtKeystoreCall.set(fx.keyFile.exists())
            throw IllegalStateException("keystore down") // best effort: the wipe goes on
        }
        withTimeout(budgetS * 1_000) { m.wipe().await() }
        assertEquals("the key file was still there during the Keystore call", false, fileAtKeystoreCall.get())
        assertNothingReadableSurvives()
    }

    // ---- R4: a writer that dies around a wipe still deletes the files ----

    @Test
    fun aWriterThatDiesWithTheWipeQueuedStillDeletesTheFiles() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state("old-sign-in"), emptySet())
        m.flush()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        m.beforeHydrateRead = {
            entered.countDown()
            gate.await(budgetS, TimeUnit.SECONDS)
            throw OutOfMemoryError("simulated")
        }
        m.hydrateAsync(origin, "s1")
        assertTrue(entered.await(budgetS, TimeUnit.SECONDS))
        val wiped = m.wipe() // queued: the writer is alive, and held
        assertTrue("the writer never ran the wipe", fx.dbFile(origin).isFile)
        gate.countDown()
        withTimeout(budgetS * 1_000) { wiped.await() }
        assertTrue(m.dead)
        assertTrue("the dead writer answered the wipe but left its files", fx.factory.existing().isEmpty())
        assertFalse(fx.dbFile(origin).exists())
        assertFalse(fx.keyFile.exists())
    }

    @Test
    fun aWipeThatDiesHalfWayWithAnErrorStillDeletesTheFiles() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state("old-sign-in"), emptySet())
        m.flush()
        val thrown = AtomicBoolean()
        fx.beforeDbDelete = { if (thrown.compareAndSet(false, true)) throw StackOverflowError("simulated") }
        withTimeout(budgetS * 1_000) { m.wipe().await() }
        assertTrue("the injected Error fired", thrown.get())
        assertTrue("a wipe that failed half-way left files", fx.factory.existing().isEmpty())
        // The reply precedes die() (handle() answers, then rethrows): wait for the writer to stop.
        val deadline = System.currentTimeMillis() + budgetS * 1_000
        while (!m.dead) {
            assertTrue("the writer never stopped", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
        assertFalse(fx.keyFile.exists())
    }

    // ---- round 2 (F2, F3, I-2) ----

    @Test
    fun aBindAfterAWipeInTheSameProcessGetsAWorkingKey() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state("old-sign-in"), emptySet())
        m.flush()
        withTimeout(budgetS * 1_000) { m.wipe().await() }
        // The next sign-in, same process: the wipe is over, so its bind is not overtaken.
        val index = withTimeout(budgetS * 1_000) { m.bind(origin) }
        assertNotNull("a bind after the wipe got no mirror", index)
        assertTrue(index!!.sessions.isEmpty() && index.cursors.isEmpty())
        assertTrue(fx.keyStore().load() is MirrorKeyStore.Loaded.Present)
        m.recordState(origin, "s2", 3, null, state("new-sign-in"), emptySet())
        m.flush()
        assertEquals(state("new-sign-in"), (m.hydrate(origin, "s2") as Hydration.Loaded).session.base)
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
    }

    @Test
    fun aDyingWriterDeletesTheFilesBeforeItAnswersTheWipe() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state("old-sign-in"), emptySet())
        m.flush()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        m.beforeHydrateRead = {
            entered.countDown()
            gate.await(budgetS, TimeUnit.SECONDS)
            throw OutOfMemoryError("simulated")
        }
        m.hydrateAsync(origin, "s1")
        assertTrue(entered.await(budgetS, TimeUnit.SECONDS))
        val wiped = m.wipe()
        val deleting = CountDownLatch(1)
        val letDelete = CountDownLatch(1)
        fx.beforeDbDelete = {
            fx.beforeDbDelete = null
            deleting.countDown()
            letDelete.await(budgetS, TimeUnit.SECONDS)
        }
        gate.countDown()
        try {
            assertTrue("die() never deleted", deleting.await(budgetS, TimeUnit.SECONDS))
            assertFalse("the wipe was answered before its files were deleted", wiped.isCompleted)
        } finally {
            letDelete.countDown()
        }
        withTimeout(budgetS * 1_000) { wiped.await() }
        assertTrue(fx.factory.existing().isEmpty())
    }

    @Test
    fun aSecondWipeNeverAnswersAQueuedOneBeforeTheFilesAreGone() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state("old-sign-in"), emptySet())
        m.flush()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        m.beforeHydrateRead = {
            m.beforeHydrateRead = null
            entered.countDown()
            gate.await(budgetS, TimeUnit.SECONDS)
        }
        val held = m.hydrateAsync(origin, "s1")
        assertTrue(entered.await(budgetS, TimeUnit.SECONDS))
        val first = m.wipe()
        val second = m.wipe()
        try {
            assertFalse("the first wipe was answered with its files still there", first.isCompleted)
            assertFalse(second.isCompleted)
            assertTrue(fx.dbFile(origin).isFile)
        } finally {
            gate.countDown()
        }
        withTimeout(budgetS * 1_000) {
            held.await()
            first.await()
            second.await()
        }
        assertNothingReadableSurvives()
    }
}
