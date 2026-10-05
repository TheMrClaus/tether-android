package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.BrowserPick
import com.tether.app.protocol.Attachment
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

object BrowserPickTags {
    const val Chip = "browser-pick-chip"
    const val Remove = "browser-pick-remove"
}

/**
 * T8.6 part 2: the elements picked in the in-console browser, riding this session's next send
 * (chat-view.tsx 90fbb9f :181-189 `browserPicks` / `browserPageUrl` / `onRemoveBrowserPick` /
 * `onClearBrowserPicks`, :3173-3210 `submit`).
 *
 * [send] is the ONE send that carries picks whose screenshots (or staged files) must go as
 * attachments: [text] is the descriptor block plus the draft, [shots] the picks' screenshots, and
 * the staged files ride with them ([com.tether.app.ui.TetherViewModel.sendAttachments]).
 */
@Immutable
class ComposerPicks(
    val items: List<BrowserPick>,
    val pageUrl: String?,
    val onRemove: (BrowserPick) -> Unit,
    val onClear: () -> Unit,
    val send: (text: String, shots: List<Attachment>) -> AttachmentSendResult,
)

/**
 * chat-view.tsx :4097-4120: `.chat-attachment-chip` with the MousePointerSquareDashed glyph, the
 * element's name (its a11y name, else its selector, else its tag, else "element") over its tag
 * (`" · shot"` after it when a screenshot rides along), and the remove key
 * (`Remove selected <tag>`). The strings are the page's: shown by the one-line rule.
 */
@Composable
fun BrowserPickChip(pick: BrowserPick, onRemove: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    val d = pick.descriptor
    Row(
        modifier = Modifier
            .widthIn(max = 256.dp)
            .background(t.mineralDeep, shape)
            .border(1.dp, t.line, shape)
            .padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs)
            .testTag(BrowserPickTags.Chip),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Icon(TetherIcons.SquareDashedMousePointer, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                codeLabel(d.name.ifEmpty { d.selector.ifEmpty { d.tag.ifEmpty { "element" } } }),
                color = t.ink,
                style = type.body.copy(fontSize = 12.8.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                codeLabel(d.tag + if (pick.screenshot != null) " · shot" else ""),
                color = t.muted,
                style = type.body.copy(fontSize = 11.2.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TetherKey(
            onClick = onRemove,
            classes = KeyClasses.IconButton,
            icon = TetherIcons.X,
            iconSize = 14.dp,
            contentDescription = "Remove selected ${SafeText.line(d.tag)}",
            modifier = Modifier.testTag(BrowserPickTags.Remove),
        )
    }
}
