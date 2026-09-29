package com.tether.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.TurnRun
import com.tether.app.protocol.model.Vocab
import com.tether.app.ui.chat.Composer
import com.tether.app.ui.chat.ToolCard
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeFamily
import com.tether.app.ui.theme.ThemeMode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Design-time previews for the key components (machine theme). */

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    TetherTheme(choice = ThemeChoice(ThemeFamily.Precision, ThemeMode.Dark)) {
        val t = LocalTetherTokens.current
        Column(Modifier.background(t.mineral).padding(12.dp)) {
            content()
        }
    }
}

private val previewNow = 1_754_000_000_000L

private fun previewSession(status: String, name: String = "tether-ui polish") = AgentSession(
    id = "preview",
    provider = "claude",
    name = name,
    cwd = "/home/operator/git/aidash",
    status = status,
    startedAt = previewNow - 3_600_000,
    updatedAt = previewNow - 90_000,
)

// T6.3: the approval / question / denial cards are covered by ApprovalCardsScreenshotTest goldens.

@Preview(name = "Tool card done", showBackground = true, backgroundColor = 0xFF070A0B)
@Composable
private fun ToolCardDonePreview() {
    PreviewSurface {
        ToolCard(
            TurnBlock(
                blockId = "b1",
                kind = Vocab.BLOCK_TOOL,
                name = "Bash",
                done = true,
                input = buildJsonObject { put("command", "npm run typecheck") },
                output = JsonPrimitive("> tsc --noEmit\n\nFound 0 errors."),
            ),
        )
    }
}

@Preview(name = "Tool card diff", showBackground = true, backgroundColor = 0xFF070A0B)
@Composable
private fun ToolCardDiffPreview() {
    PreviewSurface {
        ToolCard(
            TurnBlock(
                blockId = "b2",
                kind = Vocab.BLOCK_TOOL,
                name = "Edit",
                done = true,
                input = buildJsonObject {
                    put("file_path", "components/session-sidebar.tsx")
                    put("old_string", "<span className=\"dot\" />")
                    put("new_string", "<span className={cx(\"dot\", waiting && \"dot-ping\")} />")
                },
            ),
        )
    }
}

@Preview(name = "Tool card running", showBackground = true, backgroundColor = 0xFF070A0B)
@Composable
private fun ToolCardRunningPreview() {
    PreviewSurface {
        ToolCard(
            TurnBlock(
                blockId = "b3",
                kind = Vocab.BLOCK_TOOL,
                name = "Grep",
                done = false,
                elapsedSeconds = 12.0,
                input = buildJsonObject { put("pattern", "waiting-ping") },
            ),
        )
    }
}

@Preview(name = "Composer idle", showBackground = true, backgroundColor = 0xFF0B0F10)
@Composable
private fun ComposerIdlePreview() {
    PreviewSurface {
        Composer(
            session = previewSession("ready"),
            projection = SessionProjection(
                tetherSessionId = "preview",
                provider = "claude",
                cwd = "/home/operator/git/aidash",
            ),
            controls = null,
            serverNow = { previewNow },
            onSend = { _, _ -> true },
            onInterrupt = {},
            onQueueEdit = { _, _ -> },
            onQueueRemove = {},
            onRequestControls = {},
        )
    }
}

@Preview(name = "Composer busy", showBackground = true, backgroundColor = 0xFF0B0F10)
@Composable
private fun ComposerBusyPreview() {
    val turn = TurnProjection(
        turnId = "t1",
        status = Vocab.TURN_RUNNING,
        startedAt = previewNow - 42_000,
        liveTokens = 1_204,
        run = TurnRun(index = 1, startedAt = previewNow - 42_000, tokensStart = 0),
        runCount = 1,
    )
    PreviewSurface {
        Composer(
            session = previewSession("active"),
            projection = SessionProjection(
                tetherSessionId = "preview",
                provider = "claude",
                cwd = "/home/operator/git/aidash",
                status = Vocab.SESSION_ACTIVE,
                turnOrder = listOf("t1"),
                turnsById = mapOf("t1" to turn),
                activeTurnId = "t1",
                queuedMessages = listOf(QueuedMessage("q1", "Also update the changelog.")),
            ),
            controls = null,
            serverNow = { previewNow },
            onSend = { _, _ -> true },
            onInterrupt = {},
            onQueueEdit = { _, _ -> },
            onQueueRemove = {},
            onRequestControls = {},
        )
    }
}
