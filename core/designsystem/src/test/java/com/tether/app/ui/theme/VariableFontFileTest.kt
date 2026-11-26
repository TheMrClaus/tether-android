package com.tether.app.ui.theme

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the bundled TTFs' own tables (not the Kotlin constants): the `wght` axis each file
 * really has must cover every weight the families request, and the UI face must carry the
 * `tnum` feature so `font-variant-numeric: tabular-nums` roles are not a silent no-op.
 * (Fira Code is monospaced, so its figures are tabular without the feature.)
 */
class VariableFontFileTest {

    private class Sfnt(file: File) {
        val buf: ByteBuffer = ByteBuffer.wrap(file.readBytes())
        val tables: Map<String, Int> = (0 until buf.getShort(4).toInt()).associate { i ->
            val rec = 12 + 16 * i
            tag(rec) to buf.getInt(rec + 8)
        }

        fun tag(at: Int) = String(ByteArray(4) { buf.get(at + it) }, Charsets.US_ASCII)
        fun u16(at: Int) = buf.getShort(at).toInt() and 0xFFFF
        fun fixed(at: Int) = buf.getInt(at) / 65536f

        /** fvar axes: tag -> (min, default, max). */
        fun axes(): Map<String, Triple<Float, Float, Float>> {
            val fvar = tables.getValue("fvar")
            val first = fvar + u16(fvar + 4)
            val count = u16(fvar + 8)
            val size = u16(fvar + 10)
            return (0 until count).associate { i ->
                val a = first + i * size
                tag(a) to Triple(fixed(a + 4), fixed(a + 8), fixed(a + 12))
            }
        }

        fun gsubFeatures(): Set<String> {
            val gsub = tables["GSUB"] ?: return emptySet()
            val list = gsub + u16(gsub + 6)
            return (0 until u16(list)).map { tag(list + 2 + 6 * it) }.toSet()
        }
    }

    private fun font(name: String) = Sfnt(File("src/main/res/font/$name"))

    @Test
    fun archivoAxesCoverEveryRequestedWeightAndKeepTheDefaultWidth() {
        val axes = font("archivo_variable.ttf").axes()
        assertEquals(setOf("wght", "wdth"), axes.keys)
        val (min, _, max) = axes.getValue("wght")
        assertEquals(ArchivoWghtRange, min.toInt()..max.toInt())
        for (w in WebFontWeights) assertTrue("Archivo wght $w in $min..$max", w in ArchivoWghtRange)
        val (wMin, wDefault, wMax) = axes.getValue("wdth")
        assertEquals(ArchivoWdthRange, wMin.toInt()..wMax.toInt())
        assertEquals(ArchivoDefaultWdth, wDefault.toInt())
    }

    @Test
    fun firaCodeWghtAxisMatchesTheFamilyRange() {
        val axes = font("fira_code_variable.ttf").axes()
        assertEquals(setOf("wght"), axes.keys)
        val (min, _, max) = axes.getValue("wght")
        assertEquals(FiraCodeWghtRange, min.toInt()..max.toInt())
        // Web mono weights are 400-700; heavier web weights are UI-face only.
        for (w in WebFontWeights.filter { it <= 700 && it >= 400 }) assertTrue("Fira Code wght $w", w in FiraCodeWghtRange)
    }

    @Test
    fun archivoHasTabularFigures() {
        assertTrue("tnum" in font("archivo_variable.ttf").gsubFeatures())
    }
}
