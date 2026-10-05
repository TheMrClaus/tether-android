package com.tether.app.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tether.app.client.BrowseStatus
import com.tether.app.client.SetupApi
import com.tether.app.client.SetupCopy as SetupServerCopy
import com.tether.app.client.TetherClient
import com.tether.app.ui.FolderPickerDialog
import com.tether.app.ui.components.BrandMark
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope

/*
 * T10.6 (ta-jwbs): the first-run setup wizard, app/setup/page.tsx (tether 90fbb9f), dressed by
 * app/studio.css 863-1070. Welcome, then the console: a rail of the seven stations and one step at a time
 * with Back / Continue (Apply & start Tether on the last), then Configured and the restart step.
 * Tablet: the page's two-column Welcome and its console with the rail beside the stage. Phone: one step
 * per screen, the rail a strip of stations across the top (their labels are hidden, studio.css 1042).
 */

/** The wizard for [baseUrl], holding its state across rotation in memory (nothing typed is written to disk). */
class SetupWizardViewModel(api: SetupApi) : ViewModel() {
    val model = SetupWizardModel(api, viewModelScope)
}

/**
 * The entry the host opens when [baseUrl]'s /healthz says `setupRequired: true`. [onSignIn] goes on to
 * sign-in for that server (the wizard is done, or setup was already done: a 401); [onCancel] leaves the
 * wizard unfinished (system Back on Welcome).
 */
@Composable
fun SetupWizard(
    client: TetherClient,
    baseUrl: String,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    githubStep: (@Composable () -> Unit)? = null,
    claudeStep: (@Composable () -> Unit)? = null,
) {
    val holder = viewModel<SetupWizardViewModel>(key = "setup:$baseUrl", factory = SetupWizardFactory { client.setupApi(baseUrl) })
    val model = holder.model
    // Leaving the wizard forgets what was typed (the password first); a rotation does not leave it.
    SideEffect {
        model.onSignIn = {
            model.reset()
            onSignIn()
        }
    }
    LaunchedEffect(model) { if (model.state == null) model.load() }
    BackHandler {
        if (model.finish == null && model.step > 0) {
            model.back()
        } else if (model.finish == null) {
            model.reset()
            onCancel()
        }
    }
    SetupWizardScreen(model, modifier, githubStep, claudeStep)
}

private class SetupWizardFactory(private val api: () -> SetupApi?) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SetupWizardViewModel(api() ?: UnreachableSetupApi) as T
}

/** No client for the address (never in practice: the host checked it): every call says the service did not answer. */
private object UnreachableSetupApi : SetupApi {
    private fun <T> down(): com.tether.app.client.SetupCall<T> = com.tether.app.client.SetupCall.Failed(null, SetupServerCopy.STATE_FAILED)
    override suspend fun state() = down<com.tether.app.client.SetupState>()
    override suspend fun detect() = down<Map<String, com.tether.app.client.EngineDetection>>()
    override suspend fun browse(path: String?) = down<com.tether.app.protocol.model.DirectoryListing>()
    override suspend fun validateEngineBinary(engine: String, value: String) = down<com.tether.app.client.BinaryCheck>()
    override suspend fun complete(settings: kotlinx.serialization.json.JsonObject) = down<com.tether.app.client.SetupFinish>()
    override suspend fun configured() = false
}

@Composable
fun SetupWizardScreen(
    model: SetupWizardModel,
    modifier: Modifier = Modifier,
    githubStep: (@Composable () -> Unit)? = null,
    claudeStep: (@Composable () -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    val finish = model.finish
    // page.tsx :222: once the server is restarting by itself, watch for it to come back.
    LaunchedEffect(finish) { if (finish != null) model.pollRestart() }

    Box(
        modifier
            .fillMaxSize()
            .background(t.mineral)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        val top = if (phone) 100.dp else 112.dp
        val side = if (phone) 20.dp else 40.dp
        val scroll = rememberScrollState()
        // studio.css 863 / 1031: the shell pads 112/48 (100/32 on a phone); a window too short for it scrolls.
        // The wordmark is absolute inside the scrolling shell (studio.css 864 / 1032; globals.css 2059-2063),
        // so it scrolls away with the page: the first thing in the scrolled content, over the padded column.
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = side, end = side, top = top, bottom = if (phone) 32.dp else 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    finish != null -> SetupDone(model, finish, phone, Modifier.widthIn(max = 560.dp).fillMaxWidth())
                    model.step == 0 -> Welcome(model, phone)
                    else -> Console(model, phone, githubStep, claudeStep)
                }
            }
            // `.setup-shell .login-wordmark`: top 36 / left 40 (30 / 24 on a phone).
            Wordmark(Modifier.align(Alignment.TopStart).padding(top = if (phone) 30.dp else 36.dp, start = if (phone) 24.dp else 40.dp))
        }
    }
    val target = model.picker
    if (target != null) {
        val current = when (target) {
            PickerTarget.Workspace -> model.workspaceRoot.ifEmpty { model.state?.defaultFolder.orEmpty() }
            is PickerTarget.Locate -> model.state?.defaultFolder.orEmpty()
        }
        FolderPickerDialog(
            directories = model.pickerListing,
            current = current,
            onDismiss = model::closePicker,
            onBrowse = model::browse,
            onChoose = model::choose,
            browseStatus = model.pickerError?.let { BrowseStatus("setup", current, BrowseStatus.Phase.Error) },
            loadErrorText = model.pickerError ?: SetupServerCopy.BROWSE_FAILED,
        )
    }
}

/** `.setup-shell .login-wordmark`: the brand mark and "Tether", 20sp. */
@Composable
internal fun Wordmark(modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Tether" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark()
        Text(
            "Tether",
            modifier = Modifier.clearAndSetSemantics { },
            style = TextStyle(color = t.white, fontFamily = Manrope, fontWeight = FontWeight(750), fontSize = 20.sp, letterSpacing = (-0.03).em),
        )
    }
}

@Composable
private fun Welcome(model: SetupWizardModel, phone: Boolean) {
    if (phone) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(36.dp)) {
            // globals.css 2347-2348: the tools card first, the copy (centred) below it.
            ProviderConnections(model, phone = true, Modifier.fillMaxWidth())
            WelcomeCopy(model, phone = true, Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.widthIn(max = 1080.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(80.dp), verticalAlignment = Alignment.CenterVertically) {
            WelcomeCopy(model, phone = false, Modifier.weight(1.1f))
            ProviderConnections(model, phone = false, Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The console: rail, stage, footer (page.tsx :332-415)
// ---------------------------------------------------------------------------------------------

@Composable
private fun Console(model: SetupWizardModel, phone: Boolean, githubStep: (@Composable () -> Unit)?, claudeStep: (@Composable () -> Unit)?) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(if (phone) 14.dp else 16.dp)
    val frame = Modifier
        .widthIn(max = 1040.dp)
        .fillMaxWidth()
        .clip(shape)
        .cssSurface(shape, t.graphite, CssBorder(1.dp, t.line))
    if (phone) {
        Column(frame) {
            Rail(model, phone = true)
            Stage(model, phone = true, githubStep, claudeStep, Modifier.fillMaxWidth())
        }
    } else {
        val maxHeight = (LocalConfiguration.current.screenHeightDp - 160).dp
        val railWidth = 224.dp
        Row(
            frame
                .heightIn(max = maxHeight)
                // The rail's edge runs the console's full height, whatever the stage holds.
                .drawBehind { drawRect(t.line, topLeft = Offset(railWidth.toPx() - 1.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height)) },
        ) {
            Rail(model, phone = false)
            Stage(model, phone = false, githubStep, claudeStep, Modifier.weight(1f))
        }
    }
}

/** `.setup-rail`: the stations, done ones ticked, the active one violet; the phone's strip hides the labels. */
@Composable
private fun Rail(model: SetupWizardModel, phone: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val steps = SetupStep.entries
    if (phone) {
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(t.line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) }
                .padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEachIndexed { index, step -> Station(index, step, model.step, label = false) }
        }
    } else {
        Column(modifier.widthIn(min = 224.dp, max = 224.dp).padding(horizontal = 20.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            steps.forEachIndexed { index, step ->
                val active = index == model.step
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) t.violetWash else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Station(index, step, model.step, label = true)
                }
            }
            SetupText(SetupWords.RAIL_NOTE, 11f, Modifier.padding(start = 8.dp, end = 8.dp, top = 24.dp), color = t.faint, lineHeight = 1.6f)
        }
    }
}

/** One station: its number (a tick once done) and, beside the stage, its label. */
@Composable
private fun Station(index: Int, step: SetupStep, current: Int, label: Boolean) {
    val t = LocalTetherTokens.current
    val active = index == current
    val done = index < current
    val face = when {
        active -> t.violetStrong
        done -> t.mineral
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Step ${index + 1} of ${SetupStep.entries.size}: ${step.title}"
            selected = active
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .cssSurface(CircleShape, face, if (active) null else CssBorder(1.dp, t.line)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(TetherIcons.Check, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            } else {
                Text(
                    "${index + 1}",
                    style = TextStyle(
                        color = if (active) androidx.compose.ui.graphics.Color.White else t.faint,
                        fontFamily = Manrope,
                        fontWeight = FontWeight(600),
                        fontSize = 12.sp,
                    ),
                )
            }
        }
        if (label) {
            SetupText(
                step.title,
                13f,
                color = if (active) t.white else if (done) t.ink else t.faint,
                weight = if (active) 680 else 600,
                lineHeight = 1.4f,
            )
        }
    }
}

/** `.setup-stage`: the step's body (scrolls inside the tablet's console) and the footer. */
@Composable
private fun Stage(model: SetupWizardModel, phone: Boolean, githubStep: (@Composable () -> Unit)?, claudeStep: (@Composable () -> Unit)?, modifier: Modifier) {
    val t = LocalTetherTokens.current
    Column(modifier.background(t.graphite)) {
        val bodyScroll = rememberScrollState()
        Column(
            Modifier
                // A phone's page scrolls as a whole (unbounded height: no weight); the tablet's body scrolls inside the console.
                .then(if (phone) Modifier else Modifier.weight(1f, fill = false).verticalScroll(bodyScroll))
                .heightIn(min = if (phone) 0.dp else 420.dp)
                .padding(if (phone) androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 28.dp) else androidx.compose.foundation.layout.PaddingValues(36.dp)),
        ) {
            val state = model.state
            when (SetupStep.entries[model.step]) {
                SetupStep.Welcome -> Unit
                SetupStep.Operator -> StepOperator(model, phone)
                SetupStep.Harnesses -> if (state != null) StepHarnesses(model, state, phone)
                SetupStep.Workspace -> if (state != null) StepWorkspace(model, state, phone)
                SetupStep.GitHub -> if (state != null) {
                    if (githubStep != null) githubStep() else StepSeam("Connect GitHub.", SetupWords.GITHUB_FOOT, SetupTags.GitHubSeam, phone)
                }
                SetupStep.ClaudeAccounts -> if (state != null) {
                    if (claudeStep != null) claudeStep() else StepSeam("Add a Claude account.", SetupWords.CLAUDE_FOOT, SetupTags.ClaudeSeam, phone)
                }
                SetupStep.Review -> if (state != null) StepReview(model, state, phone)
            }
        }
        Footer(model, phone)
    }
}

/** `.setup-footer`: Back, the error line, and Continue (Apply & start Tether on Review). */
@Composable
private fun Footer(model: SetupWizardModel, phone: Boolean) {
    val t = LocalTetherTokens.current
    val last = model.step == SetupStep.entries.lastIndex
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(t.line, size = Size(size.width, 1.dp.toPx())) }
            .padding(horizontal = if (phone) 20.dp else 28.dp, vertical = if (phone) 18.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Phone: the error line first, across the footer (studio.css 1050).
        if (phone && model.error.isNotEmpty()) ErrorLine(model.error, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TetherKey(
                onClick = model::back,
                classes = KeyClasses.ButtonSecondary,
                label = SetupWords.BACK,
                icon = TetherIcons.ArrowLeft,
                iconSize = 16.dp,
                enabled = !model.busy,
                minHeight = 46.dp,
                modifier = Modifier.testTag(SetupTags.Back),
            )
            if (phone) {
                Box(Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (model.error.isNotEmpty()) ErrorLine(model.error, Modifier)
                }
            }
            if (!last) {
                TetherKey(
                    onClick = model::next,
                    classes = KeyClasses.ButtonPrimary,
                    label = SetupWords.CONTINUE,
                    enabled = model.canAdvance && !model.busy,
                    minHeight = 46.dp,
                    trailing = { Icon(TetherIcons.ArrowRight, contentDescription = null, tint = primaryKeyInk(enabled = model.canAdvance && !model.busy), modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag(SetupTags.Continue),
                )
            } else {
                TetherKey(
                    onClick = model::apply,
                    classes = KeyClasses.ButtonPrimary,
                    label = if (model.busy) SetupWords.APPLYING else SetupWords.APPLY,
                    icon = if (model.busy) TetherIcons.Loader else null,
                    iconSize = 16.dp,
                    enabled = !model.busy,
                    minHeight = 46.dp,
                    trailing = if (model.busy) null else { { Icon(TetherIcons.Check, contentDescription = null, tint = primaryKeyInk(enabled = true), modifier = Modifier.size(16.dp)) } },
                    modifier = Modifier.testTag(SetupTags.Apply),
                )
            }
        }
    }
}

@Composable
private fun ErrorLine(text: String, modifier: Modifier) {
    val t = LocalTetherTokens.current
    SetupText(
        text,
        12f,
        modifier.testTag(SetupTags.Error).semantics { liveRegion = LiveRegionMode.Assertive },
        color = t.danger,
        weight = 600,
        lineHeight = 1.5f,
    )
}
