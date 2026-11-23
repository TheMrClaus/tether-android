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
import com.tether.app.ui.components.Spinner
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
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
    const val InstallRow = "browser-install-row"
    const val InstallKey = "browser-install-key"
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
 * the Install Chromium row, only when the server says it can install (`canInstall`) or a job is running; and,
 * only while something is missing, the one command and Copy command (as "Or install by hand" beside the key).
 * Nothing installs until the key is pressed. A server without the install route draws no key and the
 * read-only section it had.
 */
@Composable
internal fun BrowserRuntimeSection(binding: BrowserBinding, narrow: Boolean, server: ServerSettingsBinding, view: com.tether.app.client.ServerSettingsView) {
    val c = binding.controller ?: return
    LaunchedEffect(c) { c.shown() }
    val type = LocalTetherTypography.current
    val status = c.status
    val error = c.error
    SettingsSection(BrowserCopy.TITLE, AnnotatedString(if (status?.canInstall != null) BrowserCopy.CAPTION_INSTALLABLE else BrowserCopy.CAPTION), narrow, modifier = Modifier.testTag(BrowserTags.Section)) {
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
                if (BrowserCopy.installedByTether(status)) append(BrowserCopy.INSTALLED_BY_TETHER)
            }
        }
        SettingsRow(
            kind = RowKind.Server,
            narrow = narrow,
            text = { m -> SettingsRowText(BrowserCopy.CHROMIUM, chromium, m.testTag(BrowserTags.Chromium)) },
        )
        val job = c.install
        val installable = status.canInstall == true
        if (job?.running == true) {
            SettingsRow(
                kind = RowKind.Server,
                narrow = narrow,
                text = { m ->
                    SettingsRowText(BrowserCopy.INSTALL, AnnotatedString(BrowserCopy.INSTALLING_CAPTION), m.testTag(BrowserTags.InstallRow).semantics { liveRegion = LiveRegionMode.Polite })
                },
                control = { m ->
                    TetherKey(
                        onClick = {},
                        enabled = false,
                        classes = KeyClasses.ButtonSecondary,
                        label = BrowserCopy.INSTALLING,
                        leading = { Spinner(14.dp, LocalTetherTokens.current.muted) },
                        modifier = m.testTag(BrowserTags.InstallKey),
                    )
                },
            )
        } else if (installable) {
            val failed = BrowserCopy.installFailed(status, job)
            val caption = remember(status, job, c.installNote, type) {
                buildAnnotatedString {
                    append(BrowserCopy.installCaption(status, job, c.installNote))
                    if (failed) {
                        BrowserCopy.outputTail(job).forEach { line ->
                            append("\n")
                            withStyle(SpanStyle(fontFamily = type.mono)) { append(line) }
                        }
                    }
                }
            }
            SettingsRow(
                kind = RowKind.Server,
                narrow = narrow,
                text = { m -> SettingsRowText(if (failed) BrowserCopy.INSTALL_FAILED else BrowserCopy.INSTALL, caption, m.testTag(BrowserTags.InstallRow)) },
                control = { m ->
                    TetherKey(
                        onClick = c::startInstall,
                        classes = KeyClasses.ButtonSecondary,
                        label = if (failed) BrowserCopy.TRY_AGAIN else BrowserCopy.INSTALL,
                        icon = TetherIcons.Download,
                        iconSize = 14.dp,
                        modifier = m.testTag(BrowserTags.InstallKey),
                    )
                },
            )
        }
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
            text = { m -> SettingsRowText(if (status.canInstall != null) BrowserCopy.BY_HAND else BrowserCopy.TO_INSTALL, install, m.testTag(BrowserTags.Install)) },
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
