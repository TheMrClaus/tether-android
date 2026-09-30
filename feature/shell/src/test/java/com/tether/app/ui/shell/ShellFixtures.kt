package com.tether.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeMode

/** The seeded web scenarios' sessions (tether scripts/parity-seed.mjs), as the shell sees them. */
object ShellFixtures {
    val idle = AgentSession(
        id = "ZneD77FMPxwo1g",
        provider = "claude",
        name = "Summarize the README in one line.",
        cwd = "/srv/ws/parity-app",
        status = "ready",
        startedAt = 0L,
        updatedAt = 0L,
    )

    val details = idle.copy(id = "Kq3sP0aZ9mWx2v", name = "Apply the config edits")

    /** `empty-state`: the seeded providers (ACP unavailable). */
    val providers = listOf(
        ProviderAvailability("Claude Code", true),
        ProviderAvailability("Codex", true),
        ProviderAvailability("OpenCode", true),
        ProviderAvailability("Reasonix", true),
        ProviderAvailability("Pi", true),
        ProviderAvailability("DeepSeek Harness", true),
        ProviderAvailability("ACP", false),
    )

    const val workspaceRoot = "/srv/ws"
}

const val ChatSlotTag = "slot-chat"
const val DrawerSlotTag = "slot-drawer"
const val InspectorSlotTag = "slot-inspector"

/**
 * Slot stand-ins: the shell's goldens capture the CHROME this task owns, so each hosted surface
 * is an empty, tagged area in the colour of the surface that will fill it — the phone chat frame
 * is flush `--mineral-deep` (Studio `--graphite`), the drawer and panel keep their own floors.
 */
fun placeholderSlots(): PhoneShellSlots = PhoneShellSlots(
    drawer = { Box(Modifier.fillMaxSize().testTag(DrawerSlotTag)) },
    chat = {
        val t = LocalTetherTokens.current
        Box(Modifier.fillMaxSize().background(if (t.skin.family == com.tether.app.ui.theme.ThemeFamily.Studio) t.graphite else t.mineralDeep).testTag(ChatSlotTag))
    },
    inspector = { Box(Modifier.testTag(InspectorSlotTag)) },
)

fun choiceFor(skin: TetherSkin): ThemeChoice = ThemeChoice(skin.family, if (skin.isDark) ThemeMode.Dark else ThemeMode.Light)

/** The phone shell in [skin] with placeholder slots and a fresh or given state. */
@Composable
fun ShellUnderTest(
    skin: TetherSkin,
    state: PhoneShellState,
    session: AgentSession?,
    emptyStage: EmptyStage = EmptyStage.Welcome(connected = true, providers = ShellFixtures.providers),
    reducedMotion: Boolean = true,
    unseenWarnings: Int = 0,
    onEvent: (String) -> Unit = {},
) {
    TetherTheme(choiceFor(skin)) {
        CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
          LiveUnlessProvided(session) {
            PhoneShell(
                state = state,
                session = session,
                workspaceRoot = ShellFixtures.workspaceRoot,
                emptyStage = emptyStage,
                unseenWarnings = unseenWarnings,
                onStartSession = { onEvent("start") },
                topbar = TopbarActions(
                    onOpenDrawer = { onEvent("drawer") },
                    onOpenFiles = { onEvent("files") },
                    onOpenUsage = { onEvent("usage") },
                    onOpenUsageAnalytics = { onEvent("analytics") },
                    onOpenLog = { onEvent("log") },
                    onLogout = { onEvent("lock") },
                    onOpenSettings = { onEvent("settings") },
                    onNavigate = { onEvent("nav:${it.key}") },
                ),
                header = WorkspaceHeaderActions(
                    onRename = { onEvent("rename") },
                    onEndSession = { onEvent("end") },
                    onTogglePinned = { onEvent("pin") },
                    onCopyPath = { onEvent("copyPath") },
                    onCopyTetherId = { onEvent("copyId") },
                ),
                slots = placeholderSlots(),
            )
          }
        }
    }
}

/**
 * T13.2 r2: the shell's own default ([ShellFreshness.None]) claims nothing (no live list, no live
 * session). A shell under test is the live one unless the test provides its freshness itself.
 */
@Composable
fun LiveUnlessProvided(session: AgentSession?, content: @Composable () -> Unit) {
    if (LocalShellFreshness.current !== ShellFreshness.None) {
        content()
        return
    }
    CompositionLocalProvider(LocalShellFreshness provides liveShellFreshness(session?.id), content = content)
}

/** A live link with [sessionId] confirmed and Live on it. */
fun liveShellFreshness(sessionId: String?) = ShellFreshness(
    syncStates = sessionId?.let { mapOf(it to com.tether.app.client.SessionSync(com.tether.app.client.Freshness.Live, null)) }.orEmpty(),
    listLive = true,
    liveSessions = setOfNotNull(sessionId),
)
