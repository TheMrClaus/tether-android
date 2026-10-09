package com.tether.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsObj
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * ta-coik.37: where the transcript's per-change derivation (rows, timeline points, subagent runs)
 * runs once the chat is open. Production: [Dispatchers.Default]. Tests inject their own, so no
 * test waits on a wall clock.
 */
internal val LocalChatDerivationDispatcher = staticCompositionLocalOf<CoroutineDispatcher> { Dispatchers.Default }

/**
 * Test seam: called with "rows" / "runs" every time a derivation actually runs, on whichever
 * thread runs it (the caller captures the thread). Null in production.
 */
internal val LocalChatDerivationObserver = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** ta-nx60: where a failed rebuild is reported (the last rows stay on screen). Tests inject their own. */
internal val LocalChatDerivationFailureLog = staticCompositionLocalOf<(Throwable) -> Unit> {
    { t -> runCatching { android.util.Log.w("ChatDerivation", "a rebuild failed; the last rows stay", t) } }
}

private class DerivedHolder<T, C>(first: T, var builtFor: Any?) {
    var value by mutableStateOf(first)
    var wanted: Any? = builtFor
    var capture: () -> C = { error("unset") }
    var compute: (C) -> T = { error("unset") }
    var onPublish: (T) -> Unit = {}
    var onFailure: (Throwable) -> Unit = {}
    var dispatcher: CoroutineDispatcher = Dispatchers.Default
    var job: Job? = null

    /**
     * Builds [wanted], then whatever was wanted meanwhile, one build at a time, until the newest is shown.
     * ta-nx60: a build that throws (anything but a cancellation) is logged and the last value stays on
     * screen; that input counts as handled, so it is not retried in a loop, and the next change builds again.
     */
    suspend fun pump() {
        while (builtFor !== wanted) {
            val target = wanted
            try {
                val captured = capture() // main thread: reads Compose state, takes immutable snapshots
                val build = compute
                val result = withContext(dispatcher) { build(captured) }
                builtFor = target
                value = result
                onPublish(result) // main thread, in build order: every build's effects land, even for a superseded input
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                builtFor = target
                onFailure(e)
            }
        }
    }
}

/**
 * [compute]'s result for the current [inputs], never computed on the main thread after the first
 * build.
 *
 * - The first value for a [sessionKey] (session open or switch) is built synchronously, so the
 *   first frame is never blank, and a value built for another session is never shown.
 * - A later change of [inputs] (a streaming delta) shows the last value meanwhile and runs
 *   [compute] on [LocalChatDerivationDispatcher]. Builds run one at a time and coalesce: a change
 *   during a build is built next, from [capture]d state taken then.
 * - [capture] runs on the main thread just before each build and is the only place Compose state
 *   is read; [compute] sees its immutable result and nothing else of the UI. [onPublish] runs on
 *   the main thread after each build (for effects that touch Compose state).
 */
@Composable
internal fun <T, C> rememberDerived(
    sessionKey: Any?,
    inputs: Any,
    capture: () -> C,
    onPublish: (T) -> Unit = {},
    compute: (C) -> T,
): T {
    val holder = remember(sessionKey) { DerivedHolder<T, C>(compute(capture()).also(onPublish), inputs) }
    val scope = rememberCoroutineScope()
    holder.dispatcher = LocalChatDerivationDispatcher.current
    holder.onFailure = LocalChatDerivationFailureLog.current
    SideEffect {
        holder.wanted = inputs
        holder.capture = capture
        holder.compute = compute
        holder.onPublish = onPublish
        if (holder.builtFor !== inputs && holder.job?.isActive != true) holder.job = scope.launch { holder.pump() }
    }
    DisposableEffect(holder) { onDispose { holder.job?.cancel() } }
    return holder.value
}

/** Everything the transcript derives from one projection: its rows and the timeline's prompts. */
internal class ChatRows(
    val items: List<ChatItem>,
    val storyPoints: List<TimelinePoint>,
    /** The toggle lookups the build made, to replay on the main thread ([GroupToggles.resolve] resets a stale toggle). */
    val toggleReads: List<ToggleRead>,
    /** ta-8hcc: the files the session's tool calls touched (main transcript and sub-agent threads), built with the rows. */
    val touched: TouchedFiles = TouchedFiles.EMPTY,
)

internal class ToggleRead(val key: String, val default: Boolean)

/** The rows' inputs, all immutable (the toggles come separately, captured on the main thread per build). Identity-compared. */
internal class ChatRowsInputs(
    val projection: SessionProjection,
    val tree: JsObj?,
    val showThinking: Boolean,
    val zone: ZoneId,
    val richCodex: Boolean,
    val showApprovals: Boolean,
    val consentSessionId: String?,
    val observer: ((String) -> Unit)?,
)

internal fun deriveChatRows(i: ChatRowsInputs, toggles: Map<String, GroupToggle>): ChatRows {
    i.observer?.invoke("rows")
    val reads = ArrayList<ToggleRead>()
    val items = buildChatItems(
        i.projection, i.tree, i.showThinking, i.zone, i.richCodex,
        groupOpen = { key, default ->
            val toggle = toggles[key]
            if (toggle != null && toggle.default != default) reads.add(ToggleRead(key, default))
            if (toggle == null || toggle.default != default) default else toggle.open
        },
        i.showApprovals, i.consentSessionId,
    )
    return ChatRows(items, TimelineModel.points(i.tree), reads, touchedFilesOf(cardTree(i.projection, i.tree)))
}

/** The session's subagent runs (the roster and the run tabs read them). */
internal fun deriveRuns(projection: SessionProjection?, tree: JsObj?, observer: ((String) -> Unit)?): List<SubagentRun> {
    if (projection == null) return emptyList()
    observer?.invoke("runs")
    return collectSubagentRuns(cardTree(projection, tree))
}

/** [deriveRuns]' inputs; compared by identity, so each new projection or tree is one change. */
internal class RunsInputs(val projection: SessionProjection?, val tree: JsObj?)
