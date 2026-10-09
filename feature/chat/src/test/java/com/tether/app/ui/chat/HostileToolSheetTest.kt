package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.tether.app.ui.theme.TetherSkin
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-qm8b: every tool kind, with inputs and outputs as hostile as a real session can hold (a 50,000-character line, 5,000
 * lines, surrogate halves, RTL, combining marks, a file mention at every boundary), is composed as a row and opened in its
 * sheet, at the default font and at 2.0x; none may throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
open class HostileToolSheetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val bigLine = "word ".repeat(10_000)
    private val manyLines = (1..5000).joinToString("\n") { "line $it /a/b.kt:$it ok" }
    private val uni = "é".repeat(500) + "👩‍👩‍👧‍👦" + "\uD800" + "مرحبا /home/u/a.png" + "‮x‬" + "\u0000"

    private fun q(s: String) = ActivityFixtures.text(s)

    private fun script(): ActivityFixtures.Script {
        val s = ActivityFixtures.Script()
        s.start("hostile")
        var n = 0
        fun id() = "h${n++}"
        for ((out, label) in listOf(bigLine to "big", manyLines to "many", uni to "uni")) {
            s.tool(id(), "Bash", """{"command":${q(out)}}""", q(out))
            s.tool(id(), "Read", """{"file_path":${q("/w/p/" + out.take(300))}}""", q(out))
            s.tool(id(), "Edit", """{"file_path":"/w/p/a.kt","old_string":${q(out)},"new_string":${q(out + "!")}}""", q("updated"))
            s.tool(id(), "MultiEdit", """{"file_path":"/w/p/a.kt","edits":[{"old_string":${q(out)},"new_string":${q(out + "!")}},{"old_string":1,"new_string":null}]}""", q("updated"))
            s.tool(id(), "Write", """{"file_path":"/w/p/a.kt","content":${q(out)}}""", q("ok"))
            s.tool(id(), "Grep", """{"pattern":${q(out.take(400))},"path":"."}""", q(out), isError = label == "uni")
            s.tool(id(), "WebFetch", """{"url":${q(out.take(500))},"prompt":${q(out)}}""", q(out))
            s.tool(id(), "TodoWrite", """{"todos":[{"content":${q(out.take(2000))},"status":"in_progress","activeForm":${q(out.take(100))}},{"content":null},7]}""", q("ok"))
            s.tool(id(), "Task", """{"description":${q(out.take(300))},"prompt":${q(out)},"subagent_type":"x"}""", q(out))
            s.tool(id(), "mcp:srv:${label}", """{"a":${q(out)},"b":[${q(out)},{"c":null}]}""", q(out))
            s.tool(id(), "Unknown_${label}", """{"a":${q(out)},"n":1e999,"m":[1,2,{"k":[]}]}""", q(out), interrupted = true)
            s.tool(id(), "command_execution", """{"command":${q(out)},"cwd":"/w"}""", """{"text":${q(out)},"exitCode":1,"status":"failed"}""")
            s.tool(id(), "file_change", """{"changes":[{"path":${q("/w/" + out.take(200))},"kind":"update","diff":${q(out)}}]}""", """{"status":"completed"}""")
        }
        // A tool that starts and never ends, with output that keeps coming; one that ends twice; a delta for a tool never started.
        s.tool(id(), "Bash", """{"command":"sleep 99"}""", done = false)
        s.delta("h${n - 1}", bigLine)
        s.delta("never", "orphan")
        s.message("m1", "See /w/p/a.kt:12 and `${"b/".repeat(300)}c.kt` and ${"/z".repeat(800)}.kt then " + uni)
        return s
    }

    private fun run(richCodex: Boolean) {
        val fixture = script().fold()
        rule.showTranscript(fixture, showThinking = true, richCodex = richCodex, richOpencode = richCodex, groupsOpen = true, wellHeight = androidx.compose.ui.unit.Dp(4000f))
        var opened = 0
        val items = rule.onNodeWithTag("chat-transcript").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.CollectionInfo].rowCount
        for (idx in 0 until items) {
            rule.onNodeWithTag("chat-transcript").performScrollToIndex(idx)
            rule.waitForIdle()
            val rows = rule.onAllNodes(hasTestTag("activity-row"))
            if (rows.fetchSemanticsNodes().isEmpty()) continue
            rows[0].performClick()
            rule.waitForIdle()
            opened++
            val dialogs = rule.onAllNodes(isDialog()).fetchSemanticsNodes().size
            assertTrue("a sheet for item $idx", dialogs == 1)
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close") and androidx.compose.ui.test.hasAnyAncestor(isDialog()), useUnmergedTree = true)
                .onFirst().performClick()
            rule.waitForIdle()
            rule.onAllNodes(isDialog()).assertCountEquals(0)
        }
        assertTrue("sheets opened: $opened", opened > 10)
        println("HOSTILE rows opened: $opened (richCodex=$richCodex)")
    }

    @Test fun everyToolKindOpensItsSheetOverHostileInput() = run(richCodex = false)

    @Test fun everyToolKindOpensItsRichSheetOverHostileInput() = run(richCodex = true)
}

/** The same at the largest system font (2.0x), where a line is wider and a layout has less room. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class HostileToolSheetLargeFontTest : HostileToolSheetTest()
