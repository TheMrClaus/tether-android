package com.tether.app.ui.draft

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tether.app.client.ProposalSeed
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The proposal card offers Options for a proposal only once its form has been seeded from the server's catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProposalOptionsHostTest {
    @get:Rule val tmp = TemporaryFolder()
    private val job = Job()

    @After fun closeStore() = runBlocking { job.cancel() }

    private fun seed(key: String) = ProposalSeed.Result(form = JsObj.EMPTY, modified = JsObj.EMPTY, codexCurrent = null, extraModel = null, key = key)

    @Test fun optionsAreOfferedOnlyForAProposalWhoseFormIsSeeded() {
        val prefs = UiPrefs.on(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") })
        val host = ShellProposalOptionsHost(TetherViewModel(DraftTestClient()), prefs)

        assertFalse("no seed yet: no Options", host.offersOptions("p1"))
        host.seeds["p1"] = seed("claude")
        assertTrue("seeded: Options offered", host.offersOptions("p1"))
        assertFalse("another proposal has no seed of its own", host.offersOptions("p2"))
    }
}
