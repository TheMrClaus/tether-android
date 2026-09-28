# T7.1 session composer vs the web reference

Montages (`web | android | diff`) built by `tools/compare-screens/composer-montages.sh` from the S0.4
web reference (Chromium headless, phone 412×915 @DPR 2.625, tablet 1280×800, seeded scenarios) and the
composer goldens in `feature/chat/src/test/screenshots/composer-*`.

The goldens render `Composer` alone: 412dp at 420dpi on a phone, the web desktop chat column's 950dp at
mdpi on a tablet. The app still draws the legacy mode row (Mode pill + model chip) ABOVE the well; the
web seats Model / Effort / Mode inside the well's toolbar, and that row is T7.2's. So the pairs are
bottom-aligned bands: the whole well on a phone, the toolbar's footer row on a tablet.

| Montage | Web scenario | What matches | Explained differences |
|---|---|---|---|
| `idle-<skin>-phone.png` | idle-session | ONE recessed well (`.chat-composer-well`): the text field on top at 1rem / line-height 1.5 with the 0.6rem·`space-md` padding, the key bank at its foot (`space-xs` gap, `xs sm sm` padding), the 44dp paperclip key with the key radius, the icon-only 44dp Send key disabled while the draft is empty, the placeholder in Chromium's default `#757575` (Studio: `--faint`), the well's `--well` shadow and `--line-strong` edge, Studio's raised graphite card (1rem radius, soft drop shadow) | (1) The web's toolbar also holds the browser-pane key (T8.6) and the combined Model/settings key (T7.2), which the app does not have yet; the space between the paperclip and Send is empty until T7.2. (2) Glyph rasterization. |
| `busy-<skin>-phone.png` | streaming | the busy placeholder "Agent is working — message queues", Interrupt as the only action key (an empty Queue key is hidden on a phone, globals.css:11944), the brick Interrupt face | As above. The run row ("Working… 9m · 960 tokens" on the web) sits above the mode row in the app, so it is outside the band; its verb is `spinnerWordFor(turnId, run)`, which differs with the fixture's turn id. |
| `idle-<skin>-tablet.png` | idle-session | the desktop footer: the 1.9rem round paperclip (a 2.75rem soft square in Studio), the `SESSION` readout beside it (0.56rem/700 tracked label, JetBrains Mono 0.66rem values; Studio "Session" in the UI face), the labelled 2.1rem Send key (2.75rem in Studio) on the right | (1) The web's idle turn settled with one token of usage; the fixture's turn settled with none, so the app reads "0 tokens" where the web reads "1 token". (2) The web footer has the browser-pane key (T8.6). |

States with goldens but no web counterpart (the seeder has no draft or queue scene):
`composer-draft` (a three-line draft, focused: the well's violet-strong edge and focus-glow ring,
the auto-grown field, Send enabled) and `composer-queue` (two queued rows, the second
`flushMode: "next-call"` with "Interrupt now", and a typed draft so the phone shows Queue beside
Interrupt; phone and tablet). 1.3× font scale: `composer-{draft,queue}-font-1.3x` (Machine, Studio).

The diff is a review aid, not a gate (PLAN §5.3). The pixel gate is `verifyRoborazziDebug`.

## Behaviour (chat-view.tsx 3104-3248, 1402-1489, 1561-1584)

- **Send vs queue.** Idle sends (`send`, durable through T1.3's PendingStore); while a turn runs the
  same keys queue (`queue-add`, flushed by the server at the next turn boundary). Attachments ride only
  an idle send: a busy submit with files flashes "Wait for the current turn to finish before sending
  attachments." and keeps them. A refused send keeps the draft. Never an auto-retry: both paths are
  T1.3's `recordAndDrain`, unchanged.
- **Keys.** A hardware Enter sends, Shift+Enter breaks the line, and nothing is sent while an IME
  composition is open (the browser reports that keystroke as "Process", never "Enter"). The soft
  keyboard's action key is Send, because the phone web's keyboard Enter sends too. With the slash menu
  open, Enter / Tab accept the first match and Escape closes it.
- **Queue rows.** Each queued message is editable in place: Enter or leaving the field commits the
  trimmed text once (`queue-edit`), a blank row is removed (`queue-remove`), an unchanged one sends
  nothing, the ✕ key removes it, and a `next-call` row says "Queued — sends at the next tool boundary."
  and offers "Interrupt now" (`interrupt`). While not being edited a row follows the server text
  (another device edited it). Status is always in words (the icon's description), never colour alone.
- **Drafts.** Per server origin and session (`<origin>|tether:draft:<sessionId>` in the drafts
  DataStore): written on every change, removed when empty (on send), kept across a session switch,
  a rotation (the view model) and process death (the file), and never shown to another server. A
  logout keeps them, as the web's localStorage does. The drafts file is excluded from cloud backup and
  device transfer (`data_extraction_rules.xml`, `backup_rules.xml`, `BackupExclusionTest`).

## Divergences (logged on the bead)

1. The Model / Effort / Mode options stay in the legacy row above the well until T7.2 moves them into
   the toolbar; `!` command mode, mentions and the slash menu's arrow-key navigation are T7.3; the
   attach sheet, paste and drop are T7.4; the browser-pane key is T8.6.
2. Android is a touch device: the placeholders are the web's coarse-pointer wording on every layout (the
   web tablet reference was captured the same way). Desktop key sizes (2.1rem Send, 1.9rem paperclip)
   apply from 1024dp, the web's 64rem; below that the 44dp touch keys apply.
3. The queue row's ✕ and "Interrupt now" keep the web's drawn size inside a 48dp touch area
   (decision log, T4.1), so a row is a little taller than the web's 1.9rem.
4. **Escape on a queue row restores the server text without saving.** That is what the web's handler
   intends (`setValue(text); setEditing(false); blur()`), but its `blur()` runs `onBlur={commit}` from
   the same render's closure, whose `value` is still the edited buffer, so the web SAVES the edit on
   Escape. Upstream finding for tether: `components/chat-view.tsx:1464-1467`.
5. `components/draft-composer.tsx` (the matrix row) is the web's NEW-SESSION composer (provider,
   folder, worktree, first message); its surface is T8.1's. T7.1 ports the session composer that
   `chat-view.tsx` renders, which is what this task's text describes.
