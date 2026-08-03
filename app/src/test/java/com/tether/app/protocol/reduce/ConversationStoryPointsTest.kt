package com.tether.app.protocol.reduce

import com.tether.app.protocol.model.AttachmentMeta
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStoryPointsTest {

    private fun userBlock(id: String, text: String? = null, attachments: List<AttachmentMeta>? = null) =
        TurnBlock(blockId = id, kind = Vocab.BLOCK_USER_MESSAGE, text = text, attachments = attachments)

    private fun messageBlock(id: String, text: String? = null) =
        TurnBlock(blockId = id, kind = Vocab.BLOCK_MESSAGE, text = text)

    private fun thinkingBlock(id: String, text: String? = null) =
        TurnBlock(blockId = id, kind = Vocab.BLOCK_THINKING, text = text)

    private fun toolBlock(id: String, name: String = "Bash") =
        TurnBlock(blockId = id, kind = Vocab.BLOCK_TOOL, name = name, done = true)

    private fun projectionOf(vararg turns: TurnProjection) = SessionProjection(
        tetherSessionId = "s1", provider = "claude", cwd = "/w",
        turnOrder = turns.map { it.turnId },
        turnsById = turns.associateBy { it.turnId },
    )

    private fun turnOf(
        id: String,
        blocks: List<TurnBlock>,
        continuation: Boolean = false,
        startedAt: Long? = null,
    ) = TurnProjection(
        turnId = id, status = Vocab.TURN_DONE, continuation = continuation, startedAt = startedAt,
        blocks = blocks.map { it.blockId },
        blocksById = blocks.associateBy { it.blockId },
    )

    @Test
    fun emptySessionHasNoPoints() {
        assertEquals(emptyList<StoryPoint>(), storyPointsFromSession(projectionOf()))
        assertEquals(emptyList<StoryPoint>(), storyPointsFromSession(null))
    }

    @Test
    fun oneUserMessageProducesOnePointPairedWithFirstReply() {
        val projection = projectionOf(
            turnOf("t1", listOf(userBlock("u1", "Hello"), messageBlock("a1", "Hi there!")), startedAt = 1_000_000L)
        )
        val points = storyPointsFromSession(projection)
        assertEquals(1, points.size)
        assertEquals("t1", points[0].turnId)
        assertEquals("u1", points[0].blockId)
        assertEquals("Hello", points[0].prompt)
        assertEquals("Hi there!", points[0].reply)
        assertEquals(1_000_000L, points[0].ts)
    }

    @Test
    fun multipleUserMessagesInOneTurnEachGetOwnFirstReply() {
        val projection = projectionOf(
            turnOf("t1", listOf(
                userBlock("u1", "First question"),
                messageBlock("a1", "First answer"),
                userBlock("u2", "Second question"),
                messageBlock("a2", "Second answer"),
            ))
        )
        val points = storyPointsFromSession(projection)
        assertEquals(2, points.size)
        assertEquals("u1", points[0].blockId)
        assertEquals("First answer", points[0].reply)
        assertEquals("u2", points[1].blockId)
        assertEquals("Second answer", points[1].reply)
    }

    @Test
    fun userMessageWithNoAgentReplyProducesPendingPointWithEmptyReply() {
        val projection = projectionOf(
            turnOf("t1", listOf(userBlock("u1", "Pending message")))
        )
        val points = storyPointsFromSession(projection)
        assertEquals(1, points.size)
        assertEquals("Pending message", points[0].prompt)
        assertEquals("", points[0].reply)
    }

    @Test
    fun continuationTurnsAreOmitted() {
        val projection = projectionOf(
            turnOf("t1", listOf(userBlock("u1", "Start"), messageBlock("a1", "Begin")), startedAt = 1L),
            turnOf("t2", listOf(messageBlock("a2", "Continued")), continuation = true, startedAt = 2L),
        )
        val points = storyPointsFromSession(projection)
        assertEquals(1, points.size)
        assertEquals("u1", points[0].blockId)
    }

    @Test
    fun thinkingAndToolBlocksNeverBecomePoints() {
        val projection = projectionOf(
            turnOf("t1", listOf(
                thinkingBlock("th1", "Pondering"),
                toolBlock("tool1"),
                userBlock("u1", "Question"),
                thinkingBlock("th2", "More pondering"),
                messageBlock("a1", "Answer"),
            ))
        )
        val points = storyPointsFromSession(projection)
        assertEquals(1, points.size)
        assertEquals("u1", points[0].blockId)
    }

    @Test
    fun whitespaceInPreviewsIsCollapsed() {
        val projection = projectionOf(
            turnOf("t1", listOf(
                userBlock("u1", "  what   is   \n  happening  "),
                messageBlock("a1", "  not   much  "),
            ))
        )
        val points = storyPointsFromSession(projection)
        assertEquals("what is happening", points[0].prompt)
        assertEquals("not much", points[0].reply)
    }

    @Test
    fun longPromptIsTruncatedWithEllipsis() {
        val longPrompt = "a".repeat(300)
        val longReply = "b".repeat(300)
        val projection = projectionOf(
            turnOf("t1", listOf(userBlock("u1", longPrompt), messageBlock("a1", longReply)))
        )
        val points = storyPointsFromSession(projection)
        assertEquals(220, points[0].prompt.length)
        assertTrue(points[0].prompt.endsWith("\u2026"))
        assertEquals(260, points[0].reply.length)
        assertTrue(points[0].reply.endsWith("\u2026"))
    }

    @Test
    fun attachmentOnlyPromptsUseAttachmentNames() {
        val projection = projectionOf(
            turnOf("t1", listOf(
                userBlock("u1", attachments = listOf(
                    AttachmentMeta(name = "screenshot.png", mediaType = "image/png"),
                    AttachmentMeta(name = "report.pdf", mediaType = "application/pdf"),
                ))
            ))
        )
        val points = storyPointsFromSession(projection)
        assertEquals(1, points.size)
        assertEquals("screenshot.png, report.pdf", points[0].prompt)
        assertEquals("", points[0].reply)
    }

    @Test
    fun multiTurnOrderAndTimestampsArePreserved() {
        val projection = projectionOf(
            turnOf("t1", listOf(userBlock("u1", "Turn one"), messageBlock("a1", "Reply one")), startedAt = 1_700_000_000_000L),
            turnOf("t2", listOf(userBlock("u2", "Turn two"), messageBlock("a2", "Reply two")), startedAt = 1_700_001_000_000L),
        )
        val points = storyPointsFromSession(projection)
        assertEquals(2, points.size)
        assertEquals("u1", points[0].blockId)
        assertEquals(1_700_000_000_000L, points[0].ts)
        assertEquals("u2", points[1].blockId)
        assertEquals(1_700_001_000_000L, points[1].ts)
    }

    @Test
    fun storyPointTimeLabelReturnsEmptyForNullTimestamp() {
        assertEquals("", storyPointTimeLabel(null))
    }

    @Test
    fun storyPointTimeLabelFormatsHHMM() {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getDefault(), java.util.Locale.getDefault())
        cal.set(2026, java.util.Calendar.AUGUST, 3, 9, 5, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val label = storyPointTimeLabel(cal.timeInMillis)
        assertEquals(5, label.length)
        assertTrue(label[2] == ':')
        assertTrue(label.substring(0, 2).toInt() in 0..23)
        assertTrue(label.substring(3, 5).toInt() in 0..59)
    }

    @Test
    fun replyPreviewStopsAtFirstUserMessageAfterPrompt() {
        val projection = projectionOf(
            turnOf("t1", listOf(
                userBlock("u1", "Question one"),
                messageBlock("a1", "Answer one"),
                userBlock("u2", "Question two"),
                messageBlock("a2", "Answer two"),
            ))
        )
        val points = storyPointsFromSession(projection)
        assertEquals("Answer one", points[0].reply)
        assertEquals("Answer two", points[1].reply)
    }
}