# T6.2 tool cards vs the web reference

Montages (`web | android | diff`) built by `tools/compare-screens/tool-montages.sh` from the S0.4 web
reference (seeded `tool-cards` and `codex-tool-cards-top` scenarios, phone 412×915 @DPR 2.625 and tablet
1280×800) and the tool goldens in `feature/chat/src/test/screenshots/tool-*`. The goldens render
`ChatTranscript` alone in the same well as T6.1's (see `../chat/README.md`); the fixtures
(`ToolFixtures.kt`) send the seeder's events verbatim (`parity-seed.mjs:192-228`) through the v128
reducer, the picture as the server journals it (a `/api/tool-media/…` `media_ref`), so the 03:01 / 04:01
send times match.

| Montage | Web scenario | What matches | Explained differences |
|---|---|---|---|
| `tools-<skin>-{phone,tablet}.png` | tool-cards | the finished run collapsed into one summary line ("1 file read, 2 searches, 2 shell commands, 2 file edits, 1 file write, 1 web request, 1 tool call"), red with the alert glyph because the lint Bash failed; the MCP card kept OUT of the run because its result carries a picture (`isGroupableBlock`); the card frame (graphite, lit edge, raised shadow; Studio flat 0.75rem), the tint head strip, uppercase DONE, pretty JSON input clamped at 9rem with "Show more" and its fade, the 48×32 picture at its own size with rounded corners, the text beside it (`remainingToolText`), the double rule over the output | (1) The timeline rail column (T6.1: 54dp reserved) makes the cards narrower, so the summary wraps one word earlier. (2) The web's todo bar above the composer shortens its transcript; the crops are bottom-aligned. (3) Glyph rasterisation. (4) The summary row is at least 44dp tall (touch target) where the web's is its text height. |
| `codex-top-<skin>-{phone,tablet}.png` | codex-tool-cards-top | the run held open by its file change (`hasFileChange`), the Command card (status glyph + terminal glyph, COMPLETED, the command on graphite, the cwd meta row, output on the tint, "Exit code 0"), the File changes card (path + UPDATE tag, "Patch 1" for a headerless patch, hunk band on `--tint-lg`, − / + markers in a column that stays put while the text scrolls sideways) | Rail column and rasterisation as above. |

States with goldens but no web counterpart (the seeder cannot freeze them, `parity-seed.mjs:701`):
`tool-tools-open` (the run expanded: every Claude input renderer — raw JSON, Edit / MultiEdit / Write
diffs, the failed Bash), `tool-codex-details` (Codex plan with the in-progress step on the violet
wash, the aggregate "Turn changes" diff of a turn with no inline diffs, a completed and a failed review),
`tool-running` (a live Codex command streaming `tool_output_delta`, a Claude Bash at "RUNNING · 12S"),
`tool-interrupted` (issue #184: the stop glyph, INTERRUPTED, the CLI's text behind "What the CLI
reported"), `tool-opencode` (the `task` card with `<task_result>` unwrapped, and a running one waiting),
`tool-git-changes` (the repository panel's card, T8.3 hosts it: words for every status, a start-clipped
long path, one file open with its hunks). 1.3× font-scale goldens: `tool-{tools-open,codex,git-changes}-font-1.3x`
(Machine, Studio). Tablet: `tools`, `tools-open`, `codex` (94% cards; cards inside an open run are 94%
of the run, as the web's nested `--chat-card-width`).

No syntax highlighting anywhere: the web's diffs are plain text with markers and tints.
The diff is a review aid, not a gate (PLAN §5.3). The pixel gate is `verifyRoborazziDebug`.

## Media (v94 / v112 / v122)

The web puts the journaled `/api/tool-media/<sha256>.<ext>` URL in an `<img>` on its own origin. The app
fetches it with `ToolMediaSource` (core/net `HttpToolMedia`): only a path matching the server's own
`FILENAME_PATTERN` is ever requested, built on the paired origin (no host, query or fragment from the
journal), the paired credential attached, redirects never followed (a 3xx fails), the content type
allow-listed and matched to the extension, the body capped while it streams. Pictures decode under three
bounds (32 MB encoded, 2048 px a side, 16 MB decoded; a header over 100 MP is refused before any pixel) and
are cached by URL. A clip downloads (bounded by the server's 100 MB cap) only when the reader opens it, then
plays in a platform `VideoView`; there is no inline player per row and no WebView. GIFs show their first
frame. The viewer is a full-screen dialog with the web's toolbar (zoom out / % / zoom in / reset / close),
prev/next, pinch and double-tap zoom.
