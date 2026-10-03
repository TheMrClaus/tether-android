package com.tether.app.ui.localnet

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WifiOff
import com.tether.app.net.AndroidLocalNetworkAccess
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.prefs.launchPreferenceWrite
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import kotlinx.coroutines.launch

// User-facing copy, kept together so the tests and the UI cannot drift apart.
internal object LocalNetworkCopy {
    const val EXPLAIN_TITLE = "Allow local network access"
    const val EXPLAIN_BODY =
        "Your Tether server is on this network, for example a computer at home or in the office. " +
            "Android asks you to allow local network access before an app can connect to devices on your network. " +
            "Tether uses it only to reach the server you entered."
    const val NOT_NOW = "Not now"
    const val CONTINUE = "Continue"
    const val NOTICE_TITLE = "Local network access is off"
    const val NOTICE_BODY = "Tether can't reach your server on this network until you allow it."
    const val NOTICE_BODY_SETTINGS =
        "Tether can't reach your server on this network. Turn on Nearby devices for Tether in Settings."
    const val ALLOW = "Allow"
    const val OPEN_SETTINGS = "Open settings"
    const val ALLOW_A11Y = "Allow local network access"
    const val OPEN_SETTINGS_A11Y = "Open Tether's settings to allow local network access"
}

/** The flow's model plus the platform actions that drive it (request, app settings). */
class LocalNetworkPrompt internal constructor(
    val model: LocalNetworkPromptModel,
    private val canRequestNow: () -> Boolean,
    private val launchRequest: () -> Unit,
    private val openSettings: () -> Unit,
) {
    fun onBlocked(source: LocalNetworkSource, retry: () -> Unit) =
        model.onBlocked(source, retry, canRequestNow())

    fun clear(source: LocalNetworkSource) = model.clear(source)

    fun onExplainContinue() {
        model.onExplainContinue()
        launchRequest()
    }

    fun onExplainDismissed() = model.onExplainDismissed(canRequestNow())

    fun onAllow() {
        if (canRequestNow()) {
            model.onRequestLaunched()
            launchRequest()
        } else {
            openSettings()
        }
    }
}

/**
 * Remembers the Android 17 ACCESS_LOCAL_NETWORK flow. The system dialog is only
 * ever requested where the OS enforces the permission (API 37 + targetSdk 37;
 * the docs say not to request it below that), and only after the client reported
 * a local server as blocked, so remote servers never trigger it.
 */
@Composable
fun rememberLocalNetworkPrompt(prefs: UiPrefs): LocalNetworkPrompt {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val model = remember { LocalNetworkPromptModel() }
    val asked by prefs.localNetworkPermissionAsked.collectAsStateWithLifecycle(initialValue = false)

    // "Never asked" and "don't ask again" both report no rationale, so the
    // persisted flag tells them apart. The platform's reset-counter strategy can
    // make the rationale true again, and then Allow re-requests.
    fun canRequestNow(): Boolean =
        AndroidLocalNetworkAccess.enforced(context) &&
            (!asked || activity?.shouldShowRequestPermissionRationale(AndroidLocalNetworkAccess.PERMISSION) == true)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launchPreferenceWrite { prefs.setLocalNetworkPermissionAsked(true) }
        val again = activity?.shouldShowRequestPermissionRationale(AndroidLocalNetworkAccess.PERMISSION) == true
        model.onPermissionResult(granted, canRequest = again)
    }

    // Returning from the settings page (or any resume) picks up a grant made there.
    LifecycleResumeEffect(model) {
        model.onResumed(AndroidLocalNetworkAccess.granted(context))
        onPauseOrDispose { }
    }

    return remember(model, launcher, asked, activity) {
        LocalNetworkPrompt(
            model = model,
            canRequestNow = ::canRequestNow,
            launchRequest = {
                if (AndroidLocalNetworkAccess.enforced(context)) {
                    launcher.launch(AndroidLocalNetworkAccess.PERMISSION)
                } else {
                    // Nothing to ask below API 37: report it as granted.
                    model.onPermissionResult(granted = true, canRequest = false)
                }
            },
            openSettings = { openAppSettings(context) },
        )
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No settings activity (a stripped-down build). The notice stays up and says what to do.
    }
}

/** Tether's explanation, shown before the system dialog. */
@Composable
fun LocalNetworkExplainDialog(onContinue: () -> Unit, onNotNow: () -> Unit) {
    val t = LocalTetherTokens.current
    TetherDialog(onDismiss = onNotNow, title = LocalNetworkCopy.EXPLAIN_TITLE) {
        Text(
            text = LocalNetworkCopy.EXPLAIN_BODY,
            color = t.ink,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 13.1.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TetherKey(
                onClick = onNotNow,
                modifier = Modifier.weight(1f),
                classes = KeyClasses.ButtonSecondary,
                label = LocalNetworkCopy.NOT_NOW,
            )
            TetherKey(
                onClick = onContinue,
                modifier = Modifier.weight(1f),
                classes = KeyClasses.ButtonPrimary,
                label = LocalNetworkCopy.CONTINUE,
            )
        }
    }
}

/**
 * The persistent denied state. The icon and the words carry the meaning, not a
 * colour alone. The action key keeps the 44dp touch target. The notice is a
 * polite live region, so TalkBack announces it when it appears.
 */
@Composable
fun LocalNetworkNotice(canRequest: Boolean, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(t.graphite, RoundedCornerShape(TetherDimens.radiusSm))
            .border(1.dp, t.warning, RoundedCornerShape(TetherDimens.radiusSm))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Decorative: the title right next to it says the same thing.
        Icon(Lucide.WifiOff, contentDescription = null, tint = t.warning, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = LocalNetworkCopy.NOTICE_TITLE,
                color = t.white,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 13.1.sp,
            )
            Text(
                text = if (canRequest) LocalNetworkCopy.NOTICE_BODY else LocalNetworkCopy.NOTICE_BODY_SETTINGS,
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 12.5.sp,
            )
        }
        val actionLabel = if (canRequest) LocalNetworkCopy.ALLOW_A11Y else LocalNetworkCopy.OPEN_SETTINGS_A11Y
        TetherKey(
            onClick = onAllow,
            // TetherKey's contentDescription only labels an icon. The legend here
            // is text, so the full TalkBack label goes on the key's own node.
            modifier = Modifier.semantics { contentDescription = actionLabel },
            classes = KeyClasses.ButtonPrimary,
            label = if (canRequest) LocalNetworkCopy.ALLOW else LocalNetworkCopy.OPEN_SETTINGS,
            minHeight = TetherDimens.touchTargetDp,
        )
    }
}
