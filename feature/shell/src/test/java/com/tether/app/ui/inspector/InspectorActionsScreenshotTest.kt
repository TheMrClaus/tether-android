package com.tether.app.ui.inspector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.tether.app.client.ChangeRequestReading
import com.tether.app.client.WorktreeLogsReading
import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.ui.inspector.InspectorBoards.obj
import com.tether.app.ui.statusline.screenshots.ScreenSize
import com.tether.app.ui.statusline.screenshots.snapBoard
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.14: the Repository and Services actions (repository-panel.tsx:53-61,
 * worktree-services-card.tsx:101-163): the pull request link and its refresh key; a running, a
 * starting and a stopped script with their keys; `inspector-actions-log` with "Output of dev" open
 * on its reply, `inspector-actions-log-empty` with "Output of test" open before any ("No output
 * yet."). T8.5 `inspector-actions-drafts`: the same with the server's metadata generation on, the
 * "Draft commit message" / "Draft pull request" keys under the branch (repository-panel.tsx:40-51).
 * Both Studio skins, phone and tablet.
 */
enum class InspectorActionsShot(val id: String, val open: String?, val drafts: Boolean = false) {
    Keys("inspector-actions", null),
    Log("inspector-actions-log", "dev"),
    LogEmpty("inspector-actions-log-empty", "test"),
    Drafts("inspector-actions-drafts", null, drafts = true),
}

private fun actionsModel(drafts: Boolean): InspectorModel = InspectorBoards.model(
    InspectorBoards.session(worktree = WorktreeInfo(path = "/w", branch = "feat/inspector-actions", status = "active", mode = "branch-off")),
    replies = InspectorReplies(
        worktreeScripts = obj(
            """{"sessionId":"s1","setupStatus":"ok","setupLog":[],"configWarnings":[],"scripts":[
               {"name":"dev","type":"service","command":"npm run dev","status":"running","port":5173,"proxyHost":null,"proxyAuthUrl":null,"proxyUnavailable":"not-configured"},
               {"name":"lint","type":"script","command":"npm run lint","status":"starting"},
               {"name":"test","type":"script","command":"npm test","status":"failed","exitCode":1,"error":"1 test failed"}]}""",
        ),
        changeRequest = ChangeRequestReading(
            obj("""{"number":12,"url":"https://example.test/pr/12","state":"OPEN","isDraft":false,"reviewDecision":"APPROVED","mergeable":"MERGEABLE"}"""),
            unknown = false,
        ),
        worktreeLogs = WorktreeLogsReading(
            "dev",
            listOf("> app@0.1.0 dev", "> vite", "", "  VITE v6.0.0  ready in 412 ms", "", "  ➜  Local:   http://localhost:5173/") +
                (1..24).map { "12:00:${"%02d".format(it)} [vite] hmr update /src/App.tsx" },
            0,
        ),
    ),
    metadataGenerationEnabled = drafts,
)

internal fun ComposeContentTestRule.snapInspectorActions(shot: InspectorActionsShot, skin: TetherSkin, size: ScreenSize) {
    val tablet = size == ScreenSize.Tablet
    snapBoard(
        shot.id,
        skin,
        size,
        reducedMotion = true,
        beforeCapture = {
            shot.open?.let {
                onNodeWithContentDescription("Output of $it", useUnmergedTree = true).performClick()
                waitForIdle()
            }
        },
    ) {
        Column(Modifier.width(if (tablet) 368.dp else 390.dp)) {
            Inspector(actionsModel(shot.drafts), null, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env })
        }
    }
}

private fun actionsParams(): List<Array<Any>> = InspectorActionsShot.entries.flatMap { s ->
    listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(s, it) }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h1400dp-420dpi")
class InspectorActionsPhoneScreenshotTest(private val shot: InspectorActionsShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun actions() = rule.snapInspectorActions(shot, skin, ScreenSize.Phone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = actionsParams()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h1400dp-mdpi")
class InspectorActionsTabletScreenshotTest(private val shot: InspectorActionsShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun actions() = rule.snapInspectorActions(shot, skin, ScreenSize.Tablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = actionsParams()
    }
}
