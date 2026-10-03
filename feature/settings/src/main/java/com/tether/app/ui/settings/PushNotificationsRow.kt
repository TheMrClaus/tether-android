package com.tether.app.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.push.PushScope
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.prefs.launchPreferenceWrite
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import kotlinx.coroutines.launch

/**
 * T12.1's push controls, moved from the interim settings sheet unchanged: the master toggle
 * (asking for POST_NOTIFICATIONS on the first enable; a denial turns it back off), the per-device
 * scope and the status line. They take effect at once, outside General's Save draft, like the
 * web's Web Push row (settings-dialog.tsx:2066-2110).
 */
@Composable
internal fun PushNotificationsRow(prefs: UiPrefs) {
    val t = LocalTetherTokens.current
    val scope = rememberCoroutineScope()
    val pushEnabled by prefs.pushEnabled.collectAsStateWithLifecycle(initialValue = true)
    val pushScope by prefs.pushScope.collectAsStateWithLifecycle(initialValue = PushScope.All)
    val pushPermissionAsked by prefs.pushPermissionAsked.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        scope.launchPreferenceWrite {
            if (!granted) prefs.setPushEnabled(false)
            prefs.setPushPermissionAsked(true)
        }
    }
    Column {
        // Master toggle. On first enable, request POST_NOTIFICATIONS; if
        // denied the toggle reverts and the status line explains why.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!pushEnabled) {
                        if (!pushPermissionAsked) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            scope.launchPreferenceWrite { prefs.setPushEnabled(true) }
                        }
                    } else {
                        scope.launchPreferenceWrite { prefs.setPushEnabled(false) }
                    }
                }
                .heightIn(min = TetherDimens.touchTargetDp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Notifications",
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.name,
                fontSize = 13.1.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (pushEnabled) "ON" else "OFF",
                color = if (pushEnabled) t.violet else t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.strong,
                fontSize = 10.7.sp,
                letterSpacing = 0.06.em,
            )
        }
        // Scope selector — disabled while the master toggle is off.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        .clickable(enabled = pushEnabled) {
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
                        modifier = Modifier.weight(1f).graphicsLayer { alpha = if (pushEnabled) 1f else 0.5f },
                    )
                    if (chosen) StatusDot(t.violet, size = 6.4.dp)
                }
            }
        }
        // Status line. The runtime permission state is read here so the
        // line tracks a denial even after the sheet is reopened.
        val notificationManager = remember(context) {
            context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        }
        val permissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val statusLine = when {
            !pushEnabled -> "Off"
            !permissionGranted -> "Permission required."
            !notificationManager.areNotificationsEnabled() -> "Notifications disabled in system settings."
            else -> "Notifications on"
        }
        Text(
            statusLine,
            color = t.faint,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 11.2.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
