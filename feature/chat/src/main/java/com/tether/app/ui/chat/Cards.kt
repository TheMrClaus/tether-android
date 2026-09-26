package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.tether.app.protocol.model.PendingApproval
import com.tether.app.protocol.model.PendingQuestion
import com.tether.app.protocol.model.PermissionDenialProjection
import com.tether.app.ui.components.KeyVariant
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

/**
 * Approval card (visual-spec §4): attention colors, the loud waiting signal.
 * Provider choices: permissionGrant -> teal primary key; otherwise a neutral
 * key-face key (Codex "Allow once" is one — NEVER destructive). Fallback:
 * Approve (primary) + Deny (brick).
 */
@Composable
fun ApprovalCard(
    approval: PendingApproval,
    onChoice: (choiceId: String?, decision: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(TetherDimens.radiusMd)
    var submitted by rememberSaveable(approval.requestId) { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .background(t.attentionBg, shape)
            .border(1.dp, t.attentionBorder, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Lucide.TriangleAlert, contentDescription = null, tint = t.attentionInk, modifier = Modifier.size(15.dp))
            Text(
                "Approval needed",
                color = t.white,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 14.7.sp,
            )
        }

        Text(
            buildAnnotatedString {
                append("The agent wants to run ")
                withStyle(SpanStyle(fontFamily = JetBrainsMono, fontSize = 12.8.sp, background = t.tintMd, color = t.white)) {
                    append(approval.name)
                }
                append(".")
            },
            color = t.ink,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 13.6.sp,
        )

        approval.metadata?.reason?.let { reason ->
            Text(reason, color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.8.sp)
        }
        approval.metadata?.cwd?.let { cwd ->
            MetaLine(label = "Working directory", value = cwd)
        }
        approval.metadata?.network?.let { network ->
            MetaLine(label = "Network", value = "${network.protocol ?: "tcp"}://${network.host}")
        }
        (approval.metadata?.command ?: toolInputSummary(approval.name, approval.input))?.let { summary ->
            Text(
                summary,
                color = t.ink,
                fontFamily = JetBrainsMono,
                fontSize = 12.2.sp,
                lineHeight = 12.2.sp * 1.5f,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(t.tintXs, RoundedCornerShape(TetherDimens.radiusSm))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        // Actions
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val choices = approval.choices
            if (!choices.isNullOrEmpty()) {
                choices.forEach { choice ->
                    val grants = choice.permissionGrant != null
                    TetherKey(
                        onClick = {
                            submitted = true
                            onChoice(choice.choiceId, null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        variant = if (grants) KeyVariant.Primary else KeyVariant.Secondary,
                        label = choice.label,
                        icon = if (grants) Lucide.Check else Lucide.Ban,
                        enabled = !submitted,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TetherKey(
                        onClick = {
                            submitted = true
                            onChoice(null, "allow")
                        },
                        modifier = Modifier.weight(1f),
                        variant = KeyVariant.Primary,
                        label = "Approve",
                        icon = Lucide.Check,
                        enabled = !submitted,
                    )
                    TetherKey(
                        onClick = {
                            submitted = true
                            onChoice(null, "deny")
                        },
                        modifier = Modifier.weight(1f),
                        variant = KeyVariant.Brick,
                        label = "Deny",
                        icon = Lucide.Ban,
                        enabled = !submitted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    val t = LocalTetherTokens.current
    Text(
        buildAnnotatedString {
            append("$label · ")
            withStyle(SpanStyle(fontFamily = JetBrainsMono, fontSize = 11.8.sp, color = t.ink)) { append(value) }
        },
        color = t.muted,
        fontFamily = Manrope,
        fontWeight = TetherWeights.body,
        fontSize = 12.2.sp,
    )
}

/**
 * Question card (AskUserQuestion). Option chips are provider content — NOT
 * uppercase. Submit stays disabled until every question has an answer.
 */
@Composable
fun QuestionCard(
    question: PendingQuestion,
    onSubmit: (answers: Map<String, String>, response: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(TetherDimens.radiusMd)
    var submitted by rememberSaveable(question.requestId) { mutableStateOf(false) }
    // question text -> set of selected labels
    val selections = remember(question.requestId) { mutableStateMapOf<String, Set<String>>() }
    val otherText = remember(question.requestId) { mutableStateMapOf<String, String>() }

    fun answerFor(prompt: String): String? {
        val other = otherText[prompt]?.trim().orEmpty()
        val picked = selections[prompt].orEmpty()
        return when {
            other.isNotEmpty() && picked.isNotEmpty() -> (picked + other).joinToString(", ")
            other.isNotEmpty() -> other
            picked.isNotEmpty() -> picked.joinToString(", ")
            else -> null
        }
    }

    val allAnswered = question.questions.all { answerFor(it.question) != null }

    Column(
        modifier
            .fillMaxWidth()
            .background(t.questionBg, shape)
            .border(1.dp, t.questionBorder, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Lucide.CircleHelp, contentDescription = null, tint = t.questionInk, modifier = Modifier.size(15.dp))
            Text(
                "The agent needs your input",
                color = t.white,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 14.7.sp,
            )
        }

        question.questions.forEach { prompt ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (prompt.header.isNotBlank()) {
                    Text(
                        prompt.header.uppercase(),
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.strong,
                        fontSize = 11.5.sp,
                        letterSpacing = 0.06.em,
                    )
                }
                Text(
                    prompt.question,
                    color = t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 14.4.sp,
                )
                if (prompt.multiSelect) {
                    Text(
                        "Select all that apply.",
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 11.8.sp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    prompt.options.forEach { option ->
                        val picked = selections[prompt.question].orEmpty().contains(option.label)
                        val chipShape = RoundedCornerShape(TetherDimens.radiusSm)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (picked) t.violetWash else t.keyFace, chipShape)
                                .border(1.dp, if (picked) t.violetStrong else t.keySide, chipShape)
                                .clickable(enabled = !submitted) {
                                    val current = selections[prompt.question].orEmpty()
                                    selections[prompt.question] = when {
                                        prompt.multiSelect && picked -> current - option.label
                                        prompt.multiSelect -> current + option.label
                                        picked -> emptySet()
                                        else -> setOf(option.label)
                                    }
                                }
                                .heightIn(min = TetherDimens.touchTargetDp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                option.label,
                                color = t.white,
                                fontFamily = Manrope,
                                fontWeight = TetherWeights.body,
                                fontSize = 13.8.sp,
                            )
                            if (option.description.isNotBlank()) {
                                Text(
                                    option.description,
                                    color = t.muted,
                                    fontFamily = Manrope,
                                    fontWeight = TetherWeights.body,
                                    fontSize = 12.5.sp,
                                )
                            }
                        }
                    }
                }
                TetherInputWell(
                    value = otherText[prompt.question].orEmpty(),
                    onValueChange = { otherText[prompt.question] = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Other…",
                    singleLine = true,
                    enabled = !submitted,
                )
            }
        }

        TetherKey(
            onClick = {
                val answers = question.questions.mapNotNull { prompt ->
                    answerFor(prompt.question)?.let { prompt.question to it }
                }.toMap()
                val freeText = question.questions
                    .mapNotNull { otherText[it.question]?.trim()?.takeIf { text -> text.isNotEmpty() } }
                    .joinToString("\n")
                    .takeIf { it.isNotEmpty() }
                submitted = true
                onSubmit(answers, freeText)
            },
            modifier = Modifier.fillMaxWidth(),
            variant = KeyVariant.Primary,
            label = if (submitted) "Answer sent" else "Submit answer",
            icon = Lucide.Check,
            enabled = allAnswered && !submitted,
        )
    }
}

/** Permission denial card: danger seam on mineral-deep, mono header. */
@Composable
fun DenialCard(denial: PermissionDenialProjection, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(TetherDimens.radiusMd)
    Column(
        modifier
            .fillMaxWidth()
            .background(t.mineralDeep, shape)
            .border(1.dp, t.dangerEdge, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Lucide.Ban, contentDescription = null, tint = t.danger, modifier = Modifier.size(14.dp))
            Text(
                denial.name,
                color = t.ink,
                fontFamily = JetBrainsMono,
                fontWeight = TetherWeights.label,
                fontSize = 12.8.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "DENIED",
                color = t.danger,
                fontFamily = Manrope,
                fontWeight = TetherWeights.strong,
                fontSize = 11.5.sp,
                letterSpacing = 0.06.em,
            )
        }
        Text(
            denial.reason,
            color = t.muted,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 12.8.sp,
        )
    }
}
