package com.tether.app.protocol.conformance

import com.tether.app.protocol.conformance.CanonicalJson.TaggedDate
import com.tether.app.protocol.conformance.CanonicalJson.TaggedFn
import com.tether.app.protocol.conformance.CanonicalJson.TaggedMap
import com.tether.app.protocol.conformance.CanonicalJson.TaggedObject
import com.tether.app.protocol.conformance.CanonicalJson.TaggedSet
import com.tether.app.protocol.conformance.CanonicalJson.Undefined
import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.helpers.AttachmentDraft
import com.tether.app.protocol.helpers.ChatViewModelCommand
import com.tether.app.protocol.helpers.ClaudeResetGrantsView
import com.tether.app.protocol.helpers.CodexModePresets
import com.tether.app.protocol.helpers.ConversationStoryPoints
import com.tether.app.protocol.helpers.DeepseekPeak
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.helpers.Elapsed
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.helpers.HistoryOrigin
import com.tether.app.protocol.helpers.InterruptNotice
import com.tether.app.protocol.helpers.MessageTime
import com.tether.app.protocol.helpers.ModeCycle
import com.tether.app.protocol.helpers.ModelBrowserView
import com.tether.app.protocol.helpers.ModelId
import com.tether.app.protocol.helpers.ModelPicker
import com.tether.app.protocol.helpers.PanelWidths
import com.tether.app.protocol.helpers.PendingInput
import com.tether.app.protocol.helpers.PendingWorkspace
import com.tether.app.protocol.helpers.SidebarOrder
import com.tether.app.protocol.helpers.SidebarWorkspaces
import com.tether.app.protocol.helpers.SpawnMarker
import com.tether.app.protocol.helpers.SpinnerWords
import com.tether.app.protocol.helpers.VersionCompare
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.time.ZoneOffset
import java.util.Locale

/**
 * T2.2: the call adapters — how each recorded `fn(...args)` maps onto its Kotlin port. The corpus
 * environment is TZ=UTC (helpers that read the host zone get [UTC]); `nowMs` pins Date.now.
 * Results come back as Kotlin values and are encoded by [CanonicalJson.encodeTagged]; a JS
 * `undefined` result is returned as [Undefined].
 */
object HelperAdapters {

    /** The decoded arguments of one call. Missing trailing arguments are `undefined`. */
    class Args(private val args: List<Any?>, private val nowMs: Double?, val constants: JsObj) {
        val size get() = args.size

        fun raw(i: Int): Any? = if (i < args.size) args[i] else Undefined

        /** A JSON-tree argument; `undefined` (tagged or absent) is Kotlin null. */
        fun v(i: Int): JsValue? = when (val a = raw(i)) {
            Undefined -> null
            is JsValue -> a
            else -> error("argument $i is ${a?.let { it::class.simpleName }}, not a JSON value")
        }

        fun num(i: Int): Double = (v(i) as JsNum).value
        fun str(i: Int): String = (v(i) as JsStr).value
        fun arr(i: Int): JsArr = v(i) as JsArr
        fun isUndefined(i: Int): Boolean = raw(i) === Undefined

        /** A Set (or an array standing in for one, as the web's `new Set(x)` accepts). */
        fun collection(i: Int): Collection<JsValue> = when (val a = raw(i)) {
            is TaggedSet -> a.values.map { it as JsValue }
            is JsArr -> a
            Undefined -> emptyList()
            else -> error("argument $i is not a Set/array: $a")
        }

        val now: Double get() = nowMs ?: error("case has no nowMs")
    }

    private val UTC = ZoneOffset.UTC
    private val EN_US: Locale = Locale.US

    private fun undef(value: Any?): Any? = value ?: Undefined

    private val adapters: Map<String, (Args) -> Any?> = buildMap {
        fun reg(table: String, fn: String, call: (Args) -> Any?) = put("$table.$fn", call)

        // lib/attachment-draft.ts
        reg("attachment-draft", "humanSize") { AttachmentDraft.humanSize(it.num(0)) }
        reg("attachment-draft", "isImageType") { AttachmentDraft.isImageType(it.v(0)) }
        reg("attachment-draft", "textToBase64") { AttachmentDraft.textToBase64(it.str(0)) }
        reg("attachment-draft", "toWireAttachments") { undef(AttachmentDraft.toWireAttachments(it.arr(0))) }

        // components/chat-view.tsx (extracted /model rule)
        reg("chat-view-model-command", "looksLikeModelId") { ChatViewModelCommand.looksLikeModelId(it.str(0)) }
        reg("chat-view-model-command", "resolveModelArg") {
            ChatViewModelCommand.resolveModelArg(it.str(0), it.constants["resolveModelArgModels"] as JsArr)
        }

        // lib/claude-reset-grants-view.mjs
        reg("claude-reset-grants-view", "windowListLabel") { ClaudeResetGrantsView.windowListLabel(it.v(0)) }
        reg("claude-reset-grants-view", "durationLabel") { ClaudeResetGrantsView.durationLabel(it.num(0)) }
        reg("claude-reset-grants-view", "resetGrantView") { ClaudeResetGrantsView.resetGrantView(it.v(0), it.v(1), it.num(2)) }
        reg("claude-reset-grants-view", "resetGrantsHeadline") { ClaudeResetGrantsView.resetGrantsHeadline(it.v(0), it.num(1)) }
        reg("claude-reset-grants-view", "ineligibleReasonLabel") { ClaudeResetGrantsView.ineligibleReasonLabel(it.v(0)) }
        reg("claude-reset-grants-view", "claimClearsList") { ClaudeResetGrantsView.claimClearsList(it.v(0)) }
        reg("claude-reset-grants-view", "claimReasonLabel") { ClaudeResetGrantsView.claimReasonLabel(it.v(0)) }
        reg("claude-reset-grants-view", "claimOutcomeCopy") { ClaudeResetGrantsView.claimOutcomeCopy(it.v(0), it.num(1), EN_US, UTC) }

        // lib/codex-mode-presets.mjs
        reg("codex-mode-presets", "codexModePreset") { CodexModePresets.codexModePreset(it.v(0)) }
        reg("codex-mode-presets", "codexModeForSession") { CodexModePresets.codexModeForSession(it.v(0)) }

        // lib/conversation-story-points.ts (the web's own 220/260 limits)
        reg("conversation-story-points", "storyPointsFromSession") { a ->
            ConversationStoryPoints.storyPointsFromSession(a.v(0)).map { p ->
                mapOf("turnId" to p.turnId, "blockId" to p.blockId, "prompt" to p.prompt, "reply" to p.reply, "ts" to p.ts)
                    .let { TaggedObject(it) }
            }
        }
        reg("conversation-story-points", "storyPointTimeLabel") { a ->
            ConversationStoryPoints.storyPointTimeLabel(if (isNullish(a.v(0))) null else a.num(0), UTC)
        }

        // lib/deepseek-peak.mjs
        reg("deepseek-peak", "deepSeekModelTier") { DeepseekPeak.deepSeekModelTier(it.v(0)) }
        reg("deepseek-peak", "deepSeekApiTier") { DeepseekPeak.deepSeekApiTier(it.v(0), it.v(1)) }
        reg("deepseek-peak", "deepSeekLiveModel") { DeepseekPeak.deepSeekLiveModel(it.v(0), it.v(1), it.v(2)) }
        reg("deepseek-peak", "isDeepSeekApiSession") { DeepseekPeak.isDeepSeekApiSession(it.v(0), it.v(1)) }
        reg("deepseek-peak", "deepSeekPeakAt") { a ->
            when (val at = a.raw(0)) {
                is TaggedDate -> DeepseekPeak.deepSeekPeakAtDate(at.ms)
                else -> DeepseekPeak.deepSeekPeakAt(a.v(0))
            }
        }
        reg("deepseek-peak", "deepSeekCountdown") { DeepseekPeak.deepSeekCountdown(it.v(0)) }
        reg("deepseek-peak", "deepSeekPeakCopy") { a ->
            when (val at = a.raw(0)) {
                is TaggedDate -> DeepseekPeak.deepSeekPeakCopyDate(at.ms)
                else -> DeepseekPeak.deepSeekPeakCopy(a.v(0))
            }
        }
        reg("deepseek-peak", "deepSeekRatesForTier") { DeepseekPeak.deepSeekRatesForTier(it.v(0)) }
        reg("deepseek-peak", "deepSeekRatesForModel") { DeepseekPeak.deepSeekRatesForModel(it.v(0)) }
        reg("deepseek-peak", "deepSeekRateVerified") { DeepseekPeak.deepSeekRateVerified(it.v(0), it.v(1)) }
        reg("deepseek-peak", "deepSeekRateInForce") { DeepseekPeak.deepSeekRateInForce(it.v(0), it.v(1)) }

        // lib/draft-form.ts
        reg("draft-form", "defaultModeFor") { DraftForm.defaultModeFor(it.v(0)) }
        reg("draft-form", "buildWorkspaceQuickPicks") { DraftForm.buildWorkspaceQuickPicks(it.v(0)) }
        reg("draft-form", "buildWorktreeCreateRequest") { DraftForm.buildWorktreeCreateRequest(it.v(0)) }
        reg("draft-form", "mergeDraftPreferences") { DraftForm.mergeDraftPreferences(it.v(0), it.v(1), it.v(2)) }
        reg("draft-form", "customModelIdsFor") { DraftForm.customModelIdsFor(it.v(0), it.v(1)) }
        reg("draft-form", "addCustomModelPref") { undef(DraftForm.addCustomModelPref(it.v(0), it.v(1), it.v(2))) }
        reg("draft-form", "removeCustomModelPref") { undef(DraftForm.removeCustomModelPref(it.v(0), it.v(1), it.v(2))) }
        reg("draft-form", "mergeCustomModelsIntoEntries") { DraftForm.mergeCustomModelsIntoEntries(it.v(0), it.v(1)) }
        reg("draft-form", "replyIsFresh") { DraftForm.replyIsFresh(it.v(0), it.v(1)) }
        reg("draft-form", "replyMatchesRequest") { DraftForm.replyMatchesRequest(it.v(0), it.v(1)) }
        reg("draft-form", "resolveDefaultModelId") { DraftForm.resolveDefaultModelId(it.v(0), it.v(1)) }
        reg("draft-form", "resolveCanonicalModelId") { DraftForm.resolveCanonicalModelId(it.v(0), it.v(1)) }
        reg("draft-form", "resolveReasoningEffortForModel") { DraftForm.resolveReasoningEffortForModel(it.v(0), it.v(1), it.v(2)) }
        reg("draft-form", "resolveDraftForm") { DraftForm.resolveDraftForm(it.v(0), it.v(1), it.v(2)) }
        reg("draft-form", "reduceDraftForm") { undef(DraftForm.reduceDraftForm(it.v(0), it.v(1))) }

        // lib/elapsed.mjs
        reg("elapsed", "elapsedLabel") { Elapsed.elapsedLabel(it.v(0)) }

        // lib/format.ts
        reg("format", "compactPath") { Format.compactPath(it.str(0), it.str(1)) }
        reg("format", "projectName") { Format.projectName(it.str(0)) }
        reg("format", "relativeTime") { Format.relativeTime(it.num(0), it.now, EN_US, UTC) }
        reg("format", "compactNumber") { Format.compactNumber(it.v(0)) }
        reg("format", "resetTime") { Format.resetTime(it.v(0), it.now) }
        reg("format", "clockTime") { Format.clockTime(it.v(0), EN_US, UTC) }
        reg("format", "providerGlyph") { Format.providerGlyph(it.v(0)) }

        // lib/history-origin.mjs
        reg("history-origin", "codexOriginFromOriginator") { HistoryOrigin.codexOriginFromOriginator(it.v(0)) }
        reg("history-origin", "claudeOriginFromEntrypoint") { HistoryOrigin.claudeOriginFromEntrypoint(it.v(0)) }
        reg("history-origin", "claudeEntrypointFromRecords") { HistoryOrigin.claudeEntrypointFromRecords(it.v(0)) }
        reg("history-origin", "historyOriginOf") { HistoryOrigin.historyOriginOf(it.v(0)) }
        reg("history-origin", "isHideableAgentCliRun") { HistoryOrigin.isHideableAgentCliRun(it.v(0)) }
        reg("history-origin", "isLinkedAgentCliRun") { HistoryOrigin.isLinkedAgentCliRun(it.v(0)) }
        reg("history-origin", "partitionAgentCliRuns") { a ->
            // `{ enabled = true, querying = false, counts = () => true } = {}`: defaults apply to undefined.
            val options: Map<String, Any?> = when (val o = a.raw(1)) {
                is TaggedObject -> o.props
                is JsObj -> o
                Undefined -> emptyMap()
                else -> error("partitionAgentCliRuns options: $o")
            }
            fun flag(key: String, default: Boolean) = when (val x = options[key]) {
                null, Undefined -> default
                is JsValue -> truthy(x)
                else -> true
            }
            val counts: (JsValue?) -> Boolean = when (val c = options["counts"]) {
                null, Undefined -> { _ -> true }
                is TaggedFn -> { entry -> truthy(c(entry)) }
                else -> error("counts: $c")
            }
            val result = HistoryOrigin.partitionAgentCliRuns(a.v(0), flag("enabled", true), flag("querying", false), counts)
            TaggedObject(mapOf("rows" to result.rows, "hiddenByWorkspace" to TaggedMap(result.hiddenByWorkspace.map { (k, v) -> k to v })))
        }

        // lib/interrupt-notice.mjs
        reg("interrupt-notice", "interruptedNoticeCopy") { InterruptNotice.interruptedNoticeCopy(it.v(0), UTC) }
        reg("interrupt-notice", "interruptNoticesByTurn") { InterruptNotice.interruptNoticesByTurn(it.v(0)) }

        // lib/message-time.mjs
        reg("message-time", "messageClockTime") { MessageTime.messageClockTime(it.v(0), UTC) }

        // lib/mode-cycle.mjs
        reg("mode-cycle", "nextModeValue") { ModeCycle.nextModeValue(it.v(0), it.v(1)) }

        // lib/model-browser-view.mjs
        reg("model-browser-view", "buildSyntheticDefaultRow") { ModelBrowserView.buildSyntheticDefaultRow(it.v(0)) }
        reg("model-browser-view", "getProviderModelRows") { ModelBrowserView.getProviderModelRows(it.v(0)) }
        reg("model-browser-view", "getAllProviderModelRows") { ModelBrowserView.getAllProviderModelRows(it.v(0)) }
        reg("model-browser-view", "filterAndRankModelRows") { ModelBrowserView.filterAndRankModelRows(it.v(0), it.v(1)) }
        reg("model-browser-view", "buildProviderQualifiedDescription") { undef(ModelBrowserView.buildProviderQualifiedDescription(it.v(0))) }
        reg("model-browser-view", "resolveModelBrowserAllView") { ModelBrowserView.resolveModelBrowserAllView(it.v(0), it.v(1)) }
        reg("model-browser-view", "resolveInitialModelBrowserView") { ModelBrowserView.resolveInitialModelBrowserView(it.v(0), it.v(1)) }
        reg("model-browser-view", "resolveSelectedModelLabel") { undef(ModelBrowserView.resolveSelectedModelLabel(it.v(0), it.v(1), it.v(2))) }

        // lib/model-id.mjs
        reg("model-id", "modelsDiverge") { ModelId.modelsDiverge(it.v(0), it.v(1)) }

        // lib/model-picker.mjs
        reg("model-picker", "stripJsonc") { ModelPicker.stripJsonc(it.v(0)) }
        reg("model-picker", "effortVariants") { ModelPicker.effortVariants(it.v(0), it.v(1)) }
        reg("model-picker", "claudeEffortVariants") { ModelPicker.claudeEffortVariants(it.v(0), it.v(1)) }
        reg("model-picker", "claudeVersionSuffix") { ModelPicker.claudeVersionSuffix(it.v(0)) }
        reg("model-picker", "modelDisplayName") { ModelPicker.modelDisplayName(it.v(0), it.v(1)) }
        reg("model-picker", "inheritDefaultRowVariants") { ModelPicker.inheritDefaultRowVariants(it.v(0)) }
        reg("model-picker", "collapseDefaultModelRow") { ModelPicker.collapseDefaultModelRow(it.v(0), it.v(1)) }
        reg("model-picker", "resolveDefaultEffort") { ModelPicker.resolveDefaultEffort(it.v(0), it.v(1)) }
        reg("model-picker", "buildOpenCodeModelOptions") { ModelPicker.buildOpenCodeModelOptions(it.v(0), it.v(1), it.v(2)) }
        reg("model-picker", "baseModelId") { ModelPicker.baseModelId(it.v(0)) }
        reg("model-picker", "legacyModelRows") { ModelPicker.legacyModelRows(it.v(0), it.v(1), it.v(2)) }
        reg("model-picker", "groupModelOptions") { ModelPicker.groupModelOptions(it.v(0), it.v(1)) }
        reg("model-picker", "reconcileModelCatalog") { ModelPicker.reconcileModelCatalog(it.v(0), it.v(1)) }
        reg("model-picker", "mergeModelCatalog") { ModelPicker.mergeModelCatalog(it.v(0), it.v(1)) }
        reg("model-picker", "isSyntheticModel") { ModelPicker.isSyntheticModel(it.v(0)) }
        reg("model-picker", "modelReadingLabel") { ModelPicker.modelReadingLabel(it.v(0)) }
        reg("model-picker", "servedModelMatches") { ModelPicker.servedModelMatches(it.v(0), it.v(1)) }
        reg("model-picker", "modelReading") { ModelPicker.modelReading(it.v(0)) }

        // lib/panel-widths.mjs
        reg("panel-widths", "parseStoredPanelWidth") { PanelWidths.parseStoredPanelWidth(it.v(0)) }
        reg("panel-widths", "panelWidthBounds") { PanelWidths.panelWidthBounds(it.v(0), it.v(1)) }
        reg("panel-widths", "clampPanelWidth") { PanelWidths.clampPanelWidth(it.v(0), it.v(1), it.v(2)) }
        reg("panel-widths", "panelWidthCss") { PanelWidths.panelWidthCss(it.v(0), it.v(1)) }
        reg("panel-widths", "panelWidthFromDrag") { PanelWidths.panelWidthFromDrag(it.v(0), it.num(1), it.num(2), it.v(3)) }
        reg("panel-widths", "panelWidthKeyDelta") { PanelWidths.panelWidthKeyDelta(it.v(0), it.v(1), truthy(it.v(2))) }

        // lib/pending-input.mjs
        reg("pending-input", "emptyStore") { PendingInput.emptyStore() }
        reg("pending-input", "acceptedKeys") { PendingInput.acceptedKeys(it.v(0)) }
        reg("pending-input", "addRecord") { PendingInput.addRecord(it.v(0), it.v(1), it.num(2)) }
        reg("pending-input", "ackKey") { PendingInput.ackKey(it.v(0), it.v(1)) }
        reg("pending-input", "discardKey") { PendingInput.discardKey(it.v(0), it.v(1)) }
        reg("pending-input", "editText") { PendingInput.editText(it.v(0), it.v(1), it.v(2)) }
        reg("pending-input", "reconcileWithSnapshot") { PendingInput.reconcileWithSnapshot(it.v(0), it.v(1), it.v(2)) }
        reg("pending-input", "dueRecords") { PendingInput.dueRecords(it.v(0), it.v(1)) }
        reg("pending-input", "sendableRecords") { PendingInput.sendableRecords(it.v(0), it.collection(1)) }
        reg("pending-input", "markSent") { PendingInput.markSent(it.v(0), it.collection(1), it.num(2)) }
        reg("pending-input", "resetInFlight") { PendingInput.resetInFlight(it.v(0)) }
        reg("pending-input", "oldestInFlightAge") { PendingInput.oldestInFlightAge(it.v(0), it.num(1)) }
        reg("pending-input", "describePending") { PendingInput.describePending(it.v(0), it.v(1)) }
        reg("pending-input", "expireRecords") { PendingInput.expireRecords(it.v(0), it.num(1)) }
        reg("pending-input", "toPersistable") { PendingInput.toPersistable(it.v(0), it.collection(1)) }
        reg("pending-input", "fromPersisted") { PendingInput.fromPersisted(it.v(0)) }
        reg("pending-input", "clearedFromPersisted") { PendingInput.clearedFromPersisted(it.v(0)) }
        reg("pending-input", "forgetSession") { PendingInput.forgetSession(it.v(0), it.v(1)) }
        reg("pending-input", "mergeStores") { PendingInput.mergeStores(it.v(0), it.v(1), it.collection(2)) }

        // lib/pending-workspace.mjs
        reg("pending-workspace", "beginIntent") { PendingWorkspace.beginIntent(it.v(0), it.v(1), it.num(2)) }
        reg("pending-workspace", "markSent") { PendingWorkspace.markSent(it.v(0), it.num(1)) }
        reg("pending-workspace", "resetInFlight") { PendingWorkspace.resetInFlight(it.v(0)) }
        reg("pending-workspace", "confirmIntent") { PendingWorkspace.confirmIntent(it.v(0), it.v(1)) }
        reg("pending-workspace", "inFlightAge") { PendingWorkspace.inFlightAge(it.v(0), it.num(1)) }
        reg("pending-workspace", "isDrainDue") { PendingWorkspace.isDrainDue(it.v(0), it.num(1)) }
        reg("pending-workspace", "describeIntent") { PendingWorkspace.describeIntent(it.v(0), it.v(1)) }

        // lib/sidebar-order.mjs
        reg("sidebar-order", "applySidebarOrder") { SidebarOrder.applySidebarOrder(it.arr(0), it.v(1)) }
        reg("sidebar-order", "sortSidebarEntries") { SidebarOrder.sortSidebarEntries(it.arr(0), it.v(1)) }
        reg("sidebar-order", "moveSidebarEntry") { SidebarOrder.moveSidebarEntry(it.arr(0), it.v(1), it.v(2)) }

        // lib/sidebar-workspaces.mjs
        reg("sidebar-workspaces", "sidebarWorkspaceList") { SidebarWorkspaces.sidebarWorkspaceList(it.v(0), it.v(1)) }
        reg("sidebar-workspaces", "pinWorkspace") { SidebarWorkspaces.pinWorkspace(it.v(0), it.v(1)) }
        reg("sidebar-workspaces", "effectivePinnedWorkspaces") { SidebarWorkspaces.effectivePinnedWorkspaces(it.v(0), it.v(1)) }
        reg("sidebar-workspaces", "workspaceGroupFor") { SidebarWorkspaces.workspaceGroupFor(it.v(0), it.v(1)) }
        reg("sidebar-workspaces", "paginateWorkspaceRows") { a ->
            val predicate = when (val f = a.raw(3)) {
                is TaggedFn -> { row: JsValue -> truthy(f(row)) }
                Undefined -> null
                else -> error("isSelected: $f")
            }
            SidebarWorkspaces.paginateWorkspaceRows(a.v(0), a.v(1), a.num(2), predicate)
        }

        // lib/spawn-marker.mjs
        reg("spawn-marker", "isValidMarkerId") { SpawnMarker.isValidMarkerId(it.v(0)) }
        reg("spawn-marker", "formatSpawnMarker") { SpawnMarker.formatSpawnMarker(it.v(0)) }
        reg("spawn-marker", "parseSpawnMarker") { a ->
            SpawnMarker.parseSpawnMarker(a.v(0))?.let { mapOf("parentSessionId" to it.parentSessionId, "spawnKey" to it.spawnKey) }
                ?.let { TaggedObject(it) }
        }
        reg("spawn-marker", "parentSpawnMarkerEnv") { a -> TaggedObject(SpawnMarker.parentSpawnMarkerEnv(a.v(0))) }

        // lib/spinner-words.mjs
        reg("spinner-words", "spinnerWordFor") { SpinnerWords.spinnerWordFor(it.v(0), it.v(1)) }

        // lib/version-compare.mjs
        reg("version-compare", "compareVersionTriples") { VersionCompare.compareVersionTriples(it.v(0), it.v(1)) }
    }

    fun has(table: String, fn: String): Boolean = "$table.$fn" in adapters

    fun call(table: String, fn: String, args: Args): Any? =
        (adapters["$table.$fn"] ?: error("no Kotlin adapter for $table.$fn"))(args)

    /** Every adapter key, for the "no orphan adapter / no unported fn" check. */
    val keys: Set<String> get() = adapters.keys
}
