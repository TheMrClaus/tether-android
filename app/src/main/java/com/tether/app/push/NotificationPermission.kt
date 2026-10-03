package com.tether.app.push

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.prefs.launchPreferenceWrite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Where the Android 13+ POST_NOTIFICATIONS grant stands. minSdk is 34, so it
 * is a runtime permission on every supported device.
 */
enum class NotificationPermissionStatus {
    Granted,

    /** Never requested: the system dialog can be shown. */
    NotAsked,

    /** Denied once. The system shows the dialog again if asked. */
    Rationale,

    /** Denied for good ("don't ask again"): only the app's settings page can grant it. */
    Denied,
}

/**
 * The policy, kept pure so every branch is JVM-tested. "Never asked" and
 * "don't ask again" both report no rationale, so the persisted
 * [UiPrefs.pushPermissionAsked] flag tells them apart (the same approach as the
 * local-network flow).
 */
object NotificationPermissionPolicy {
    fun status(granted: Boolean, asked: Boolean, shouldShowRationale: Boolean): NotificationPermissionStatus = when {
        granted -> NotificationPermissionStatus.Granted
        !asked -> NotificationPermissionStatus.NotAsked
        shouldShowRationale -> NotificationPermissionStatus.Rationale
        else -> NotificationPermissionStatus.Denied
    }

    /**
     * The one automatic request: after sign-in, with notifications on (the
     * default), when the app has never asked. After that, asking again is the
     * user's choice (the settings toggles, T12.2). A denial is never nagged.
     * [asked] is null until the stored flag has loaded, and then nothing is
     * requested, so a slow DataStore read cannot cause a second prompt.
     */
    fun shouldAutoRequest(
        signedIn: Boolean,
        pushEnabled: Boolean,
        granted: Boolean,
        asked: Boolean?,
    ): Boolean = signedIn && pushEnabled && !granted && asked == false

    /** What a user-initiated "Allow notifications" does from [status]. */
    fun actionFor(status: NotificationPermissionStatus): NotificationPermissionAction = when (status) {
        NotificationPermissionStatus.Granted -> NotificationPermissionAction.None
        NotificationPermissionStatus.NotAsked,
        NotificationPermissionStatus.Rationale,
        -> NotificationPermissionAction.Request
        NotificationPermissionStatus.Denied -> NotificationPermissionAction.OpenSettings
    }
}

enum class NotificationPermissionAction { None, Request, OpenSettings }

/** Platform side of the flow; see [rememberNotificationPermission]. */
class NotificationPermissionPrompt internal constructor(
    val status: NotificationPermissionStatus,
    /** Null until the stored "asked" flag has loaded. */
    val asked: Boolean?,
    private val launchRequest: () -> Unit,
    private val openSettings: () -> Unit,
) {
    val granted: Boolean get() = status == NotificationPermissionStatus.Granted

    /** The automatic first request; see [NotificationPermissionPolicy.shouldAutoRequest]. */
    fun autoRequestIfDue(signedIn: Boolean, pushEnabled: Boolean) {
        if (NotificationPermissionPolicy.shouldAutoRequest(signedIn, pushEnabled, granted, asked)) launchRequest()
    }

    /** A user-initiated "Allow notifications" (for the settings UI, T12.2 / T10.1). */
    fun onAllow() {
        when (NotificationPermissionPolicy.actionFor(status)) {
            NotificationPermissionAction.None -> Unit
            NotificationPermissionAction.Request -> launchRequest()
            NotificationPermissionAction.OpenSettings -> openSettings()
        }
    }
}

/**
 * The persisted "have we shown the system dialog?" flag. Production reads it
 * from [UiPrefs.pushPermissionAsked] ([of]). It is a seam because UiPrefs can
 * only be built over the app's single DataStore.
 */
interface NotificationPermissionAskedStore {
    val asked: Flow<Boolean>

    /** Records that the system dialog was answered. */
    suspend fun markAsked()

    companion object {
        fun of(prefs: UiPrefs): NotificationPermissionAskedStore = object : NotificationPermissionAskedStore {
            override val asked: Flow<Boolean> = prefs.pushPermissionAsked
            override suspend fun markAsked() = prefs.setPushPermissionAsked(true)
        }
    }
}

/** [rememberNotificationPermission] over the app's [UiPrefs]. */
@Composable
fun rememberNotificationPermission(prefs: UiPrefs): NotificationPermissionPrompt =
    rememberNotificationPermission(remember(prefs) { NotificationPermissionAskedStore.of(prefs) })

/**
 * Remembers the POST_NOTIFICATIONS flow. The status is re-read on every resume,
 * so a grant made on the settings page is picked up when the user returns.
 */
@Composable
fun rememberNotificationPermission(store: NotificationPermissionAskedStore): NotificationPermissionPrompt {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val asked by store.asked.collectAsStateWithLifecycle(initialValue = null)
    // Bumped on resume and after a result, so the status below is re-read.
    var refresh by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Remembered, so a denial is never asked again automatically.
        scope.launchPreferenceWrite { store.markAsked() }
        refresh++
    }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }

    val status = remember(refresh, asked, activity) {
        NotificationPermissionPolicy.status(
            granted = PushNotifier.canPost(context),
            asked = asked == true,
            shouldShowRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true,
        )
    }
    return remember(status, asked, launcher) {
        NotificationPermissionPrompt(
            status = status,
            asked = asked,
            launchRequest = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            openSettings = { openNotificationSettings(context) },
        )
    }
}

/** The app's own notification settings page, where a permanent denial is undone. */
internal fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun openNotificationSettings(context: Context) {
    try {
        context.startActivity(notificationSettingsIntent(context))
    } catch (_: ActivityNotFoundException) {
        // No settings activity (a stripped-down build): nothing else to offer.
    }
}
