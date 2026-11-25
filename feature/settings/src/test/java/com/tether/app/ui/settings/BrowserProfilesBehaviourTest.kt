package com.tether.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.getOrNull
import com.tether.app.client.BROWSER_PROFILES_MIN_PROTOCOL
import com.tether.app.client.BrowserProfilesReply
import com.tether.app.client.BrowserProfilesRequest
import com.tether.app.protocol.BrowserProfileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A server's profile list as a scripted source: it keeps the list, records each request, and refuses what the script says. */
class FakeBrowserProfiles(initial: List<BrowserProfileInfo> = emptyList()) : BrowserProfilesSource {
    val requests = java.util.concurrent.CopyOnWriteArrayList<BrowserProfilesRequest>()
    @Volatile var list: List<BrowserProfileInfo> = initial
    /** The reply a request gets instead of the working one (the list still rides along, as the server sends it). */
    @Volatile var refuse: ((BrowserProfilesRequest) -> BrowserProfilesReply?)? = null

    override suspend fun request(request: BrowserProfilesRequest): BrowserProfilesReply {
        requests += request
        refuse?.invoke(request)?.let { return it }
        when (request) {
            BrowserProfilesRequest.List -> Unit
            is BrowserProfilesRequest.Create -> list = list + BrowserProfileInfo("id-${request.name}", request.name)
            is BrowserProfilesRequest.Rename -> list = list.map { if (it.id == request.profileId) it.copy(name = request.name) else it }
            is BrowserProfilesRequest.Delete -> list = list.filter { it.id != request.profileId }
        }
        return BrowserProfilesReply(true, profiles = list)
    }
}

/** Settings > Browser > Profiles against the web's block: the gate, the empty state, create, rename, delete with a confirm, every refusal in the server's words. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserProfilesBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Advanced)
    private val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })

    private val work = BrowserProfileInfo("p1", "Work", heldBy = "Fix login")
    private val home = BrowserProfileInfo("p2", "Personal")

    private fun show(source: FakeBrowserProfiles, protocol: Int? = BROWSER_PROFILES_MIN_PROTOCOL) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val runtime = remember { BrowserRuntimeController(FakeBrowser(), ServerFixtures.ORIGIN, scope, seed = BrowserSeed(status = BrowserFixtures.FOUND)) }
            val profiles = remember { BrowserProfilesController(source, scope) }
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                SettingsUnderTest(store.prefs, state, serverSettings = ServerFixtures.binding(serverProtocol = protocol), browser = BrowserBinding(runtime, profiles))
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun settled(until: () -> Boolean) { compose.waitUntil(5_000) { until() }; compose.waitForIdle() }
    /** The text drawn under the node tagged [t] (a column's own lines), in order. */
    private fun texts(t: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.forEach { out += it.text }
            n.children.forEach(::walk)
        }
        walk(tag(t).fetchSemanticsNode())
        return out
    }
    private fun press(t: String) = tag(t).performScrollTo().performClick()

    @Test fun aServerBelow171IsNeverOfferedProfilesAndNothingIsSent() {
        val source = FakeBrowserProfiles(listOf(work))
        show(source, protocol = BROWSER_PROFILES_MIN_PROTOCOL - 1)
        assertTrue(!exists(BrowserProfilesTags.Block))
        assertEquals(emptyList<BrowserProfilesRequest>(), source.requests.toList())
    }

    @Test fun aServerAt171ReadsTheListOnceWhenShown() {
        val source = FakeBrowserProfiles(listOf(work, home))
        show(source)
        settled { exists(BrowserProfilesTags.row("p1")) }
        assertEquals(listOf<BrowserProfilesRequest>(BrowserProfilesRequest.List), source.requests.toList())
        assertEquals(listOf("Work", "In use by Fix login"), texts(BrowserProfilesTags.use("p1")))
        assertEquals(listOf("Personal", "Not in use"), texts(BrowserProfilesTags.use("p2")))
    }

    @Test fun noProfilesShowsTheEmptySentence() {
        show(FakeBrowserProfiles())
        settled { exists(BrowserProfilesTags.Empty) }
        assertEquals(listOf(BrowserProfilesCopy.EMPTY), texts(BrowserProfilesTags.Empty))
    }

    @Test fun createSendsTheTrimmedNameAddsTheRowAndClearsTheField() {
        val source = FakeBrowserProfiles()
        show(source)
        settled { exists(BrowserProfilesTags.Empty) }
        tag(BrowserProfilesTags.CreateField).performScrollTo().performTextInput("  Work  ")
        press(BrowserProfilesTags.CreateKey)
        settled { exists(BrowserProfilesTags.row("id-Work")) }
        assertEquals(BrowserProfilesRequest.Create("Work"), source.requests.last())
        tag(BrowserProfilesTags.CreateField).assertTextEquals("")
        assertTrue(!exists(BrowserProfilesTags.Empty))
    }

    @Test fun aBlankNameIsAskedForAndNothingIsSent() {
        val source = FakeBrowserProfiles()
        show(source)
        settled { exists(BrowserProfilesTags.Empty) }
        press(BrowserProfilesTags.CreateKey)
        settled { exists(BrowserProfilesTags.CreateError) }
        tag(BrowserProfilesTags.CreateError).assertTextEquals("Give the profile a name.")
        assertEquals(listOf<BrowserProfilesRequest>(BrowserProfilesRequest.List), source.requests.toList())
    }

    @Test fun aRefusedCreateShowsTheServersOwnSentenceAndKeepsTheName() {
        val source = FakeBrowserProfiles(listOf(work))
        source.refuse = { if (it is BrowserProfilesRequest.Create) BrowserProfilesReply(false, "name_taken", "A profile named Work already exists.", listOf(work)) else null }
        show(source)
        settled { exists(BrowserProfilesTags.row("p1")) }
        tag(BrowserProfilesTags.CreateField).performScrollTo().performTextInput("Work")
        press(BrowserProfilesTags.CreateKey)
        settled { exists(BrowserProfilesTags.CreateError) }
        tag(BrowserProfilesTags.CreateError).assertTextEquals("A profile named Work already exists.")
        tag(BrowserProfilesTags.CreateField).assertTextEquals("Work")
    }

    @Test fun aRefusalWithoutASentenceFallsBackByCode() {
        val source = FakeBrowserProfiles(listOf(work))
        source.refuse = { if (it is BrowserProfilesRequest.Create) BrowserProfilesReply(false, "grade_refused", null, emptyList()) else null }
        show(source)
        settled { exists(BrowserProfilesTags.row("p1")) }
        tag(BrowserProfilesTags.CreateField).performScrollTo().performTextInput("X")
        press(BrowserProfilesTags.CreateKey)
        settled { exists(BrowserProfilesTags.CreateError) }
        tag(BrowserProfilesTags.CreateError).assertTextEquals("Only the owner can change profiles.")
        assertTrue("an empty list on a refusal leaves the shown list alone", exists(BrowserProfilesTags.row("p1")))
    }

    @Test fun renameOpensAFieldWithTheNameAndSavesTheNewOne() {
        val source = FakeBrowserProfiles(listOf(home))
        show(source)
        settled { exists(BrowserProfilesTags.row("p2")) }
        press(BrowserProfilesTags.rename("p2"))
        tag(BrowserProfilesTags.renameField("p2")).assertTextEquals("Personal")
        tag(BrowserProfilesTags.renameField("p2")).performTextClearance()
        tag(BrowserProfilesTags.renameField("p2")).performTextInput("Banking")
        press(BrowserProfilesTags.save("p2"))
        settled { !exists(BrowserProfilesTags.renameField("p2")) }
        assertEquals(BrowserProfilesRequest.Rename("p2", "Banking"), source.requests.last())
        assertEquals(listOf("Banking", "Not in use"), texts(BrowserProfilesTags.use("p2")))
    }

    @Test fun cancelLeavesTheNameAndSendsNothing() {
        val source = FakeBrowserProfiles(listOf(home))
        show(source)
        settled { exists(BrowserProfilesTags.row("p2")) }
        press(BrowserProfilesTags.rename("p2"))
        press(BrowserProfilesTags.cancel("p2"))
        settled { !exists(BrowserProfilesTags.renameField("p2")) }
        assertEquals(listOf<BrowserProfilesRequest>(BrowserProfilesRequest.List), source.requests.toList())
    }

    @Test fun deleteAsksFirstAndKeepItSendsNothing() {
        val source = FakeBrowserProfiles(listOf(home))
        show(source)
        settled { exists(BrowserProfilesTags.row("p2")) }
        press(BrowserProfilesTags.delete("p2"))
        assertTrue(exists(BrowserProfilesTags.confirm("p2")))
        assertEquals(listOf<BrowserProfilesRequest>(BrowserProfilesRequest.List), source.requests.toList())
        press(BrowserProfilesTags.keep("p2"))
        settled { !exists(BrowserProfilesTags.confirm("p2")) }
        assertEquals(listOf<BrowserProfilesRequest>(BrowserProfilesRequest.List), source.requests.toList())
    }

    @Test fun confirmedDeleteSendsTheIdAndRemovesTheRow() {
        val source = FakeBrowserProfiles(listOf(home))
        show(source)
        settled { exists(BrowserProfilesTags.row("p2")) }
        press(BrowserProfilesTags.delete("p2"))
        press(BrowserProfilesTags.confirmDelete("p2"))
        settled { !exists(BrowserProfilesTags.row("p2")) }
        assertEquals(BrowserProfilesRequest.Delete("p2"), source.requests.last())
        assertTrue(exists(BrowserProfilesTags.Empty))
    }

    @Test fun aProfileInUseRefusesTheDeleteInTheServersWords() {
        val source = FakeBrowserProfiles(listOf(work))
        source.refuse = { if (it is BrowserProfilesRequest.Delete) BrowserProfilesReply(false, "PROFILE_IN_USE", "Work is in use by Fix login.", listOf(work)) else null }
        show(source)
        settled { exists(BrowserProfilesTags.row("p1")) }
        press(BrowserProfilesTags.delete("p1"))
        press(BrowserProfilesTags.confirmDelete("p1"))
        settled { exists(BrowserProfilesTags.error("p1")) }
        tag(BrowserProfilesTags.error("p1")).assertTextEquals("Work is in use by Fix login.")
        assertTrue(exists(BrowserProfilesTags.row("p1")))
    }

    @Test fun aFailedReadShowsItsSentenceAndTryAgainReadsAgain() {
        val source = FakeBrowserProfiles(listOf(home))
        var fail = true
        source.refuse = { if (fail) BrowserProfilesReply.Unavailable else null }
        show(source)
        settled { exists(BrowserProfilesTags.LoadError) }
        assertEquals(listOf(BrowserProfilesReply.UNAVAILABLE_TEXT), texts(BrowserProfilesTags.LoadError))
        fail = false
        press(BrowserProfilesTags.Retry)
        settled { exists(BrowserProfilesTags.row("p2")) }
        assertTrue(!exists(BrowserProfilesTags.LoadError))
    }
}
