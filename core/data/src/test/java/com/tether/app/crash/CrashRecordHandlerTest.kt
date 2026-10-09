package com.tether.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CrashRecordHandlerTest {
    @get:Rule val dir = TemporaryFolder()

    private val build = CrashBuildInfo("0.6.0", 16, "16", 36)

    private class Seen : Thread.UncaughtExceptionHandler {
        val threads = mutableListOf<Thread>()
        val errors = mutableListOf<Throwable>()
        override fun uncaughtException(t: Thread, e: Throwable) {
            threads += t
            errors += e
        }
    }

    private fun file() = File(dir.root, CrashRecordStore.FILE_NAME)

    @Test
    fun writesTheRecordThenDelegatesWithTheSameThreadAndThrowable() {
        val seen = Seen()
        val handler = CrashRecordHandler(CrashRecordStore(file()), seen, build, clock = { 1_700_000_000_123L })
        val worker = Thread({}, "worker-7")
        val error = IllegalStateException("boom", IllegalArgumentException("root"))

        handler.uncaughtException(worker, error)

        assertSame(worker, seen.threads.single())
        assertSame(error, seen.errors.single())
        val r = notNull(CrashRecordStore(file()).read())
        assertEquals(1_700_000_000_123L, r.timeMs)
        assertEquals("0.6.0", r.versionName)
        assertEquals(16L, r.versionCode)
        assertEquals("16", r.androidRelease)
        assertEquals(36, r.androidSdk)
        assertEquals("worker-7", r.thread)
        assertEquals("java.lang.IllegalStateException", r.exceptionClass)
        assertEquals("boom", r.message)
        assertTrue(r.stack.contains("Caused by: java.lang.IllegalArgumentException: root"))
    }

    @Test
    fun aStoreThatThrowsStillDelegates() {
        val seen = Seen()
        val store = object : CrashRecordStoreWriter {
            override fun write(record: CrashRecord) = throw java.io.IOException("disk full")
        }
        val handler = CrashRecordHandler(store, seen, build)
        val error = RuntimeException("x")
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, seen.errors.single())
    }

    @Test
    fun aStoreThatThrowsOutOfMemoryStillDelegates() {
        val seen = Seen()
        val store = object : CrashRecordStoreWriter {
            override fun write(record: CrashRecord) = throw OutOfMemoryError("no room")
        }
        val handler = CrashRecordHandler(store, seen, build)
        val error = RuntimeException("x")
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, seen.errors.single())
    }

    @Test
    fun aClockThatThrowsStillDelegates() {
        val seen = Seen()
        val handler = CrashRecordHandler(CrashRecordStore(file()), seen, build, clock = { throw IllegalStateException("no clock") })
        val error = RuntimeException("x")
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, seen.errors.single())
        assertNull(CrashRecordStore(file()).read())
    }

    @Test
    fun aThrowableWhoseMessageThrowsStillDelegates() {
        val seen = Seen()
        val handler = CrashRecordHandler(CrashRecordStore(file()), seen, build)
        val error = object : RuntimeException("x") {
            override val message: String get() = throw IllegalStateException("hostile getMessage")
            override fun toString(): String = throw IllegalStateException("hostile toString")
        }
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, seen.errors.single())
    }

    @Test
    fun aCrashInsideTheHandlerItselfSkipsTheWriteAndStillDelegates() {
        val seen = Seen()
        lateinit var handler: CrashRecordHandler
        val writes = AtomicInteger()
        val inner = RuntimeException("inner")
        val store = object : CrashRecordStoreWriter {
            override fun write(record: CrashRecord) {
                writes.incrementAndGet()
                // The platform dispatches a throw from our own path to the same handler, on the same thread.
                handler.uncaughtException(Thread.currentThread(), inner)
            }
        }
        handler = CrashRecordHandler(store, seen, build)
        val outer = RuntimeException("outer")
        handler.uncaughtException(Thread.currentThread(), outer)
        assertEquals("the nested crash does not write again", 1, writes.get())
        assertEquals(listOf<Throwable>(inner, outer), seen.errors)
        // and the guard is released: the next crash writes again
        handler.uncaughtException(Thread.currentThread(), RuntimeException("later"))
        assertEquals(2, writes.get())
    }

    @Test
    fun noPreviousHandlerIsNotAnError() {
        val handler = CrashRecordHandler(CrashRecordStore(file()), null, build)
        handler.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        notNull(CrashRecordStore(file()).read())
    }

    @Test
    fun twoThreadsCrashingAtOnceBothDelegateAndLeaveOneWholeRecord() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.UncaughtExceptionHandler { _, e -> seen += e }
        val handler = CrashRecordHandler(CrashRecordStore(file()), previous, build)
        val start = CountDownLatch(1)
        val errors = (1..2).map { RuntimeException("crash $it " + "q".repeat(30_000)) }
        val threads = errors.mapIndexed { i, e -> thread(name = "t$i") { start.await(); handler.uncaughtException(Thread.currentThread(), e) } }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(2, seen.size)
        val r = notNull(CrashRecordStore(file()).read())
        assertTrue(r.message!!.startsWith("crash "))
        assertTrue(r.thread == "t0" || r.thread == "t1")
        assertEquals(listOf(CrashRecordStore.FILE_NAME), dir.root.list()!!.toList())
    }

    @Test
    fun installChainsToTheCurrentDefaultAndASecondInstallDoesNothing() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val seen = Seen()
            Thread.setDefaultUncaughtExceptionHandler(seen)
            val store = CrashRecordStore(file())
            val installed = CrashRecordHandler.install(store, build)
            assertNotNull(installed)
            assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
            assertNull(CrashRecordHandler.install(store, build))
            val error = RuntimeException("x")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertSame(error, seen.errors.single())
            notNull(store.read())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test
    fun theHandlerNeverCallsTheProcessEndingApis() {
        // The process dies exactly as before: only the previous handler may end it. (Structural: no exit / kill in the sources.)
        val src = File("src/main/java/com/tether/app/crash/CrashRecordHandler.kt").readText()
        assertTrue(!src.contains("exitProcess") && !src.contains("System.exit") && !src.contains("killProcess") && !src.contains("android.util.Log"))
    }

    private fun <T : Any> notNull(v: T?): T = v.also { assertNotNull(it) }!!
}
