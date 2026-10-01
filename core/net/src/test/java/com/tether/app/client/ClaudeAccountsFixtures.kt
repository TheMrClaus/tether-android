package com.tether.app.client

/**
 * Bodies in the shapes tether 887c222 sends (server.mjs `claudeAccountsWithPlan` over
 * lib/claude-accounts.mjs `listAccounts`, lib/claude-account-plan.mjs `toPlan`, `accountStatus`,
 * `readSyncConfig` / `syncClaudeAccounts`). Every `plan.raw` carries [RAW_SENTINEL]: it must never
 * come out the other side.
 */
object ClaudeAccountsFixtures {
    /** Planted in every `raw` (and its keys): a decoded value, string or toString that holds it is a leak. */
    const val RAW_SENTINEL = "RAW-SENTINEL-team_tier_1-default_raven-7f3c"

    val LIST_JSON = """
        {"accounts":[
          {"id":"claude-default","label":"Claude Code (default)","enabled":true,"configDir":"/srv/tether/.claude","hasConfigDir":true,"managed":false,"imported":true,"syncEligible":true,
           "plan":{"label":"Team Premium 5x","organizationName":"Brainrocket","source":"profile",
                   "raw":{"seatTier":"team_tier_1","rateLimitTier":"default_claude_max_5x","organizationType":"claude_team","organizationName":"Brainrocket","hasClaudeMax":false,"probe":"$RAW_SENTINEL","$RAW_SENTINEL":true}}},
          {"id":"claude-work","label":"Claude Code (work)","enabled":true,"configDir":"/srv/tether/state/claude-accounts/claude-work","hasConfigDir":true,"managed":true,"imported":false,"syncEligible":true,
           "plan":{"label":"Team Standard","source":"credentials","raw":{"subscriptionType":"team","rateLimitTier":"default_raven","probe":"$RAW_SENTINEL"}}},
          {"id":"claude-fresh","label":"Claude Code (fresh)","enabled":true,"configDir":"/srv/tether/state/claude-accounts/claude-fresh","hasConfigDir":true,"managed":true,"imported":false,"syncEligible":true,"plan":null}
        ]}
    """.trimIndent()

    val LIST = listOf(
        ClaudeAccount(
            id = "claude-default", label = "Claude Code (default)", enabled = true, configDir = "/srv/tether/.claude", hasConfigDir = true,
            managed = false, imported = true, syncEligible = true,
            plan = ClaudeAccountPlan("Team Premium 5x", "Brainrocket", ClaudeAccountPlanSource.Profile),
        ),
        ClaudeAccount(
            id = "claude-work", label = "Claude Code (work)", enabled = true, configDir = "/srv/tether/state/claude-accounts/claude-work", hasConfigDir = true,
            managed = true, imported = false, syncEligible = true,
            plan = ClaudeAccountPlan("Team Standard", null, ClaudeAccountPlanSource.Credentials),
        ),
        ClaudeAccount(
            id = "claude-fresh", label = "Claude Code (fresh)", enabled = true, configDir = "/srv/tether/state/claude-accounts/claude-fresh", hasConfigDir = true,
            managed = true, imported = false, syncEligible = true, plan = null,
        ),
    )

    const val STATUS_LOGGED_IN_JSON = """{"ok":true,"id":"claude-work","loggedIn":true,"authMethod":"claude.ai","email":"work@example.com"}"""
    val STATUS_LOGGED_IN = ClaudeAccountStatus(loggedIn = true, authMethod = "claude.ai", email = "work@example.com", error = null)

    const val SYNC_JSON = """
        {"config":{"mode":"all","categories":{"plugins":true,"skills":true,"hooks":true,"mcp":true},"primaryAccountId":"claude-default"},
         "lastResult":{"ranAt":1790000000000,"status":"ok","primaryAccountId":"claude-default","entries":[
           {"accountId":"claude-work","category":"skills","kind":"dir","path":"skills","status":"linked"},
           {"accountId":"claude-work","category":"plugins","kind":"dir","path":"plugins","status":"up-to-date"},
           {"accountId":"claude-work","category":"mcp","kind":"field","path":".claude.json","status":"merged"},
           {"accountId":"claude-work","category":"hooks","kind":"field","path":"settings.json","status":"up-to-date"}]}}
    """
    val SYNC = ClaudeAccountsSync(
        ClaudeSyncConfig(ClaudeSyncMode.All, ClaudeSyncCategories(plugins = true, skills = true, mcp = true, hooks = true), "claude-default"),
        ClaudeSyncResult(ranAt = 1_790_000_000_000L, status = "ok", changed = 2, upToDate = 2, error = null),
    )
}
