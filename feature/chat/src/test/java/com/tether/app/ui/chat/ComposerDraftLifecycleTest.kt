package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.TetherViewModelFactory
import com.tether.app.ui.prefs.DataStoreDraftStore
import com.tether.app.ui.prefs.DraftStore
import com.tether.app.ui.theme.TetherSkin
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T7.1 draft persistence end to end — the composer wired to the real [TetherViewModel] and a real
 * DataStore draft file, as ChatScreen wires them: a rotation (the activity recreated; MainActivity
 * declares no configChanges) keeps the typed text; process death (a new view model on a new store
 * over the same file) restores it; a send clears the stored copy; and a sign-in to another server
 * opens that server's own (empty) draft for the same session id, never server A's text.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ComposerDraftLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val client = ChatTestClient().apply { server.value = SERVER_A }
    private lateinit var file: File
    private var storeJob = Job()
    private lateinit var store: DraftStore
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var vm: TetherViewModel? = null
    private val id = ComposerFixtures.SESSION_ID

    @Before fun setUp() {
        file = File(tmp.root, DraftStore.FILE_NAME)
        openStore()
        client.show(ComposerFixtures.session, ComposerFixtures.idle)
        launch()
    }

    @After fun tearDown() {
        scenario.close()
        runBlocking { storeJob.cancelAndJoin() }
    }

    private fun openStore() {
        storeJob = Job()
        store = DataStoreDraftStore.create(file, CoroutineScope(Dispatchers.IO + storeJob))
    }

    private fun launch() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        host()
    }

    /** The composition, set again after every recreation (as MainActivity's onCreate does). */
    private fun host() {
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity, TetherViewModelFactory(client, store))[TetherViewModel::class.java]
            vm = model
            model.selectSession(id)
            activity.setContent {
                val projections by client.projections.collectAsState()
                ComposerHost(TetherSkin.StudioDark) {
                    Composer(
                        session = ComposerFixtures.session,
                        projection = projections[id],
                        controls = null,
                        serverNow = { ComposerFixtures.BUSY_NOW },
                        onSend = { text, attachments -> model.sendOrQueue(id, text, attachments) },
                        onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                        onQueueEdit = { _, _ -> },
                        onQueueRemove = {},
                        onRequestControls = {},
                        liveness = ComposerLiveness.Live,
                        initialDraft = model.loadedDraft(id),
                        awaitDraft = { model.awaitDraft(id) },
                        onDraftChange = { model.setDraft(id, it) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun input() = compose.onNodeWithContentDescription("Message the agent")

    private fun inputText(): String =
        input().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** DataStore writes land on IO: poll with the main looper drained (20 s budget, returns when true). */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun stored(origin: String = ORIGIN_A): String = runBlocking { store.read(origin, id) }

    /** Process death: the activity and its view model go, the store's scope dies, a new one opens the file. */
    private fun killAndRelaunch() {
        scenario.close()
        runBlocking { storeJob.cancelAndJoin() }
        openStore()
        launch()
    }

    @Test
    fun aRotationKeepsTheTypedDraft() {
        input().performTextInput("typed before the rotation")
        compose.waitForIdle()
        val before = vm
        scenario.recreate()
        host()
        assertSame("the view model outlives the activity", before, vm)
        assertEquals("typed before the rotation", inputText())
    }

    @Test
    fun processDeathRestoresTheDraftFromDisk() {
        input().performTextInput("half a thought")
        waitFor { stored() == "half a thought" }
        killAndRelaunch()
        waitFor { inputText() == "half a thought" }
    }

    @Test
    fun aSendClearsTheStoredDraft() {
        input().performTextInput("send me")
        waitFor { stored() == "send me" }
        input().performKeyInput { pressKey(Key.Enter) }
        waitFor { stored() == "" }
        assertEquals(listOf("send:send me"), client.outbox.toList())
        killAndRelaunch()
        assertEquals("", inputText())
    }

    @Test
    fun anotherServerNeverSeesThisServersDraft() {
        input().performTextInput("private to server A")
        waitFor { stored() == "private to server A" }
        client.server.value = SERVER_B
        killAndRelaunch()
        compose.waitForIdle()
        assertEquals("", inputText())
        assertEquals("", stored(ORIGIN_B))
        input().performTextInput("B's own")
        waitFor { stored(ORIGIN_B) == "B's own" }
        assertEquals("private to server A", stored(ORIGIN_A))
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val SERVER_A = "https://tether-a.example"
        const val ORIGIN_A = "https://tether-a.example:443"
        const val SERVER_B = "http://192.168.1.20:4173"
        const val ORIGIN_B = "http://192.168.1.20:4173"
    }
}
