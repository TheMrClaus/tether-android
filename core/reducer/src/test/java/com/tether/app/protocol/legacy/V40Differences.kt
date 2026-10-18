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
        Allowed(
            "*",
            "*",
            Regex("""\$\.queuedMessages\[\d+]\.(origin|noticeKind)"""),
            "ta-ylh: the typed QueuedMessage carries v133's origin / noticeKind (issue #211, events.mjs queued_message_added; null when absent); v40 had no such fields",
        ),
        Allowed(
            "*",
            "*",
            Regex("""\$\.queuedMessages\[\d+]\.queuedAt"""),
            "T15.8: the typed QueuedMessage carries v136's queuedAt (issue #229, events.mjs:2968 queued_message_added; null unless a stamped deferred row); v40 had no such field",
        ),
        Allowed(
            "*",
            "*",
            Regex("""\$\.turnsById\["[^"]*"]\.blocksById\["[^"]*"]\.attachments\[\d+]\.(delivery|mediaRef)"""),
            "T7.4: the typed AttachmentMeta carries v112's delivery and mediaRef (lib/protocol.ts AttachmentMeta, null when the journal has none); v40 had no such fields",
        ),
        // ta-d3tj.10 (R1): the v152 fold writes these keys (web 19c56e25 events.ts); the typed projection carries them since W48
        // (core/protocol Projection.kt) and v40 had none of them.
        Allowed(
            "*",
            "*",
            Regex("""\$\.turnsById\["[^"]*"]\.(endedAt|errorAt)"""),
            "R1: the turn carries the journal-stamped end / first-error time (#256: events.ts:3572 turn_end endedAt, :3553-3554 error errorAt, a key only when the event is stamped); v40 had no such fields",
        ),
        Allowed(
            "*",
            "*",
            Regex("""\$\.sessionErrors(\[\d+](\.(seq|ts|message|cause))?)?"""),
            "R1: the durable record of every turn-less error (#256: events.ts:948 initial [], :1092 foldSessionError, :3544 the turn-less error branch); v40 only had lastError",
        ),
        Allowed(
            "*",
            "*",
            Regex("""\$\.withdrawnQueueIds(\[\d+])?"""),
            "R1: queued_message_removed reason \"withdrawn\" folds into withdrawnQueueIds (#249: events.ts:3761-3773); v40 had no such list",
        ),
        Allowed(
            "*",
            "*",
            Regex("""\$\.queuedMessages\[\d+]\.(held|fromSessionId|fromSessionName)"""),
            "R1: the typed QueuedMessage carries v147's held (events.ts:3734-3741) and v149's peer sender (events.ts:3714-3724, noticeKind peer/handover); v40 had no such fields",
        ),
    )

    // NOT listed, and why none is needed: backgroundTasks[].summary / outputFile (events.ts:2813-2833), the spawned-run summary
    // fields (events.ts:1676-1698) and the pi-dialog notice replace (events.ts:1214) have no typed home (core/protocol keeps them
    // in the raw tree only), and task reopen / terminal dedupe (events.ts:2759, :2818) change values of existing typed keys only.

    fun allows(testClass: String, method: String, path: String): Boolean =
        ALLOWED.any { (it.testClass == testClass || it.testClass == "*") && (it.method == method || it.method == "*") && it.path.matches(path) }
}
