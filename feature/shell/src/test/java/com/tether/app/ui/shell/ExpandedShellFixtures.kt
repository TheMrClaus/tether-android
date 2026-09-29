package com.tether.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.softShadow
import com.tether.app.ui.statusline.ContextGauge
import com.tether.app.ui.statusline.ReadingEnv
import com.tether.app.ui.statusline.SessionDial
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeFamily
import java.time.ZoneOffset
import java.util.Locale

/**
 * The seeded web scenarios as the expanded shell sees them (tether scripts/parity-seed.mjs:410,
 * :472): the idle session is named "Summarize the README"; the browser clock is frozen, so the
 * dial reads 06:09:01 on idle-session and 04:09:00 on session-details (the tablet shots).
 */
object ExpandedFixtures {
    const val Now: Long = 1_790_078_400_000
    val idle = ShellFixtures.idle.copy(name = "Summarize the README", startedAt = Now - (6 * 3600 + 9 * 60 + 1) * 1000L)
    val details = ShellFixtures.details.copy(startedAt = Now - (4 * 3600 + 9 * 60) * 1000L)
    val env: () -> ReadingEnv = { ReadingEnv(Now.toDouble(), Locale.US, ZoneOffset.UTC) }
}

const val StageFrameTag = "slot-chat-frame"

/**
 * Slot stand-ins for the expanded goldens. The chat slot draws the web's chat SCREEN — the frame
 * the chat (T6) will own: instrument `1px --line-strong`, `--radius-lg`, `--mineral-deep`, `--well`
 * + `--bezel` + the contact shade (globals.css 11230-11236); Studio flat `--graphite` (studio.css
 * 368-369) — so the stage's bay padding reads as it does on the web. The gauge and dial are T4.3's
 * real components on a frozen clock; the rail and inspector are empty tagged areas.
 */
fun expandedSlots(): PhoneShellSlots = PhoneShellSlots(
    drawer = { Box(Modifier.fillMaxSize().testTag(DrawerSlotTag)) },
    chat = {
        val t = LocalTetherTokens.current
        val frame = if (t.skin.family == ThemeFamily.Studio) {
            Modifier.background(t.graphite)
        } else {
            Modifier.cssSurface(
                RoundedCornerShape(t.radiusLg),
                t.mineralDeep,
                CssBorder(1.dp, t.lineStrong),
                t.css.well + t.css.bezel + softShadow(2.dp, 6.dp, t.contact.copy(alpha = 0.1f), spread = 7.dp),
            )
        }
        Box(Modifier.fillMaxSize().then(frame).testTag(ChatSlotTag)) { Box(Modifier.testTag(StageFrameTag)) }
    },
    inspector = { Box(Modifier.testTag(InspectorSlotTag)) },
    gauge = { host -> ContextGauge(null, showLabel = host.showLabel, pressed = host.open, onClick = host.onToggle, env = ExpandedFixtures.env) },
    dial = { s -> SessionDial(s.startedAt, s.endedAt, active = s.status != "exited", clock = { ExpandedFixtures.Now }) },
)

/** Holds [PanelPrefs] like the host's preference store would, and records every commit. */
class PanelStore(initial: PanelPrefs = PanelPrefs()) {
    var panels by mutableStateOf(initial)
    val commits = mutableListOf<PanelPrefs>()

    fun commit(next: PanelPrefs) {
        commits += next
        panels = next
    }
}

/** The expanded shell in [skin] with the expanded slot stand-ins. */
@Composable
fun ExpandedShellUnderTest(
    skin: TetherSkin,
    state: PhoneShellState,
    session: AgentSession?,
    store: PanelStore = PanelStore(),
    emptyStage: EmptyStage = EmptyStage.Welcome(connected = true, providers = ShellFixtures.providers),
    reducedMotion: Boolean = true,
    unseenWarnings: Int = 0,
    onEvent: (String) -> Unit = {},
    /** Non-null: the real preference-store binding instead of [store]. */
    persisted: PersistedPanels? = null,
) {
    TetherTheme(choiceFor(skin)) {
        CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
          LiveUnlessProvided(session) {
            ExpandedShell(
                state = state,
                panels = persisted?.panels ?: store.panels,
                onPanelsChange = persisted?.onChange ?: store::commit,
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
                ),
                header = WorkspaceHeaderActions(
                    onRename = { onEvent("rename") },
                    onEndSession = { onEvent("end") },
                    onTogglePinned = { onEvent("pin") },
                    onCopyPath = { onEvent("copyPath") },
                    onCopyTetherId = { onEvent("copyId") },
                ),
                slots = expandedSlots(),
            )
          }
        }
    }
}
