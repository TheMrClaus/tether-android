# T13.2 offline mode and stale indicators (native-only surface)

**There is no web reference.** The web client has no saved copies and no offline mode, so none of these
screens has a web scenario to compare against, and there are no montages. They are reviewed against
SYNC_DESIGN §4 (the freshness model and its rules), not against the web.

Copies of the checked-in goldens (all six skins, phone 412×915 @420dpi and tablet/expanded 1280×800, at
**1.3× font**, reduced motion so the refresh glyph is still):

| Files | Golden source | What it shows |
|---|---|---|
| `sync-indicators-font-1.3x-<skin>-{phone,tablet}.png` | `core/designsystem/src/test/screenshots/sync-indicators-font-1.3x/` (`FreshnessScreenshotTest`) | The primitive board. The link banner in two states: `wifi-off` + "Offline. Showing saved copies" and `refresh-cw` + "Reconnecting…". The session chip: Catching up… / Saved copy · updated 12 min ago / Not downloaded. Connect to load. The sidebar glyph: `history` + "12m", and `cloud-off`. The qualified badges: "Was running · 12 min ago" and "Was waiting on you · 12 min ago", each on a faint, still dot. The "Older turns not downloaded" row. |
| `shell-sync-offline-font-1.3x-<skin>-phone.png` | `feature/shell/src/test/screenshots/shell-sync-offline-font-1.3x/` (`ShellSyncPhoneScreenshotTest`) | The phone shell offline with a running session read from a saved copy: the banner under the topbar, the header's status pill reading "Was running", and the freshness chip under the title row. |
| `shell-sync-offline-font-1.3x-<skin>-tablet.png` | same (`ShellSyncExpandedScreenshotTest`) | The same in the expanded shell. |
| `sidebar-sync-offline-font-1.3x-<skin>-{phone,tablet}.png` | `feature/sidebar/src/test/screenshots/sidebar-sync-offline-font-1.3x/` (`SidebarSyncTest`) | Sidebar rows offline. Running and waiting rows read "Was running" / "Was waiting on you" on a faint dot, with no spinner and no violet ping. Each row has its copy's glyph (`history` + age, or `cloud-off`), and the glyph pieces wrap whole at 1.3×. |

Rules checked in review (SYNC_DESIGN §4.2):

- **Never colour alone.** Every state is a Lucide glyph and words, and the words are the TalkBack label. The
  labels are asserted in `FreshnessMarksTest`, `ShellFreshnessTest`, `SidebarSyncTest` and `ChatSyncTest`.
- **Neutral ink only.** The marks use `--muted`/`--faint` on `--graphite-raised` with a `--line` edge. There
  is no violet, which means focus, selected or waiting on you, and no red, because stale is not an error.
- **Live is unmarked.** Nothing is drawn for Live (asserted).
- **A saved copy never claims anything is happening now.** The status pills say "was" and never show the
  spinner or the waiting ping. Approval and question cards from a copy that is not Live render disabled
  with their reason ("Connect to answer. This is a saved copy." offline). `ChatSyncTest` covers this,
  including a moment when freshness and the live set disagree.

The header pill drops the age ("Was running", not "Was running · 12 min ago") because the chip under the
row already carries it, and at 1.3× the longer pill crowded the session name out of the phone header.
