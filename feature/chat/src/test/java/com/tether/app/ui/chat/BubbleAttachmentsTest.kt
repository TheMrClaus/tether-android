package com.tether.app.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7.4 (chat-view.tsx:561-600, v112): the operator's own attachments in the user bubble. A picture
 * the server materialized (`AttachmentMeta.mediaRef`) is a thumbnail loaded through the transcript's
 * tool-media path, with its failure tile; any other attachment keeps its name chip, drawn by the code
 * rule on one line; a picture sent as a staged path says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BubbleAttachmentsTest {
    @get:Rule val rule = createComposeRule()

    private fun show(block: TurnBlock, loader: ToolMediaLoader = ToolFixtures.FakeLoader()) {
        rule.setContent {
            ChatHost(TetherSkin.Studio) {
                CompositionLocalProvider(LocalToolMediaLoader provides loader) { UserBubble(block) }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theWireShapeDecodesWithItsV112Fields() {
        val block = BubbleFixtures.imageAndFile
        val image = block.attachments!![0]
        assertEquals("image", image.delivery)
        assertTrue(image.mediaRef.toString().contains(BubbleFixtures.MEDIA_URL))
        assertEquals(null, block.attachments!![1].mediaRef)
    }

    @Test
    fun aMaterializedPictureIsAThumbnailThroughTheToolMediaPathAndAFileIsAChip() {
        val loader = ToolFixtures.FakeLoader()
        show(BubbleFixtures.imageAndFile, loader)
        assertEquals(listOf(BubbleFixtures.MEDIA_URL), loader.loads)
        rule.onNodeWithContentDescription("View image full size").assertExists()
        rule.onAllNodesWithTag("bubble-attachment-chip").assertCountEquals(1)
        rule.onNodeWithText("build.log", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("screenshot.png", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aPictureThatCannotLoadShowsTheSameFailureTile() {
        show(BubbleFixtures.imageAndFile, ToolFixtures.FakeLoader(MediaImage.Failed))
        rule.onNodeWithText("Image unavailable", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("build.log", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aPictureTooLargeToShowSaysSo() {
        show(BubbleFixtures.imageAndFile, ToolFixtures.FakeLoader(MediaImage.TooLarge))
        rule.onNodeWithText("Image too large to show", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aRefThatIsNotAnImageMediaRefKeepsItsChip() {
        val split = splitAttachments(BubbleFixtures.odd.attachments!!)
        assertTrue(split.media.isEmpty())
        assertEquals(listOf("inline.png", "clip.mp4", "nameless.png"), split.chips.map { it.name })
    }

    @Test
    fun aPictureSentAsAPathSaysSo() {
        show(BubbleFixtures.asPath)
        rule.onNodeWithText("Sent to the agent as a file path, not an image.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aChipSentAsAPathSaysSo() {
        show(BubbleFixtures.pathChip)
        rule.onNodeWithText("sent as a path", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("Sent to the agent as a file path, not an image.", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aHostileNameIsDrawnByTheCodeRule() {
        show(BubbleFixtures.hostile)
        rule.onNodeWithText("U+202E", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("U+0000", substring = true, useUnmergedTree = true).assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BubbleAttachmentLineRuleTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aNameWithALineFeedCarriageReturnOrTabShowsThemAsTokensOnOneLine() {
        rule.setContent {
            ChatHost(TetherSkin.Studio) {
                CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader()) { UserBubble(BubbleFixtures.breaks) }
            }
        }
        rule.waitForIdle()
        // ta-28i one-line rule: a break in a name is a visible token, never a second line.
        for (token in listOf("U+000A", "U+000D", "U+0009")) {
            rule.onNodeWithText(token, substring = true, useUnmergedTree = true).assertExists()
        }
        val drawn = rule.onNodeWithText("report", substring = true, useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")
        assertTrue("a raw break was drawn: $drawn", drawn.none { it == '\n' || it == '\r' || it == '\t' })
    }
}

object BubbleFixtures {
    const val MEDIA_URL = "/api/tool-media/0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0.png"

    private fun decode(json: String): TurnBlock = TetherJson.decodeFromString(TurnBlock.serializer(), json)

    private fun ref(url: String = MEDIA_URL, kind: String = "image", type: String = "media_ref") =
        """{"type":"$type","mediaKind":"$kind","mediaType":"image/png","url":"$url","bytes":20480}"""

    val imageAndFile: TurnBlock = decode(
        """{"blockId":"u1","kind":"user_message","text":"Here is the screenshot and the build log.","attachments":[
          {"name":"screenshot.png","mediaType":"image/png","delivery":"image","mediaRef":${ref()}},
          {"name":"build.log","mediaType":"text/plain"}]}""",
    )

    val odd: TurnBlock = decode(
        """{"blockId":"u2","kind":"user_message","text":"x","attachments":[
          {"name":"inline.png","mediaType":"image/png","mediaRef":{"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}},
          {"name":"clip.mp4","mediaType":"video/mp4","mediaRef":${ref(kind = "video")}},
          {"name":"nameless.png","mediaType":"image/png","mediaRef":{"type":"media_ref","mediaKind":"image"}}]}""",
    )

    val asPath: TurnBlock = decode(
        """{"blockId":"u3","kind":"user_message","text":"x","attachments":[
          {"name":"screenshot.png","mediaType":"image/png","delivery":"path","mediaRef":${ref()}}]}""",
    )

    val pathChip: TurnBlock = decode(
        """{"blockId":"u4","kind":"user_message","text":"x","attachments":[{"name":"scan.heic","mediaType":"image/heic","delivery":"path"}]}""",
    )

    val breaks: TurnBlock = decode(
        """{"blockId":"u6","kind":"user_message","text":"x","attachments":[{"name":"report\nfinal\r\tv2.txt","mediaType":"text/plain"}]}""",
    )

    val hostile: TurnBlock = decode(
        """{"blockId":"u5","kind":"user_message","text":"x","attachments":[{"name":"invoice\u202Efdp.exe\u0000","mediaType":"application/octet-stream"}]}""",
    )
}
