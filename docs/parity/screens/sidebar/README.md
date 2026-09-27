# T5.1 session list vs the web reference

Montages (`web | android | diff`) built by `tools/compare-screens/sidebar-montages.sh` from the S0.4
web reference `session-drawer` (Chromium headless, phone 412×915 @DPR 2.625 and tablet 1280×800) and
the sidebar's Roborazzi goldens in `feature/sidebar/src/test/screenshots/` (phone 412dp @420dpi =
2.625 px/dp, tablet 1280dp @mdpi, so crops compare 1:1).

The goldens render `SessionSidebar` (components/session-sidebar.tsx) inside a copy of its host
container: the phone drawer (`min(20rem, 88vw)`, Studio `min(21rem, 92vw)`, padded `space-md`) and
the expanded layout's 264dp rail. feature/shell owns the real containers (T4.1 `SessionDrawerHost`,
T4.2 `SidebarColumn`), and both host the same `SessionDrawer` composable. The fixture reproduces the
seeded scenario: one workspace block (`ws`, current, unpinned), 11 live headless chats, and Hide runs
on.

| Montage | Mean diff | What matches | Explained differences |
|---|---|---|---|
| `drawer-top-<skin>-phone.png` | 7.9–10.5/255 | the "Workspaces" mobile header and close key; the New session key (face, legend, `N` cap: charcoal in the instrument skins, the white 15% chip in Studio, where the rail's token scope makes it `#365cde`); the legend and its count pill; the recessed filter bank with Hide runs latched; the two recessed wells | (1) **Scheduled actions** renders disabled (0.48) with no count. Its host (T9.3) and the `scheduled-actions` data are not built yet, so the web's "2" badge has no source. This follows the T4.1 rule for keys whose host is missing. (2) The **New session key is 44dp tall** (web `2.6rem` = 41.6dp), for the 44dp minimum target, so everything below it sits about 2.4dp lower. (3) **Studio phone filter keys** are 2.5rem wide (web 2.75rem) so the legend and its count fit on one line in the 21rem drawer. The touch target stays ≥ 44dp. (4) The rest is glyph rasterization and Manrope metrics (the same as the T4.1 / T3.3 montages). |
| `drawer-rows-<skin>-phone.png` | 7.6–9.9/255 | the current block header (violet wash + edge; Studio borderless on the scoped `#263b66`), its badge, dot, chevron, + and star; the row grid (40dp handle with the 28dp molded provider cap, copy, chevron, the 44dp end ×); two-line clamped titles; CHAT tags (hidden in Studio); status dot + words + time; the `~/repo` sub-path; the footer seam and PRIVATE RUNTIME | The rows drift a few px per row (row pitch within about 3dp). This comes from the two-line title's line-height (1.4) under Android's text layout, and from Studio's `0.75rem` row padding meeting a slightly shorter status line. It is metrics, not layout: every padding and gap is the cited CSS value. |
| `column-<skin>-tablet.png` | 12.1–13.3/255 | no mobile header; the Collapse key in the footer; the filter bank running past the 264dp rail as on the web; the ellipsized two-line titles; the 16px chevrons in the desktop's 0.75rem grid column | (1) The web tablet crop starts under its 48px (Studio 64px) topbar. The golden's rail starts at y = 0, so a sub-pixel start offset remains. (2) Scheduled actions and New session as above. (3) Title wrapping differs where Chromium and Android break at different characters in a very narrow column ("Worktree / with a…" vs "Worktree / with a servi…"). |

States with no web shot (goldens only, all 6 skins at phone size unless noted):
`sidebar-empty` (connected, no sessions: "No sessions yet"), `sidebar-groups` (two kept workspaces +
current, activity dots, `1`/`2` kbd caps, a folded block, delegate children open under their
parent, "Reset to default order"; also at tablet), `sidebar-archived` (the Archived group open),
`sidebar-status` (Active spinner, Needs you radar ping, an unread row with its "3 new turns since
you left" digest, a history-only row "1d ago", a handed-off source), `sidebar-row-actions` (the armed
**END?** control, a row swiped open onto **ARCHIVE**), `sidebar-sort-menu`, `sidebar-harness-menu`,
`sidebar-drag` (a hold-and-drag in progress: the lifted row, the block's tentative order),
`sidebar-unread-lens` (Unread on: only unseen work, blocks forced open). The 1.3× font-scale goldens
cover `drawer` and `status` in Machine and Studio.

## Deliberate deviations and scope notes (for review)

- **No per-row actions menu, no rename in the list.** The web's session list has no row menu.
  Its row actions are the trailing end control (two taps: × then **End?**, disarmed after 4s) and, on
  phones, a swipe-left that reveals **Archive**. Both send `kill` (`dashboard.tsx:1314`), because the
  server archives a killed session. **Pin and rename** live in the workspace header for the open
  session (T4.1, `dashboard.tsx:1095/1109`). The web never sends `archive`. The app matches this.
  The four frames are pinned to the corpus in `SidebarSyncTest`.
- **The sidebar's end control does not open a dialog.** As on the web, the two-tap arm is the
  confirmation (`dashboard.tsx:1311-1313`). The header's End session keeps its confirm dialog (T4.1).
- **TalkBack reorder.** The web's reorder is pointer-only. The app adds "Move up" / "Move down"
  custom actions on each drag handle. They commit the same full-order `set-session-order`.
- **Touch targets.** The filter-bank keys (1.7rem), block actions (2.25rem wide) and delegate toggle
  are drawn at the web's size. Compose extends each hit area to the 48dp minimum
  (`SidebarBehaviourTest.everyControlHasA44dpTouchTarget`). Menu items are 2.75rem tall (web 2.25rem).
- **Archived group** is capped at 12rem and scrolls when open. On the web the list above it shrinks
  instead.
- **Hosts not built yet**: Search all conversations (T5.3) and Scheduled actions (T9.3) render
  disabled. The title filter works, but its content hits (`search`) are T5.3's. New session and Add
  workspace open the existing interim provider and folder pickers (T8.1 / T8.2). Settings opens the
  interim settings dialog (T10.1). The DeepSeek peak badge (#193) is T9.2's.
