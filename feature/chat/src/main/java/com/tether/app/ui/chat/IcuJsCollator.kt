package com.tether.app.ui.chat

import android.icu.text.Collator
import android.icu.util.ULocale
import com.tether.app.protocol.helpers.JsCollator
import java.util.Locale

/**
 * T2.2: the production [JsCollator] — `localeCompare` as the web's browser runs it: ICU's default
 * collator for the device locale (tertiary strength = Intl sensitivity "variant", punctuation and
 * spaces significant, no numeric collation). Pass it to the helpers that sort by label
 * (ModelBrowserView.filterAndRankModelRows, SidebarOrder.sortSidebarEntries).
 */
object IcuJsCollator {
    fun forLocale(locale: Locale = Locale.getDefault()): JsCollator {
        val collator = Collator.getInstance(ULocale.forLocale(locale)).apply { strength = Collator.TERTIARY }
        return JsCollator { a, b -> synchronized(collator) { collator.compare(a, b) } }
    }
}
