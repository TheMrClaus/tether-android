package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsObj

// T2.1 unit B (notices): background_interrupted/background_abandoned, turn_interrupted,
// external_advancement and notice_dismissed 2753-2862; provider_notice 2047-2071 with 1059;
// context_compacted 1968-1985. The dismissal helpers (dismiss keys, isNoticeDismissed,
// applyNoticeDismissal) are shared and live in TurnOps.kt (H1).
internal fun foldNotices(state: JsObj, event: JsObj, type: String): JsObj = when (type) {
    else -> state // not yet ported (unit B)
}
