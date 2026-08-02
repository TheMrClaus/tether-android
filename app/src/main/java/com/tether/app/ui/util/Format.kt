package com.tether.app.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/** Ports of aidash lib/format.ts + lib/elapsed.mjs — keep the copy identical. */

fun statusCopy(status: String): String = when (status) {
    "ready" -> "Ready"
    "active" -> "Active"
    "waiting" -> "Needs you"
    "exited" -> "Ended"
    else -> status
}

fun providerGlyph(provider: String?): String = when (provider) {
    "claude" -> "C"
    "codex" -> "X"
    "gemini" -> "G"
    "opencode" -> "O"
    else -> "?"
}

fun projectName(path: String): String =
    path.split("/").lastOrNull { it.isNotBlank() } ?: "Root"

fun compactPath(value: String?, root: String?): String {
    if (value.isNullOrEmpty()) return "—"
    return if (!root.isNullOrEmpty() && value.startsWith(root)) value.replaceFirst(root, "~") else value
}

/** "now" / "5m" / "3h" / "2d" / "Jul 4" — relative to [now] millis. */
fun relativeTime(timestamp: Long, now: Long): String {
    val seconds = max(0L, (now - timestamp) / 1000)
    if (seconds < 60) return "now"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h"
    val days = hours / 24
    if (days < 7) return "${days}d"
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
}

/** Compact elapsed label: "42s", "3m 7s", then a clock past an hour. */
fun elapsedLabel(seconds: Long?): String {
    if (seconds == null || seconds < 0) return ""
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    val remainder = seconds % 60
    if (minutes < 60) return if (remainder > 0) "${minutes}m ${remainder}s" else "${minutes}m"
    val hours = minutes / 60
    return "%d:%02d:%02d".format(hours, minutes % 60, remainder)
}

/** Intl compact-notation equivalent: 950 -> "950", 1234 -> "1.2K", 2_400_000 -> "2.4M". */
fun compactNumber(value: Long?): String {
    if (value == null) return "—"
    val abs = if (value < 0) -value else value
    if (abs < 1000) return value.toString()
    val units = listOf(1_000_000_000L to "B", 1_000_000L to "M", 1_000L to "K")
    for ((divisor, suffix) in units) {
        if (abs >= divisor) {
            val scaled = floor(value.toDouble() / divisor * 10) / 10
            return if (scaled == floor(scaled)) "${scaled.toLong()}$suffix" else "$scaled$suffix"
        }
    }
    return value.toString()
}

fun tokenLabel(value: Long): String = "${compactNumber(value)} ${if (value == 1L) "token" else "tokens"}"

// --- Spinner vocabulary (lib/spinner-words.mjs, transcribed verbatim) ---

private val SPINNER_WORDS = listOf(
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

/** FNV-1a, same avalanche as the web client so both show the same word. */
private fun fnv1a(text: String): Long {
    var value = 0x811c9dc5L
    for (ch in text) {
        value = value xor ch.code.toLong()
        value = (value * 0x01000193L) and 0xffffffffL
    }
    return value
}

/** The spinner word for run [runIndex] of [turnId] — keyed on the RUN, never a clock. */
fun spinnerWordFor(turnId: String?, runIndex: Int?): String {
    val run = if (runIndex != null && runIndex > 0) runIndex else 0
    val seed = fnv1a(turnId ?: "")
    val index = ((seed + run.toLong() * 47L) % SPINNER_WORDS.size).toInt()
    return SPINNER_WORDS[index]
}
