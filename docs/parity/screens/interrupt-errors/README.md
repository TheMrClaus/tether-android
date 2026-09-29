# Interrupt, End session, error surfaces, selection & copy (T6.7)

Web reference: the S0.4 corpus (tether `3f69e4f`), checked against tether `de1b0aa` (none of these
surfaces changed). Montages are rebuilt by `tools/compare-screens/interrupt-montages.sh`. Goldens
live in `feature/chat/src/test/screenshots/{composer-busy,interrupt-busy,interrupt-refused,chat-errors,end-session-confirm}*`
and `feature/shell/src/test/screenshots/error-toast-*`.

## Montages (web | android | diff)

| File | Web scenario | Android golden |
|---|---|---|
| `interrupt-<skin>-phone.png` | `streaming` (the busy well, Interrupt icon-only) | `composer-busy` |
| `interrupt-<skin>-tablet.png` | `streaming` (the toolbar footer: Queue + Interrupt) | `interrupt-busy` |

That is 6 skins × phone + tablet = 12 montages. The remaining differences:

- **The in-console browser key** (#162, the web's `AppWindow` button) sits between attach and the
  model pill on the web. Android does not have it; it is not part of T6.7.
- **The model glyph** and the Manrope rasterisation are as in the T7.1 composer montages.

## States with no web reference

The web seeder's fake engine never freezes a frame on any of these states, so they are goldens only,
built from the reducer's own event shapes (`InterruptErrorFixtures`) and checked by eye against the
web code:

- `chat-errors` (6 skins, phone + tablet, 1.3×). A failed turn: its `.chat-outcome-error` row shows
  the turn's `error`. The session's `lastError` (an `error` event with no turn) follows it as the
  web's `.chat-outcome.chat-outcome-error` row (chat-view.tsx:3534-3536). The engine's words are
  cleaned (`LabelText`): a line break and a right-to-left override in the fixture are removed.
- `interrupt-refused` (6 skins, phone, 1.3×). The busy deck after a tap whose turn had already
  ended. The refusal is said in words: "That turn already ended — the turn running now was not
  interrupted."
- `end-session-confirm` (6 skins, phone, 1.3×). The web's words (dashboard.tsx:1902-1911): "End
  session?", "<name> — its running process will stop.", then Cancel and End session.
- `error-toast-server` (6 skins, phone + tablet, 1.3×) and `error-toast-local` (6 skins, phone). The
  web's `.error-toast`.

## What the web does, and what Android does

| Surface | Web | Android |
|---|---|---|
| `interrupt` | `{type:"interrupt", sessionId}` on a click, from the key and from "Interrupt now" | The same frame, and no new field. Sent only from a tap on an armed key, over a live handshaken socket, while the session is live and not read-only or handed off. It is bound to the origin and, new in T6.7, to the turn the key was drawn for. |
| `interrupt_result` | `failed`: `setError(error \|\| "The interrupt request could not be delivered.")`. The other statuses say nothing. | The same. The server's words are cleaned and attributed to the server. A `requested` result for a turn other than the one the tap was bound to is reported in the app's words. |
| `{type:"error"}` | `setError(message)`, shown in the `.error-toast` | Shown from the live socket only. The text is cleaned (`LabelText.error`), shown under "From the server", and read by TalkBack as "Server error: …". |
| `error` event | Folded into `turn.error` (the outcome row) and `lastError` (the session row) | The same. Both rows are cleaned. |
| `process_exit` | Folded into `turn.exit`, never drawn | The same (a test checks that nothing is drawn). |
| `cancel_requested` | `turn.status = "cancelling"`, and the run row reads "Interrupting" | The same (TurnActivity; a test checks it). |
| `cancelled` | The outcome row "Turn interrupted", or the `turn_interrupted` account | The same (T6.6). |
| End session | A confirm dialog, then `kill` | The same words and frame, with T13.2's live-copy and same-server rules. |
| Selection | Transcript text is selectable. The chrome bars carry `user-select: none` per child, so a drag cannot run into them. The activity summary, diff gutter and thinking head are not selectable. | Each transcript row is its own selection area, so a selection can never run across rows or into the header and composer. The activity summary, diff gutter and thinking head are excluded, as on the web. Long-press selects a word; the system handles and "Copy" work from there. |

## Deliberate divergences

- **Interrupt keys are armed.** The composer's Interrupt and a queued row's "Interrupt now" are not
  usable for 500 ms after they appear for a turn. A new turn re-arms them, and so does a move of
  more than 4dp. Touches through an overlay are refused. While a key arms it is drawn as before,
  and a tap on it does nothing. The web's buttons act at once.
- **Interrupt is bound to its turn.** The client refuses unless the key's turn is still the open
  active turn of the live projection. A tap that lands after turn A ended and turn B began sends
  nothing, and the composer says so. One race remains that the client cannot close: the server may
  already have moved to B while this client still shows A. When the server's `interrupt_result`
  then names B, the app says the interrupt reached a later turn. It sends nothing and undoes
  nothing.
- **A server's error words are attributed.** They sit under a "From the server" caption, because
  the web's bare toast would let a server write text that reads like the app's own ("The secure link
  is reconnecting… sign in again").
- **The End session confirmation is armed and closes itself.** It closes when the link drops or
  the copy stops being live, on a server switch, and when the app stops. The web's `<dialog>`
  stays open.
- **The consent and limit cards are not selectable.** No long press or drag should compete with
  their armed keys. The web lets their text be selected.
- **No drag across rows.** Android cannot extend one selection across lazy rows without the
  runaway the web fixed, so copying spans one row at a time.
