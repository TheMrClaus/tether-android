# T7.3 slash commands, `!` commands and mentions vs the web reference

Montages (`web | android | diff`), 6 skins at phone size, built by
`tools/compare-screens/commands-montages.sh` from:

- **web**: `tools/compare-screens/commands-web-reference.py`. The S0.4 seeder reaches none of these
  states: no fake-engine step runs a `!` command, opens the palette or the `@` picker. So the reference
  is a **reconstruction**. Each state uses the web's own markup (components/chat-view.tsx at
  PARITY_BASE `7d65611`, element for element, with its class names and copy), styled by the web's own
  stylesheets at that SHA (`app/globals.css` + `app/studio.css`, read with `git show`), with the
  lucide icons and provider marks the web draws. It is rendered by a headless Chromium at the S0.4
  phone viewport (412×915 @DPR 2.625). Nothing of tether is built, served or run. The app's bundled
  Manrope / JetBrains Mono stand in for next/font. The deck is pinned to the window's foot, and the band
  is the golden's height taken from the bottom of both images.
- **android**: the goldens in `feature/chat/src/test/screenshots/commands-*` (`CommandScreenshotTest`):
  6 skins on the phone; `command` and `foreground` also on the tablet; every state at 1.3× font
  (Machine, Studio).

| Montage | What matches | Explained differences |
|---|---|---|
| `command-<skin>-phone.png` | The `.chat-command-flag` row (Terminal glyph, the web's words, `--danger` on `--danger-wash` under `--danger-edge`). The whole well turns into the red-edged command line: `:root .chat-composer-shell--command .chat-composer-well` (0,3,0) outranks Studio's own well, so every skin draws it. The draft is in the mono face. Two keys, **Send to agent** (Terminal) and **Background** (SendToBack), icon-only on a phone. | **Cascade finding:** the web's `.chat-send--command` / `--command-bg` colours (0,1,0) lose to the material layer's `:root .chat-send` (0,2,0). The web therefore paints both keys as ordinary Send keys, and so does the app; the glyphs and the accessible names tell them apart. The web's toolbar also has the browser-pane key (T8.6). Glyph rasterization. |
| `foreground-<skin>-phone.png` | While a FOREGROUND command runs, **Background** replaces Queue, and Interrupt becomes **Stop** (`chat-send chat-interrupt`, brick). Both are named for TalkBack ("Send this command to the background", "Stop the command"). | The golden also has the run row above the well (TurnActivity); the reconstruction leaves it out. |
| `slash-<skin>-phone.png` | `.chat-slash-menu`: `/name` 650 `--white` with the argument hint in `--faint`, the description in one line, the "Tether" tag (violet wash), and the blocked `/exit` at 0.72 with "terminal only" (Terminal glyph, `--mineral`). Supported entries first, by name. The first row is active (`--violet-wash`). | **Rows are 44dp** (touch target, PLAN §4); the web's are one text line plus `space-sm`, so the app's menu is taller. |
| `mention-<skin>-phone.png` | `.chat-mention-menu` **Agents** section: the uppercase section label, then per agent the provider mark, the name, the status line in words (the default model, "loading models…", "2 models"), and the violet "delegate" tag. | The **Sessions on this project** section (the takeover) belongs to T8.5 (`handoff-brief` / `handoff`). |
| `delegate-<skin>-phone.png` | `.chat-delegate-bar`: the chip (mark, `@Claude`, the model, the mode uppercase in `--faint`, the X) and the delegate's Model / Effort / Mode selects. | The selects are T7.2's `ControlSelect` pills, with 44dp touch targets. The bar wraps to a second line on a phone. |
| `panel-<skin>-phone.png` | `.chat-command-panel`: a 2px left edge (violet-strong while running, danger when failed), the head (Terminal, the command in mono, the status in words, a spinner while running), the output with stderr in `--warning`, the violet caret while running, and the log-file foot ("Full output:" / "Output truncated above — full log:"). Like the web, the panel is as wide as its content (`.chat-row` is a flex row). | The app's line height is a little taller (Compose text metrics), so the golden is taller and the band cuts off the top of the web image. |

## Behaviour (web parity, and where the app is deliberately stricter)

1. **`!` command mode is free-form, as on the web.** The command is the operator's own shell line
   (chat-view.tsx:3083-3102), not a value from an inventory. It is offered only where the server's
   `ready` sets `capabilities.commandRunner` for the session's provider. It is checked for shape only:
   non-empty, and at most 16 KiB of UTF-8 (protocol-validate.mjs). It is sent only on a tap of an armed
   key or an explicit submit (Enter, or the soft keyboard's Send). The client re-checks everything under
   its lock: a live handshaken socket, the composer's server origin, the session live and neither
   read-only, handed off nor archived, and no turn running for a foreground run. Each run gets a fresh
   idempotency key. Nothing is retried or queued (`CommandGuard`, `RealTetherClient.runCommand`).
2. **Background / Stop** are bound to the turn they were drawn for. Background (`background-command`)
   is sent only while that turn is still the open foreground command turn. Stop is T6.7's turn-bound
   `interrupt`, as the web's relabelled key is. Both keys are armed and locked on a copy that is not
   live. **Ctrl+B** does the same as Background from a hardware keyboard, and only while a foreground
   command runs. **Android addition:** the web leaves both keys live on a stale copy.
3. **Slash commands follow the web's passthrough.** The palette is offered on every engine. For Codex
   it lists only compaction, once the catalog says it is ready, and runs it through T7.2's guarded
   `CodexCompaction` control. `/model <id>` stays T7.2's guarded control. `/exit` and `/stop` (by name
   or alias) are refused with the web's words. `/clear`, `/reset`, `/new` and `/compact` warn, then
   forward. Everything else, **including a typed name the inventory does not list, is sent as ordinary
   prompt text, as on the web**: the CLI advertises exactly what it can dispatch headless, and it parses
   a leading "/" itself. Kept from T7.2: a bare `/model` on Claude opens the Model list (the web forwards
   it; see `../controls/README.md` item 4).
4. **Inventory events.** The palette reads the projection's `cliInventory`:
   - `native_session_id` carries the init inventory;
   - `cli_commands_changed` replaces the commands;
   - `cli_inventory_reset` falls back to the `session-controls` reply.

   Opening the palette asks once for the `warm` list (v96). The Inspector's CLI / Inventory rows are
   T9.1's.
5. **Mentions.** A picked agent's mention goes out only if the catalog the current server pushed
   offers it: an enabled, non-profile, non-ACP, non-Gemini row, a model that row lists, and an effort
   that model lists. Nothing is offered to a delegate child. It is sent through the durable send, like
   the web's `sendText`, idle only, never queued. Mode "build" is the parent's own permission mode
   (engines/tether-tools.mjs `planModeFor`), so it grants nothing beyond the session and needs no
   confirmation. A pending mention is dropped when its link drops or the server changes (it is keyed on the origin it was picked on). **Android difference:** the default
   model is pre-selected only when the catalog's row lists it. **Android difference:** the catalog is
   asked for when the picker first opens with none, not on every `ready`.
6. **Output and names are cleaned.** Command output goes through `LabelText.output`: ANSI escape
   sequences are removed whole; C0 / C1 controls, bidi embeddings / overrides / isolates / marks and
   invisible code points are dropped; CR / CRLF / U+2028 become line breaks. The panel draws the cleaned
   tail (16,000 characters, with a note when earlier output is cut). Command names, argument hints,
   descriptions, agent and model names, signals and paths go through `LabelText`, and TalkBack reads the
   same cleaned words. The output is plain text in a framed mono panel: it cannot draw a control, a
   link or an approval.

The diff is a review aid, not a gate (PLAN §5.3). The pixel gate is `verifyRoborazziDebug`.
