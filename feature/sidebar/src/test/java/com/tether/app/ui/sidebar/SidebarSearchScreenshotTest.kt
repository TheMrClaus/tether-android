package com.tether.app.ui.sidebar

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.client.SearchResults
import com.tether.app.protocol.SearchHit
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.prefs.SidebarSort
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T5.3: the sidebar filter with the workspace content search merged in (dashboard.tsx:846-893):
 * "readme" typed over the web drawer's eleven chats — the title match, then two conversations
 * found only by content, each with its `.session-item-snippet` line (Search glyph, the context
 * window, "· N matches" past one).
 */
private val F = SidebarFixtures

fun contentSearchState(): SidebarState {
    val base = F.state(F.drawerSessions, query = "readme")
    val hits = SearchResults(
        "readme",
        listOf(
            SearchHit(
                historyId = "c-1", provider = "codex", name = "Docs pass from last month", cwd = F.APP,
                updatedAt = F.NOW - 3 * 24 * 60 * 60_000L, createdAt = F.NOW - 3 * 24 * 60 * 60_000L,
                snippet = "…updated the README table and the install section…", matchCount = 4,
            ),
            SearchHit(
                historyId = "c-2", provider = "claude", name = "Release checklist", cwd = F.APP,
                updatedAt = F.NOW - 9 * 24 * 60 * 60_000L, createdAt = F.NOW - 9 * 24 * 60 * 60_000L,
                snippet = "…link the README from the release notes…", matchCount = 1,
            ),
        ),
    )
    return base.copy(
        filteredSessions = SidebarModel.filteredSessions(base.sidebarSessions, "readme", null, hits, base.workspaces, F.ROOT, SidebarSort.Created, F.collator),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarSearchPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun contentSearch() {
        rule.mainClock.autoAdvance = false
        rule.setContent { SidebarUnderTest(skin, contentSearchState(), TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions(onCollapse = {}, onOpenGlobalSearch = {})) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        captureScreenRoboImage(
            "src/test/screenshots/sidebar-content-search/${skin.id}-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
