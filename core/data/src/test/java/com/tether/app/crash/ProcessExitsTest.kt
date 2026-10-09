package com.tether.app.crash

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProcessExitsTest {
    @Test
    fun readingTheHistoryNeverThrowsAndRespectsTheCount() {
        val exits = ProcessExits.read(ApplicationProvider.getApplicationContext(), 5)
        assertTrue(exits.size <= 5)
    }
}
