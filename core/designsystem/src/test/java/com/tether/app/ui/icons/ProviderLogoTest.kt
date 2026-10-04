package com.tether.app.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.tether.app.ui.util.providerGlyph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The provider -> logo table of components/provider-logo.tsx (issue #59) at tether 90fbb9f. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderLogoTest {
    @get:Rule val rule = createComposeRule()

    /** Every LOGO_MARKS key of the web, in its key order (`dsh` is the `get dsh()` alias). */
    private val webIds = listOf("claude", "codex", "pi", "reasonix", "gemini", "dsh", "opencode")

    /**
     * The web's LOGO_MARKS, extracted mechanically from provider-logo.tsx into a checked-in fixture
     * (src/test/resources/provider-logo-marks.json), so an edited or truncated path fails here.
     */
    private val web: JsonObject by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResource("provider-logo-marks.json")).readText()
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun marksMatchTheWebLogoMarksVerbatim() {
        val marks = web.getValue("marks").jsonObject
        val aliases = web.getValue("aliases").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(mapOf("dsh" to "reasonix"), aliases)
        assertEquals(webIds.toSet(), marks.keys + aliases.keys)
        assertEquals(webIds, ProviderLogos.marks.keys.toList())
        for (id in webIds) {
            val w = marks.getValue(aliases[id] ?: id).jsonObject
            val mine = ProviderLogos.marks.getValue(id)
            assertEquals("$id viewBox", w.getValue("viewBox").jsonPrimitive.content, mine.viewBox)
            assertEquals("$id path", w.getValue("path").jsonPrimitive.content, mine.path)
            assertEquals("$id evenOdd", w["evenOdd"]?.jsonPrimitive?.boolean ?: false, mine.evenOdd)
            val parts = w["colored"]?.jsonArray?.map {
                ProviderMarkPart(it.jsonObject.getValue("fill").jsonPrimitive.content, it.jsonObject.getValue("d").jsonPrimitive.content)
            }
            assertEquals("$id colored", parts, mine.colored)
        }
        assertSame("dsh is the reasonix whale", ProviderLogos.marks["reasonix"], ProviderLogos.marks["dsh"])
    }

    @Test
    fun theNewMarksEmbedTheWebStrings() {
        // Spot copies straight from provider-logo.tsx @ 90fbb9f, independent of the fixture.
        val pi = ProviderLogos.marks.getValue("pi")
        assertEquals("0 0 4 4", pi.viewBox)
        assertEquals("M0 0H3V2H2V3H1V4H0ZM1 1V2H2V1ZM3 2H4V4H3Z", pi.path)
        assertTrue(pi.evenOdd)
        val whale = ProviderLogos.marks.getValue("reasonix")
        assertEquals("140 143 860 860", whale.viewBox)
        assertTrue(whale.path.startsWith("M968.002 326.025C959.5 321.85 955.842 329.804 950.865 333.835C"))
        assertTrue(whale.path.endsWith("C709.128 607.699 706.249 613.038 698.539 616.288V616.301Z"))
        val gemini = ProviderLogos.marks.getValue("gemini")
        assertEquals("4 4 40 40", gemini.viewBox)
        assertEquals("", gemini.path)
        assertEquals(listOf("#FFC107", "#FF3D00", "#4CAF50", "#1976D2"), gemini.colored!!.map { it.fill })
        assertEquals(
            "M24,44c5.166,0,9.86-1.977,13.409-5.192l-6.19-5.238C29.211,35.091,26.715,36,24,36c-5.202,0-9.619-3.317-11.283-7.946l-6.522,5.025C9.505,39.556,16.227,44,24,44z",
            gemini.colored!![2].d,
        )
    }

    @Test
    fun everyWebIdHasAVectorOnItsViewBox() {
        for (id in webIds) {
            val mark = checkNotNull(ProviderLogos.mark(id)) { "$id has no mark" }
            val (minX, minY, w, h) = ProviderLogos.parseViewBox(ProviderLogos.marks.getValue(id).viewBox)
            assertEquals("$id width", w, mark.viewportWidth)
            assertEquals("$id height", h, mark.viewportHeight)
            val group = mark.root.single() as VectorGroup
            assertEquals("$id minX", -minX, group.translationX)
            assertEquals("$id minY", -minY, group.translationY)
            val paths = group.toList().map { it as VectorPath }
            assertTrue("$id paths parsed", paths.all { it.pathData.size > 1 })
            val spec = ProviderLogos.marks.getValue(id)
            assertEquals("$id path count", spec.colored?.size ?: 1, paths.size)
            val fillType = if (spec.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
            if (spec.colored == null) assertEquals("$id fill rule", fillType, paths.single().pathFillType)
        }
        val pi = (ProviderLogos.mark("pi")!!.root.single() as VectorGroup).single() as VectorPath
        assertEquals("pi is evenodd", PathFillType.EvenOdd, pi.pathFillType)
    }

    @Test
    fun geminiPartsCarryTheirBrandFills() {
        val parts = (ProviderLogos.mark("gemini")!!.root.single() as VectorGroup).toList().map { (it as VectorPath).fill }
        assertEquals(
            listOf(Color(0xFFFFC107), Color(0xFFFF3D00), Color(0xFF4CAF50), Color(0xFF1976D2)).map { SolidColor(it) },
            parts,
        )
        assertTrue(ProviderLogos.keepsOwnColours("gemini"))
        for (id in webIds - "gemini") assertFalse(id, ProviderLogos.keepsOwnColours(id))
        assertFalse(ProviderLogos.keepsOwnColours(null))
        assertFalse(ProviderLogos.keepsOwnColours("acp"))
    }

    @Test
    fun geminiKeepsItsColoursUnderATintWhileMonochromeMarksTakeIt() {
        val tint = Color(0xFFFF00FF) // magenta: in no brand mark
        rule.setContent {
            Row {
                for (id in listOf("gemini", "pi", "reasonix")) {
                    Box(Modifier.size(64.dp).background(Color.White).testTag(id), contentAlignment = Alignment.Center) {
                        ProviderLogo(id, color = tint, markSize = 64.dp)
                    }
                }
            }
        }
        fun counts(id: String): Map<Color, Int> {
            val px = rule.onNodeWithTag(id).captureToImage().toPixelMap()
            val out = HashMap<Color, Int>()
            for (x in 0 until px.width) for (y in 0 until px.height) out.merge(px[x, y], 1, Int::plus)
            return out
        }
        val gemini = counts("gemini")
        assertEquals("gemini ignores the tint", 0, gemini[tint] ?: 0)
        for (fill in listOf(0xFFFFC107, 0xFFFF3D00, 0xFF4CAF50, 0xFF1976D2)) {
            assertTrue("gemini draws ${fill.toString(16)}", (gemini[Color(fill)] ?: 0) > 20)
        }
        // pi covers 10 of its 16 grid cells: the tint fills most of the box.
        val pi = counts("pi")
        val piTotal = pi.values.sum()
        assertTrue("pi takes the tint", (pi[tint] ?: 0) > piTotal / 2)
        // The whale sits inside its offset viewBox (140 143 …), not pushed out of view.
        val whale = counts("reasonix")
        assertTrue("whale takes the tint", (whale[tint] ?: 0) > whale.values.sum() / 5)
    }

    @Test
    fun unknownProvidersFallBackToALetter() {
        for (id in listOf("acp", "future-agent", "Gemini", "", null)) {
            assertNull("$id has no mark", ProviderLogos.mark(id))
            assertNull("$id has no brand", ProviderLogos.brand(id))
        }
        rule.setContent {
            Row {
                ProviderLogo("acp")
                ProviderLogo("future-agent", fallback = "Z")
                ProviderLogo(null)
            }
        }
        rule.onNodeWithText("A").assertExists()
        rule.onNodeWithText("Z").assertExists()
        rule.onNodeWithText("?").assertExists()
    }

    @Test
    fun brandIsTheProviderIdWhenItHasAMark() {
        // The web's `data-brand={provider}` on the svg; dsh keeps its own id, not "reasonix".
        for (id in webIds) assertEquals(id, ProviderLogos.brand(id))
    }

    @Test
    fun fallbackLetterMatchesTheWeb() {
        // fallback ?? (provider ? provider.slice(0, 1).toUpperCase() : "?")
        assertEquals("R", ProviderLogos.fallbackLetter("reasonix"))
        assertEquals("P", ProviderLogos.fallbackLetter("pi"))
        assertEquals("A", ProviderLogos.fallbackLetter("acp"))
        assertEquals("D", ProviderLogos.fallbackLetter("dsh"))
        assertEquals("?", ProviderLogos.fallbackLetter(null))
        assertEquals("?", ProviderLogos.fallbackLetter(""))
        assertEquals("X", ProviderLogos.fallbackLetter("pi", "X"))
        assertEquals("", ProviderLogos.fallbackLetter("pi", "")) // `??` keeps an empty fallback
    }

    @Test
    fun viewBoxAndFillParsing() {
        assertEquals(listOf(140f, 143f, 860f, 860f), ProviderLogos.parseViewBox("140 143 860 860"))
        assertEquals(listOf(-1f, 2.5f, 3f, 4f), ProviderLogos.parseViewBox(" -1, 2.5 3,4 "))
        assertEquals(Color(0xFF1976D2), ProviderLogos.parseFill("#1976D2"))
        assertEquals(Color(0xFFFF0000), ProviderLogos.parseFill("#f00"))
    }

    @Test
    fun callersFallbackIsLibFormatProviderGlyph() {
        // lib/format.ts providerGlyph, the fallback most web call sites pass.
        val expected = mapOf(
            "claude" to "C", "codex" to "X", "opencode" to "O", "reasonix" to "R",
            "pi" to "P", "gemini" to "G", "acp" to "?", null to "?",
        )
        for ((id, glyph) in expected) assertEquals("$id", glyph, providerGlyph(id))
    }

    @Test
    fun inkProvidersMatchTheCss() {
        // .provider-claude, .provider-codex, .provider-opencode { color: var(--ink) }
        assertEquals(setOf("claude", "codex", "opencode"), ProviderLogoDefaults.INK_PROVIDERS)
        assertEquals(18.56f, ProviderLogoDefaults.markSize().value, 0.001f)
    }
}
