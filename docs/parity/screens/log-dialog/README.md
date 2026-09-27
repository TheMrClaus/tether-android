# T4.5: Health & Event Log dialog

Native port of `components/log-dialog.tsx`, the server `log` message, and the topbar's unseen-warnings
badge (`components/dashboard.tsx:194-199`), measured against tether @ PARITY_BASE `7d65611`.

## What is where

| Piece | Kotlin | Web source |
|---|---|---|
| `log` frame decoder, tolerant `LogEntry` (typeof checks per field) | `core/protocol/.../ServerMessage.kt` (`LogEntry.fromJson`) | lib/protocol.ts 3153-3167, 3200 |
| Event-log fold (seq dedupe, bootId restart, 500 cap) | `core/net/.../client/EventLog.kt` | hooks/use-tether.ts 274-277, 1047-1059 |
| GET /api/stats (any credential) | `RealTetherClient.fetchStats`, `ServerStats` | log-dialog.tsx refreshStats; server.mjs computeStats |
| Unseen-warnings badge, `openLog` | `TetherViewModel.unseenWarnings` / `openLog()` | dashboard.tsx 194-199 |
| Row/tile/meta strings, filters, ordering | `feature/shell/.../ui/log/LogReadings.kt` | log-dialog.tsx 39-86, 130-147 |
| The dialog | `feature/shell/.../ui/log/LogDialog.kt` | log-dialog.tsx 149-282; globals.css 3308-3327, 4546-4657, 4737, 9144-9160, 9255-9281, 11593-11606; studio.css 504-547, 792-832, 954-966, 1017-1024 |

## Behaviour

- Rows are newest first. **All** shows every entry. **Warnings** hides only `info`, so an entry with no
  level counts as a warning, as on the web. The session menu lists every sid the log mentions, in the
  order first seen, by session name or else by short id. It is hidden when the log names no session.
  The filters, the last stats snapshot and the last stats error stay in place when the dialog is
  closed and reopened, because the web `<dialog>` stays mounted.
- Opening the dialog acknowledges the warnings and fetches stats. **Refresh** fetches them again. A
  failed fetch shows `stats request failed (<status>)` above the previous snapshot. A fetch that
  failed without an HTTP status shows "Could not load stats." (the web's fallback).
- The badge counts `max(0, warnings - warnings acknowledged at the last open)`. When a server restart
  empties the log, the badge comes back only once the count rises past the acknowledged mark, as on
  the web. Client-side errors go to the toast only. They no longer count on the badge. This replaces
  the old `errorLog`, which counted every error and never cleared (the T4.1 verifier's defect).
- The log is kept across reconnects, because the replayed tail dedupes. It is emptied on sign-out,
  on a server-side sign-out and on a new sign-in, the same lifecycle as the node registry.
- Dismissal: Back (the web's Esc), Close and Done. A tap on the backdrop does nothing (a web
  `<dialog>` has no backdrop handler).
- TalkBack: each row reads "time, level, label, session, turn, detail". The level is spoken as text,
  so a status is never shown by colour alone. Each tile reads "caption: value" and each meta line
  reads "label: value". The title is a heading. The filter keys expose their selected state. They are
  drawn at the web's size, and Compose widens their touch target to 48dp (tested).

## Montages (`montages.sh`)

The S0.4 corpus has no log-dialog scenario. `capture-web.mjs` adds one to the S0.4 harness in memory
(no tether file is changed) and captures the idle-session page with the log open, in 6 skins at phone
and tablet size. The `log-dialog-web` goldens render the same state natively: the fake server's stats
and its connection records at the frozen 07:02:00 clock.

- **Tablet row counts differ by skin.** Each web tablet capture reloads the page, so its log holds a
  different number of `ws.*` records (3 to 7). The native tablet golden always shows 3 (the Studio
  capture's count), so the case is shorter and is centred lower. Compare the case itself, not its
  vertical position.
- **Backdrop.** The web frames show the app behind the scrim. The goldens show a plain scrim.
- **Remaining differences** are font rasterisation, plus a slightly fainter outline on the
  instrument filter strip.
- **Width.** The instrument case is `min(58rem, 100vw - 1.5rem)`, and the UA sheet's
  `max-width: calc(100% - 6px - 2em)` still caps it. On a phone that makes it 374dp.
- **Stat values.** These use `line-height: 1` (Studio 1.25), which is tighter than the face's natural
  line. Compose's `lineHeight` never shrinks below the natural line, so the values are wrapped in an
  explicit CSS line box.

## Divergences (web wins unless noted)

- The row `title` (the full JSON record on hover) has no touch equivalent and is not ported. Mobile web
  cannot show it either.
- The 5-second re-render while the dialog is open is not ported. It changes nothing visible, because
  uptime comes from the fetched snapshot.
- The session menu opens start-aligned (`TetherSelect` has no `alignEnd`). The popup is clamped to
  the window, so it stays on screen.
- Clock times use the fixed en-US `hh:mm:ss a` pattern. This follows the `lib/format.ts` port's
  `clockTime`.
