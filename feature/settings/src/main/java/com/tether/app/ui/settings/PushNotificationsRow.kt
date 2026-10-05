package com.tether.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.push.PushRegistration
import com.tether.app.push.PushRegistrationStatus
import com.tether.app.push.PushScope
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.prefs.launchPreferenceWrite
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

/**
 * The process's push registration (T12.2). Read lazily, so production gets what `PushController`
 * installed in [PushRegistration.current]; tests provide a fake.
 */
internal val LocalPushRegistration = staticCompositionLocalOf<PushRegistration> { PushRegistration.current }

internal object PushTags {
    const val Row = "settings-push"
    const val Action = "settings-push-action"
}

/**
 * The web's Web Push row (tether 90fbb9f settings-dialog.tsx:2066-2097, over the
 * hooks/use-push-notifications.ts states; T12.2): the title with its tip, the phase's message
 * (a polite live region, the web's `aria-live`) and the one button: Re-enable when stale,
 * Disable while subscribed, else Enable when it can (disabled / error). It takes effect at once,
 * outside General's Save draft. Enable asks for POST_NOTIFICATIONS first when it is not granted
 * (the web's `Notification.requestPermission()`). Opening it re-runs the registration (the web's
 * refresh on mount). Below it, T12.1's per-device scope.
 */
@Composable
internal fun PushNotificationsRow(prefs: UiPrefs, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val scope = rememberCoroutineScope()
    val registration = LocalPushRegistration.current
    val pushEnabled by prefs.pushEnabled.collectAsStateWithLifecycle(initialValue = true)
    val pushScope by prefs.pushScope.collectAsStateWithLifecycle(initialValue = PushScope.All)
    val pushPermissionAsked by prefs.pushPermissionAsked.collectAsStateWithLifecycle(initialValue = false)
    val status by registration.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    // Bumped on resume and after the system dialog, so a grant made elsewhere is re-read.
    var permissionReads by remember { mutableIntStateOf(0) }
    var permissionRefused by remember { mutableStateOf(false) }
    var enabling by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        permissionReads++
        onPauseOrDispose { }
    }
    val permission = remember(permissionReads, pushPermissionAsked, activity) {
        PushNotificationsModel.permission(
            granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            asked = pushPermissionAsked,
            shouldShowRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionReads++
        permissionRefused = !granted
        if (!granted) enabling = false
        scope.launchPreferenceWrite {
            prefs.setPushPermissionAsked(true)
            if (granted) prefs.setPushEnabled(true)
        }
    }
    // The web's refresh on the dialog's mount: repair a lost server row, and show where it stands.
    LaunchedEffect(registration) { registration.refresh() }

    val state = PushNotificationsModel.derive(pushEnabled, permission, status, permissionRefused)
    // An Enable / Re-enable is busy until its registration settles (the web's `busy`).
    LaunchedEffect(state.phase, status) { if (state.phase != PushPhase.Loading && status != PushRegistrationStatus.Registering) enabling = false }
    val action = PushNotificationsModel.action(state) ?: PushAction.Enable.takeIf { enabling }
    val busy = enabling

    Column {
        SettingsRow(
            narrow = narrow,
            modifier = Modifier.testTag(PushTags.Row),
            text = { m ->
                SettingsRowText(
                    PushCopy.TITLE,
                    AnnotatedString(state.message),
                    m.semantics { liveRegion = LiveRegionMode.Polite },
                    tip = PushCopy.TIP,
                )
            },
            control = if (action == null) null else { m ->
                TetherKey(
                    onClick = {
                        when (action) {
                            PushAction.ReEnable -> {
                                enabling = true
                                registration.reEnable()
                            }
                            PushAction.Disable -> {
                                permissionRefused = false
                                scope.launchPreferenceWrite { prefs.setPushEnabled(false) }
                            }
                            PushAction.Enable -> {
                                enabling = true
                                permissionRefused = false
                                if (permission == PushPermission.Granted) {
                                    if (pushEnabled) registration.refresh() else scope.launchPreferenceWrite { prefs.setPushEnabled(true) }
                                } else {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    classes = KeyClasses.ButtonSecondary,
                    label = if (busy) action.busyLabel else action.label,
                    modifier = m.testTag(PushTags.Action),
                )
            },
        )
        // T12.1's per-device scope: which sessions this device is alerted for. Off while push is.
        val scopeOn = pushEnabled
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            PushScope.entries.forEach { choice ->
                val chosen = choice == pushScope
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (chosen) t.violetWash else t.keyFace,
                            RoundedCornerShape(TetherDimens.radiusSm),
                        )
                        .border(
                            1.dp,
                            if (chosen) t.violetStrong else t.keySide,
                            RoundedCornerShape(TetherDimens.radiusSm),
                        )
                        .clickable(enabled = scopeOn) {
                            scope.launchPreferenceWrite { prefs.setPushScope(choice) }
                        }
                        .heightIn(min = TetherDimens.touchTargetDp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (choice) {
                            PushScope.All -> "All sessions"
                            PushScope.Attached -> "Sessions opened on this phone"
                            PushScope.Pinned -> "Pinned sessions"
                        },
                        color = if (chosen) t.white else t.ink,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.name,
                        fontSize = 13.1.sp,
                        modifier = Modifier.weight(1f).graphicsLayer { alpha = if (scopeOn) 1f else 0.5f },
                    )
                    if (chosen) StatusDot(t.violet, size = 6.4.dp)
                }
            }
        }
    }
}
