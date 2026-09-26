package com.tether.app.protocol.reduce

/**
 * Port of aidash/lib/model-id.mjs — did the model that actually served the
 * last turn demonstrably differ from the operator-selected one?
 *
 * The two sides come from different vocabularies, so equality is judged on a
 * normalized form (lowercase, alphanumerics only) with containment either way
 * counting as a match. Deliberately conservative: with either side missing it
 * returns false — the badge must never cry wolf.
 */
fun modelsDiverge(selected: String?, served: String?): Boolean {
    val a = normalizeModelId(selected)
    val b = normalizeModelId(served)
    if (a.isEmpty() || b.isEmpty()) return false
    return a != b && !a.contains(b) && !b.contains(a)
}

private val NON_ALNUM = Regex("[^a-z0-9]")

private fun normalizeModelId(value: String?): String =
    value?.lowercase()?.replace(NON_ALNUM, "") ?: ""
