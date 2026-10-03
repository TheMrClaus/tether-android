# Interrupt, End session, error surfaces, selection & copy (T6.7)

Web reference: the S0.4 corpus frames (tether `3f69e4f`; the vendored corpus is now at `887c222`,
ta-lx3, and these montages were not rebuilt); the behaviour and copy were read from the
web code at tether `de1b0aa`. Montages are rebuilt by `tools/compare-screens/interrupt-montages.sh`. Goldens
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
| `interrupt` | `{type:"interrupt", sessionId}` on a click, from the key and from "Interrupt now" | The same frame, and no new field. Sent only from a tap on the key (its first tap, as on the web; ta-coik.13), over a live handshaken socket, while the session is live and not read-only or handed off. It is bound to the origin and, new in T6.7, to the turn the key was drawn for. |
| `interrupt_result` | `failed`: `setError(error \|\| "The interrupt request could not be delivered.")`. The other statuses say nothing. | The same. The server's words are cleaned and attributed to the server. A `requested` result for a turn other than the one the tap was bound to is reported in the app's words. |
| `{type:"error"}` | `setError(message)`, shown in the `.error-toast` | Shown from the live socket only. The text is cleaned (`LabelText.error`), shown under "From the server", and read by TalkBack as "Server error: …". |
| `error` event | Folded into `turn.error` (the outcome row) and `lastError` (the session row) | The same. Both rows are cleaned. |
| `process_exit` | Folded into `turn.exit`, never drawn | The same (a test checks that nothing is drawn). |
| `cancel_requested` | `turn.status = "cancelling"`, and the run row reads "Interrupting" | The same (TurnActivity; a test checks it). |
| `cancelled` | The outcome row "Turn interrupted", or the `turn_interrupted` account | The same (T6.6). |
| End session | A confirm dialog, then `kill` | The same words and frame, with T13.2's live-copy and same-server rules. |
| Selection | Transcript text is selectable. The chrome bars carry `user-select: none` per child, so a drag cannot run into them. The activity summary, diff gutter and thinking head are not selectable. | Each transcript row is its own selection area, so a selection can never run across rows or into the header and composer. The activity summary, diff gutter and thinking head are excluded, as on the web. Long-press selects a word; the system handles and "Copy" work from there. |

## Deliberate divergences

- *(Retired by ta-coik.13.)* The composer's Interrupt and a queued row's "Interrupt now" act on the
  first tap, as on the web (chat-view.tsx 90fbb9f :1495-1506, :4559-4573): no arm delay and no
  re-arm after a move. Kept: a press that began on a key drawn for another turn is dropped (the key
  is keyed by its turn), and touches through an overlay are refused.
- **Interrupt is bound to its turn.** The client refuses unless the key's turn is still the open
  active turn of the live projection. A tap that lands after turn A ended and turn B began sends
  nothing, and the composer says so. One race remains that the client cannot close: the server may
  already have moved to B while this client still shows A. When the server's `interrupt_result`
  then names B, the app says the interrupt reached a later turn. It sends nothing and undoes
  nothing.
- **A server's error words are attributed.** They sit under a "From the server" caption, because
  the web's bare toast would let a server write text that reads like the app's own ("The secure link
  is reconnecting… sign in again").
- **The End session confirmation closes itself.** Its key acts on the first tap, as on the web
  (dashboard.tsx 90fbb9f :1909; ta-coik.13 retired the 500 ms arm). It closes when the link drops or
  the copy stops being live, on a server switch, and when the app stops. The web's `<dialog>`
  stays open.
- **Only reading rows are selectable (r2: an allowlist).** Selectable: blocks, denials, answered
  questions, outcome and session-error rows, a Codex turn's plan, diff and review, and the
  continuation and retry markers. Not selectable: every row with an action key (the consent and
  limit cards, and the notices with their X), and the single-control rows. The web lets the cards'
  and notices' text be selected. The X itself is never part of a selection. As on the web, the
  +/- column of a Codex diff (`.diffMarker`) and of an edit diff (`.diff-gutter`) is left out of a
  copy.
- **Every interrupt control is locked while the turn is already being interrupted (r2/r3).** That
  is the composer's Interrupt key and every row's "Interrupt now". The queue's head message flushes
  into a new turn the moment the turn stops, and this client may not have seen that turn yet when
  a second tap lands. The controls say "Interrupting… the turn is already stopping." They unlock
  when the server reports that this turn's interrupt failed, so the operator can retry. The
  server-side fix is ta-yw0.
- **The toast is a surface (r2/r3).** A touch on it never reaches the composer's keys underneath.
  It only observes touches, so its own X still takes a finger that moves a little. Once it has gone,
  the key it uncovered acts on its next tap, as on the web (ta-coik.13 retired the re-arm). A server's toast is tagged with the server it came from,
  and is dropped when the configured or linked server changes.
- **The session error row** is read as "Session error: …". The web's row has only the glyph, so no
  caption is drawn.
- **No drag across rows.** Android cannot extend one selection across lazy rows without the
  runaway the web fixed, so copying spans one row at a time.
