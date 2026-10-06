package com.tether.app.ui.chat

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.tether.app.protocol.DelegateMention

/**
 * ta-coik.20: the composer's non-secret form state, saved across a configuration change (a rotation
 * keeps what was typed and picked, as a browser resize keeps a page's inputs). Nothing here is a
 * secret; see `rememberRetained` for those.
 */
internal val DelegateMentionSaver: Saver<DelegateMention?, Any> = listSaver(
    save = { m -> if (m == null) emptyList() else listOf(m.provider, m.mode, m.model.orEmpty(), m.reasoningEffort.orEmpty()) },
    restore = { v ->
        if (v.size != 4) null else DelegateMention(v[0] as String, v[1] as String, (v[2] as String).ifEmpty { null }, (v[3] as String).ifEmpty { null })
    },
)

/** A nullable [SheetView] by name; an unknown name restores as closed. */
internal val SheetViewSaver: Saver<SheetView?, Any> = Saver(
    save = { it?.name ?: "" },
    restore = { name -> SheetView.entries.firstOrNull { it.name == name as String } },
)

/** A [SheetView] by name (the settings sheet's current view). */
internal val SheetViewRequiredSaver: Saver<SheetView, Any> = Saver(
    save = { it.name },
    restore = { name -> SheetView.entries.firstOrNull { it.name == name as String } ?: SheetView.Root },
)
