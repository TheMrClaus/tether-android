# T4.2 expanded shell vs the web reference

The web's DESKTOP layout (components/dashboard.tsx at 48rem and wider) for Android windows at or
above the 840dp expanded width (PLAN D10, TRACKER decision 2026-09-27). Code:
`feature/shell/src/main/java/com/tether/app/ui/shell/` (`ExpandedShell.kt`, `PanelResizeHandle.kt`,
`PanelWidthGeometry.kt`, `ExpandedTopbar.kt`, plus the expanded variants of `WorkspaceHeader`,
`TelemetrySheet`, `EmptyWorkspace`). `MainShell` picks the layout with `shellLayoutFor()`.

Montages (`web | android | diff`) are built by `montages.sh` from the S0.4 web reference (Chromium,
1280×800 @1x, seeded scenarios) and the Roborazzi goldens at `w1280dp-h800dp-mdpi` (1 px/dp), so
crops compare 1:1. Like the phone shell, the goldens fill hosted surfaces with stand-ins: the
rail's session list (T5.1), the transcript and composer (T6/T7; the stand-in draws only the chat
screen's frame), and the inspector (T9.1). The gauge and dial are T4.3's real components on the
web's frozen clock.

## What the layout does (web source at PARITY_BASE 7d65611, unchanged through 0e6e862)

| Piece | Web | Android |
|---|---|---|
| Grid | `var(--rail-width) minmax(0,1fr)` under a 3rem topbar (Studio 4rem); from 100rem a third `var(--inspector-width)` column (globals.css 3957-3962, 4139-4143; studio.css 280) | `ExpandedShell`: topbar, then a row of rail, workspace and (≥ 1600dp, with a session) inspector column |
| Column widths | stored px width through CSS `clamp(minRem, px, min(maxRem, maxVw))`, else the theme default (panel-widths.mjs:101-106; dashboard.tsx:448-471) | `PanelWidthGeometry.effectiveWidth`, re-clamped against the window on every layout |
| Theme defaults | instrument rail 18rem, 16.5rem at 48-100rem, 20rem from 100rem; inspector 16.5rem, 17rem from 90rem (globals.css 70-71, 11727, 11856); Studio 17rem / 18rem (studio.css 48-49) | `PanelWidthGeometry.defaultWidth`, checked against the generated tokens |
| Handles | 1rem strip straddling the column edge; drag (2px threshold), ←/→ 16px, Shift 64px, Home and double-click reset; `role=separator` with value/min/max (panel-resize-handle.tsx) | `PanelResizeHandle`: drag, double tap, hardware-keyboard arrows / Home, TalkBack adjustable range + "Reset to default width" |
| Persistence | per-device `sidebarWidth` / `inspectorWidth` / `sidebarCollapsed` (use-preferences.ts:164-171, 342-343) | the same `TetherPreferences` fields in `UiPrefs` (DataStore); a drag commits once on release |
| Collapsed rail | the column drops to 0, the bottom-left expand dock shows (3964-4003) | `PanelPrefs.sidebarCollapsed` + `ExpandDock` |
| Telemetry | below 100rem the sheet floats beside the conversation (11894-11910); from 100rem the inspector column owns it and the gauge is tooltip-only (dashboard.tsx:74-82) | the floating `TelemetrySheet`; the inspector column and a non-toggling gauge |
| Topbar | no menu key; brand on the rail's vertical; link readout; tool words from 80rem; "Lock" (3974-4031, 10892-10948, 11843-11847) | `ExpandedTetherTopbar` |
| Header | dial, labelled gauge, links key, Pin with its word, End session with its legend (4019-4021, 4117-4121, 11882) | `WorkspaceHeader(expanded = true)` |

## Panel-width semantics (lib/panel-widths.mjs)

The geometry is the T2.2 port `com.tether.app.protocol.helpers.PanelWidths` (conformance-tested
against the JS). `PanelWidthGeometry` is its typed entry point. `PanelWidthGeometryTest` covers:
the limits (rail 14-30rem / 40vw, inspector 13-28rem / 35vw, :30-33), bounds where the rem floor
beats a smaller vw ceiling (:81-87), clamp with JS half-up rounding and junk going to the floor
(:91-95), the drag direction (the inspector's edge is its left side, :113-117), the key deltas
(:124-133), fail-soft stored values that keep out-of-range numbers (:66-74), the theme defaults per
breakpoint, and a round-trip through the preference model. `PanelPrefsPersistenceTest` round-trips
through `UiPrefs`. CSS px are drawn 1:1 as dp and 1rem = 16dp. As in every other shell surface,
only text follows the font scale, and the media breakpoints are unscaled dp.

## Montages

| Montage | mean abs. diff | What matches | Explained differences |
|---|---|---|---|
| `bay-<skin>-tablet.png` (rail edge + stage bay) | 0.00-0.01 / 255 | the rail's `1px --line-strong` edge with its `--seam-lip` shade, the bay floor, the stage padding and the screen's bezel ring, pixel for pixel | none |
| `idle-<skin>-tablet.png` (topbar + header band) | instrument 7.6-8.0; Studio 15.2-15.4 | bar heights (48 / 64dp incl. the parting line), the brand's position and the rail-wide Studio brand block, the readout plate, the recessed labelled tool bank and Lock, the header band (61dp; Studio 80dp), the title, dial plate, labelled gauge, links key, Pin and End session positions within 1-9px | (1) **the rail's content**: the web draws the New session key and list here (T5.1); the stand-in is empty. In Studio that key is a large blue block, which is most of the higher Studio figure. (2) **Tracked mono legends measure narrower**: "SECURE LINK", "READY" and the dial digits come out about 0.6px per character narrower than Chromium's at 1 px/dp (for example, the readout text is 63px here and 70px on the web). The same shows in T3.3's status pill, so this is the text stack, not this layout. The plates are therefore a few px narrower, and the rail items to their right shift by the same amount. (3) Manrope's title and label advances (as in T4.1). |
| `details-<skin>-tablet.png` (header band + the floating sheet's header) | band as above; sheet 1.3-1.6 (instrument), 5.2-5.3 (Studio) | the sheet's placement (4.5rem down, `space-sm` from the right edge, 23rem wide), its `1px --line-strong` frame, `--radius-lg`, floating shadow, and its header ("Session details" + close) | **The gauge while the sheet is open**: the web shot was taken right after a tap, so Chromium's touch-emulated `:hover` paints the raised or grey key face. The app follows the T3.3 decision (pressed = `:active` only), so it shows the open state as written: a violet wash in the instrument skins, and violet ink on a flat key in Studio. The same explanation appears in the T4.1 and T4.3 READMEs. The sheet's body is the inspector (T9.1). |
| `empty-<skin>-tablet.png` (workspace column) | instrument 2.3-2.6; Studio 15.8-16.7 | the well seated in the bay (`calc(space-lg + 7px)` margin, bezel ring and contact shade), the orbit instrument, the title at `clamp(1.7rem, 2.6vw, 2.15rem)`, the paragraph, the primary key and the providers rule | Font metrics as in T4.1. **Studio**: the web forks this stage into `StudioWelcome` ("Room for your next big idea.", T8.1). Until T8.1 fills `PhoneShellSlots.studioWelcome`, the app shows the instrument stage in Studio tokens, as the phone shell does. |

States with no web reference have goldens only: `resized` (a stored 360px rail), `collapsed` (the
dock), `links` (the desktop popover: "Copy Tether id", no pin), the 900dp `foldable` set (no tool
words, the narrow stage gutter, the 40vw ceiling), the 1680dp `desktop` set (`column`: the
inspector column at its defaults, 320 / 272; `column-resized`: 300 / 360), and 1.3× font scale
(`*-font-1.3x`: idle, details and links in Machine and Studio).

## Deliberate deviations (for review)

- **Handle touch area.** The web's handle is 1rem wide so it does not swallow the rail's and the
  transcript's own edge controls. The app draws the same 16dp strip. Compose's pointer
  hit-expansion gives it a 48dp touch area where no other control is hit.
  `theHandleTakesATouchBesideItsDrawnStripOn{Stage,Rail}Side` prove that a touch 23.5dp either
  side of the strip's centre drags it.
- **TalkBack instead of `role="separator"`.** Compose has no separator role. The handle is an
  adjustable range (the width in px between the bounds, named "Resize session sidebar" /
  "Resize session details"), and a custom action stands in for Home / double-click.
- **Status and navigation bars.** The web's desktop topbar has no safe-area padding. The app keeps
  the status-bar inset on the topbar and the navigation-bar inset under the rail and the dock,
  because an Android tablet still has system bars.
- **The phone drawer closes on entry.** A window that grows past 840dp with the drawer open closes
  it, so back is never consumed by an invisible surface. The popover and telemetry state carry
  across the cutoff (both layouts share `PhoneShellState`).

## Deferred to other tasks

- The rail's own collapse key (`.sidebar-collapse` in the sidebar footer) belongs to T5.1's
  sidebar. The shell exposes the state (`PanelPrefs.sidebarCollapsed`) and the expand dock.
- The `[` keyboard shortcut (collapse / expand) lives in the web dashboard's global key handler
  with `n` and `j` / `k`, which no Android task ports yet.
- `.is-sidebar-collapsed .chat-composer` clears the dock by padding the composer (T7).
- The timeline marker in the stage's left gutter (ConversationTimeline) and the chat screen frame
  itself belong to T6. The shell provides the bay padding they sit in.

The diff is a review aid, not a gate (PLAN §5.3). The pixel gate is `verifyRoborazziDebug`.
Regenerate the goldens with `./gradlew :feature:shell:recordRoborazziDebug`, then run
`docs/parity/screens/shell-expanded/montages.sh [web-screens-dir]`.
