package com.tether.app.ui.compat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ServerCrash
import com.tether.app.BuildConfig
import com.tether.app.client.IncompatibleReason
import com.tether.app.client.Incompatibility
import com.tether.app.client.ReleaseCheck
import com.tether.app.ui.components.KeyVariant
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import okhttp3.OkHttpClient

// User-facing copy, kept together so the UI and any test cannot drift apart.
internal object CompatCopy {
    const val UPDATE_TITLE = "Update available"
    const val UPDATE_NEEDED_TITLE = "App update needed"
    fun updateBody(tag: String) = "Tether $tag is out. This server needs it to connect."
    const val UPDATE_NEEDED_BODY = "This server needs a newer version of Tether to connect."
    const val UPDATE_ACTION = "Update"
    const val UPDATE_A11Y = "Update Tether. Opens the release page."

    const val SERVER_TITLE = "Server is older than the app"
    const val SERVER_BODY = "Update the Tether server, then retry."
    const val RETRY_ACTION = "Retry"
    const val RETRY_A11Y = "Retry connecting to the server"
}

/** One client for the occasional release check; never the protocol client. */
private val releaseHttp: OkHttpClient by lazy { OkHttpClient() }

/**
 * D5/D13: the connection is halted because this app and the server are outside
 * each other's native compatibility window. Icon + title + body + a 44 dp
 * action (never colour alone), announced politely to TalkBack.
 *
 * - client too old: "Update available" when the GitHub Releases API names a
 *   release newer than this build, otherwise "App update needed"; the action
 *   opens the release page in the browser.
 * - server too old: says so; the action retries (after the server was updated).
 */
@Composable
fun CompatibilityBanner(incompatibility: Incompatibility, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (incompatibility.reason) {
        IncompatibleReason.ClientTooOld -> {
            val context = LocalContext.current
            var latest by remember { mutableStateOf<ReleaseCheck.Release?>(null) }
            LaunchedEffect(Unit) { latest = ReleaseCheck.fetchLatest(releaseHttp) }
            val newer = latest?.takeIf { ReleaseCheck.isNewer(it.tag, BuildConfig.VERSION_NAME) }
            BannerRow(
                icon = Lucide.Download,
                title = if (newer != null) CompatCopy.UPDATE_TITLE else CompatCopy.UPDATE_NEEDED_TITLE,
                body = newer?.let { CompatCopy.updateBody(it.tag) } ?: CompatCopy.UPDATE_NEEDED_BODY,
                actionLabel = CompatCopy.UPDATE_ACTION,
                actionA11y = CompatCopy.UPDATE_A11Y,
                onAction = { openReleasePage(context, newer?.pageUrl ?: ReleaseCheck.RELEASES_PAGE_URL) },
                modifier = modifier,
            )
        }
        IncompatibleReason.ServerTooOld -> BannerRow(
            icon = Lucide.ServerCrash,
            title = CompatCopy.SERVER_TITLE,
            body = CompatCopy.SERVER_BODY,
            actionLabel = CompatCopy.RETRY_ACTION,
            actionA11y = CompatCopy.RETRY_A11Y,
            onAction = onRetry,
            modifier = modifier,
        )
    }
}

@Composable
private fun BannerRow(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    actionA11y: String,
    onAction: () -> Unit,
    modifier: Modifier,
) {
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
        // Decorative: the title next to it says the same thing.
        Icon(icon, contentDescription = null, tint = t.warning, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = t.white,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 13.1.sp,
            )
            Text(
                text = body,
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 12.5.sp,
            )
        }
        TetherKey(
            onClick = onAction,
            // TetherKey's contentDescription only labels an icon; the full
            // TalkBack label goes on the key's own node.
            modifier = Modifier.semantics { contentDescription = actionA11y },
            variant = KeyVariant.Primary,
            label = actionLabel,
            minHeight = TetherDimens.touchTargetDp,
        )
    }
}

/** ACTION_VIEW on an https github.com URL (ReleaseCheck only yields those). */
private fun openReleasePage(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser: nothing sensible to do; the banner stays up.
    }
}
