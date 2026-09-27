# T6.1 transcript vs the web reference

Montages (`web | android | diff`) built by `tools/compare-screens/chat-montages.sh` from the S0.4 web
reference (Chromium headless, phone 412×915 @DPR 2.625, tablet 1280×800, seeded scenarios) and the
transcript goldens in `feature/chat/src/test/screenshots/`.

The goldens render `ChatTranscript` alone, in a well sized like the web's transcript: phone 412dp ×
678dp at 420dpi (2.625 px/dp), the web's y 310..2090 band between the workspace header and the
composer; tablet 950dp × 530dp at mdpi, the web desktop chat frame (x 318..1268, y 115..645). The
topbar, header and composer belong to T4.x/T7.x and are not in these crops. The fixtures fold the
same events the seeder sends (`LONG_MARKDOWN` verbatim from `scripts/parity-seed.mjs:133`) through the
v128 reducer, so the send times (01:01, 02:01, 00:01 UTC) match the web shots.

| Montage | Web scenario | What matches | Explained differences |
|---|---|---|---|
| `idle-<skin>-phone.png` | idle-session | shrink-to-fit bubbles (the user bubble is as wide as its line, right-aligned; the agent bubble fills the well on a phone, `max-width: 100%`), user colours and border, the lit top edge and raised shadow (instrument), Studio's borderless tinted user bubble and transparent agent column, the right-aligned 0.65rem send time at 0.55 opacity, `space-md`/`space-sm` padding | (1) **Timeline rail width.** The web overlays the conversation-timeline rail on the transcript. The app keeps the 54dp column it already reserved for the rail (T6.5 owns the rail, and a 54dp touch strip over the transcript would eat taps on the code blocks' copy keys, which sit at the right edge). So bubbles are 54dp narrower and some lines wrap earlier. The rail mark's colour and size are T6.5's. (2) Glyph rasterization and Manrope vertical metrics in Android's text stack vs Chromium (lines sit about 1-2px apart). |
| `markdown-top-<skin>-phone.png` | long-markdown-top | heading levels shifted down two (`#` renders as h3: 1.05rem/680), inline bold, synthesised italic, the inline-code pill (mono 0.85em, `--tint-md`, 0.35rem side padding), violet underlined link, the literal `~~a retired idea~~`, the flattened nested list (the web restarts the ordered list at "1."), the Chrome-style disc markers, the literal `[x]` / `[ ]` checklist, the table header wash, column alignment and rules; "Jump to latest" after a reader scroll | (1) The rail column as above, which moves wraps and so everything below the intro down a line. (2) The web has no strikethrough and no task-list checkboxes (`components/markdown.tsx` has neither rule), so the app has neither. That is parity, not a gap. (3) Table rows are about 1dp taller: the same line-height 1.45 gives a slightly taller line box with Android's font metrics. |
| `markdown-<skin>-phone.png` | long-markdown | table bottom, the `Code` heading, both fenced blocks (mono 0.8rem/1.5, `white-space: pre` scrolling sideways, JetBrains Mono's `=>` ligature as in Chromium), the 44dp copy keys (key face, bevels, side wall, 0.85 opacity), quote rule and muted text, the hr, the closing paragraph | (1) The web shot was taken after the harness had scrolled up and back, so it shows "Jump to latest" over the send time. The golden is still in follow mode, so no jump key shows and the 02:01 is visible. (2) The rail column as above. (3) **No syntax highlighting, on either side.** The web renders fences as plain text (`<code data-lang>` with no highlighter). PLAN D11's "Kotlin highlighter" assumed one existed. Adding highlighting would diverge from the web, so the app does not add it. |
| `streaming-<skin>-phone.png` | streaming | the operator's bubble | The web seeder cannot freeze a message mid-stream (`parity-seed.mjs:701`). Its streaming scene is a turn frozen on an open Bash call, which is a T6.2 tool card. The golden `chat-streaming` shows what the web draws while a message streams, per `chat-view.tsx:677-702` and `.chat-caret`: plain pre-wrap text (backticks still literal), the 0.5rem × 1rem violet caret on its own line below the text, and no send time until done. Only the user bubble is paired. |
| `<state>-<skin>-tablet.png` | idle-session, long-markdown(-top) | desktop bubble widths (86%, Studio 88%), the desktop `.chat-scroll` padding, the 16rem code clamp | (1) The web desktop puts the timeline rail left of the frame. The app reserves its right-hand column as on the phone. (2) The web frame's bezel and inner shadow are the expanded shell's (T4.2). The golden is the bare well. (3) Font rasterization at 1 px/dp. |

States with goldens but no web counterpart: `chat-thinking-closed` / `chat-thinking-open` (the web
seeds no thinking scene; styled from `globals.css` 6405-6451: the mono 0.78rem muted head, a chevron
that turns 90°, then a `--tint-xs` body with a 1px border, clamped at 13rem), `chat-code-copied`
(the copy key's check after a tap, `aria-label="Copied"`), `chat-load-earlier` (a v115 bounded
snapshot's "Load 3 earlier turns" key, `.load-earlier-button`). 1.3× font-scale goldens:
`chat-{markdown-top,markdown,streaming,thinking-open}-font-1.3x` (Machine, Studio). At 1.3× the
table re-flows as CSS auto layout does: cells wrap only when the max-content width does not fit.

The diff is a review aid, not a gate (PLAN §5.3). The pixel gate is `verifyRoborazziDebug`.

## Markdown port: deliberate JVM-vs-JS rules

`feature/chat/.../MarkdownParser.kt` ports `components/markdown.tsx` regex for regex. Two rules
keep it faithful where the JVM differs from JS:

- **Case-insensitive matching is ASCII-only.** JS `/i` without the `u` flag folds only `A`-`Z`.
  Kotlin `RegexOption.IGNORE_CASE` and `ignoreCase = true` fold Unicode too (`ſ` U+017F == `s`,
  `ı` U+0131 / `İ` U+0130 == `i`), which would let `httpſ://x` or `maılto:x` past the link
  allowlist and into a Custom Tab intent. Never use either for a ported web regex. The allowlist
  uses `startsWithAsciiIgnoreCase` instead.
- **Two web infinite loops are not reproduced.** A line that passes the heading-start check but
  fails the full heading regex hangs the web parser: `"# x\r"` (a lone CR) and `"## title\u2028"`
  (U+2028 LINE SEPARATOR). JS `.` cannot cross either terminator and `split("\n")` leaves both
  inside the line. The port consumes such a line as a one-line paragraph. Both cases are tested.
