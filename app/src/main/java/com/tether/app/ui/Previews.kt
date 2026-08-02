package com.tether.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.ApprovalChoice
import com.tether.app.protocol.model.ApprovalRequestMetadata
import com.tether.app.protocol.model.PendingApproval
import com.tether.app.protocol.model.PendingQuestion
import com.tether.app.protocol.model.QuestionOption
import com.tether.app.protocol.model.QuestionPrompt
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.TurnRun
import com.tether.app.protocol.model.Vocab
import com.tether.app.ui.chat.ApprovalCard
import com.tether.app.ui.chat.Composer
import com.tether.app.ui.chat.QuestionCard
import com.tether.app.ui.chat.ToolCard
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Design-time previews for the key components (machine theme). */

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    TetherTheme(choice = ThemeChoice.Machine) {
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

@Preview(name = "Session rows", showBackground = true, backgroundColor = 0xFF111517)
@Composable
private fun SessionRowPreview() {
    PreviewSurface {
        SessionRow(previewSession("active"), selected = false, now = previewNow, onClick = {})
        SessionRow(previewSession("waiting", "release pipeline"), selected = false, now = previewNow, onClick = {})
        SessionRow(previewSession("ready", "docs sweep"), selected = true, now = previewNow, onClick = {})
        SessionRow(previewSession("exited", "spike: worker threads"), selected = false, now = previewNow, onClick = {})
    }
}

@Preview(name = "Approval card", showBackground = true, backgroundColor = 0xFF070A0B)
@Composable
private fun ApprovalCardPreview() {
    PreviewSurface {
        ApprovalCard(
            approval = PendingApproval(
                requestId = "req-1",
                toolId = "tool-1",
                name = "Bash",
                input = buildJsonObject { put("command", "./scripts/publish.sh --tag v1.4.0") },
                choices = listOf(
                    ApprovalChoice("once", "Allow once"),
                    ApprovalChoice("always", "Always allow publish.sh", permissionGrant = "exact"),
                ),
                metadata = ApprovalRequestMetadata(
                    provider = "codex",
                    kind = "exec",
                    reason = "Command is outside the sandbox policy",
                    command = "./scripts/publish.sh --tag v1.4.0",
                    cwd = "/home/operator/git/pipeline",
                ),
            ),
            onChoice = { _, _ -> },
        )
    }
}

@Preview(name = "Approval fallback", showBackground = true, backgroundColor = 0xFF070A0B)
@Composable
private fun ApprovalFallbackPreview() {
    PreviewSurface {
        ApprovalCard(
            approval = PendingApproval(requestId = "req-2", toolId = "tool-2", name = "WebFetch"),
            onChoice = { _, _ -> },
        )
    }
}

@Preview(name = "Question card", showBackground = true, backgroundColor = 0xFF070A0B)
@Composable
private fun QuestionCardPreview() {
    PreviewSurface {
        QuestionCard(
            question = PendingQuestion(
                requestId = "req-q",
                toolId = "tool-q",
                questions = listOf(
                    QuestionPrompt(
                        question = "Which migration strategy should I use?",
                        header = "Strategy",
                        options = listOf(
                            QuestionOption("Expand-contract", "Dual-write, zero downtime"),
                            QuestionOption("Locked rewrite", "Short maintenance window"),
                        ),
                    ),
                ),
            ),
            onSubmit = { _, _ -> },
        )
    }
}

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
            serverNow = { previewNow },
            onSend = {},
            onInterrupt = {},
            onQueueEdit = { _, _ -> },
            onQueueRemove = {},
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
            serverNow = { previewNow },
            onSend = {},
            onInterrupt = {},
            onQueueEdit = { _, _ -> },
            onQueueRemove = {},
        )
    }
}
