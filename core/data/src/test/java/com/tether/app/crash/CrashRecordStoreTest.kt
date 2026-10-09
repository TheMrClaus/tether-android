package com.tether.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class CrashRecordStoreTest {
    @get:Rule val dir = TemporaryFolder()

    private fun record(n: Int = 1, stack: String = "java.lang.IllegalStateException: boom\n\tat a.B.c(B.kt:1)") = CrashRecord(
        timeMs = 1_767_225_600_000L + n, versionName = "0.6.0", versionCode = 16, androidRelease = "16", androidSdk = 36,
        thread = "main", exceptionClass = "java.lang.IllegalStateException", message = "boom ☃ 😀", stack = stack,
    )

    private fun store(name: String = CrashRecordStore.FILE_NAME) = CrashRecordStore(File(dir.root, name))

    @Test
    fun aRecordReadsBackExactlyWithUnicodeAndANullMessage() {
        val s = store()
        assertNull(s.read())
        s.write(record())
        assertEquals(record(), s.read())
        val noMessage = record().copy(message = null)
        s.write(noMessage)
        assertEquals(noMessage, s.read())
    }

    @Test
    fun aNewRecordReplacesTheOldAndLeavesNoTempFile() {
        val s = store()
        s.write(record(1))
        s.write(record(2))
        assertEquals(record(2), s.read())
        assertEquals(listOf(CrashRecordStore.FILE_NAME), dir.root.list()!!.toList())
    }

    @Test
    fun clearDeletesOnlyTheRecord() {
        val other = File(dir.root, "mirror.key").also { it.writeText("keep") }
        val s = store()
        s.write(record())
        s.clear()
        assertNull(s.read())
        assertTrue(other.isFile)
        assertEquals("keep", other.readText())
        s.clear() // nothing to clear is not an error
    }

    @Test
    fun aMissingDirectoryIsMade() {
        val s = CrashRecordStore(File(dir.root, "a/b/${CrashRecordStore.FILE_NAME}"))
        s.write(record())
        assertEquals(record(), s.read())
    }

    @Test
    fun garbageTruncationAndAnEmptyFileReadAsNoRecord() {
        val file = File(dir.root, CrashRecordStore.FILE_NAME)
        val s = CrashRecordStore(file)
        s.write(record())
        val whole = file.readBytes()
        file.writeBytes(whole.copyOf(whole.size / 2))
        assertNull(s.read())
        file.writeBytes(ByteArray(0))
        assertNull(s.read())
        file.writeBytes(ByteArray(300) { (it * 7).toByte() })
        assertNull(s.read())
        file.writeBytes(whole.copyOf(whole.size - 1))
        assertNull(s.read())
    }

    @Test
    fun twoThreadsWritingAtOnceLeaveOneWholeRecordNeverATornOne() {
        val s = store()
        val big = "x".repeat(60_000)
        val start = CountDownLatch(1)
        val writers = (1..6).map { n ->
            thread { start.await(); repeat(20) { s.write(record(n, stack = big + n)) } }
        }
        val reader = thread {
            start.await()
            repeat(200) {
                s.read()?.let { r ->
                    assertEquals(big.length + 1, r.stack.length)
                    assertEquals(r.timeMs - 1_767_225_600_000L, r.stack.last().digitToInt().toLong())
                }
            }
        }
        start.countDown()
        (writers + reader).forEach { it.join() }
        val last = notNull(s.read())
        assertEquals(big.length + 1, last.stack.length)
        assertEquals(last.timeMs - 1_767_225_600_000L, last.stack.last().digitToInt().toLong())
        assertEquals(listOf(CrashRecordStore.FILE_NAME), dir.root.list()!!.toList())
    }

    private fun <T : Any> notNull(v: T?): T = v.also { org.junit.Assert.assertNotNull(it) }!!
}
