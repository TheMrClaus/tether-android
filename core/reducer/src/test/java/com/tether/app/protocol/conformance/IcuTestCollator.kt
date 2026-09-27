package com.tether.app.protocol.conformance

import com.ibm.icu.text.Collator
import com.ibm.icu.util.ULocale
import com.tether.app.protocol.helpers.JsCollator

/**
 * T2.2: `localeCompare` for JVM tests — ICU4J's default collator for en-US (the corpus host's
 * `new Intl.Collator().resolvedOptions()`: en-US, sensitivity "variant" = tertiary, punctuation
 * not ignored). Android production uses android.icu the same way (feature/chat IcuJsCollator).
 */
object IcuTestCollator {
    private val icu: Collator = Collator.getInstance(ULocale.forLanguageTag("en-US")).apply { strength = Collator.TERTIARY }.freeze()

    val EN_US: JsCollator = JsCollator { a, b -> icu.compare(a, b) }
}
