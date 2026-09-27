package com.tether.app.ui.icons

import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SquareTerminal
import com.composables.icons.lucide.TriangleAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** TetherIcons covers every lucide-react glyph the web imports (fixture: lucide-web-glyphs.txt). */
class TetherIconsTest {

    private val webGlyphs: List<String> by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("lucide-web-glyphs.txt")) {
            "lucide-web-glyphs.txt fixture missing"
        }
        stream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    @Test
    fun fixtureIsTheWebInventory() {
        assertEquals(136, webGlyphs.size)
        assertEquals("fixture has duplicates", webGlyphs.size, webGlyphs.toSet().size)
    }

    @Test
    fun everyWebGlyphResolves() {
        val missing = webGlyphs.filterNot { it in TetherIcons.byWebName }
        assertTrue("web glyphs with no TetherIcons entry: $missing", missing.isEmpty())
        val extra = TetherIcons.byWebName.keys - webGlyphs.toSet()
        assertTrue("TetherIcons entries the web no longer imports: $extra", extra.isEmpty())
        for ((name, vector) in TetherIcons.byWebName) {
            assertEquals("$name viewport", 24f, vector.viewportWidth)
            assertTrue("$name has no paths", vector.root.hasPath())
        }
    }

    @Test
    fun deprecatedWebNamesUseTheCanonicalGlyph() {
        assertEquals(10, TetherIcons.deprecatedAliases.size)
        for ((old, canonical) in TetherIcons.deprecatedAliases) {
            assertTrue("$old is a web import", old in webGlyphs)
            val name = TetherIcons.byWebName[old]!!.name
            assertEquals("$old -> $canonical (vector '$name')", canonical.normalized(), name.normalized())
        }
        assertSame(Lucide.TriangleAlert, TetherIcons.byWebName["AlertTriangle"])
        assertSame(Lucide.SquareTerminal, TetherIcons.byWebName["TerminalSquare"])
    }

    // Vector names are not necessarily PascalCase; compare letters and digits only.
    private fun String.normalized(): String = lowercase().filter { it.isLetterOrDigit() }

    private fun VectorNode.hasPath(): Boolean = when (this) {
        is VectorPath -> pathData.isNotEmpty()
        is VectorGroup -> any { it.hasPath() }
        else -> false
    }
}
