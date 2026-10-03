package com.tether.app.ui.draft

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as testTagProperty
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.tether.app.client.WorktreeCopy
import com.tether.app.client.WorktreeField
import com.tether.app.client.WorktreeModes
import com.tether.app.client.WorktreeSourceInfo
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.chat.ControlSelect
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.proseText
import com.tether.app.ui.text.styledDisplay
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/*
 * ta-23f (T8.1 slice 5): the composer's worktree isolation — components/draft-composer.tsx
 * WorktreeSelect (887c222 ~631-679, in the project row beside the folder chip) and WorktreeDetails
 * (~687-809, its own row under the project row), drawn from [com.tether.app.client.DraftComposerModel].
 * ta-coik.11: no setup confirmation; the web's setup note (90fbb9f:789-795) is all it shows.
 *
 * Every branch, ref and path here comes from the repo or the server: suggestions, the base
 * placeholder, the confirmation's ref / branch / folder are drawn by the exact rule (every bidi
 * control, invisible character and odd space a visible token, in an LTR paragraph), so what is
 * confirmed is what is sent. Picking a suggestion only fills the field (nothing is sent).
 */

/** Test tags of the worktree controls and notes. */
object WorktreeTags {
    const val Select = "draft-worktree"
    const val Details = "draft-worktree-row"
    const val NotRepo = "draft-worktree-not-repo"
    const val SetupNote = "draft-worktree-setup-note"
    const val Suggestions = "draft-worktree-suggestions"
    fun warning(index: Int) = "draft-worktree-warning-$index"
    fun field(field: WorktreeField) = "draft-worktree-field-${field.key}"
    fun suggestion(value: String) = "draft-worktree-suggestion:$value"
}

/** The most suggestions one field lists at a time (the datalist's own list scrolls; so does this). */
internal const val SUGGESTIONS_SHOWN = 50

private fun JsObj.s(key: String): String = (this[key] as? JsStr)?.value.orEmpty()

/**
 * draft-composer.tsx WorktreeSelect: `#draft-worktree`, the Split glyph, "Local" or the mode's name,
 * `is-active` while isolated; its rows and their descriptions verbatim ([WorktreeModes.OPTIONS]).
 */
@Composable
internal fun WorktreeSelect(form: JsObj, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        ControlSelect(
            control = WorktreeModes.control(form),
            name = WorktreeCopy.SELECT_NAME,
            enabled = true,
            lockCopy = null,
            onSelect = onSelect,
            testTag = WorktreeTags.Select,
            icon = TetherIcons.Split,
            active = form["useWorktree"] == JsBool.TRUE,
        )
    }
}

/**
 * draft-composer.tsx WorktreeDetails: absent for a local session; for a folder the server says is
 * not a repository, that note alone; otherwise the mode's fields in the web's order (Base, Branch,
 * Pull request, New branch, then Name), the setup / scripts note and the config warnings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorktreeDetails(form: JsObj, source: WorktreeSourceInfo?, onField: (WorktreeField, String) -> Unit) {
    if (form["useWorktree"] != JsBool.TRUE) return
    val t = LocalTetherTokens.current
    val mode = WorktreeModes.selected(form)
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(bottom = t.css.spaceSm)
            .semantics { contentDescription = WorktreeCopy.ROW_LABEL }
            .testTag(WorktreeTags.Details),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        if (source != null && !source.isRepo) {
            WorktreeNote(WorktreeCopy.notARepo(form.s("cwd")), WorktreeTags.NotRepo, status = true)
        } else {
            WorktreeFields(form, mode, source, onField)
        }
    }
}

/** The fields of [mode], then the setup / scripts note and the config warnings. */
@Composable
private fun WorktreeFields(form: JsObj, mode: String, source: WorktreeSourceInfo?, onField: (WorktreeField, String) -> Unit) {
    // Remote-tracking refs first (the web's baseChoices: names with a "/"), every branch for checkout.
    val branches = source?.branches.orEmpty()
    if (mode == WorktreeModes.BRANCH_OFF) {
        WorktreeInput(
            label = WorktreeCopy.BASE,
            value = form.s(WorktreeField.BaseRef.key),
            placeholder = source?.defaultBaseRef?.takeIf { it.isNotEmpty() } ?: WorktreeCopy.BASE_PLACEHOLDER,
            field = WorktreeField.BaseRef,
            suggestions = branches.filter { it.contains('/') },
            onField = onField,
        )
    }
    if (mode == WorktreeModes.CHECKOUT_BRANCH) {
        WorktreeInput(WorktreeCopy.BRANCH, form.s(WorktreeField.Branch.key), WorktreeCopy.BRANCH_PLACEHOLDER, WorktreeField.Branch, branches, onField)
    }
    if (mode == WorktreeModes.CHECKOUT_PR) {
        WorktreeInput(WorktreeCopy.PR, form.s(WorktreeField.Pr.key), WorktreeCopy.PR_PLACEHOLDER, WorktreeField.Pr, emptyList(), onField, numeric = true)
    }
    if (mode == WorktreeModes.BRANCH_OFF) {
        WorktreeInput(WorktreeCopy.NEW_BRANCH, form.s(WorktreeField.Branch.key), WorktreeCopy.NEW_BRANCH_PLACEHOLDER, WorktreeField.Branch, emptyList(), onField)
    }
    WorktreeInput(WorktreeCopy.NAME, form.s(WorktreeField.Slug.key), WorktreeCopy.NAME_PLACEHOLDER, WorktreeField.Slug, emptyList(), onField)
    source?.let(WorktreeCopy::setupNote)?.let { WorktreeNote(it, WorktreeTags.SetupNote, status = false) }
    source?.configWarnings.orEmpty().forEachIndexed { i, warning -> WorktreeNote(warning, WorktreeTags.warning(i), status = true) }
}

/** `.draft-worktree-note`: a full-width line in the row's muted ink; `role=status` ones are announced. */
@Composable
private fun WorktreeNote(text: String, tag: String, status: Boolean) {
    val t = LocalTetherTokens.current
    Text(
        // The folder and the config warnings are not the app's words: prose, every bidi control a token.
        proseText(text),
        color = t.muted,
        style = LocalTetherTypography.current.body.copy(fontSize = 12.sp, lineHeight = 1.5.em),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (status) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .testTag(tag),
    )
}

/**
 * `<label class="draft-worktree-field"><span>Base</span><input …/></label>`: the name bound to its
 * input (the row's `label` style), the input with its placeholder, no spell check or capitals, and
 * for Base / Branch the datalist's suggestions under it while it has focus.
 */
@Composable
private fun WorktreeInput(
    label: String,
    value: String,
    placeholder: String,
    field: WorktreeField,
    suggestions: List<String>,
    onField: (WorktreeField, String) -> Unit,
    numeric: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var dismissed by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
        // The input carries the name for TalkBack; the visible word is the label's text.
        Text(
            label,
            color = t.muted,
            style = type.body.copy(fontSize = 11.52.sp, fontWeight = FontWeight(650)),
            modifier = Modifier.clearAndSetSemantics { },
        )
        Box {
            val style = type.body.copy(fontSize = 14.sp, lineHeight = 20.sp)
            BasicTextField(
                value = value,
                onValueChange = {
                    dismissed = false
                    onField(field, it)
                },
                singleLine = true,
                textStyle = style.copy(color = t.ink),
                cursorBrush = SolidColor(t.violet),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    dismissed = true
                    focus.clearFocus()
                }),
                interactionSource = interaction,
                modifier = Modifier
                    .width(176.dp)
                    .heightIn(min = 44.dp)
                    .semantics { contentDescription = label }
                    .testTag(WorktreeTags.field(field)),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .heightIn(min = 44.dp)
                            .cssSurface(RoundedCornerShape(8.dp), t.mineralDeep, CssBorder(1.dp, if (focused) t.violetStrong else t.lineStrong), emptyList())
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // The base placeholder can be the server's default ref: drawn by the code rule.
                        if (value.isEmpty()) Text(codeLabel(placeholder), style = style, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            val shown = remember(suggestions, value) {
                suggestions.asSequence().filter { it != value && (value.isEmpty() || it.contains(value, ignoreCase = true)) }.take(SUGGESTIONS_SHOWN).toList()
            }
            if (focused && !dismissed && shown.isNotEmpty()) {
                val density = LocalDensity.current
                Popup(
                    offset = with(density) { IntOffset(0, 48.dp.roundToPx()) },
                    onDismissRequest = { dismissed = true },
                    // Not focusable: the input keeps the keyboard while the list is up.
                    properties = PopupProperties(focusable = false),
                ) {
                    SuggestionList(shown) { pick ->
                        dismissed = true
                        onField(field, pick)
                    }
                }
            }
        }
    }
}

/** The datalist's rows: each name by the exact rule (it is the repo's text), a tap fills the field. */
@Composable
private fun SuggestionList(values: List<String>, onPick: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        Modifier
            .widthIn(min = 176.dp, max = 320.dp)
            .heightIn(max = 240.dp)
            .cssSurface(shape, t.graphite, CssBorder(1.dp, t.keySide), t.css.shadowMenu)
            .clip(shape)
            .verticalScroll(rememberScrollState())
            .testTag(WorktreeTags.Suggestions),
    ) {
        values.forEach { value ->
            val shown = exactText(value)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onPick(value) }
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = shown.text
                        onClick("Use this name") { onPick(value); true }
                        testTagProperty = WorktreeTags.suggestion(value)
                    }
                    .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(shown, color = t.ink, style = type.body.copy(fontSize = 12.8.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** [text] by the exact rule, tokens styled, wrapping anywhere but inside a token, in an LTR paragraph. */
@Composable
private fun exactText(text: String): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(text, t) {
        AnnotatedString.Builder(text.length + 8).apply {
            withStyle(ParagraphStyle(textDirection = codeDirection)) {
                append(styledDisplay(SafeText.breakAnywhere(SafeText.exact(text)), tokenStyle(t)))
            }
        }.toAnnotatedString()
    }
}
