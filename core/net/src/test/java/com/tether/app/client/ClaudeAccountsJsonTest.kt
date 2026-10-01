package com.tether.app.client

import com.tether.app.client.ClaudeAccountsFixtures.RAW_SENTINEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-9q2 / ta-ebc: the tolerant read of the three Claude-accounts bodies (tether 887c222). The
 * plan cases follow tests/claude-account-plan.test.mjs (the popular names its mapping produces,
 * and the rule that `raw` never reaches what is shown); the row and status cases follow
 * tests/claude-accounts.test.mjs (`listAccounts`, `parseAuthStatusJson`); the sync cases
 * lib/claude-account-sync.mjs's result statuses.
 */
class ClaudeAccountsJsonTest {
    private fun obj(text: String) = ClaudeAccountsJson.parseObject(text) ?: error("not an object: $text")
    private fun accounts(text: String) = ClaudeAccountsJson.accounts(obj(text))
    private fun plan(planJson: String) = accounts("""{"accounts":[{"id":"claude-a","label":"A","plan":$planJson}]}""")!!.single().plan

    @Test fun theServersListDecodesRowByRow() {
        assertEquals(ClaudeAccountsFixtures.LIST, accounts(ClaudeAccountsFixtures.LIST_JSON))
    }

    /** #231: `raw` is never read, so nothing it holds, key or value, survives in any decoded form. */
    @Test fun rawIsNeverDecoded() {
        val decoded = accounts(ClaudeAccountsFixtures.LIST_JSON)!!
        assertFalse(decoded.toString().contains(RAW_SENTINEL))
        assertFalse(decoded.toString().contains("team_tier_1"))
        assertFalse(decoded.toString().contains("default_raven"))
        // A raw of any shape, even one that looks like a plan, changes nothing.
        val shapes = listOf("null", "\"$RAW_SENTINEL\"", "[\"$RAW_SENTINEL\"]", """{"label":"$RAW_SENTINEL","organizationName":"$RAW_SENTINEL"}""")
        for (raw in shapes) {
            val p = plan("""{"label":"Max 5x","source":"profile","raw":$raw}""")
            assertEquals(raw, ClaudeAccountPlan("Max 5x", null, ClaudeAccountPlanSource.Profile), p)
            assertFalse(raw, p.toString().contains(RAW_SENTINEL))
        }
    }

    /** tests/claude-account-plan.test.mjs: every label its mapping can produce is shown as sent. */
    @Test fun thePopularNamesPassThroughAsSent() {
        val names = listOf("Team Premium 5x", "Team Standard", "Max 5x", "Max 20x", "Pro", "Enterprise", "Team", "Max", "Claude")
        for (name in names) {
            assertEquals(name, plan("""{"label":"$name","source":"credentials","raw":{}}""")?.label)
        }
    }

    @Test fun aPlanIsReadTolerantly() {
        // Absent, null or not an object: no plan.
        assertNull(accounts("""{"accounts":[{"id":"claude-a","label":"A"}]}""")!!.single().plan)
        for (bad in listOf("null", "\"Team\"", "42", "true", "[]", "{}")) assertNull(bad, plan(bad))
        // No usable label: nothing to show, so no plan.
        for (label in listOf("null", "42", "\"\"", "\"   \"", "{\"x\":1}")) assertNull(label, plan("""{"label":$label,"source":"profile"}"""))
        // organizationName: absent, null, blank or not a string is none.
        for (org in listOf("null", "42", "\"\"", "\" \"", "[\"x\"]")) {
            assertNull(org, plan("""{"label":"Pro","organizationName":$org,"source":"profile"}""")!!.organizationName)
        }
        assertEquals("Acme", plan("""{"label":"Pro","organizationName":"Acme","source":"profile"}""")!!.organizationName)
        // source: the two known values; anything else (absent, null, a newer value, another type) is unknown.
        assertEquals(ClaudeAccountPlanSource.Profile, plan("""{"label":"Pro","source":"profile"}""")!!.source)
        assertEquals(ClaudeAccountPlanSource.Credentials, plan("""{"label":"Pro","source":"credentials"}""")!!.source)
        for (source in listOf("", ",\"source\":null", ",\"source\":\"live\"", ",\"source\":1", ",\"source\":\"PROFILE\"")) {
            assertEquals(source, ClaudeAccountPlanSource.Unknown, plan("""{"label":"Pro"$source}""")!!.source)
        }
    }

    @Test fun aRowIsReadTolerantly() {
        val rows = accounts(
            """
            {"accounts":[
              {"label":"no id"},
              {"id":42,"label":"numeric id"},
              {"id":"","label":"empty id"},
              "not a row", null, 7,
              {"id":"claude-a","label":5,"enabled":"yes","configDir":17,"hasConfigDir":"true","managed":1,"imported":null,"syncEligible":"true"},
              {"id":"claude-a","label":"duplicate"},
              {"id":"claude-b","label":"B","hasConfigDir":true},
              {"id":"claude-c","label":"C","configDir":"","hasConfigDir":true},
              {"id":"claude-d","label":"D","configDir":"/srv/d","hasConfigDir":false,"future":{"x":1}}
            ]}
            """,
        )!!
        assertEquals(listOf("claude-a", "claude-b", "claude-c", "claude-d"), rows.map { it.id })
        val a = rows[0]
        assertEquals("", a.label)
        assertEquals(ClaudeAccount("claude-a", "", null, null, false, false, false, false, null), a)
        // `hasConfigDir` needs a path to show.
        assertFalse(rows[1].hasConfigDir)
        assertFalse(rows[2].hasConfigDir)
        assertNull(rows[2].configDir)
        // Kept, but not to be shown: the web shows the path only when hasConfigDir.
        assertEquals("/srv/d", rows[3].configDir)
        assertFalse(rows[3].hasConfigDir)
    }

    @Test fun aBodyWithoutTheListIsNotTheServersAnswer() {
        for (body in listOf("""{"error":"login required"}""", "{}", """{"accounts":null}""", """{"accounts":{}}""", """{"accounts":"x"}""")) {
            assertNull(body, accounts(body))
        }
        assertEquals(emptyList<ClaudeAccount>(), accounts("""{"accounts":[]}"""))
    }

    @Test fun textIsBoundedAndTheListCapped() {
        val long = "x".repeat(10_000)
        val row = accounts("""{"accounts":[{"id":"claude-a","label":"$long","configDir":"/$long","hasConfigDir":true,"plan":{"label":"$long","organizationName":"$long"}}]}""")!!.single()
        assertEquals(ClaudeAccountsJson.MAX_TEXT, row.label.length)
        assertEquals(ClaudeAccountsJson.MAX_PATH, row.configDir!!.length)
        assertEquals(ClaudeAccountsJson.MAX_TEXT, row.plan!!.label.length)
        assertEquals(ClaudeAccountsJson.MAX_TEXT, row.plan!!.organizationName!!.length)
        val many = (1..200).joinToString(",") { """{"id":"claude-$it","label":"$it"}""" }
        assertEquals(ClaudeAccountsJson.MAX_ACCOUNTS, accounts("""{"accounts":[$many]}""")!!.size)
    }

    /** lib/providers-registry.mjs ID_PATTERN `^[a-z][a-z0-9-]*$`, at most 64: only such an id is put in a path. */
    @Test fun onlyARegistryShapedIdIsCheckable() {
        for (id in listOf("claude-default", "claude-work", "claude-work-2", "c", "a" + "b".repeat(63))) assertTrue(id, ClaudeAccountsJson.isAccountId(id))
        val refused = listOf(
            "", ".", "..", "claude/..", "../devices", "claude%2Fx", "claude.x", "Claude", "-claude", "1claude", "claude x",
            "claude?x", "claude#x", "claude\nx", "claudé", "a" + "b".repeat(64), "claude\u202Ex",
        )
        for (id in refused) assertFalse(id, ClaudeAccountsJson.isAccountId(id))
        assertFalse(ClaudeAccount("../devices", "x", null, null, false, false, false, false, null).checkable)
    }

    /** tests/claude-accounts.test.mjs `parseAuthStatusJson` / `accountStatus`: the allowlist, read leniently. */
    @Test fun aStatusIsReadFromItsAllowlist() {
        assertEquals(ClaudeAccountsFixtures.STATUS_LOGGED_IN, ClaudeAccountsJson.status(obj(ClaudeAccountsFixtures.STATUS_LOGGED_IN_JSON)))
        assertEquals(
            ClaudeAccountStatus(false, "none", null, null),
            ClaudeAccountsJson.status(obj("""{"ok":true,"id":"claude-a","loggedIn":false,"authMethod":"none"}""")),
        )
        assertEquals(
            ClaudeAccountStatus(false, null, null, "unrecognized-status-output"),
            ClaudeAccountsJson.status(obj("""{"ok":true,"id":"claude-a","loggedIn":false,"authMethod":null,"error":"unrecognized-status-output"}""")),
        )
        // Anything else the CLI might add never comes through.
        val extra = ClaudeAccountsJson.status(obj("""{"ok":true,"loggedIn":true,"accessToken":"sk-ant-$RAW_SENTINEL","email":42}"""))!!
        assertEquals(ClaudeAccountStatus(true, null, null, null), extra)
        // No boolean loggedIn: not the server's answer.
        for (body in listOf("{}", """{"loggedIn":"true"}""", """{"loggedIn":1}""", """{"loggedIn":null}""", """{"error":"login required"}""")) {
            assertNull(body, ClaudeAccountsJson.status(obj(body)))
        }
    }

    @Test fun theSyncStateIsReadAndItsEntriesOnlyCounted() {
        val sync = ClaudeAccountsJson.sync(obj(ClaudeAccountsFixtures.SYNC_JSON))
        assertEquals(ClaudeAccountsFixtures.SYNC, sync)
        // The default config, never synced.
        assertEquals(
            ClaudeAccountsSync(ClaudeSyncConfig(ClaudeSyncMode.None, ClaudeSyncCategories(true, true, true, true), null), null),
            ClaudeAccountsJson.sync(obj("""{"config":{"mode":"none","categories":{"plugins":true,"skills":true,"hooks":true,"mcp":true},"primaryAccountId":null},"lastResult":null}""")),
        )
        // Each refusal status, with its error sentence.
        for (status in listOf("disabled", "not-enough-accounts", "no-primary")) {
            val r = ClaudeAccountsJson.sync(obj("""{"config":{"mode":"all"},"lastResult":{"ranAt":5,"status":"$status","entries":[]}}"""))!!.lastResult!!
            assertEquals(status, r.status)
        }
        val failed = ClaudeAccountsJson.sync(obj("""{"config":{"mode":"selected","categories":{"skills":true}},"lastResult":{"ranAt":5,"status":"error","error":"EACCES","entries":[]}}"""))!!
        assertEquals(ClaudeSyncCategories(plugins = false, skills = true, mcp = false, hooks = false), failed.config.categories)
        assertEquals(ClaudeSyncMode.Selected, failed.config.mode)
        assertEquals("EACCES", failed.lastResult!!.error)
        // An unknown mode is unknown; a missing / zero / negative ranAt is none; entries that are not objects are skipped.
        val odd = ClaudeAccountsJson.sync(obj("""{"config":{"mode":"mirror","categories":"x","primaryAccountId":7},"lastResult":{"status":"ok","ranAt":-1,"entries":[1,null,{"status":"linked"},{"status":"not-managed"},{"status":"error"}]}}"""))!!
        assertEquals(ClaudeSyncMode.Unknown, odd.config.mode)
        assertEquals(ClaudeSyncCategories(false, false, false, false), odd.config.categories)
        assertNull(odd.config.primaryAccountId)
        assertEquals(ClaudeSyncResult(null, "ok", 1, 0, null), odd.lastResult)
        // A result without a status is no result; a body without its config is not the answer.
        assertNull(ClaudeAccountsJson.sync(obj("""{"config":{"mode":"all"},"lastResult":{"ranAt":5}}"""))!!.lastResult)
        for (body in listOf("{}", """{"config":null}""", """{"error":"login required"}""")) assertNull(body, ClaudeAccountsJson.sync(obj(body)))
    }

    @Test fun anUnusableBodyIsNoObject() {
        for (text in listOf("", "not json", "[]", "null", "42", "\"x\"", "{\"accounts\":", "[".repeat(100_000), "{\"a\":" + "[".repeat(100_000))) {
            assertNull(text.take(20), ClaudeAccountsJson.parseObject(text))
        }
    }

    /**
     * r2: a subtree deeper than anything read (a nested `plan.raw`, a newer field) is dropped before
     * parsing, not the body: the rows and their plans stay, and the parser never sees the depth.
     */
    @Test fun aDeepSubtreeIsDroppedAndTheRowsKept() {
        val deep = "[".repeat(50_000) + "]".repeat(50_000)
        val body = """{"accounts":[
            {"id":"claude-a","label":"A","plan":{"label":"Max 5x","source":"profile","raw":{"nested":$deep,"probe":"$RAW_SENTINEL"}},"future":{"x":$deep}},
            {"id":"claude-b","label":"B"}],"extra":$deep}"""
        val rows = accounts(body)!!
        assertEquals(listOf("claude-a", "claude-b"), rows.map { it.id })
        assertEquals(ClaudeAccountPlan("Max 5x", null, ClaudeAccountPlanSource.Profile), rows[0].plan)
        assertFalse(rows.toString().contains(RAW_SENTINEL))
        // The sync body the same way: its entries stay counted.
        val sync = ClaudeAccountsJson.sync(obj("""{"config":{"mode":"all","x":$deep},"lastResult":{"status":"ok","entries":[{"status":"linked","meta":$deep}]}}"""))!!
        assertEquals(1, sync.lastResult!!.changed)
    }
}
