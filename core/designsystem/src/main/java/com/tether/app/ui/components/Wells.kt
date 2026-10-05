package com.tether.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CredentialRequestData
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.credentialRequest
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherTokens

/**
 * Recessed wells: text inputs, filters, pickers, plates (globals.css 8923-8944). A well is a
 * pocket machined into the panel — `1px solid var(--line-strong)`, `var(--mineral-deep)` floor,
 * no shadow: Studio's `--well` was transparent and was retired at tether 887c222, so its wells
 * are flat fields. Focus is `border-color: var(--violet-strong)` plus a `0 0 0 3px
 * var(--focus-glow)` halo (violet = focus).
 */
fun wellShadows(t: TetherTokens, focused: Boolean): List<CssShadow> =
    if (focused) listOf(CssShadow(false, 0.dp, 0.dp, 0.dp, 3.dp, t.focusGlow)) else emptyList()

/** The well surface on any shape; content goes inside. */
fun Modifier.tetherWell(t: TetherTokens, shape: Shape, focused: Boolean = false): Modifier =
    cssSurface(shape, t.mineralDeep, CssBorder(1.dp, if (focused) t.violetStrong else t.lineStrong), wellShadows(t, focused))

/**
 * A screen's own input rule over the plain well, for a screen whose CSS restyles its inputs
 * (ta-coik.53: studio-login.module.css `.field input`). Each value replaces the well's default.
 */
@Immutable
data class InputWellStyle(
    val radius: Dp,
    /** The input's CSS height, held as a floor so a larger font scale still fits. */
    val minHeight: Dp,
    val horizontalPadding: Dp,
    val face: Color,
    val border: Color,
    /** The border while focused; no glow is drawn. */
    val focusBorder: Color,
    /** A CSS `outline` drawn while focused: its colour, width and offset. */
    val focusOutline: Color,
    val focusOutlineWidth: Dp,
    val focusOutlineOffset: Dp,
    /** The border while the input is marked invalid (see [TetherInputWell]'s `invalid`), focused or not. */
    val invalidBorder: Color,
    val disabledAlpha: Float,
)

/** A recessed plate / frame (e.g. `.session-elapsed`, `.chat-frame`) holding arbitrary content. */
@Composable
fun TetherWell(
    modifier: Modifier = Modifier,
    radius: Dp? = null,
    focused: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    Box(modifier.tetherWell(t, RoundedCornerShape(radius ?: t.radiusMd), focused), content = content)
}

/**
 * The text-input well (`.chat-input` / `.field-group input`): radius `--radius-md`, touch height
 * ≥44dp, body type (1rem, 400), `--faint` placeholder, violet caret. Disabled inputs fade to 0.48
 * (globals.css 641-647).
 */
@Composable
fun TetherInputWell(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maxLines: Int = if (singleLine) 1 else 6,
    // Manrope is the UI face; JetBrains Mono is for code-like values (paths, a pairing code).
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    interactionSource: MutableInteractionSource? = null,
    /**
     * What an autofill service may put here (ta-s4r: the sign-in's username and password), on
     * the editable node itself so a password manager never has to guess which field is which.
     */
    contentType: ContentType? = null,
    /**
     * ta-coik.1: a pending Credential Manager request on the same editable node (the web's
     * `autocomplete="… webauthn"`): from Android 15 an autofill service lists its passkey among the
     * field's suggestions. Ignored below API 35.
     */
    credentialRequest: CredentialRequestData? = null,
    /** Null: the plain well. */
    style: InputWellStyle? = null,
    /** The screen marks this input invalid ([InputWellStyle.invalidBorder]; the plain well has no such state). */
    invalid: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(style?.radius ?: t.radiusMd)
    val base = type.body.let { if (fontFamily != null) it.copy(fontFamily = fontFamily) else it }
        .let { if (letterSpacing != TextUnit.Unspecified) it.copy(letterSpacing = letterSpacing) else it }

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = if (enabled) 1f else style?.disabledAlpha ?: DisabledOpacity
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .then(
                if (style == null) {
                    Modifier.tetherWell(t, shape, focused)
                } else {
                    // An invalid rule outranks the focus border (equal specificity, later in source on the web).
                    val edge = when {
                        invalid -> style.invalidBorder
                        focused -> style.focusBorder
                        else -> style.border
                    }
                    Modifier
                        .focusRing(focused, shape, style.focusOutline, style.focusOutlineWidth, style.focusOutlineOffset)
                        .cssSurface(shape, style.face, CssBorder(1.dp, edge))
                },
            )
            .heightIn(min = style?.minHeight ?: TetherDimens.touchTargetDp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .then(
                    if (contentType != null || credentialRequest != null) {
                        Modifier.semantics {
                            if (contentType != null) this.contentType = contentType
                            if (credentialRequest != null) this.credentialRequest = credentialRequest
                        }
                    } else {
                        Modifier
                    },
                )
                .fillMaxWidth()
                .padding(horizontal = style?.horizontalPadding ?: t.css.spaceMd, vertical = 11.dp),
            enabled = enabled,
            textStyle = base.copy(color = t.ink),
            cursorBrush = SolidColor(t.violet),
            singleLine = singleLine,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(text = placeholder, style = base, color = t.faint, maxLines = 2)
                    }
                    innerTextField()
                }
            },
        )
    }
}
