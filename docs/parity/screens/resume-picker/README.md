# T5.2 resume picker vs the web reference

**The web has no separate history or resume picker.** Discovered conversations (`histories`, one list
per watched workspace, v93) are merged into the session sidebar as **history-only rows**
(`dashboard.tsx` buildBlock, `session-sidebar.tsx`). A tap on one calls `onReopenHistory` →
`dashboard.tsx` `reopen` → `use-tether.ts` `resumeHistory`. The app does the same: the picker is the
T5.1 `SessionSidebar`, and T5.2 finishes the resume path behind it.

Montages (`web | android | diff`) are built by `tools/compare-screens/resume-picker-montages.sh`. The
Android side is the Roborazzi goldens in `feature/sidebar/src/test/screenshots/resume-*`. The web
side is the S0.4 `session-drawer` shot, which is the only web scenario that shows the sidebar. **That
scenario seeds no history-only row**, so these montages compare the row grid, block header, rail and
footer, not the row content. The mean diff (12.5–21/255) is mostly the different rows. A web shot of
history rows needs a new S0.4 scenario, which is a follow-up for the tether side.

| Montage | What it shows | Notes |
|---|---|---|
| `rows-list-<skin>-phone.png` | one live chat (selected) above four history-only rows: unread with its "2 new turns since you left" digest, codex, opencode, and a profile-pinned thread (v89 `profileId`, which is not visible) | History rows use the web's status line (History glyph + "5h ago", no status word) and keep the Chat tag (`session-sidebar.tsx:173`). They have no end control. The Android block header sits above the crop because the fixture's list is short, so the web's "Filter sessions…" well is not shown. |
| `rows-opening-<skin>-phone.png` | the round trip after a tap on "Codex: tidy the release notes": the tapped row is the selected one and the live chat is released (`dashboard.tsx:202-206, 400-404`) | The row stays selected until the server's unicast `created` reply opens the new session. |
| `column-list-<skin>-tablet.png` | the same list in the expanded layout's 264dp rail | The same differences as the T5.1 `column-*` montages. |

Goldens without a web shot: `resume-opening` at tablet size, and `resume-list` / `resume-opening`
at 1.3× font scale in Machine and Studio.

## Behaviour (tests, not pictures)

- **Frame.** `resume {historyId, cwd}` is byte-identical to the corpus client example and to the
  recorded `resume.jsonl` frame. `profileId` is added after `cwd` only when the history has a
  non-empty one (`use-tether.ts:1494`) (`ResumeTest`, `SidebarControllerTest`).
- **Only a sent frame counts.** If the socket refuses the frame, the row is not marked seen, the
  drawer stays open and the selection is kept (`dashboard.tsx:400`). The workspace that owns the row
  still becomes current, as on the web.
- **Navigation follows the `created` reply** (`dashboard.tsx:708-722`), not whichever session shares
  the historyId. An ended session of the same conversation never wins. A dedup hit that returns the
  same session again still opens it, because each reply carries a monotonic sequence number. The
  session is attached exactly once (`TetherViewModelResumeTest`, `ResumePickerBehaviourTest`).
- **Refusals** ("That saved session is no longer available.", or recoverable work in another
  checkout) arrive as `error` frames and show in the existing error toast. As on the web, the row
  stays in its opening state until the operator selects something else.

## Divergences and scope notes

- The web follows every `created` reply. The app does too, but it skips re-selecting a session that
  is already selected, so the provider picker's existing create-then-select does not attach twice.
- **Boot restore** (the web reopens `lastOpenedSession.historyId` when the remembered session has
  exited) is not wired here. Nothing in the app writes `lastOpenedSession` yet. That belongs to T4.4
  (navigation). T4.4 can call `TetherViewModel.resumeHistory`.
- The global-search entry point (`openGlobalHit` → `reopen`) is T5.3's.
- `discover`'s `requestId` stays with T8.2. On the web it is the folder picker's durable
  workspace-activation intent (issue #141), and the history rows do not use it.
