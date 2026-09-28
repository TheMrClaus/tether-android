package com.tether.app.protocol

/**
 * Hand-authored frames for the server types the corpus cannot contain
 * (parity-corpus/wire/manifest.json `serverTypesMissing`), written from the
 * lib/protocol.ts shapes:
 *  - `approval` / `approval_resolved`: declared in ServerMessage but never sent by
 *    server.mjs at 7d65611 (approvals arrive as `event` frames);
 *  - `acp-agents`: retired server-side (the request is answered with `error`);
 *  - `overview-snapshot` / `overview-delta` (v131/v132): sent only to a subscribed socket.
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

    /**
     * v131 opt-in Overview feed (lib/protocol.ts @ 79c3d37 OverviewSnapshotBody): only a socket
     * that sent `overview-subscribe` receives it, and the capture's scenarios never subscribe.
     * Carries every card field, a v132 `workspace` on one activity row and a pre-v132 row without.
     */
    const val OVERVIEW_SNAPSHOT = """
        {"type":"overview-snapshot","feedId":"feed-1","cursor":7,"generatedAt":1790000009000,
         "activitySince":1790000000000,
         "filters":{"workspaces":["/workspace/project"],"providers":["claude"],"statuses":["running","waiting"]},
         "page":0,"pageSize":24,"pageCount":1,"totalCards":1,
         "counts":{"running":0,"waiting":1,"attention":0,"ready":2,"workspaces":1,"total":3},
         "facets":{"workspaces":[{"key":"/workspace/project","label":"project","count":3}],
                   "providers":[{"key":"claude","provider":"claude","label":"Claude","count":3}]},
         "cards":[{"sessionId":"sess-1","nodeId":"node-1","seq":42,"title":"Fix the build","provider":"claude",
                   "profileId":"work","providerLabel":"Claude · work",
                   "workspace":{"key":"/workspace/project","label":"project"},"cwd":"/workspace/project",
                   "branch":"main","status":"waiting","statusSince":1790000005000,"turnStartedAt":1790000001000,
                   "startedAt":1789990000000,"lastActivityAt":1790000005000,
                   "excerpt":{"kind":"request","text":"Bash: npm test"},
                   "progress":{"done":2,"total":5,"label":"Run tests"},
                   "attention":{"kind":"approval","label":"Needs approval","since":1790000005000},
                   "pending":[{"sessionId":"sess-1","requestId":"req-1","kind":"approval","createdAt":1790000005000,
                               "title":"Fix the build","provider":"claude","summary":"Bash","detail":"npm test"}],
                   "parentSessionId":"sess-0","spawnedRunsActive":1}],
         "pending":{"items":[{"sessionId":"sess-1","requestId":"req-1","kind":"approval","createdAt":1790000005000,
                              "title":"Fix the build","provider":"claude","summary":"Bash"}],
                    "total":1,"outsideFilters":0},
         "activity":[{"id":"node-1:sess-1:42:request","ts":1790000005000,"sessionId":"sess-1","nodeId":"node-1",
                      "seq":42,"title":"Fix the build","provider":"claude","workspace":"project",
                      "kind":"request","text":"Bash: npm test"},
                     {"id":"node-1:sess-1:40:tool_started","ts":1790000004000,"sessionId":"sess-1","nodeId":"node-1",
                      "seq":40,"title":"Fix the build","provider":"claude","kind":"tool_started","text":"Bash"}]}
    """

    /** v131 Overview delta: the next step after [OVERVIEW_SNAPSHOT] (prevCursor = its cursor). */
    const val OVERVIEW_DELTA = """
        {"type":"overview-delta","feedId":"feed-1","cursor":8,"prevCursor":7,
         "upserts":[{"sessionId":"sess-1","nodeId":"node-1","seq":43,"title":"Fix the build","provider":"claude",
                     "providerLabel":"Claude","workspace":{"key":"/workspace/project","label":"project"},
                     "cwd":"/workspace/project","status":"running","pending":[]}],
         "removals":["sess-9"],
         "counts":{"running":1,"waiting":0,"attention":0,"ready":2,"workspaces":1,"total":3},
         "pending":{"items":[],"total":0,"outsideFilters":0},
         "activity":[{"id":"node-1:sess-1:43:tool_result","ts":1790000006000,"sessionId":"sess-1","nodeId":"node-1",
                      "seq":43,"title":"Fix the build","provider":"claude","workspace":"project",
                      "kind":"tool_result","text":"Bash finished"}]}
    """

    val HAND_AUTHORED: Map<String, String> = mapOf(
        "approval" to APPROVAL,
        "approval_resolved" to APPROVAL_RESOLVED,
        "acp-agents" to ACP_AGENTS,
        "overview-snapshot" to OVERVIEW_SNAPSHOT,
        "overview-delta" to OVERVIEW_DELTA,
    )
}
