package com.tether.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.X
import com.tether.app.ui.chat.ChatScreen
import com.tether.app.ui.components.BrandMark
import com.tether.app.ui.components.KeyVariant
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.Wordmark
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The main single-activity shell: topbar + chat workspace + session drawer. */
@Composable
fun MainShell(vm: TetherViewModel, prefs: UiPrefs) {
    val t = LocalTetherTokens.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val sessions by vm.client.sessions.collectAsStateWithLifecycle()
    val projections by vm.client.projections.collectAsStateWithLifecycle()
    val selectedId by vm.selectedSessionId.collectAsStateWithLifecycle()
    val workspaceRoot by vm.client.workspaceRoot.collectAsStateWithLifecycle()
    val toast by vm.activeToast.collectAsStateWithLifecycle()

    val session = sessions.firstOrNull { it.id == selectedId }
    val projection = selectedId?.let { projections[it] }

    val screenWidth = LocalConfiguration.current.screenWidthDp
    val drawerWidth = minOf(320, (screenWidth * 0.88f).toInt()).dp

    var showErrorLog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            scrimColor = t.scrim,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(drawerWidth),
                    drawerShape = RectangleShape,
                    drawerContainerColor = t.graphite,
                    drawerContentColor = t.ink,
                ) {
                    SessionDrawer(
                        vm = vm,
                        prefs = prefs,
                        sessions = sessions,
                        selectedId = selectedId,
                        workspaceRoot = workspaceRoot,
                        onSelect = { id ->
                            vm.selectSession(id)
                            scope.launch { drawerState.close() }
                        },
                        onClose = { scope.launch { drawerState.close() } },
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxSize().background(t.mineral)) {
                TopBar(
                    errorCount = vm.errorLog.size,
                    onMenu = { scope.launch { drawerState.open() } },
                    onErrorLog = { showErrorLog = true },
                    onLock = { showLogoutConfirm = true },
                )
                ChatScreen(
                    vm = vm,
                    session = session,
                    projection = projection,
                    workspaceRoot = workspaceRoot,
                    prefs = prefs,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
            }
        }

        toast?.let { message ->
            LaunchedEffect(message) {
                delay(10_000)
                vm.dismissToast()
            }
            ErrorToast(
                message = message,
                onClose = { vm.dismissToast() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .zIndex(10f),
            )
        }
    }

    if (showErrorLog) {
        TetherDialog(onDismiss = { showErrorLog = false }, title = "Activity log") {
            if (vm.errorLog.isEmpty()) {
                Text("No errors this session.", color = t.muted, fontFamily = Manrope, fontSize = 13.1.sp)
            } else {
                vm.errorLog.asReversed().take(20).forEach { entry ->
                    Text(
                        entry,
                        color = t.ink,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 12.8.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }

    if (showLogoutConfirm) {
        TetherDialog(onDismiss = { showLogoutConfirm = false }, title = "Disconnect") {
            Text(
                "Sign out and forget this server?",
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 13.6.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TetherKey(
                    onClick = { showLogoutConfirm = false },
                    variant = KeyVariant.Secondary,
                    label = "Cancel",
                )
                TetherKey(
                    onClick = {
                        showLogoutConfirm = false
                        vm.client.stop()
                    },
                    variant = KeyVariant.Brick,
                    label = "Disconnect",
                    icon = Lucide.LogOut,
                )
            }
        }
    }
}

/** 52dp topbar over the status bar inset, graphite with a 1px bottom seam. */
@Composable
private fun TopBar(
    errorCount: Int,
    onMenu: () -> Unit,
    onErrorLog: () -> Unit,
    onLock: () -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(Modifier.fillMaxWidth().background(t.graphite).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
                Icon(Lucide.Menu, contentDescription = "Sessions", tint = t.ink, modifier = Modifier.size(20.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandMark()
                Wordmark()
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = onErrorLog, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
                    Icon(Lucide.Activity, contentDescription = "Activity log", tint = t.muted, modifier = Modifier.size(18.dp))
                }
                if (errorCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-6).dp, y = 6.dp)
                            .size(15.dp)
                            .background(t.danger, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (errorCount > 9) "9+" else "$errorCount",
                            color = t.accentInk,
                            fontFamily = Manrope,
                            fontWeight = TetherWeights.strong,
                            fontSize = 8.5.sp,
                        )
                    }
                }
            }
            IconButton(onClick = onLock, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
                Icon(Lucide.LogOut, contentDescription = "Disconnect", tint = t.muted, modifier = Modifier.size(18.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/** Fixed-bottom error toast: danger-wash surface, 1px brick border. */
@Composable
fun ErrorToast(message: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(t.dangerWash, RoundedCornerShape(TetherDimens.radiusSm))
            .border(1.dp, t.brick, RoundedCornerShape(TetherDimens.radiusSm))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Lucide.CircleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(18.dp))
        Text(
            text = message,
            color = t.white,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 12.5.sp,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
            Icon(Lucide.X, contentDescription = "Dismiss", tint = t.muted, modifier = Modifier.size(16.dp))
        }
    }
}
