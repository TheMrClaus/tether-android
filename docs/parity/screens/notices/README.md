# Notices, limits, model fallback, handoff / read-only, MCP health (T6.6)

Web reference: tether `7d65611` (PARITY_BASE), checked against `79c3d37` (v129–v132 change none of
these surfaces). Montages rebuilt by `tools/compare-screens/notice-montages.sh`; goldens in
`feature/chat/src/test/screenshots/notice-*` and `feature/shell/src/test/screenshots/inspector-*`.

## Montages (web | android | diff)

| File | Web scenario | Android golden |
|---|---|---|
| `outcome-unknown-<skin>-phone.png` / `-tablet.png` | `notices` (a turn recovered as outcome_unknown) | `notice-outcome-unknown` |
| `handoff-lock-<skin>-phone.png` / `-tablet.png` | `handoff-source-locked` (the composer's "Continued in →") | `notice-handoff` |

6 skins × phone + tablet = 24 montages. Remaining differences:

- **Clock stamps** (06:01 vs 01:01): the web seeder's journal time differs from the fixture's.
- **Target name** ("Handoff target" vs "Parser rewrite"): fixture data.
- **Line breaks**: Manrope rasterises a little wider on Android at the same 0.78rem, so the
  outcome sentence wraps one word earlier on the phone.
- **Handoff link height**: the link is a 44dp target (12dp vertical padding). The web's inline
  button is text-high, so the Android link sits about 2px lower.

## States with no web reference

The web's fake engine journals the api retry, failed MCP health and warning, but renders no inline
notice for them (manifest note on `notices`). It seeds none of the other states, and `session-details` has no MCP
card. So these are goldens only, built from the reducer's own event shapes
(`NoticeFixtures`, `InspectorFixtures`), and compared by eye against the web code:

`notice-fallback` (Claude model fallback, issue #179), `notice-codex` (Codex compaction + info +
error notices), `notice-session` (external advancement, background loss ×2), `notice-interrupted`
(issue #184), `notice-limit` (the limit card), `notice-scheduled` (armed resume), `notice-retry`,
`notice-read-only`, `inspector-mcp`, `inspector-plugins`, `inspector-rate-limit`.

## Divergences from the web (deliberate)

- **Limit card keys** act on the first tap, as on the web (ta-coik.13 retired the 500 ms arm; a
  press across a change of prompt or session is dropped). They refuse touches through an overlay,
  and send once per link: the card then says "Choice sent. Waiting for the server." The web
  re-enables its keys after 4 s. Here a new connection re-enables them; nothing is ever retried
  automatically.
- **Rate-limit choices and the scheduled resume's cancel** take T7.2's guarded `sessionControl`
  path. They are bound to the prompt's exact `resetsAt`. All are locked on a read-only session
  (server `READ_ONLY_MUTATIONS`). On a handed-off source, Schedule and Resume now stay locked
  (they would start work there), but Dismiss and the scheduled row's cancel stay live: a resume
  left scheduled would otherwise start a turn in the source after the handoff (the web draws that
  X ungated and the server allows it). The card then says the prompt can still be dismissed here.
  "Take over in a new session" is not offered (handoff is T8.5).
- **Dismiss X** is allowed read-only and handed off (the server and web allow it), but only on a
  live link. It sends once per connection, and the client re-checks that the projection still
  shows the key.
- **Provider notice level** is also spoken for TalkBack ("Warning" / "Error" / "Info"). Visually,
  warning and error share the web's triangle and differ by colour and edge, as on the web.
- **MCP "View error"** is a 44dp target (the web's summary is 2rem).
- **Inspector**: the rate-limit notice and MCP health mount in the interim telemetry body until
  T9.1 builds the inspector. The inspector's duplicate Codex session notices are left to T9.1,
  since the chat already shows them with their X.
- **Outcome row** now uses the web's body weight (400); it was the label weight.
