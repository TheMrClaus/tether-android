package com.tether.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.EngineDetection
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens

/** Test tags of the wizard's controls. */
object SetupTags {
    const val Begin = "setup-begin"
    const val Retry = "setup-retry"
    const val Back = "setup-back"
    const val Continue = "setup-continue"
    const val Apply = "setup-apply"
    const val Error = "setup-error"
    const val Username = "setup-username"
    const val Password = "setup-password"
    const val Confirm = "setup-confirm"
    const val Reveal = "setup-reveal"
    const val Rescan = "setup-rescan"
    const val Bundled = "setup-bundled"
    const val IsolationOpen = "setup-isolation-open"
    const val IsolationGuided = "setup-isolation-guided"
    const val Browse = "setup-browse"
    const val Plate = "setup-plate"
    const val SignIn = "setup-sign-in"
    const val Waiting = "setup-waiting"
    const val Command = "setup-command"
    const val GitHubSeam = "setup-seam-github"
    const val ClaudeSeam = "setup-seam-claude"
    fun engine(engine: String) = "setup-engine:$engine"
    fun locator(engine: String) = "setup-locator:$engine"
    fun locate(engine: String) = "setup-locate:$engine"
    fun candidate(path: String) = "setup-candidate:$path"
}

/** page.tsx's words, in one place (the page is the authority; nothing here is the app's own). */
object SetupWords {
    const val WELCOME = "Welcome to Tether."
    const val LEDE = "Your coding agents, connected in one workspace. Set up your account, choose your tools, and pick up your work from any device."
    const val BEGIN = "Begin setup"
    const val REACHING = "Reaching the console…"
    const val FOOTNOTE = "Takes about two minutes. Nothing is written until the final step."
    const val TRY_AGAIN = "Try again"
    const val CONNECTIONS_TITLE = "Bring your tools together."
    const val CONNECTIONS_LEAD = "Tether connects to the coding agents on this machine."
    const val CONNECTIONS_FOOT = "Choose which agents to connect in the next steps. Your projects stay on your machine."
    const val RAIL_NOTE = "Single-operator console"
    const val BACK = "Back"
    const val CONTINUE = "Continue"
    const val APPLY = "Apply & start Tether"
    const val APPLYING = "Applying…"
    const val GITHUB_FOOT = "Optional — skip and set up later from Settings if you prefer."
    const val CLAUDE_FOOT = "Optional — skip and add accounts later from Settings if you prefer."
}

// ---------------------------------------------------------------------------------------------
// Welcome (page.tsx :298-330, ProviderConnections :437-467)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun WelcomeCopy(model: SetupWizardModel, phone: Boolean, modifier: Modifier = Modifier) {
    // globals.css 2347-2348: below the stacking point the hero is centred (`justify-items: center; text-align: center`).
    val align = if (phone) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start
    val t = LocalTetherTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (phone) 20.dp else 24.dp), horizontalAlignment = if (phone) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            SetupWords.WELCOME,
            modifier = Modifier.semantics { heading() }.widthIn(max = (if (phone) 290f else 380f).dp),
            style = androidx.compose.ui.text.TextStyle(
                color = t.white,
                fontFamily = com.tether.app.ui.theme.Manrope,
                fontWeight = androidx.compose.ui.text.font.FontWeight(650),
                fontSize = (if (phone) 42f else 64f).sp,
                lineHeight = (if (phone) 42f else 64f).times(1.08f).sp,
                letterSpacing = (-0.035).em,
                textAlign = align,
            ),
        )
        SetupText(SetupWords.LEDE, if (phone) 15f else 16f, Modifier.widthIn(max = (380f).dp), lineHeight = if (phone) 1.7f else 1.8f, textAlign = align)
        if (model.stateError.isNotEmpty()) {
            Column(Modifier.testTag(SetupTags.Error).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = if (phone) Alignment.CenterHorizontally else Alignment.Start) {
                SetupText(model.stateError, 12f, color = t.danger, weight = 600, lineHeight = 1.5f, textAlign = align)
                SetupText(
                    SetupWords.TRY_AGAIN,
                    12f,
                    Modifier
                        .testTag(SetupTags.Retry)
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button, onClick = model::retryLoad)
                        .padding(top = 12.dp),
                    color = t.ink,
                    weight = 650,
                )
            }
        }
        val ready = model.state != null
        TetherKey(
            onClick = model::begin,
            classes = KeyClasses.ButtonPrimary,
            label = if (ready) SetupWords.BEGIN else SetupWords.REACHING,
            icon = if (ready) null else TetherIcons.Loader,
            iconSize = 16.dp,
            enabled = ready,
            minHeight = 50.dp,
            contentPadding = 24.dp,
            trailing = if (ready) {
                { Icon(TetherIcons.ArrowRight, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
            modifier = Modifier.testTag(SetupTags.Begin),
        )
        SetupText(SetupWords.FOOTNOTE, 12f, Modifier.widthIn(max = (380f).dp), color = t.faint, lineHeight = 1.7f, textAlign = align)
    }
}

/** `.setup-connections`: the detected tools, plainly (the same /api/setup/detect result Harnesses reads). */
@Composable
internal fun ProviderConnections(model: SetupWizardModel, phone: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Column(
        modifier.setupCard(16).padding(if (phone) 24.dp else 32.dp),
    ) {
        Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SetupText(
                SetupWords.CONNECTIONS_TITLE,
                20f,
                Modifier.semantics { heading() },
                color = t.white,
                weight = 700,
                lineHeight = 1.3f,
            )
            SetupText(SetupWords.CONNECTIONS_LEAD, 13f)
        }
        for ((engine, meta) in SetupEngines.meta) {
            val found = model.detected[engine]?.found == true
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .drawTopLine(t.line),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    ProviderLogo(engine, color = t.ink, markSize = 22.dp)
                }
                SetupText(meta.label, 14f, Modifier.weight(1f), color = t.ink, weight = 600, lineHeight = 1.4f)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (found) Icon(TetherIcons.Check, contentDescription = null, tint = t.running, modifier = Modifier.size(14.dp))
                    SetupText(
                        if (found) "Detected" else if (model.detecting) "Checking…" else "Set up later",
                        12f,
                        color = if (found) t.running else t.muted,
                    )
                }
            }
        }
        SetupText(
            SetupWords.CONNECTIONS_FOOT,
            12f,
            Modifier.fillMaxWidth().padding(top = 16.dp).drawTopLine(t.line).padding(top = 20.dp),
            lineHeight = 1.7f,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Operator (page.tsx :469-510)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun StepOperator(model: SetupWizardModel, phone: Boolean) {
    val t = LocalTetherTokens.current
    val mismatch = model.confirmPassword.isNotEmpty() && model.password != model.confirmPassword
    StepColumn(phone) {
        StepTitle("Name the operator.", phone)
        SetupCopy(
            "Tether is a single-operator console — one account, every device. Choose how you sign in. " +
                "A signed-cookie secret is generated and stored for you.",
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Username")
            TetherInputWell(
                value = model.username,
                onValueChange = { model.username = it },
                placeholder = "operator",
                singleLine = true,
                style = setupInputStyle(),
                contentType = ContentType.Username,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Username" }.testTag(SetupTags.Username),
            )
        }
        if (model.forced("password")) {
            SetupNote(TetherIcons.Lock, "The password is set by the environment (TETHER_PASSWORD) and cannot be changed here.")
        } else {
            val visual = if (model.showPassword) VisualTransformation.None else PasswordVisualTransformation()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Password")
                Box(Modifier.fillMaxWidth()) {
                    TetherInputWell(
                        value = model.password,
                        onValueChange = { model.password = it },
                        singleLine = true,
                        style = setupInputStyle(),
                        visualTransformation = visual,
                        contentType = ContentType.NewPassword,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Password" }.testTag(SetupTags.Password),
                    )
                    // `.setup-reveal button`: 44dp, at the field's end; pressed when the password is shown.
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(44.dp)
                            .testTag(SetupTags.Reveal)
                            .toggleable(
                                value = model.showPassword,
                                role = Role.Switch,
                                onValueChange = { model.showPassword = it },
                            )
                            .semantics { contentDescription = if (model.showPassword) "Hide password" else "Show password" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (model.showPassword) TetherIcons.EyeOff else TetherIcons.Eye,
                            contentDescription = null,
                            tint = t.muted,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Confirm password")
                TetherInputWell(
                    value = model.confirmPassword,
                    onValueChange = { model.confirmPassword = it },
                    singleLine = true,
                    style = setupInputStyle(),
                    visualTransformation = visual,
                    contentType = ContentType.NewPassword,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Confirm password" }.testTag(SetupTags.Confirm),
                )
                FieldNote(if (mismatch) "Passwords do not match yet." else "", warning = mismatch)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Harnesses (page.tsx :512-697)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun StepHarnesses(model: SetupWizardModel, state: com.tether.app.client.SetupState, phone: Boolean) {
    val t = LocalTetherTokens.current
    val modesForced = model.forced("headlessModes")
    val available = state.supportedModes.filter { it != "fake" || state.dev }
    StepColumn(phone) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StepTitle("Choose your harnesses.", phone, Modifier.weight(1f))
            TetherKey(
                onClick = model::runDetect,
                classes = KeyClasses.ButtonSecondary,
                label = "Scan again",
                icon = if (model.detecting) TetherIcons.Loader else TetherIcons.RefreshCw,
                iconSize = 14.dp,
                fontSize = 12.sp,
                enabled = !model.detecting && !modesForced,
                modifier = Modifier.testTag(SetupTags.Rescan),
            )
        }
        SetupCopy(
            "Tether drives the agent CLIs already on this machine. Installs found in their usual " +
                "homes are ticked — tick another and Tether will look for it, or point it at the folder yourself.",
        )
        if (modesForced) {
            SetupNote(TetherIcons.Lock, "Harnesses are set by the environment: ${model.engines.joinToString(", ").ifEmpty { "none" }}")
        }
        Column(Modifier.fillMaxWidth().setupCard(12), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            available.forEachIndexed { index, engine ->
                HarnessItem(model, engine, first = index == 0)
            }
        }
        OptionToggle(
            checked = model.useBundled,
            onChange = { model.useBundled = it },
            title = "Use Tether’s bundled harnesses instead",
            detail = "Skip the host CLIs and run the versions Tether ships and manages.",
            modifier = Modifier.testTag(SetupTags.Bundled),
        )
        if (model.useBundled) {
            Row(
                Modifier.fillMaxWidth().background(t.attentionBg, RoundedCornerShape(10.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                SetupText(
                    "Bundled harnesses start factory-fresh. Nothing carries over from this machine — no " +
                        "settings, logins, MCP servers, skills or slash commands — and you will sign in to " +
                        "each agent again from inside Tether.",
                    13f,
                    Modifier.weight(1f),
                    color = t.ink,
                    lineHeight = 1.65f,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SetupText("Isolation", 13f, Modifier.semantics { heading() }, color = t.ink, weight = 600)
                ChoiceCard(
                    selected = model.isolation == Isolation.Open,
                    onSelect = { model.isolation = Isolation.Open },
                    title = "Run open",
                    detail = "Bundled agents see this machine like any process you run yourself.",
                    modifier = Modifier.testTag(SetupTags.IsolationOpen),
                )
                ChoiceCard(
                    selected = model.isolation == Isolation.Guided,
                    onSelect = { model.isolation = Isolation.Guided },
                    title = "Guided isolation",
                    detail = "After setup, Tether walks you through confining sessions to your workspace folder and enabling each engine’s sandbox — it applies every setting for you.",
                    modifier = Modifier.testTag(SetupTags.IsolationGuided),
                )
            }
        } else {
            SetupNote(
                null,
                "Your own CLIs keep their host access, logins and configuration — Tether inherits them as-is, so there is nothing to sandbox here.",
            )
        }
    }
}

@Composable
private fun HarnessItem(model: SetupWizardModel, engine: String, first: Boolean) {
    val t = LocalTetherTokens.current
    val meta = SetupEngines.meta[engine]
    val label = meta?.label ?: if (engine == "fake") "Fake (test engine)" else engine
    val modesForced = model.forced("headlessModes")
    val selected = engine in model.engines
    val det: EngineDetection? = model.detected[engine]
    val located = (model.locators[engine] ?: "").trim()
    val check = model.locatorChecks[engine]
    val needsLocation = selected && !model.useBundled && meta != null && det != null && !det.found
    // A detection whose source is "bundled" found no HOST install but Tether's own npm-installed copy
    // runs with zero configuration (issue #23): flagged honestly as BUNDLED, never DETECTED.
    val bundledFallback = det?.found == true && det.source == "bundled"
    val flag = if (model.useBundled) {
        if (selected) "BUNDLED" else ""
    } else if (det?.found == true) {
        if (bundledFallback) "BUNDLED" else "DETECTED"
    } else if (located.isNotEmpty()) {
        "MANUAL"
    } else if (det != null) {
        "NOT FOUND"
    } else {
        "SCANNING"
    }
    val detail: CharSequence = when {
        model.useBundled -> "Runs the version Tether ships."
        bundledFallback -> "No host install found — runs Tether’s bundled v${det?.version ?: "?"}."
        det?.found == true -> codeLabel("v${det.version ?: "?"} — ${det.binPath}")
        located.isNotEmpty() -> codeLabel(located)
        det != null -> "Not detected in the usual install folders."
        else -> meta?.blurb ?: ""
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.drawTopLine(t.line)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .testTag(SetupTags.engine(engine))
                .toggleable(
                    value = selected,
                    enabled = !modesForced,
                    role = Role.Checkbox,
                    onValueChange = { model.toggleEngine(engine) },
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SetupCheckGlyph(selected)
            Box(
                Modifier.size(34.dp).background(t.graphiteRaised, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ProviderLogo(engine, fallback = SetupEngines.glyph[engine] ?: "?", color = t.ink, markSize = 20.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SetupText(label, 14f, color = t.white, weight = 650, lineHeight = 1.4f)
                SetupText(detail, 12f, color = t.faint, lineHeight = 1.5f, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (flag.isNotEmpty()) {
                val tone = when {
                    det?.found == true && !model.useBundled -> t.running
                    flag == "NOT FOUND" -> t.warning
                    else -> t.muted
                }
                SetupText(
                    flag,
                    11f,
                    Modifier.background(t.mineral, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    color = tone,
                    weight = 650,
                    lineHeight = 1.2f,
                )
            }
        }
        if (needsLocation && meta != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .drawTopLine(t.line)
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SetupCopy(withCode("Browse to the folder that contains the ", meta.bin, " executable."))
                TetherKey(
                    onClick = { model.openPicker(PickerTarget.Locate(engine), model.state?.defaultFolder) },
                    classes = KeyClasses.ButtonSecondary,
                    label = "Browse folders",
                    icon = TetherIcons.FolderOpen,
                    iconSize = 15.dp,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag(SetupTags.locate(engine)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel("…or paste the folder path")
                    TetherInputWell(
                        value = model.locators[engine] ?: "",
                        onValueChange = { model.setLocator(engine, it) },
                        placeholder = "/absolute/path",
                        singleLine = true,
                        style = setupInputStyle(),
                        fontFamily = JetBrainsMono,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "…or paste the folder path" }.testTag(SetupTags.locator(engine)),
                    )
                    if (check != null) {
                        FieldNote(
                            check.message ?: if (check.ok) "Found ${meta.bin} here." else "No ${meta.bin} executable in that folder.",
                            warning = !check.ok,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Workspace (page.tsx :699-755)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun StepWorkspace(model: SetupWizardModel, state: com.tether.app.client.SetupState, phone: Boolean) {
    val t = LocalTetherTokens.current
    StepColumn(phone) {
        StepTitle("Point at your projects.", phone)
        if (model.forced("workspaceRoot")) {
            SetupNote(
                TetherIcons.Lock,
                withCode("The workspace folder is set by the environment (TETHER_WORKSPACE_ROOT): ", model.workspaceRoot.ifEmpty { state.defaultFolder }),
            )
            return@StepColumn
        }
        SetupCopy("Pick the folder your repositories live in. New sessions start here — you can always open anything else later.")
        if (state.workspaceCandidates.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Mounted project folders")
                Column(Modifier.fillMaxWidth().setupCard(12).padding(8.dp)) {
                    state.workspaceCandidates.forEach { candidate ->
                        PickRow(
                            TetherIcons.FolderOpen,
                            candidate,
                            pressed = model.workspaceRoot == candidate,
                            onClick = { model.pickWorkspace(candidate) },
                            modifier = Modifier.testTag(SetupTags.candidate(candidate)),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SetupText("Workspace", 12f, color = t.faint, lineHeight = 1.6f)
            Text(
                codeLabel(model.workspaceRoot.ifEmpty { state.defaultFolder }),
                color = t.ink,
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .background(t.mineralDeep, RoundedCornerShape(5.dp))
                    .border(1.dp, t.line, RoundedCornerShape(5.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        TetherKey(
            onClick = { model.openPicker(PickerTarget.Workspace, model.workspaceRoot.ifEmpty { state.defaultFolder }) },
            classes = KeyClasses.ButtonSecondary,
            label = "Browse folders",
            icon = TetherIcons.FolderOpen,
            iconSize = 15.dp,
            modifier = Modifier.testTag(SetupTags.Browse),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// GitHub and Claude accounts: ta-pqui (part 2). The web lets both be skipped (page.tsx :260-262).
// ---------------------------------------------------------------------------------------------

/**
 * The seam part 2 fills: its step replaces this body through [SetupWizardScreen]'s `githubStep` /
 * `claudeStep`. Until then the station keeps the web's title and its "Optional — skip…" line, and
 * Continue passes it, exactly as the web lets the operator skip it.
 */
@Composable
internal fun StepSeam(title: String, footnote: String, tag: String, phone: Boolean) {
    StepColumn(phone, Modifier.testTag(tag)) {
        StepTitle(title, phone)
        SetupFootnote(footnote)
    }
}

// ---------------------------------------------------------------------------------------------
// Review (page.tsx :1220-1282)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun StepReview(model: SetupWizardModel, state: com.tether.app.client.SetupState, phone: Boolean) {
    val t = LocalTetherTokens.current
    StepColumn(phone) {
        StepTitle("Review and switch on.", phone)
        SetupCopy(
            "Nothing has been written yet. Apply to save this configuration and start your console" +
                (if (state.runtime == "container") " — the server restarts itself" else " — you will restart Tether by hand") + ".",
        )
        Column(Modifier.fillMaxWidth().testTag(SetupTags.Plate)) {
            PlateRow("Operator", phone, first = true) {
                SetupText(model.username.trim().ifEmpty { "—" }, 14f, color = t.ink, lineHeight = 1.6f)
            }
            PlateRow("Password", phone) {
                SetupText(if (model.forced("password")) "set by environment" else "••••••••", 14f, color = t.ink, lineHeight = 1.6f)
            }
            PlateRow("Harnesses", phone) {
                if (model.engines.isEmpty()) {
                    SetupText("none", 14f, color = t.ink, lineHeight = 1.6f)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        model.engines.forEach { engine ->
                            val det = model.detected[engine]
                            val located = (model.locators[engine] ?: "").trim()
                            val source = when {
                                model.useBundled -> "bundled"
                                det?.found == true -> if (det.source == "bundled") "v${det.version ?: "?"} · bundled" else "v${det.version ?: "?"} · detected"
                                located.isNotEmpty() -> "located manually"
                                else -> "—"
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SetupText(SetupEngines.meta[engine]?.label ?: engine, 14f, color = t.ink, lineHeight = 1.6f)
                                SetupText(source, 12f, color = t.faint, lineHeight = 1.8f)
                            }
                        }
                    }
                }
            }
            PlateRow("Environment", phone) {
                SetupText(
                    if (model.useBundled) "Bundled — factory-fresh, nothing inherited from this machine"
                    else "Your CLIs — host settings, logins and access inherited as-is",
                    14f,
                    color = t.ink,
                    lineHeight = 1.6f,
                )
            }
            if (model.useBundled) {
                PlateRow("Isolation", phone) {
                    SetupText(
                        if (model.isolation == Isolation.Guided) "Guided — Tether configures it with you after setup" else "Run open",
                        14f,
                        color = t.ink,
                        lineHeight = 1.6f,
                    )
                }
            }
            PlateRow("Workspace", phone) {
                Text(codeLabel(model.workspaceRoot), color = t.ink, fontFamily = JetBrainsMono, fontSize = 12.sp)
            }
            PlateRow("Runtime", phone) {
                SetupText(
                    state.runtime + if (state.runtime == "container") " · restarts automatically" else " · manual restart",
                    14f,
                    color = t.ink,
                    lineHeight = 1.6f,
                )
            }
        }
    }
}

/** `.setup-plate > div`: a label column (116dp; stacked on a phone) and its value, parted by a line. */
@Composable
private fun PlateRow(label: String, phone: Boolean, first: Boolean = false, value: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val modifier = Modifier
        .fillMaxWidth()
        .then(if (first) Modifier else Modifier.drawTopLine(t.line))
        .padding(vertical = if (phone) 16.dp else 18.dp)
    if (phone) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SetupText(label, 13f, color = t.muted, lineHeight = 1.4f)
            value()
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SetupText(label, 13f, Modifier.width(116.dp), color = t.muted, lineHeight = 1.6f)
            Box(Modifier.weight(1f)) { value() }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Configured (page.tsx :272-302)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun SetupDone(model: SetupWizardModel, finish: com.tether.app.client.SetupFinish, phone: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.Start) {
        Box(
            Modifier.size(60.dp).background(t.violetWash, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TetherIcons.Check, contentDescription = null, tint = t.violetStrong, modifier = Modifier.size(26.dp))
        }
        Text(
            "Configured.",
            modifier = Modifier.semantics { heading() },
            style = androidx.compose.ui.text.TextStyle(
                color = t.white,
                fontFamily = com.tether.app.ui.theme.Manrope,
                fontWeight = androidx.compose.ui.text.font.FontWeight(560),
                fontSize = (if (phone) 36f else 42f).sp,
                lineHeight = (if (phone) 40f else 46f).sp,
            ),
        )
        if (finish.automatic) {
            SetupText("Tether is restarting into your console.", 16f, lineHeight = 1.8f)
            Row(
                Modifier.testTag(SetupTags.Waiting).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.tether.app.ui.components.SpinningIcon(TetherIcons.Loader, t.violet, 16.dp)
                SetupText("Waiting for the server to come back…", 13f, color = t.violet)
            }
        } else {
            SetupText("Restart Tether to boot your console, then sign in.", 16f, lineHeight = 1.8f)
            // issue #28: the actual service-manager command, not the native hint, for a systemd deployment.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(t.mineralDeep, RoundedCornerShape(8.dp))
                    .border(1.dp, t.lineStrong, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(16.dp)
                    .testTag(SetupTags.Command),
            ) {
                Text(
                    if (finish.runtime == "systemd") "systemctl --user restart tether.service" else "npm start",
                    color = t.white,
                    fontFamily = JetBrainsMono,
                    fontSize = 13.sp,
                    softWrap = false,
                )
            }
            TetherKey(
                onClick = model::goToSignIn,
                classes = KeyClasses.ButtonPrimary,
                label = "Go to sign-in",
                modifier = Modifier.testTag(SetupTags.SignIn),
            )
        }
    }
}

/** A step's column: the page's `.setup-step` (620dp wide at most, 22dp apart; 20dp on a phone). */
@Composable
internal fun StepColumn(phone: Boolean, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().widthIn(max = (620f).dp),
        verticalArrangement = Arrangement.spacedBy(if (phone) 20.dp else 22.dp),
        content = content,
    )
}

/** A top rule of [color] (`border-top: 1px solid var(--line)`). */
internal fun Modifier.drawTopLine(color: androidx.compose.ui.graphics.Color): Modifier = this.then(
    Modifier.drawBehind { drawRect(color, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) },
)
