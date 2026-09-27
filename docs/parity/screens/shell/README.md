# T4.1 phone shell vs the web reference

Montages (`web | android | diff`) built by `tools/compare-screens/shell-montages.sh` from the S0.4
web reference (Chromium headless, 412×915 @DPR 2.625, seeded scenarios) and the shell's Roborazzi
goldens in `feature/shell/src/test/screenshots/` (412dp @420dpi = 2.625 px/dp, so crops compare 1:1).

The goldens render `PhoneShell` with **empty slots** where other tasks' surfaces go: the chat stage
(transcript + composer, T6.x/T7.x), the drawer's session list (T5.1), the telemetry panel's body
(the inspector, T9.1). So the montages pair the chrome T4.1 owns. The Session links popover
(`shell-links`) has no web shot, so it has goldens only.

| Montage | Web scenario | What matches | Explained differences |
|---|---|---|---|
| `idle-<skin>-phone.png` | idle-session | topbar height (56dp incl. the 1px parting line), lit lip and seam shade; menu key, brand cap and needle, wordmark, the recessed four-key tool bank (34.4dp keys, 2px pad/gap, `--well`), Lock key position (±1px, measured with `align`); the header band (61dp), title/pencil/pill/action cluster positions, End session's brick key at 2.35rem (Studio 2.75rem) | (1) **gauge**: the web draws T4.3's live 270° arc gauge; the slot holds a placeholder (the lucide gauge in the same 2.75rem `.telemetry-button`) until T4.3 fills it. (2) The title ellipsis breaks at a different character (Chromium keeps "Summarize …" at the word space) and the title glyphs sit about 1.5dp lower. Both come from Manrope's advance widths and vertical metrics in Android's text stack vs Chromium's. Setting the title's CSS line-height on or off does not move it, so this is metrics, not layout. (3) The wordmark starts about 1.5dp further right (advance and side-bearing rasterization). (4) The rest of the red is glyph-edge and stroke rasterization. |
| `details-<skin>-phone.png` | session-details | the panel's placement (in the workspace column under the header, replacing the stage), its `1px --line` top edge, the header band (8dp × 16dp padding, 44dp close key, 1px rule), the "Session details" type (1rem / 650) | (1) **gauge while the panel is open**: the web shot was taken right after a tap, so Chromium's touch-emulated `:hover` paints the raised key face (`:root .telemetry-button:hover`, (0,3,0), beats `.context-gauge.is-open`). The app follows the T3.3 decision (pressed = `:active`, no touch-hover), so it shows the open state as written: violet wash in the instrument skins, and transparent with violet ink in Studio (Studio's flat rail rule, (0,3,1), beats `.is-open`). (2) The body is the inspector slot (T9.1); the web shows the Inspector. (3) Title metrics as above. |
| `drawer-<skin>-phone.png` | session-drawer | the container's right edge at the same pixel (320dp; Studio 336dp, `min(21rem, 92vw)`); the `--scrim` backdrop over the whole shell including the topbar; Studio's fixed ink-blue panel (`#141d2e`), no right border | The list content (web: close ×, New session key) is T5.1's; the slot is empty. |
| `empty-<skin>-phone.png` | empty-state | the screen well (`space-md` margin, `--radius-lg`, `1px --line-strong`, `--well`); the 7rem orbit instrument, its three rings and the satellite dot; title (1.7rem / 640 / −0.035em); paragraph measure and wrap; the primary key (3rem tall, `0 space-xl` padding); the providers rule and legends | (1) The whole block sits a few px off vertically: it is centred in the well, and its text heights come out slightly different (the same font metrics as above). (2) The primary key is about 3dp narrower because the legend is narrower (glyph advances, as in the T3.3 footer keys). (3) The providers row wraps "PI" onto the second line: JetBrains Mono's tracked advances come out slightly wider than in Chromium, and the first line is within 2dp of the well width. (4) **Studio**: the web forks this stage into `StudioWelcome` ("Room for your next big idea.", T8.1). The app passes it as `PhoneShellSlots.studioWelcome`. Until T8.1 fills that slot, Studio renders the instrument stage in Studio tokens, which is why the Studio diffs are large. |

## Deliberate deviations (for review)

- **The telemetry "sheet" is a panel, not a bottom sheet.** The web's v5x `TelemetrySheet` is a
  collapsible panel in the workspace column. It is not a modal and not a sliding sheet, and the
  header gauge is its handle (telemetry-sheet.tsx, globals.css 3775-3852). The app ports that form
  exactly. The chat stage stays composed while the panel is open, so its scroll position and
  composer draft survive, but it is not placed, drawn, touchable or announced.
- **Touch targets.** The web draws some phone controls smaller than 44dp: the tool-bank keys are
  2.15rem (34.4dp), the rename pencil is about 24dp, and the instrument End session key is 37.6dp
  tall. The app keeps the drawn sizes, and Compose extends each clickable's hit area to the 48dp
  minimum touch target. `PhoneShellBehaviourTest.everyShellControlHasA44dpTouchTarget` asserts a
  touch area of at least 44dp on every shell control, and `renamePencilAcceptsATouchOutsideItsDrawnBounds`
  proves the extension works. Neighbouring tool keys share the 2px gap between them.
- **Lock asks first.** The web's Lock logs out at once. The app keeps its existing
  "Sign out of this server?" confirmation, because on a phone signing out forgets the paired
  credential. Only the owner can decide whether to change that.
- **Lock has a name.** The web's Lock word is `display: none` on phones, which leaves the button
  without an accessible name. The app names it "Lock".
- **The closed drawer is hidden from TalkBack.** The web's off-canvas sidebar stays in the
  accessibility tree while closed. The app removes it from both touch and semantics.
- **Hosts not built yet.** Files (T11.1), Accounts and Usage (T9.2) have no screen in the app yet.
  `MainShell` passes `null` for them, so those keys render disabled, which differs from the web
  shots. The goldens pass live hosts, so they show the chrome as it will look. Health opens the
  existing activity log, which T4.5 will replace.

Accessibility covered by tests: names for every control ("Open sessions", "Console tools", "Browse
workspace files", "Account usage", "Usage analytics", "Health & event log" with an "N warnings"
state, "Lock", "Rename session", "Session telemetry" with a toggle state, "Session links", "End
session", "Close sessions", "Close"); the status pill's printed word; provider availability read
as "available" or "unavailable", never by the dot colour alone. 1.3× font-scale goldens:
`shell-{idle,empty,details}-font-1.3x` (Machine and Studio). The links popover is capped at
`calc(100dvh - 8rem)` and scrolls (T4.2 follow-up): `shell-links-short-font-1.3x` (412×320 at 1.3×)
and `LinksPopoverShortScreenTest`.

The diff is a review aid, not a gate (PLAN §5.3). The pixel gate is `verifyRoborazziDebug`.
