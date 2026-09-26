package com.tether.app.protocol.reduce

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducerSessionLevelTest {

    private fun openTurn() = fold(freshState(), ev("turn_started", turnId = "t1", ts = 1_000))

    @Test
    fun noticesAreSeqDeduped() {
        var state = fold(
            freshState(),
            evNullTurn("background_interrupted", seq = 7, ts = 1_000) { put("outstanding", 2) },
        )
        assertEquals(1, state.notices.size)
        assertEquals("background_interrupted", state.notices[0].kind)
        assertEquals(2L, state.notices[0].outstanding)
        assertEquals(7L, state.notices[0].seq)

        // Same stamped marker replayed: no-op.
        val replay = reduce(
            state,
            evNullTurn("background_interrupted", seq = 7, ts = 1_000) { put("outstanding", 2) },
        )
        assertSame(state, replay)

        // Different seq appends; reason is carried when present.
        state = fold(
            state,
            evNullTurn("background_abandoned", seq = 8, ts = 2_000) {
                put("outstanding", 1)
                put("reason", "idle")
            },
            evNullTurn("external_advancement", seq = 9, ts = 3_000) { put("count", 3) },
        )
        assertEquals(3, state.notices.size)
        assertEquals("idle", state.notices[1].reason)
        assertEquals(3L, state.notices[2].count)
        // external_advancement dedup too.
        assertSame(state, reduce(state, evNullTurn("external_advancement", seq = 9, ts = 3_000) { put("count", 3) }))
    }

    @Test
    fun tokenProgressIsMonotonicallyClamped() {
        var state = fold(openTurn(), ev("token_progress", turnId = "t1", ts = 1_100) { put("tokens", 99) })
        assertEquals(99L, state.turnsById.getValue("t1").liveTokens)

        // A lower restatement never ticks the counter backwards.
        val clamped = reduce(state, ev("token_progress", turnId = "t1", ts = 1_200) { put("tokens", 50) })
        assertEquals(99L, clamped.turnsById.getValue("t1").liveTokens)

        state = reduce(state, ev("token_progress", turnId = "t1", ts = 1_300) { put("tokens", 150) })
        assertEquals(150L, state.turnsById.getValue("t1").liveTokens)

        // Negative / non-numeric tokens are no-ops; null stays distinct from 0.
        val fresh = openTurn()
        assertNull(fresh.turnsById.getValue("t1").liveTokens)
        assertSame(fresh, reduce(fresh, ev("token_progress", turnId = "t1", ts = 1_100) { put("tokens", -5) }))
    }

    @Test
    fun queuedMessagesAddUpdateRemove() {
        var state = fold(
            freshState(),
            evNullTurn("queued_message_added", seq = 1, ts = 1_000) {
                put("queueId", "q1")
                put("text", "first")
            },
        )
        assertEquals("first", state.queuedMessages.single().text)

        // Duplicate add is a no-op.
        assertSame(
            state,
            reduce(state, evNullTurn("queued_message_added", seq = 2, ts = 1_100) {
                put("queueId", "q1")
                put("text", "other")
            }),
        )

        state = reduce(state, evNullTurn("queued_message_updated", seq = 3, ts = 1_200) {
            put("queueId", "q1")
            put("text", "edited")
        })
        assertEquals("edited", state.queuedMessages.single().text)

        state = reduce(state, evNullTurn("queued_message_removed", seq = 4, ts = 1_300) { put("queueId", "q1") })
        assertTrue(state.queuedMessages.isEmpty())

        // Update after removal never resurrects.
        assertSame(state, reduce(state, evNullTurn("queued_message_updated", seq = 5, ts = 1_400) {
            put("queueId", "q1")
            put("text", "zombie")
        }))
        // Remove of an absent id is a no-op.
        assertSame(state, reduce(state, evNullTurn("queued_message_removed", seq = 6, ts = 1_500) { put("queueId", "q1") }))
    }

    @Test
    fun rateLimitRejectedWithinHorizonOffersResume() {
        val resetsAt = 100_000L
        var state = fold(
            freshState(),
            evNullTurn("rate_limit", seq = 1, ts = 40_000) {
                put("status", "rejected")
                put("limitType", "five_hour")
                put("resetsAt", resetsAt)
            },
        )
        assertEquals("rejected", state.rateLimit?.status)
        assertEquals("awaiting_choice", state.rateLimitResume?.status)
        assertEquals(resetsAt, state.rateLimitResume?.resetsAt)
        assertEquals(resetsAt + 120_000, state.rateLimitResume?.resumeAt)

        // scheduled requires exact resumeAt = resetsAt + delay.
        val wrong = reduce(state, evNullTurn("rate_limit_resume_scheduled", seq = 2, ts = 41_000) {
            put("resetsAt", resetsAt)
            put("resumeAt", resetsAt + 5)
        })
        assertSame(state, wrong)

        state = reduce(state, evNullTurn("rate_limit_resume_scheduled", seq = 3, ts = 42_000) {
            put("resetsAt", resetsAt)
            put("resumeAt", resetsAt + 120_000)
        })
        assertEquals("scheduled", state.rateLimitResume?.status)

        state = reduce(state, evNullTurn("rate_limit_resume_fired", seq = 4, ts = 43_000) { put("resetsAt", resetsAt) })
        assertEquals("fired", state.rateLimitResume?.status)

        // dismissed does not overwrite fired.
        assertSame(state, reduce(state, evNullTurn("rate_limit_resume_dismissed", seq = 5, ts = 44_000) {
            put("resetsAt", resetsAt)
        }))
    }

    @Test
    fun rateLimitBeyondHorizonOrUnstampedMakesNoOffer() {
        // Beyond the 5h horizon.
        val far = fold(
            freshState(),
            evNullTurn("rate_limit", seq = 1, ts = 1_000) {
                put("status", "rejected")
                put("resetsAt", 1_000 + 18_000_001)
            },
        )
        assertNull(far.rateLimitResume)

        // Unstamped event (no ts) makes no offer, but still records the status.
        val unstamped = reduce(
            freshState(),
            evNullTurn("rate_limit") {
                put("status", "rejected")
                put("resetsAt", 5_000)
            },
        )
        assertEquals("rejected", unstamped.rateLimit?.status)
        assertNull(unstamped.rateLimitResume)
    }

    @Test
    fun todoUpdatedNormalizesAndIgnoresEmpty() {
        var state = fold(
            freshState(),
            evNullTurn("todo_updated", seq = 1, ts = 1_000) {
                putJsonArray("items") {
                    addJsonObject {
                        put("content", "write code")
                        put("activeForm", "Writing code")
                        put("status", "in_progress")
                    }
                    addJsonObject {
                        put("content", "run tests")
                        put("status", "pending")
                    }
                    addJsonObject {
                        put("content", "   ") // blank content dropped
                        put("status", "pending")
                    }
                    addJsonObject {
                        put("content", "bad status kept out")
                        put("status", "paused")
                    }
                }
            },
        )
        val todo = state.todo!!
        assertEquals(2, todo.total)
        assertEquals(0, todo.completed)
        assertEquals("Writing code", todo.activeForm)
        assertEquals("", todo.items[1].activeForm)

        // Identical rewrite: same state instance (value dedup).
        assertSame(state, reduce(state, evNullTurn("todo_updated", seq = 2, ts = 2_000) {
            putJsonArray("items") {
                addJsonObject {
                    put("content", "write code")
                    put("activeForm", "Writing code")
                    put("status", "in_progress")
                }
                addJsonObject {
                    put("content", "run tests")
                    put("status", "pending")
                }
            }
        }))

        // An entirely-unusable list is IGNORED, never folded as empty.
        val after = reduce(state, evNullTurn("todo_updated", seq = 3, ts = 3_000) { putJsonArray("items") {} })
        assertSame(state, after)
    }

    @Test
    fun providerNoticesDedupByNoticeIdAndValidateLevel() {
        var state = fold(
            freshState(),
            evNullTurn("provider_notice", seq = 1, ts = 1_000) {
                put("noticeId", "n1")
                put("level", "bogus")
                put("message", "something happened")
            },
        )
        assertEquals("warning", state.providerNotices.single().level)

        assertSame(state, reduce(state, evNullTurn("provider_notice", seq = 2, ts = 2_000) {
            put("noticeId", "n1")
            put("level", "error")
            put("message", "duplicate id")
        }))

        // Turn-scoped notice goes to the open turn's list.
        state = fold(
            state,
            ev("turn_started", turnId = "t1", ts = 3_000),
            ev("provider_notice", turnId = "t1", seq = 3, ts = 3_100) {
                put("noticeId", "n2")
                put("level", "info")
                put("message", "turn scoped")
            },
        )
        assertEquals("n2", state.turnsById.getValue("t1").providerNotices.single().noticeId)
        assertEquals(1, state.providerNotices.size)
    }

    @Test
    fun permissionDeniedUpsertsAndEnriches() {
        // Unattributed (turnId null).
        var state = fold(
            freshState(),
            evNullTurn("permission_denied", seq = 1, ts = 1_000) {
                put("toolId", "toolu_d")
                put("name", "Bash")
                put("reason", "unknown")
            },
        )
        assertEquals("unknown", state.unattributedPermissionDenials.single().reason)

        // Later record enriches the same toolId with a real reason + code.
        state = reduce(state, evNullTurn("permission_denied", seq = 2, ts = 2_000) {
            put("toolId", "toolu_d")
            put("name", "Bash")
            put("reason", "safety_check")
            put("reasonCode", "subcommandResults")
            put("subagent", true)
        })
        val denial = state.unattributedPermissionDenials.single()
        assertEquals("safety_check", denial.reason)
        assertEquals("subcommandResults", denial.reasonCode)
        assertEquals(true, denial.subagent)

        // Byte-identical replay is a true no-op.
        assertSame(state, reduce(state, evNullTurn("permission_denied", seq = 3, ts = 3_000) {
            put("toolId", "toolu_d")
            put("name", "Bash")
            put("reason", "safety_check")
            put("reasonCode", "subcommandResults")
            put("subagent", true)
        }))

        // Out-of-vocabulary reason folds to "unknown"; prose-shaped code dropped.
        state = reduce(state, evNullTurn("permission_denied", seq = 4, ts = 4_000) {
            put("toolId", "toolu_e")
            put("name", "Edit")
            put("reason", "brand_new_bucket")
            put("reasonCode", "not a valid code!")
        })
        assertEquals("unknown", state.unattributedPermissionDenials[1].reason)
        assertNull(state.unattributedPermissionDenials[1].reasonCode)
    }

    @Test
    fun approvalChoiceNormalizationBounds() {
        val oversizeId = "x".repeat(129)
        val state = fold(
            openTurn(),
            ev("approval_request", turnId = "t1", ts = 2_000) {
                put("requestId", "r1")
                put("toolId", "toolu_1")
                put("name", "Bash")
                putJsonArray("choices") {
                    addJsonObject {
                        put("choiceId", "allow-once")
                        put("label", "Allow once")
                        put("permissionGrant", "exact")
                    }
                    addJsonObject {
                        put("choiceId", oversizeId) // REJECTED, not truncated
                        put("label", "Bad id")
                    }
                    addJsonObject {
                        put("choiceId", "allow-once") // duplicate id dropped
                        put("label", "Dup")
                    }
                    addJsonObject {
                        put("choiceId", "deny")
                        put("label", "") // empty label dropped
                    }
                    addJsonObject {
                        put("choiceId", "weird-grant")
                        put("label", "Weird")
                        put("permissionGrant", "everything") // unknown grant normalized away
                    }
                }
            },
        )
        val choices = state.turnsById.getValue("t1").pendingApprovals.getValue("r1").choices!!
        assertEquals(listOf("allow-once", "weird-grant"), choices.map { it.choiceId })
        assertEquals("exact", choices[0].permissionGrant)
        assertNull(choices[1].permissionGrant)
    }

    @Test
    fun boundedDisplayTextTruncatesByCodePoints() {
        // 2001 astral (2-UTF-16-unit) code points: must keep exactly 2000 CODE
        // POINTS, never split a surrogate pair.
        val astral = "😀" // 😀
        val state = fold(
            openTurn(),
            ev("plan_updated", turnId = "t1", ts = 2_000) {
                put("explanation", astral.repeat(2_001))
                putJsonArray("steps") {
                    addJsonObject {
                        put("step", "only step")
                        put("status", "pending")
                    }
                }
            },
        )
        val explanation = state.turnsById.getValue("t1").plan!!.explanation!!
        assertEquals(2_000, explanation.codePointCount(0, explanation.length))
        assertEquals(4_000, explanation.length)
        assertEquals("only step", state.turnsById.getValue("t1").plan!!.steps.single().step)
    }

    @Test
    fun mcpHealthValidatesDedupsAndCaps() {
        var state = fold(
            freshState(),
            evNullTurn("mcp_health_updated", seq = 1, ts = 1_000) {
                put("name", "linear")
                put("status", "exploded") // invalid -> unknown
            },
        )
        assertEquals("unknown", state.mcpHealth.getValue("linear").status)
        assertSame(state, reduce(state, evNullTurn("mcp_health_updated", seq = 2, ts = 2_000) {
            put("name", "linear")
            put("status", "exploded")
        }))
        state = reduce(state, evNullTurn("mcp_health_updated", seq = 3, ts = 3_000) {
            put("name", "linear")
            put("status", "ready")
        })
        assertEquals("ready", state.mcpHealth.getValue("linear").status)
    }

    @Test
    fun cliInventoryLifecycle() {
        var state = fold(
            freshState(),
            ev("native_session_id", turnId = "t0", ts = 1_000) {
                put("nativeSessionId", "native-1")
                putJsonArray("cliCapabilities") { add(kotlinx.serialization.json.JsonPrimitive("x")) }
                put("cliVersion", "2.1.0")
                put("cliInventory", kotlinx.serialization.json.buildJsonObject {
                    putJsonArray("commands") {
                        addJsonObject {
                            put("name", "/compact")
                            put("description", "Compact the conversation")
                        }
                    }
                    putJsonArray("tools") { add(kotlinx.serialization.json.JsonPrimitive("Bash")) }
                    putJsonArray("mcpServers") {
                        addJsonObject {
                            put("name", "linear")
                            put("status", "connected")
                        }
                    }
                })
            },
        )
        assertEquals("native-1", state.nativeSessionId)
        assertEquals(listOf("x"), state.cliCapabilities)
        assertEquals("2.1.0", state.cliVersion)
        assertEquals("compact", state.cliInventory!!.commands.single().name)

        // A bare re-announcement (compaction replay) must not clear learned facts.
        val bare = reduce(state, ev("native_session_id", turnId = "t0", ts = 2_000) {
            put("nativeSessionId", "native-1")
        })
        assertEquals("2.1.0", bare.cliVersion)
        assertEquals(listOf("x"), bare.cliCapabilities)

        // Metadata-less init command inherits the previous same-name command.
        state = reduce(state, ev("native_session_id", turnId = "t0", ts = 3_000) {
            put("nativeSessionId", "native-1")
            put("cliInventory", kotlinx.serialization.json.buildJsonObject {
                putJsonArray("commands") { add(kotlinx.serialization.json.JsonPrimitive("/compact")) }
                putJsonArray("tools") { add(kotlinx.serialization.json.JsonPrimitive("Bash")) }
                putJsonArray("mcpServers") {}
            })
        })
        assertEquals("Compact the conversation", state.cliInventory!!.commands.single().description)

        // commands_changed replaces commands only.
        state = reduce(state, evNullTurn("cli_commands_changed", ts = 4_000) {
            putJsonArray("commands") {
                addJsonObject { put("name", "review") }
            }
        })
        assertEquals(listOf("review"), state.cliInventory!!.commands.map { it.name })
        assertEquals(listOf("Bash"), state.cliInventory!!.tools)

        state = reduce(state, evNullTurn("cli_inventory_reset", ts = 5_000))
        assertNull(state.cliInventory)
    }
}
