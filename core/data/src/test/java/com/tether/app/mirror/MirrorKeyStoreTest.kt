package com.tether.app.mirror

import com.tether.app.client.AesGcmCredentialCipher
import com.tether.app.client.SoftwareKeySource
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T13.1 (SYNC_DESIGN §8.1–§8.3): the data key's envelope, with a software KEK standing in for the Keystore. */
class MirrorKeyStoreTest {
    private val dir: File = Files.createTempDirectory("mirror-key").toFile()
    private val file = File(dir, MirrorKeyStore.KEY_FILE)
    private val kekKeys = SoftwareKeySource()
    private val store = MirrorKeyStore(file, AesGcmCredentialCipher(kekKeys))

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun createThenLoadReturnsTheSameKey() {
        assertEquals(MirrorKeyStore.Loaded.Absent, store.load())
        val key = store.create()
        val loaded = store.load() as MirrorKeyStore.Loaded.Present
        assertEquals(key.id, loaded.key.id)
        assertArrayEquals(key.bytes, loaded.key.bytes)
        assertEquals(32, key.bytes.size)
        // The toString never prints the key.
        assertFalse(key.toString().contains(key.bytes.toHex()))
    }

    @Test
    fun theKeyFileHoldsOnlyTheWrappedKey() {
        val key = store.create()
        val onDisk = file.readBytes()
        assertFalse(onDisk.toHex().contains(key.bytes.toHex()))
        assertFalse(onDisk.toHex().contains(key.bytes.copyOfRange(0, 8).toHex()))
    }

    @Test
    fun everyCreateMintsANewKey() {
        val a = store.create()
        val b = store.create()
        assertNotEquals(a.id, b.id)
        assertFalse(a.bytes.contentEquals(b.bytes))
        assertEquals(b.id, (store.load() as MirrorKeyStore.Loaded.Present).key.id)
    }

    @Test
    fun aTamperedFileIsLost() {
        store.create()
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertEquals(MirrorKeyStore.Loaded.Lost, store.load())
    }

    @Test
    fun aGoneKekIsLost() {
        store.create()
        kekKeys.key = null // Keystore reset / restored to a new device
        assertEquals(MirrorKeyStore.Loaded.Lost, store.load())
    }

    @Test
    fun aKeystoreHiccupIsUnavailableNotLost() {
        store.create()
        kekKeys.failure = java.security.KeyStoreException("busy")
        assertEquals(MirrorKeyStore.Loaded.Unavailable, store.load())
        kekKeys.failure = null
        assertTrue(store.load() is MirrorKeyStore.Loaded.Present)
    }

    @Test
    fun destroyRemovesTheFileAndTheKek() {
        store.create()
        store.destroy()
        assertFalse(file.exists())
        assertEquals(1, kekKeys.destroyed)
        assertEquals(MirrorKeyStore.Loaded.Absent, store.load())
    }

    @Test
    fun deleteDataKeyKeepsTheKek() {
        store.create()
        store.deleteDataKey()
        assertFalse(file.exists())
        assertEquals(0, kekKeys.destroyed)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
