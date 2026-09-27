package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsValue
import kotlin.math.floor

/** T2.2: faithful port of lib/spinner-words.mjs (Claude Code's spinner vocabulary, verbatim). */
object SpinnerWords {

    // lib/spinner-words.mjs:14
    val SPINNER_WORDS: List<String> = listOf(
        "Accomplishing", "Actioning", "Actualizing", "Architecting", "Baking", "Beaming",
        "Beboppin'", "Befuddling", "Billowing", "Blanching", "Bloviating", "Boogieing",
        "Boondoggling", "Booping", "Bootstrapping", "Brewing", "Bunning", "Burrowing",
        "Calculating", "Canoodling", "Caramelizing", "Cascading", "Catapulting", "Cerebrating",
        "Channeling", "Channelling", "Choreographing", "Churning", "Clauding", "Coalescing",
        "Cogitating", "Combobulating", "Composing", "Computing", "Concocting", "Considering",
        "Contemplating", "Cooking", "Crafting", "Creating", "Crunching", "Crystallizing",
        "Cultivating", "Deciphering", "Deliberating", "Determining", "Dilly-dallying", "Discombobulating",
        "Doing", "Doodling", "Drizzling", "Ebbing", "Effecting", "Elucidating",
        "Embellishing", "Enchanting", "Envisioning", "Fermenting", "Fiddle-faddling", "Finagling",
        "Flambéing", "Flibbertigibbeting", "Flowing", "Flummoxing", "Fluttering", "Forging",
        "Forming", "Frolicking", "Frosting", "Gallivanting", "Galloping", "Garnishing",
        "Generating", "Gesticulating", "Germinating", "Gitifying", "Grooving", "Gusting",
        "Harmonizing", "Hashing", "Hatching", "Herding", "Honking", "Hullaballooing",
        "Hyperspacing", "Ideating", "Imagining", "Improvising", "Incubating", "Inferring",
        "Infusing", "Ionizing", "Jitterbugging", "Julienning", "Kneading", "Leavening",
        "Levitating", "Lollygagging", "Manifesting", "Marinating", "Meandering", "Metamorphosing",
        "Misting", "Moonwalking", "Moseying", "Mulling", "Mustering", "Musing",
        "Nebulizing", "Nesting", "Newspapering", "Noodling", "Nucleating", "Orbiting",
        "Orchestrating", "Osmosing", "Perambulating", "Percolating", "Perusing", "Philosophising",
        "Photosynthesizing", "Pollinating", "Pondering", "Pontificating", "Pouncing", "Precipitating",
        "Prestidigitating", "Processing", "Proofing", "Propagating", "Puttering", "Puzzling",
        "Quantumizing", "Razzle-dazzling", "Razzmatazzing", "Recombobulating", "Reticulating", "Roosting",
        "Ruminating", "Sautéing", "Scampering", "Schlepping", "Scurrying", "Seasoning",
        "Shenaniganing", "Shimmying", "Simmering", "Skedaddling", "Sketching", "Slithering",
        "Smooshing", "Sock-hopping", "Spelunking", "Spinning", "Sprouting", "Stewing",
        "Sublimating", "Swirling", "Swooping", "Symbioting", "Synthesizing", "Tempering",
        "Thinking", "Thundering", "Tinkering", "Tomfoolering", "Topsy-turvying", "Transfiguring",
        "Transmuting", "Twisting", "Undulating", "Unfurling", "Unravelling", "Vibing",
        "Waddling", "Wandering", "Warping", "Whatchamacalliting", "Whirlpooling", "Whirring",
        "Whisking", "Wibbling", "Working", "Wrangling", "Zesting", "Zigzagging",
    )

    // lib/spinner-words.mjs:49 — FNV-1a over UTF-16 code units (charCodeAt), Math.imul, >>> 0.
    private fun hash(text: String): Long {
        var value = 0x811c9dc5.toInt()
        for (ch in text) {
            value = value xor ch.code
            value *= 0x01000193 // Math.imul: the low 32 bits of the product
        }
        return value.toLong() and 0xffffffffL
    }

    // lib/spinner-words.mjs:75 — keyed on the RUN, never on a clock.
    fun spinnerWordFor(turnId: JsValue?, runIndex: JsValue?): String {
        val index = (runIndex as? JsNum)?.value
        val run = if (index != null && index.isFinite() && index > 0) floor(index) else 0.0
        val seed = hash(if (isNullish(turnId)) "" else jsToString(turnId))
        val slot = (seed.toDouble() + run * 47) % SPINNER_WORDS.size
        return SPINNER_WORDS[slot.toInt()]
    }
}
