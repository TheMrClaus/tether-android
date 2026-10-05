package com.tether.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.HiddenCharacters
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/*
 * ta-m7ef (tether PR #241, PROTOCOL 143): how the app shows repo-controlled commands for approval, as
 * the console does (components/setup-commands.tsx, app/globals.css `.draft-setup-confirm*`, 1bf4a465):
 * a warning-bordered panel, one numbered block per command (a multiline entry is ONE shell script),
 * every invisible or text-reordering character drawn as a highlighted U+XXXX token. Plain text only.
 * The new-session composer, the schedule editor and the end-session teardown confirmation share it.
 */

/** Stable hooks for the behaviour tests and goldens of the three confirmations. */
object ConsentTags {
    const val Title = "consent-title"
    const val Body = "consent-body"
    const val Warning = "consent-warning"
    const val Hidden = "consent-hidden-characters"
    const val Command = "consent-command"
    const val Label = "consent-label"
}

/** `.draft-setup-confirm`: a warning-bordered panel holding a title, [content] and the [actions]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConsentPanel(
    title: String,
    modifier: Modifier = Modifier,
    titleTag: String = ConsentTags.Title,
    actions: @Composable FlowRowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, t.warning, RoundedCornerShape(t.radiusSm))
            .padding(t.css.spaceSm),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.size(14.dp))
            Text(
                title,
                color = t.warning,
                style = type.body.copy(fontSize = 14.sp, fontWeight = FontWeight(600)),
                modifier = Modifier.semantics { heading() }.testTag(titleTag),
            )
        }
        content()
        // The keys wrap under each other when the panel is too narrow for them side by side (a phone).
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            itemVerticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** `.draft-setup-confirm-body` / `-label`: muted 0.78rem / 1.45. [code] spans are drawn mono. */
@Composable
fun ConsentText(text: AnnotatedString, modifier: Modifier = Modifier, tag: String = ConsentTags.Body) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(text, color = t.muted, style = type.body.copy(fontSize = 12.48.sp, lineHeight = 1.45.em), modifier = modifier.testTag(tag))
}

/** [ConsentText] for plain words. */
@Composable
fun ConsentText(text: String, modifier: Modifier = Modifier, tag: String = ConsentTags.Body) = ConsentText(AnnotatedString(text), modifier, tag)

/** A sentence with `code` pieces: [parts] alternate plain and code, starting plain. */
@Composable
fun consentSentence(vararg parts: String): AnnotatedString {
    val type = LocalTetherTypography.current
    return remember(parts.toList(), type) {
        buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                if (index % 2 == 1) withStyle(SpanStyle(fontFamily = type.mono)) { append(part) } else append(part)
            }
        }
    }
}

/** `.draft-setup-confirm-warning` (role=status): a warning glyph and the words, in the warning colour, 0.78rem / 600. */
@Composable
fun ConsentWarning(text: String, modifier: Modifier = Modifier, tag: String = ConsentTags.Warning) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(5.6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Text(text, color = t.warning, style = type.body.copy(fontSize = 12.48.sp, fontWeight = FontWeight(600), lineHeight = 1.45.em))
    }
}

/** components/setup-commands.tsx HiddenCharactersWarning, verbatim. */
const val HIDDEN_CHARACTERS_WARNING =
    "These commands contain characters outside plain ASCII (invisible, text-reordering or look-alike letters), shown below as highlighted U+XXXX codes. Commands rarely need them: treat this as suspicious."

@Composable
fun HiddenCharactersWarning(modifier: Modifier = Modifier) = ConsentWarning(HIDDEN_CHARACTERS_WARNING, modifier, ConsentTags.Hidden)

/**
 * components/setup-commands.tsx CommandList: [label], then one numbered block per command in mono on the
 * mineral well (`.draft-setup-confirm-commands`: 0.75rem, max 10rem tall, scrolling), every hidden
 * character drawn as a highlighted `U+XXXX` token ([HiddenCharacters.reveal] with `command = true`).
 */
@Composable
fun CommandList(label: String, commands: List<String>, modifier: Modifier = Modifier, tagPrefix: String = ConsentTags.Command) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
        ConsentText(label, tag = ConsentTags.Label)
        commands.forEachIndexed { index, command ->
            val text = remember(command, t) {
                buildAnnotatedString {
                    for (segment in HiddenCharacters.reveal(command, command = true)) {
                        if (segment.hidden) {
                            withStyle(SpanStyle(background = t.warning, color = t.mineral, fontWeight = FontWeight(700))) { append(segment.text) }
                        } else {
                            append(segment.text)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${index + 1}.", color = t.muted, style = type.mono.let { androidx.compose.ui.text.TextStyle(fontFamily = it, fontSize = 12.sp) })
                Text(
                    text,
                    color = t.ink,
                    style = androidx.compose.ui.text.TextStyle(fontFamily = type.mono, fontSize = 12.sp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 160.dp)
                        .background(t.mineral, RoundedCornerShape(t.radiusSm))
                        .padding(t.css.spaceXs)
                        .verticalScroll(rememberScrollState())
                        .semantics { contentDescription = "Command ${index + 1}: ${HiddenCharacters.reveal(command, command = true).joinToString("") { it.text }}" }
                        .testTag("$tagPrefix:$index"),
                )
            }
        }
    }
}
