package com.tether.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTypography
import kotlinx.coroutines.launch

/** Tags of the Browser section. */
object BrowserTags {
    const val Section = "browser-section"
    const val Status = "browser-status"
    const val Refresh = "browser-refresh"
    const val Playwright = "browser-playwright"
    const val Chromium = "browser-chromium"
    const val Install = "browser-install"
    const val Command = "browser-install-command"
    const val Copy = "browser-copy"
}

/** What Advanced needs for the Browser section: its [controller] (held by [BrowserRuntimeViewModel] in the app, built from a seed by the goldens). A null one draws nothing. */
data class BrowserBinding(val controller: BrowserRuntimeController?) {
    companion object {
        val None = BrowserBinding(null)
    }
}

/**
 * settings-dialog.tsx BrowserRuntimeSection, in the web's order: Status (ready or not, or the read's
 * error, or "Checking the browser…") with Refresh; Playwright; Chromium (revision, found or not, path);
 * and, only while something is missing, To install with the one command and Copy command. Read-only:
 * no install key.
 */
@Composable
internal fun BrowserRuntimeSection(binding: BrowserBinding, narrow: Boolean, server: ServerSettingsBinding, view: com.tether.app.client.ServerSettingsView) {
    val c = binding.controller ?: return
    LaunchedEffect(c) { c.shown() }
    val type = LocalTetherTypography.current
    val status = c.status
    val error = c.error
    SettingsSection(BrowserCopy.TITLE, AnnotatedString(BrowserCopy.CAPTION), narrow, modifier = Modifier.testTag(BrowserTags.Section)) {
        val refresh: @Composable (Modifier) -> Unit = { m ->
            TetherKey(
                onClick = c::load,
                enabled = !c.loading,
                classes = KeyClasses.ButtonSecondary,
                label = if (c.loading) BrowserCopy.REFRESHING else BrowserCopy.REFRESH,
                icon = TetherIcons.RefreshCw,
                iconSize = 14.dp,
                modifier = m.testTag(BrowserTags.Refresh),
            )
        }
        if (com.tether.app.client.browserToolsSupported(server.serverProtocol, view)) {
            ServerToggleRow(AdvancedRows.browserAgentTools, view, server, narrow)
        }
        if (status == null) {
            SettingsRow(
                kind = RowKind.Server,
                narrow = narrow,
                text = { m ->
                    SettingsRowText(BrowserCopy.STATUS, AnnotatedString(error ?: BrowserCopy.CHECKING), m.testTag(BrowserTags.Status).semantics { liveRegion = LiveRegionMode.Polite })
                },
                control = if (error != null) refresh else null,
            )
            return@SettingsSection
        }
        SettingsRow(
            kind = RowKind.Server,
            narrow = narrow,
            text = { m -> SettingsRowText(BrowserCopy.STATUS, AnnotatedString(BrowserCopy.headline(status)), m.testTag(BrowserTags.Status).semantics { liveRegion = LiveRegionMode.Polite }) },
            control = refresh,
        )
        SettingsRow(
            kind = RowKind.Server,
            narrow = narrow,
            text = { m -> SettingsRowText(BrowserCopy.PLAYWRIGHT, AnnotatedString(BrowserCopy.playwrightLine(status)), m.testTag(BrowserTags.Playwright)) },
        )
        val chromium = remember(status, type) {
            buildAnnotatedString {
                append(BrowserCopy.chromiumLine(status))
                BrowserCopy.path(status)?.let { withStyle(SpanStyle(fontFamily = type.mono)) { append(it) } }
            }
        }
        SettingsRow(
            kind = RowKind.Server,
            narrow = narrow,
            text = { m -> SettingsRowText(BrowserCopy.CHROMIUM, chromium, m.testTag(BrowserTags.Chromium)) },
        )
        val command = BrowserCopy.command(status) ?: return@SettingsSection
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        var copied by remember(command) { mutableStateOf(false) }
        val install = remember(command, type) {
            buildAnnotatedString {
                append(BrowserCopy.INSTALL_CAPTION)
                withStyle(SpanStyle(fontFamily = type.mono)) { append(command) }
            }
        }
        SettingsRow(
            kind = RowKind.Server,
            narrow = narrow,
            text = { m -> SettingsRowText(BrowserCopy.TO_INSTALL, install, m.testTag(BrowserTags.Install)) },
            control = { m ->
                TetherKey(
                    onClick = {
                        scope.launch {
                            // The web's `catch {}`: a clipboard that refuses leaves the command on screen to select.
                            copied = runCatching { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(BrowserCopy.TO_INSTALL, command))) }.isSuccess
                        }
                    },
                    classes = KeyClasses.ButtonSecondary,
                    label = if (copied) BrowserCopy.COPIED else BrowserCopy.COPY,
                    icon = TetherIcons.Copy,
                    iconSize = 14.dp,
                    modifier = m.testTag(BrowserTags.Copy),
                )
            },
        )
    }
}
