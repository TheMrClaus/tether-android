package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.TetherWeights

/**
 * Compact hand-rolled markdown renderer for finished agent messages.
 *
 * Supported subset: paragraphs, ATX headings (#, ##, ### -> w680), unordered
 * (-, *) and ordered (1.) lists, fenced code blocks (``` on mineral-deep),
 * `inline code` chips on a tint, **bold**, *italic* / _italic_, [links](url)
 * (violet, underlined, opened via LocalUriHandler), and > blockquotes.
 * Not supported: tables, images, nested lists, setext headings, HTML.
 */

internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Bullets(val ordered: Boolean, val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
}

internal fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
        paragraph.setLength(0)
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimEnd()
        when {
            trimmed.trimStart().startsWith("```") -> {
                flushParagraph()
                val code = StringBuilder()
                i += 1
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i += 1
                }
                blocks.add(MdBlock.Code(code.toString()))
            }
            trimmed.matches(Regex("^#{1,3}\\s+.*")) -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks.add(MdBlock.Heading(level, trimmed.dropWhile { it == '#' }.trim()))
            }
            trimmed.matches(Regex("^\\s*[-*]\\s+.*")) || trimmed.matches(Regex("^\\s*\\d+\\.\\s+.*")) -> {
                flushParagraph()
                val ordered = trimmed.matches(Regex("^\\s*\\d+\\.\\s+.*"))
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val itemLine = lines[i].trimEnd()
                    val m = Regex("^\\s*(?:[-*]|\\d+\\.)\\s+(.*)").find(itemLine) ?: break
                    items.add(m.groupValues[1])
                    i += 1
                }
                i -= 1
                blocks.add(MdBlock.Bullets(ordered, items))
            }
            trimmed.startsWith("> ") || trimmed == ">" -> {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size && (lines[i].trimEnd().startsWith(">"))) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[i].trimEnd().removePrefix(">").trim())
                    i += 1
                }
                i -= 1
                blocks.add(MdBlock.Quote(quote.toString()))
            }
            trimmed.isBlank() -> flushParagraph()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(trimmed)
            }
        }
        i += 1
    }
    flushParagraph()
    return blocks
}

private val INLINE = Regex(
    "(`[^`\n]+`)" + // code
        "|(\\*\\*[^*\n]+\\*\\*)" + // bold
        "|(\\*[^*\n]+\\*)" + // italic *
        "|(_[^_\n]+_)" + // italic _
        "|(\\[[^\\]\n]+\\]\\([^)\n]+\\))", // link
)

internal fun inlineMarkdown(text: String, t: TetherTokens, codeSize: TextUnit = 13.sp): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("`") -> withStyle(
                    SpanStyle(
                        fontFamily = JetBrainsMono,
                        fontSize = codeSize,
                        background = t.tintMd,
                        color = t.white,
                    ),
                ) { append(token.trim('`')) }

                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight(700))) {
                    append(token.removeSurrounding("**"))
                }

                token.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.removeSurrounding("*"))
                }

                token.startsWith("_") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.removeSurrounding("_"))
                }

                token.startsWith("[") -> {
                    val label = token.substringAfter('[').substringBefore(']')
                    val url = token.substringAfter('(').substringBeforeLast(')')
                    withLink(
                        LinkAnnotation.Url(
                            url,
                            TextLinkStyles(
                                style = SpanStyle(color = t.violet, textDecoration = TextDecoration.Underline),
                            ),
                        ),
                    ) { append(label) }
                }

                else -> append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

/** Renders a finished message body. */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.4.sp,
) {
    val t = LocalTetherTokens.current
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    inlineMarkdown(block.text, t),
                    color = color,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.55f,
                )

                is MdBlock.Heading -> Text(
                    inlineMarkdown(block.text, t),
                    color = t.white,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.heading,
                    fontSize = when (block.level) {
                        1 -> 16.8.sp
                        2 -> 15.2.sp
                        else -> 14.4.sp
                    },
                )

                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(t.mineralDeep, RoundedCornerShape(TetherDimens.radiusSm))
                        .border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusSm)),
                ) {
                    Text(
                        block.code,
                        color = t.ink,
                        fontFamily = JetBrainsMono,
                        fontSize = 12.8.sp,
                        lineHeight = 12.8.sp * 1.5f,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row {
                            Text(
                                if (block.ordered) "${index + 1}." else "•",
                                color = t.muted,
                                fontFamily = Manrope,
                                fontWeight = TetherWeights.body,
                                fontSize = fontSize,
                                lineHeight = fontSize * 1.55f,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                inlineMarkdown(item, t),
                                color = color,
                                fontFamily = Manrope,
                                fontWeight = TetherWeights.body,
                                fontSize = fontSize,
                                lineHeight = fontSize * 1.55f,
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(t.lineStrong),
                    )
                    Text(
                        inlineMarkdown(block.text, t),
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = fontSize,
                        lineHeight = fontSize * 1.55f,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}
