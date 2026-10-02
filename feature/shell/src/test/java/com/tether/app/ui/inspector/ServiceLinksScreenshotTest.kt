package com.tether.app.ui.inspector

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tether.app.client.ServiceOpenSource
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
 * ta-coik.2: a running service's links in the inspector, Studio light and dark.
 * `inspector-service-links`: a loopback console, so both "Open" and "On this machine"
 * (worktree-services-card.tsx:141-154); `inspector-service-links-refused`: "Open" tapped and the
 * console refused, its reason under the links.
 */
enum class ServiceLinksShot(val id: String) { Both("inspector-service-links"), Refused("inspector-service-links-refused") }

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h1400dp-420dpi")
class ServiceLinksScreenshotTest(private val shot: ServiceLinksShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    private val model: InspectorModel = InspectorBoards.model(
        InspectorBoards.session(worktree = WorktreeInfo(path = "/w", branch = "feat", status = "active")),
        replies = InspectorReplies(
            worktreeScripts = obj(
                """{"sessionId":"s1","setupStatus":"ok","setupLog":[],"configWarnings":[],"scripts":[
                   {"name":"web","type":"service","command":"npm run dev","status":"running","port":5173,
                    "proxyHost":"web--feat.svc.example.test","proxyUrl":"https://web--feat.svc.example.test",
                    "proxyPath":"/services/~0abc.AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde/s1/web/",
                    "proxyAuthUrl":"/api/worktree/open?session=s1&script=web","proxyUnavailable":null}]}""",
            ),
        ),
    )

    private val refusing = object : ServiceOpenSource {
        override suspend fun open(link: String, serviceHost: String) = ServiceOpenSource.Outcome.Refused("That service has no proxied address.")
    }

    @Test fun links() = rule.snapBoard(
        shot.id,
        skin,
        ScreenSize.Phone,
        reducedMotion = true,
        beforeCapture = {
            if (shot == ServiceLinksShot.Refused) {
                rule.onNodeWithTag(InspectorTags.ServiceOpen).performClick()
                rule.mainClock.advanceTimeBy(100)
                rule.waitForIdle()
            }
        },
    ) {
        Inspector(model, null, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env }, serviceOpener = { _, _, _ -> }, serviceOpen = refusing)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ServiceLinksShot.entries.flatMap { s ->
            listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(s, it) }
        }
    }
}
