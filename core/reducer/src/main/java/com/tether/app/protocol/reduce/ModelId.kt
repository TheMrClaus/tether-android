package com.tether.app.protocol.reduce

import com.tether.app.protocol.helpers.ModelId

/**
 * Port of aidash/lib/model-id.mjs — did the model that actually served the
 * last turn demonstrably differ from the operator-selected one?
 *
 * The two sides come from different vocabularies, so equality is judged on a
 * normalized form (lowercase, alphanumerics only) with containment either way
 * counting as a match. Deliberately conservative: with either side missing it
 * returns false — the badge must never cry wolf.
 *
 * T2.2: delegates to the corpus-verified port, [ModelId.modelsDiverge] (helpers package).
 */
fun modelsDiverge(selected: String?, served: String?): Boolean = ModelId.modelsDiverge(selected, served)
