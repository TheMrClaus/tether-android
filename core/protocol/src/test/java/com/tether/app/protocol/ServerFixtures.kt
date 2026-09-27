package com.tether.app.protocol

/**
 * Hand-authored frames for the server types the corpus cannot contain
 * (parity-corpus/wire/manifest.json `serverTypesMissing`), written from the
 * v129 lib/protocol.ts shapes:
 *  - `approval` / `approval_resolved`: declared in ServerMessage but never sent by
 *    server.mjs at 7d65611 (approvals arrive as `event` frames);
 *  - `acp-agents`: retired server-side (the request is answered with `error`).
 */
object ServerFixtures {
    const val APPROVAL = """
        {"type":"approval","sessionId":"sess-1","requestId":"req-1","toolId":"toolu_1","name":"Bash",
         "input":{"command":"rm -rf /tmp/x"},
         "choices":[{"choiceId":"allow","label":"Allow","description":"Run it once","permissionGrant":"exact"},
                    {"choiceId":"deny","label":"Deny"}],
         "metadata":{"provider":"codex","kind":"command","command":"rm -rf /tmp/x","cwd":"/w",
                     "requestedPermissions":{"fileSystem":{"write":["/tmp/x"]}}}}
    """

    const val APPROVAL_RESOLVED = """{"type":"approval_resolved","sessionId":"sess-1","requestId":"req-1","choiceId":"allow"}"""

    const val ACP_AGENTS = """
        {"type":"acp-agents","agents":[{"id":"gemini","label":"Gemini CLI","command":"gemini",
         "args":["--experimental-acp"],"enabled":true}]}
    """

    val HAND_AUTHORED: Map<String, String> = mapOf(
        "approval" to APPROVAL,
        "approval_resolved" to APPROVAL_RESOLVED,
        "acp-agents" to ACP_AGENTS,
    )
}
