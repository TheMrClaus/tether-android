# ta-coik.10: telemetry bar and telemetry panel, as the deployed web

The reference is the web the owner runs: tether `90fbb9f` (PROTOCOL_VERSION 140). Between `90fbb9f`
and tether `main` there are no telemetry changes. The panel follows the f4c4133 redesign ("Headroom
first") and #233 (0b4d91f). The statusline, the context gauge, `telemetry-readings.tsx`,
`telemetry-sheet.tsx` and the wrap-up badge (fe4c025, c3a5f25) have not changed since T4.3 ported
them, so nothing changed on the app side for those.

## What is where

| Piece | Kotlin | Web source (90fbb9f) |
|---|---|---|
| v138 `SessionMetrics.subagentDefaults` (tolerant: present, absent, malformed, capped at 64) | `core/protocol/.../model/Session.kt` (`subagentDefaultMap`, `SubagentDefault`) | lib/protocol.ts SessionMetrics / SubagentDefault; lib/subagent-defaults.mjs |
| Band model, in the operator's order | `feature/shell/.../inspector/InspectorModel.kt` | components/inspector.tsx 491-1009 |
| Panel drawing | `feature/shell/.../inspector/Inspector.kt` | inspector.tsx; app/telemetry-panel.css |
| Ink meter (`UsageTrackPlacement.Panel`) | `feature/shell/.../statusline/UsageTrack.kt` | telemetry-panel.css 153-169, 348 |
| MCP card as a band head | `InspectorNotices.kt` (`banded`) | telemetry-panel.css 331-333 |
| Attention re-derived when the rate-limit window resets | `InspectorHost.kt` (`rememberRateLimitExpiry`) | inspector.tsx RateLimitNotice / WrapUpNotice timers |

## Behaviour

- **Order:** the header (harness and status; Model and Effort as the largest readings; the account
  email and organisation; the divergence note; Now), the attention strip, Context, Limits,
  Subagents, the selected run's Sub-agent band, the "Session" divider, MCP health, Tokens,
  Repository, Services, Codex notices, Runtime (collapsed, with `CLI x` in its summary), and the ACP
  capabilities.
- **Meters:** Context and Limits share one grid (label, track, number). The fill is ink. It turns
  warning at 75% and danger at 90%, and the number takes the same tone. The number is always
  printed. A snapshot reading draws a dashed hairline instead of a bar. Violet appears only on the
  selected run row.
- **Attention:** shown only while there is something to report. Wrap-up replaces the rate-limit
  alert. Other entries are the MCP failed / needs sign-in count and a failed worktree setup.
- **Subagents:** six rows, then "Show N more". That disclosure starts open when the selected run is
  behind it. Each row shows the model (served, then asked, then declared) and the effort (asked,
  then declared). When nothing is known it says "model not captured" or "effort not set". The
  declared values come from the Claude provider only.
- **Empty and partial sessions:** before the first response the panel shows "Telemetry appears after
  the agent completes its first response." and omits the Limits band. When there is no reading,
  the panel prints "No current 5-hour or weekly reading for this account." (#233).
- **Coarse pointer:** Android always counts as a coarse pointer, so phones and tablets both use the
  web's phone sizes (telemetry-panel.css 338-353) and 44dp targets.

## Montages (`montages.sh`)

The goldens (`telemetry-panel-{full,open,empty,attention,selected}`, Studio and Studio dark, phone
and tablet, plus 1.3x font for full, attention and selected) render `InspectorBoards.Reference`.
That is the web spec's own fixture (tests/telemetry-panel.spec.ts), so each montage pairs the web's
reference PNG with the app rendering the same data. The phone captures pair with the web's 390px
sheet. The tablet captures show the floating card the web uses between 48rem and 100rem, and they
pair with the web's 288px desktop column. That column is narrower, so the tablet diffs are mostly
reflow (wider lines and different wrap points), not missing or misplaced content.

## Divergences and leftovers (owned elsewhere)

- The Limits band's "Use reset" key and the reset dialogs belong to T9.2, which is in progress with
  another actor. The panel shows the banked count and the grant headline only.
- The Repository band's "Draft commit message" and "Draft pull request" actions belong to T8.5.
