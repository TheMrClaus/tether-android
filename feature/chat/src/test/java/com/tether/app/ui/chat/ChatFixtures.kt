package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import com.tether.app.ui.theme.mode
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ta-coik.13: how long the behaviour tests let the composition settle after a change (frames,
 * effects, a window opening). It was the old 500 ms arm delay plus 100 ms; no control waits for it
 * any more (every control acts on its first tap), so it is only a settle time now.
 */
internal const val SETTLE_MS = 600L

/**
 * ta-coik.13: a stale tap. A press begins on [node], then [change] lands (the control's meaning
 * changes under the finger), then the finger lifts. [settle] runs after each step (the default lets
 * the composition catch up; a test driving the clock by hand passes its own).
 */
internal fun androidx.compose.ui.test.junit4.ComposeTestRule.pressAcross(
    node: () -> androidx.compose.ui.test.SemanticsNodeInteraction,
    settle: () -> Unit = { waitForIdle() },
    change: () -> Unit,
) {
    node().performTouchInput { down(center) }
    settle()
    runOnIdle { change() }
    settle()
    node().performTouchInput { up() }
    settle()
}

/**
 * Transcript fixtures folded by the real v128 reducer (the path RealTetherClient takes), mirroring
 * the S0.4 seeded web scenarios (tether scripts/parity-seed.mjs): `idle-session`,
 * `long-markdown`, `streaming`.
 */
object ChatFixtures {
    /** tether scripts/parity-seed.mjs:133 `LONG_MARKDOWN`, verbatim. */
    val LONG_MARKDOWN: String = """# Release notes draft

The **parity** pass touched three areas. Here is a *summary* with `inline code`, a [link to the docs](https://example.test/docs) and ~~a retired idea~~.

## What changed

1. The session sidebar now groups by workspace.
2. Approvals render the exact diff before you allow them.
   - nested bullet one
   - nested bullet two
3. Scheduled actions can pause and resume.

### Checklist

- [x] Wire corpus captured
- [x] Tokens exported
- [ ] Screens compared

## Numbers

| Area | Before | After | Delta |
| --- | ---: | ---: | :---: |
| Cold start | 1.8 s | 0.9 s | -50% |
| Bundle | 412 kB | 388 kB | -6% |
| Tests | 1,204 | 1,322 | +118 |

## Code

```ts
export function retry<T>(fn: () => Promise<T>, attempts = 3): Promise<T> {
  return fn().catch((error) => (attempts > 1 ? retry(fn, attempts - 1) : Promise.reject(error)));
}
```

```bash
npm run test:unit && npm run lint
```

> A quoted note: keep the diff small and the evidence line honest.

---

That's everything for this release."""

    /** The web shots' clock: the seeded turns land at 01:01 / 02:01 UTC. */
    val zone: ZoneId = ZoneOffset.UTC

    const val T_IDLE = 3_660_000L // 01:01 UTC
    const val T_MARKDOWN = 7_260_000L // 02:01 UTC
    const val T_STREAM = 60_000L // 00:01 UTC

    /** A projection and the tree it was adapted from (the bubbles read `ts` from the tree). */
    data class Folded(val projection: SessionProjection, val tree: JsObj)

    fun fold(vararg events: AgentEvent): Folded {
        val tree = foldTree(freshTree(), *events)
        return Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree)
    }

    /** One finished turn: the operator's prompt, then [reply] as a completed message. */
    fun turn(turnId: String, prompt: String, reply: String, ts: Long, thinking: String? = null): Array<AgentEvent> = buildList {
        add(ev("turn_started", turnId, ts = ts) { put("idempotencyKey", "k-$turnId") })
        add(ev("user_message_accepted", turnId, ts = ts) { put("text", prompt) })
        if (thinking != null) {
            add(ev("thinking_delta", turnId, ts = ts) { put("blockId", "$turnId:th0"); put("text", thinking) })
            add(ev("thinking_stop", turnId, ts = ts) { put("blockId", "$turnId:th0") })
        }
        add(ev("message_started", turnId, ts = ts) { put("blockId", "$turnId:m0") })
        add(ev("message_delta", turnId, ts = ts) { put("blockId", "$turnId:m0"); put("text", reply) })
        add(ev("message_completed", turnId, ts = ts) { put("blockId", "$turnId:m0"); put("text", reply) })
        add(ev("turn_end", turnId, ts = ts) { put("outcome", "ok") })
    }.toTypedArray()

    /** idle-session: "Summarize the README in one line." → the fake engine's echo. */
    val idle: Folded by lazy {
        fold(*turn("t1", "Summarize the README in one line.", "(fake engine) you said: Summarize the README in one line.", T_IDLE))
    }

    /** long-markdown: "Draft the release notes." → [LONG_MARKDOWN]. */
    val markdown: Folded by lazy { fold(*turn("t1", "Draft the release notes.", LONG_MARKDOWN, T_MARKDOWN)) }

    /**
     * streaming, mid-message: a finished first reply, then the agent still typing (no
     * message_completed / turn_end yet) — the caret state the web seeder cannot freeze.
     */
    val streaming: Folded by lazy {
        fold(
            ev("turn_started", "t1", ts = T_STREAM) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = T_STREAM) { put("text", "Run the test suite and tell me what fails.") },
            ev("message_started", "t1", ts = T_STREAM) { put("blockId", "t1:m0") },
            ev("message_completed", "t1", ts = T_STREAM) { put("blockId", "t1:m0"); put("text", "Running the test suite now.") },
            ev("message_started", "t1", ts = T_STREAM + 1_000) { put("blockId", "t1:m1") },
            ev("message_delta", "t1", ts = T_STREAM + 1_000) {
                put("blockId", "t1:m1")
                put("text", "Two suites fail so far:\n\n1. `reducer` — a snapshot mismatch in the timeline fold\n2. `net` — a timeout")
            },
        )
    }

    /**
     * A v115 bounded snapshot: five turns, the first three trimmed the way the server's
     * `tailTurns` strips them (`blocks` and `blocksById` emptied, the turn ids kept).
     */
    val bounded: Folded by lazy { boundedOf(5, trimmed = 3) }

    fun boundedOf(turns: Int, trimmed: Int): Folded {
        val events = (1..turns).flatMap { n -> turn("t$n", "Prompt $n", "Reply $n", T_IDLE + n * 60_000L).toList() }
        val full = foldTree(freshTree(), *events.toTypedArray())
        val byId = full["turnsById"] as JsObj
        var stripped = byId
        for (n in 1..trimmed) {
            val t = byId["t$n"] as JsObj
            stripped = stripped.put("t$n", t.put("blocks", JsArr.EMPTY).put("blocksById", JsObj.EMPTY))
        }
        val tree = full.put("turnsById", stripped)
        return Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree)
    }

    /** thinking open/closed: a finished turn whose reply follows a reasoning block. */
    val thinking: Folded by lazy {
        fold(
            *turn(
                "t1",
                "Why does the retry loop spin?",
                "The loop never awaits the backoff, so every attempt fires at once. Awaiting `sleep(delay)` fixes it.",
                T_IDLE,
                thinking = "The user asks about the retry loop.\n\nLooking at `retry()`: the delay is computed but the promise is never awaited, so **all attempts run immediately**.\n\n- attempt 1 fails\n- attempt 2 fires in the same tick",
            ),
        )
    }
}

fun choiceFor(skin: TetherSkin): ThemeMode = skin.mode

/**
 * The chat well as the web frames it: below the topbar + workspace header band and above the
 * composer (412dp wide; [wellHeight] tall). Ambient motion static unless [reducedMotion] is false.
 */
@Composable
fun ChatHost(
    skin: TetherSkin,
    wellHeight: Dp = WellHeightPhone,
    wellWidth: Dp? = null,
    reducedMotion: Boolean = true,
    content: @Composable () -> Unit,
) {
    TetherTheme(choiceFor(skin)) {
        // ta-coik.37: the derivation runs inline (no wall-clock waits); the off-main behaviour has its own test.
        CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalChatDerivationDispatcher provides kotlinx.coroutines.Dispatchers.Unconfined) {
            Box(
                (if (wellWidth != null) Modifier.width(wellWidth) else Modifier.fillMaxWidth())
                    .height(wellHeight)
                    .background(chatWellColor(LocalTetherTokens.current))
                    .testTag(WellTag),
            ) { content() }
        }
    }
}

const val WellTag = "chat-well"

/** The web phone shot's transcript well: y 310..2090 px at 2.625 px/dp ≈ 678dp. */
val WellHeightPhone: Dp = 678.dp

/**
 * The tablet well (web desktop layout at 1280×800): the chat frame's transcript between the
 * sidebar and the right edge (x 318..1268) and between the header and the composer (y 115..645).
 */
val WellHeightTablet: Dp = 530.dp
val WellWidthTablet: Dp = 950.dp
