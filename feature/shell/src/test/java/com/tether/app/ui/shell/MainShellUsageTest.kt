package com.tether.app.ui.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tether.app.ui.overview.OverviewTags
import com.tether.app.ui.usage.AccountsTags
import com.tether.app.ui.usage.UsageTags
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T9.2 through MainShell: the bar's Usage opens the Usage page (the web's `/usage`) and reads
 * current, Back returns to the view under it without storing the page as the last view, and the
 * bar's Accounts opens the accounts dialog over whatever is shown (topbar.tsx onOpenUsage).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class MainShellUsageTest : NavigationBase(1200, 1000) {

    @Test fun usageOpensThePageAndBackReturnsToTheViewUnderIt() {
        launch()
        onOverview()
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Usage)).performClick()
        awaitTag(UsageTags.Page)
        assertTrue(selected(ShellTags.nav(TopBarDestination.Usage)))
        assertFalse("the page is shown instead of the Overview", exists(OverviewTags.Root))
        back()
        onOverview()
        assertFalse(exists(UsageTags.Page))
        assertTrue(selected(ShellTags.nav(TopBarDestination.Overview)))
        assertNotEquals("the page is never the stored view", "usage", storedView())
    }

    @Test fun accountsOpensTheDialog() {
        launch()
        onOverview()
        rule.onNodeWithTag(ShellTags.AccountsKey).performClick()
        awaitTag(AccountsTags.Dialog)
    }
}
