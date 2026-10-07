# T5.3 search surfaces vs the web reference

Three surfaces, ported from the web at `PARITY_BASE`:

- **Global search** (`components/global-search.tsx`, v71 `global-search` / `global-search-results`).
  It opens from the sidebar's "Search all conversations…" key or with Ctrl/Cmd+Shift+F.
- **Sidebar content search** (`dashboard.tsx:835-893`, v6 `search` / `search-results`). The
  sidebar's "Filter sessions…" box sends a debounced workspace search. The hits merge into the rows,
  each with its `.session-item-snippet` line.
- **In-chat find** (`chat-view.tsx:2013-2120`, `markdown.tsx:83-146`). A find bar over the
  transcript opens with Ctrl/Cmd+F, or when you open a global-search result. Every occurrence is
  marked yellow and the active one is a stronger yellow.

**The S0.4 corpus has no web scenario with either search surface open.** The montages
(`web | android | diff`, built by `tools/compare-screens/search-montages.sh`) therefore use the
nearest web shot of the same content:

| Montage | Web reference | What the diff shows |
|---|---|---|
| `find-markdown-<skin>-phone.png`, `…-tablet.png` | `long-markdown-top` (the same reply, no find bar) | The bar in the top-right corner, and the marks on "the" and "them" (substring matches, as on the web). The transcript is scrolled so the active occurrence is centred. The rest of the diff comes from T6.1's existing bubble-width difference (see `docs/parity/screens/chat/README.md`). |
| `sidebar-content-search-<skin>-phone.png` | `session-drawer` (no query) | The row grid, the block header, the filter well and the footer. The rows differ: this golden shows the title match plus two content hits with their snippet lines. |

The global-search modal has no web screenshot to pair with, so its goldens are the evidence:
`feature/sidebar/src/test/screenshots/global-search-{hint,results,pending,searching,no-match}`
(6 skins at phone size), `hint` and `results` at tablet size, and `results` at 1.3× font scale in
Machine and Studio. Every value comes from `globals.css` 5757-5905 / 11810 and `studio.css`
772-789 / 1008-1016. A web shot needs a new S0.4 scenario, which is a tether-side follow-up.

## Behaviour (tests, not pictures)

- **Wire** (`SearchTest`, core:net). `search` and `global-search` are byte-identical to the
  corpus client examples and the recorded `discovery.jsonl` frames. Absent filters are omitted.
  The `search-results` / `global-search-results` replies decode with their HistorySession fields,
  including v89 `profileId`, which the resume needs.
- **Stale replies never land.** Every global request takes a new monotonic `requestId`. A reply
  whose id is not the newest is dropped: after a newer request, after a close, after a
  too-short query, and after a sign-in switch.
- **Debounce.** The modal waits 220 ms and the sidebar filter 250 ms (`GlobalSearchBehaviourTest`,
  `SidebarSearchTest`). `since` is stamped when the search fires. Chips are sent in the order you
  turned them on.
- **Labels, as the web shows them.** The hint shows under two characters. "Searching…" shows
  only while the typed text is ahead of the pending request. Otherwise the modal shows the spinner
  and the count of the hits it holds; the previous query's hits stay listed while a new one is
  pending. "No conversations matched “…”." shows once a request has been answered with no hits.
- **Opening a result** (`openGlobalHit`). If the conversation has a live session, that session is
  selected, with no resume and no mark-seen. Otherwise `resume` is sent (with the profile), and
  only a sent resume marks it seen and closes the drawer. In both cases the query arms that
  conversation's find bar, and the modal closes and clears its results.
- **Find** (`ChatFindTest`, `ChatFindBehaviourTest`). Occurrences are counted by the same leaf
  walk the renderer paints. A match inside a link URL or markup is not a match. Matching is
  case-insensitive the way JS `/i` without `u` is: `ſ` does not match `s`. Only chat text is
  searched. The count reads "N of M · turn X of Y". Enter goes to the next match, Shift+Enter to
  the previous one, and Escape closes the bar; the Previous and Next keys wrap. An active match
  inside a clamped fence expands the fence. A request applies once per nonce and only to its own
  conversation. Switching session closes the bar. A rotation keeps the bar, the query and the
  active match.

## Divergences and scope notes

- **Find does not auto-load trimmed turns.** On the web the find covers only the loaded turns of
  a v115 bounded snapshot. A hit in a trimmed turn appears after "Load N earlier turns", because
  the find recomputes when the projection changes. The app does the same.
- **Opening from a result does not focus the find box.** The web focuses it. On a phone the soft
  keyboard would cover the match the bar just jumped to. Ctrl+F still focuses the box and selects
  its text.
- **A find jump stops the follow mode.** Otherwise the next streamed delta would pull the view off
  the match. The web's follow mode ignores programmatic scrolls.
- **The IME's Search key** puts the keyboard away in the modal (the web has no Enter action) and
  goes to the next match in the find bar (the web's Enter).
- **No backdrop blur** (`backdrop-filter: blur(2px)`). The house rules forbid glass. The mark's
  inline box uses Manrope's metrics, even inside a code span.
- **Upstream finding:** the web paints `.global-search-hit-snippet` in `--slate`, which is a
  surface colour (`#283135` in Machine). The snippet is therefore nearly invisible on dark skins,
  on the web too. The app keeps it faithful. Fixing it belongs on the web side.
- The modal lives in `:feature:sidebar` (package `com.tether.app.ui.search`), not in a separate
  `:feature:search` module. It reuses the sidebar's controller (workspace focus, mark-seen), its
  provider caps and its harness list.
