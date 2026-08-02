package com.tether.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun `plain junit assertion works`() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `coroutines test scaffolding works`() = runTest {
        delay(1_000) // virtual time; completes instantly
        assertEquals("tether", "TETHER".lowercase())
    }
}
