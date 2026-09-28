package com.tether.app.protocol.legacy

/**
 * T2.1D Revision 9: every place the v128 fold + legacy adapter legitimately differs from what the
 * v40 reducer produced for the v40 test inputs — each with the events.mjs line that makes v128
 * behave this way. Anything not listed fails [V40Parity]'s step comparison.
 */
object V40Differences {

    class Allowed(val testClass: String, val method: String, val path: Regex, val why: String)

    val ALLOWED: List<Allowed> = listOf(
        Allowed(
            "*",
            "*",
            Regex("""\$\.queuedMessages\[\d+]\.flushMode"""),
            "T7.1: the typed QueuedMessage carries v128's flushMode (issue #47/#183, null for the default end-of-turn flush); v40 had no such field",
        ),
    )

    fun allows(testClass: String, method: String, path: String): Boolean =
        ALLOWED.any { (it.testClass == testClass || it.testClass == "*") && (it.method == method || it.method == "*") && it.path.matches(path) }
}
