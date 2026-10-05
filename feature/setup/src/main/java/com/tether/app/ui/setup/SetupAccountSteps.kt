package com.tether.app.ui.setup

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.client.ClaudeLoginLink
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.settings.LoginLinkOpener
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import kotlinx.coroutines.launch

/*
 * T10.6 (ta-pqui): the GitHub station (page.tsx StepGitHub :757-970) and the Claude accounts station
 * (StepClaudeAccounts :971-1219), dressed as app/studio.css 863-939 dresses the page's .setup-* classes.
 * Both stay skippable: Continue has no gate for either (page.tsx :260-262), so a half-done login never holds
 * the wizard. The station's state lives in [SetupGitHubModel] / [SetupClaudeModel].
 *
 * What the page draws as `<a target="_blank">` (the GitHub device page, the Claude sign-in URL) is a key
 * that opens it in the phone's browser by the same rules Settings uses ([LoginLinkOpener], the shared link
 * rule): the address's host is drawn beside it. The one-time code is selectable and has a Copy key.
 */

/** How a link is opened: the phone's browser. Tests provide a recorder. */
internal val LocalSetupLinkOpener = compositionLocalOf<LoginLinkOpener?> { null }

private object AccountCopy {
    const val GITHUB_TITLE = "Connect GitHub."
    const val GITHUB_CHECKING = "Checking the host GitHub login…"
    const val RETRY = "Try again"
    const val CLAUDE_TITLE = "Add a Claude account."
    const val CLAUDE_CHECKING = "Checking configured accounts…"
    const val LINK_REFUSED = "The server sent a link that a browser will not open (a javascript:, data:, file: or content: address, or not an address at all)."
    const val LINK_UNOPENED = "No browser on this phone could open the link."
    const val LINK_UNOPENED_APP = "No app on this phone could open the link."
    const val DEFAULT_DEVICE_URL = "https://github.com/login/device"
}

/** `.setup-note` with the page's spinning Loader. */
@Composable
private fun BusyNote(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 13.dp, modifier = Modifier.padding(top = 2.dp))
        SetupText(text, 12f, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, lineHeight = 1.6f)
    }
}

/** `.setup-problem` (a danger line, an alert) and its `.setup-retry`. */
@Composable
private fun ProblemLine(text: String, retryTag: String, onRetry: () -> Unit) {
    val t = LocalTetherTokens.current
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
        SetupText(text, 12f, color = t.danger, weight = 600, lineHeight = 1.5f)
        SetupText(
            AccountCopy.RETRY,
            12f,
            Modifier
                .testTag(retryTag)
                .heightIn(min = 44.dp)
                .androidClickable(onRetry),
            color = t.violetStrong,
            weight = 500,
        )
    }
}

private fun Modifier.androidClickable(onClick: () -> Unit): Modifier =
    clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)

/** `.setup-warning`: the attention card the sign-in prompts sit in. */
@Composable
private fun WarningCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val t = LocalTetherTokens.current
    Row(
        modifier.fillMaxWidth().background(t.attentionBg, RoundedCornerShape(10.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(TetherIcons.KeyRound, contentDescription = null, tint = t.warning, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/**
 * The key that opens [address] (`<a class="button-secondary" target="_blank">`): the host beside it, the
 * refusal when a browser would not open it, the note when nothing on the phone could.
 */
@Composable
private fun OpenLink(address: String, label: String, tag: String, description: String) {
    val t = LocalTetherTokens.current
    val opener = LocalSetupLinkOpener.current ?: LoginLinkOpener.browser(LocalContext.current)
    val link = remember(address) { ClaudeLoginLink.parse(address) }
    var unopened by remember(address) { mutableStateOf<String?>(null) }
    if (link == null) {
        SetupText(AccountCopy.LINK_REFUSED, 12f, color = t.danger, weight = 600, lineHeight = 1.5f)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TetherKey(
                onClick = {
                    unopened = when {
                        opener.open(link) -> null
                        link.web -> AccountCopy.LINK_UNOPENED
                        else -> AccountCopy.LINK_UNOPENED_APP
                    }
                },
                classes = KeyClasses.ButtonSecondary,
                label = label,
                icon = TetherIcons.ExternalLink,
                iconSize = 14.dp,
                minHeight = 44.dp,
                contentDescription = if (link.web) "$description (${link.shownHost})" else description,
                modifier = Modifier.testTag(tag),
            )
            if (link.web) SetupText(link.shownHost, 12f, Modifier.weight(1f, fill = false), color = t.muted, lineHeight = 1.5f, maxLines = 2)
        }
        unopened?.let { SetupText(it, 12f, color = t.danger, weight = 600, lineHeight = 1.5f) }
    }
}

// ---------------------------------------------------------------------------------------------
// GitHub (page.tsx :757-970)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun StepGitHub(model: SetupGitHubModel, phone: Boolean) {
    val t = LocalTetherTokens.current
    LaunchedEffect(model) { model.enter() }
    StepColumn(phone, Modifier.testTag(SetupTags.GitHub)) {
        StepTitle(AccountCopy.GITHUB_TITLE, phone)
        SetupCopy(
            withCode(
                "Tether reads issues and pull requests through the ",
                "gh",
                " CLI. Reusing an existing host login needs no setup; otherwise connect here, paste a token, or skip — you can finish this later from Settings.",
            ),
        )
        val status = model.status
        when {
            model.loading -> BusyNote(AccountCopy.GITHUB_CHECKING)
            model.error.isNotEmpty() -> ProblemLine(model.error, SetupTags.GitHubRetry, model::loadStatus)
            status != null -> when {
                status.authenticated -> SetupNote(
                    TetherIcons.Check,
                    buildAnnotatedString {
                        append("Using existing GitHub login: ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = t.ink)) { append("@${status.account?.takeIf { it.isNotEmpty() } ?: "unknown"}") }
                        if (status.scopes.isNotEmpty()) append(" · scopes: ${status.scopes.joinToString(", ")}")
                        if (status.managedToken) append(" · managed token")
                        status.ghVersion?.takeIf { it.isNotEmpty() }?.let { append(" · gh $it") }
                        append(" — no setup needed.")
                    },
                )
                status.ghInstalled -> SetupNote(TetherIcons.TriangleAlert, "No GitHub login detected on this host. Connect below, or skip and set it up later.")
                else -> SetupNote(
                    TetherIcons.TriangleAlert,
                    buildAnnotatedString {
                        val code = SpanStyle(fontFamily = JetBrainsMono, color = t.ink, fontSize = 12.9.sp)
                        append("The ")
                        withStyle(code) { append("gh") }
                        append(" CLI is not installed. Install ")
                        withStyle(code) { append("gh") }
                        append(", or paste a token below — Tether can call GitHub through a managed token alone.")
                    },
                )
            }
        }
        if (!model.connected) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SetupText("Connect GitHub", 13f, Modifier.semantics { heading() }, color = t.ink, weight = 600)
                ChoiceCard(
                    selected = model.method == GitHubMethod.Device,
                    onSelect = model::startDevice,
                    title = "Connect in the app",
                    detail = "Open the device flow and enter a one-time code in your browser.",
                    modifier = Modifier.testTag(SetupTags.GitHubDevice),
                )
                ChoiceCard(
                    selected = model.method == GitHubMethod.Token,
                    onSelect = model::chooseToken,
                    title = "Paste a personal access token",
                    detail = "Stored server-side, masked after entry. Requires repo, read:org, gist scopes.",
                    modifier = Modifier.testTag(SetupTags.GitHubToken),
                )
            }
        }
        val poll = model.poll
        if (model.method == GitHubMethod.Device && poll != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val code = poll.deviceCode
                if (code != null) {
                    DeviceCodeCard(code, poll.verificationUri ?: AccountCopy.DEFAULT_DEVICE_URL, model::cancelDevice)
                } else {
                    BusyNote("Requesting a one-time code…")
                }
                if (poll.status == "error") {
                    SetupText(poll.error?.takeIf { it.isNotEmpty() } ?: "The login failed.", 12f, color = t.danger, weight = 600, lineHeight = 1.5f)
                }
            }
        }
        if (model.method == GitHubMethod.Token) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Personal access token")
                TetherInputWell(
                    value = model.tokenInput,
                    onValueChange = model::typeToken,
                    placeholder = "ghp_…",
                    singleLine = true,
                    style = setupInputStyle(),
                    visualTransformation = PasswordVisualTransformation(),
                    // A token is never offered to an autofill service and is not auto-corrected.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Personal access token" }.testTag(SetupTags.GitHubTokenField),
                )
                FieldNote(model.tokenError.ifEmpty { if (model.tokenSaved) "Token verified and saved." else "" }, warning = model.tokenError.isNotEmpty())
                TetherKey(
                    onClick = model::saveToken,
                    classes = KeyClasses.ButtonPrimary,
                    label = if (model.busy) "Verifying…" else "Verify & save token",
                    icon = if (model.busy) TetherIcons.Loader else TetherIcons.Check,
                    iconSize = 14.dp,
                    enabled = !model.busy && model.tokenInput.trim().isNotEmpty(),
                    minHeight = 46.dp,
                    modifier = Modifier.testTag(SetupTags.GitHubTokenSave),
                )
            }
        }
        SetupFootnote(SetupWords.GITHUB_FOOT)
    }
}

/** The device flow's prompt: the one-time code (selectable, with a Copy key), Open GitHub, the wait and Cancel. */
@Composable
private fun DeviceCodeCard(code: String, address: String, onCancel: () -> Unit) {
    val t = LocalTetherTokens.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    WarningCard {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SetupText("Copy this one-time code, then open the verification URL:", 13f, color = t.ink, lineHeight = 1.65f)
                SetupText(
                    buildAnnotatedString { withStyle(SpanStyle(fontFamily = JetBrainsMono, color = t.white, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)) { append(code) } },
                    20f,
                    Modifier.testTag(SetupTags.GitHubCode),
                    color = t.white,
                    lineHeight = 1.4f,
                )
            }
        }
        FlowRowOfKeys {
            OpenLink(address, "Open GitHub", SetupTags.GitHubOpen, "Open the GitHub device page in the browser")
            TetherKey(
                onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("GitHub one-time code", code))) } },
                classes = KeyClasses.ButtonSecondary,
                label = "Copy code",
                icon = TetherIcons.Copy,
                iconSize = 14.dp,
                minHeight = 44.dp,
                modifier = Modifier.testTag(SetupTags.GitHubCopy),
            )
        }
        BusyNote("Waiting for you to authorize…")
        TetherKey(
            onClick = onCancel,
            classes = KeyClasses.ButtonSecondary,
            label = "Cancel",
            minHeight = 44.dp,
            modifier = Modifier.testTag(SetupTags.GitHubCancel),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowOfKeys(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

// ---------------------------------------------------------------------------------------------
// Claude accounts (page.tsx :971-1219)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun StepClaudeAccounts(model: SetupClaudeModel, phone: Boolean) {
    val t = LocalTetherTokens.current
    LaunchedEffect(model) { model.enter() }
    StepColumn(phone, Modifier.testTag(SetupTags.ClaudeAccounts)) {
        StepTitle(AccountCopy.CLAUDE_TITLE, phone)
        if (!model.available) {
            // page.tsx :1123: no Claude harness, nothing to configure, no request made.
            SetupCopy(
                "This step only applies when Claude Code is one of your harnesses. You didn’t select it on " +
                    "the previous step, so there’s nothing to configure here — continue on.",
            )
            SetupFootnote("Optional — you can enable Claude Code and add accounts anytime from Settings.")
            return@StepColumn
        }
        SetupCopy(
            "Tether can run more than one Anthropic login side by side. One account is already enough " +
                "to finish setup — add another now only if you already know you want it, or skip and do this " +
                "later from Settings.",
        )
        when {
            model.loading -> BusyNote(AccountCopy.CLAUDE_CHECKING)
            model.listError.isNotEmpty() -> ProblemLine(model.listError, SetupTags.ClaudeRetry, model::loadAccounts)
            model.accounts.isNotEmpty() -> Column(Modifier.fillMaxWidth().setupCard(12).padding(8.dp)) {
                for (account in model.accounts) {
                    val status = model.statuses[account.id]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SetupText(account.label, 13f, Modifier.weight(1f), color = t.ink, weight = 700, lineHeight = 1.4f)
                        if (status?.loggedIn == true) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(TetherIcons.Check, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
                                SetupText("Logged in" + (status.email?.takeIf { it.isNotEmpty() }?.let { " as $it" } ?: ""), 12f, lineHeight = 1.4f)
                            }
                        } else {
                            TetherKey(
                                onClick = { model.startLogin(account.id) },
                                classes = KeyClasses.ButtonSecondary,
                                label = "Log in",
                                enabled = !model.loginBusy && model.activeLoginId != account.id,
                                minHeight = 44.dp,
                                modifier = Modifier.testTag(SetupTags.claudeLogin(account.id)),
                            )
                        }
                    }
                }
            }
            else -> SetupNote(null, "No additional accounts configured yet.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Add another account")
            TetherInputWell(
                value = model.nickname,
                onValueChange = model::typeNickname,
                placeholder = "e.g. work, personal",
                singleLine = true,
                style = setupInputStyle(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Add another account" }.testTag(SetupTags.ClaudeNickname),
            )
            FieldNote(model.addError, warning = model.addError.isNotEmpty())
            TetherKey(
                onClick = model::addAccount,
                classes = KeyClasses.ButtonPrimary,
                label = if (model.addBusy) "Adding…" else "Add account",
                icon = if (model.addBusy) TetherIcons.Loader else null,
                iconSize = 14.dp,
                enabled = !model.addBusy && model.nickname.trim().isNotEmpty(),
                minHeight = 46.dp,
                modifier = Modifier.testTag(SetupTags.ClaudeAdd),
            )
        }
        if (model.activeLoginId != null && model.loginStage != ClaudeLoginStage.Idle) {
            when (model.loginStage) {
                ClaudeLoginStage.Success -> SetupNote(TetherIcons.Check, "Logged in — this account is ready to use.")
                ClaudeLoginStage.Error -> Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
                    SetupText(model.loginError.ifEmpty { "The login failed." }, 12f, color = t.danger, weight = 600, lineHeight = 1.5f)
                }
                else -> WarningCard {
                    val url = model.loginUrl
                    if (url != null) {
                        SetupText("Open this URL and sign in, then paste the code Anthropic shows you:", 13f, color = t.ink, lineHeight = 1.65f)
                        OpenLink(url, "Open Claude login", SetupTags.ClaudeOpen, "Open the Claude sign-in page in the browser")
                    } else {
                        BusyNote("Starting the login…")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FieldLabel("Authorization code")
                        TetherInputWell(
                            value = model.codeInput,
                            onValueChange = model::typeCode,
                            singleLine = true,
                            style = setupInputStyle(),
                            // The code is never offered to an autofill service and is not auto-corrected.
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Authorization code" }.testTag(SetupTags.ClaudeCode),
                        )
                    }
                    FlowRowOfKeys {
                        TetherKey(
                            onClick = model::submitCode,
                            classes = KeyClasses.ButtonPrimary,
                            label = if (model.codeBusy) "Submitting…" else "Submit code",
                            icon = if (model.codeBusy) TetherIcons.Loader else null,
                            iconSize = 14.dp,
                            enabled = !model.codeBusy && model.codeInput.trim().isNotEmpty(),
                            minHeight = 46.dp,
                            modifier = Modifier.testTag(SetupTags.ClaudeSubmit),
                        )
                        TetherKey(
                            onClick = model::cancelLogin,
                            classes = KeyClasses.ButtonSecondary,
                            label = "Cancel",
                            minHeight = 46.dp,
                            modifier = Modifier.testTag(SetupTags.ClaudeCancel),
                        )
                    }
                }
            }
        }
        SetupFootnote(SetupWords.CLAUDE_FOOT)
    }
}
