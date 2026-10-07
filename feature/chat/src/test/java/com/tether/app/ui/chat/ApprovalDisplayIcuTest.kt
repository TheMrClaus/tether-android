package com.tether.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-57l: [ApprovalModelTest] is plain JUnit, where android.icu is absent and [needsEscape] only has
 * its fallback list. Here ICU answers (Robolectric), so the DEFAULT_IGNORABLE_CODE_POINT branch is
 * exercised for real.
 */
@RunWith(RobolectricTestRunner::class)
class ApprovalDisplayIcuTest {
    @Test fun icuAnswersTheDefaultIgnorableProperty() {
        // Non-null: ICU is live (a plain JVM answers null and falls back to the listed set).
        assertNotNull(icuDefaultIgnorable('a'.code))
        assertEquals(false, icuDefaultIgnorable('a'.code))
        assertEquals(true, icuDefaultIgnorable(0x034F)) // combining grapheme joiner: Mn, ignorable only by property
        assertEquals(true, icuDefaultIgnorable(0x115F)) // Hangul choseong filler
        assertEquals(true, icuDefaultIgnorable(0xFE0F)) // a variation selector
    }

    @Test fun anIcuIgnorableIsShownAsAnEscape() {
        assertTrue(needsEscape(0x034F))
        assertEquals("$LRI“a\\u034Fb”$PDI", displayPath("a\u034Fb"))
        assertEquals("$FSI\\u3164$PDI", displayText("\u3164"))
    }
}
