# Conversation timeline (T6.5)

Web reference: tether `7d65611` (PARITY_BASE) `components/conversation-timeline.tsx`,
`conversation-timeline.module.css` and `lib/conversation-story-points.ts`. The tsx and ts files are
unchanged at tether `main` today. The module CSS there has since dropped the Precision/Machine
rules, when the web retired those skins. Android keeps all 6 skins, so it follows the PARITY_BASE
CSS. Montages are rebuilt by `tools/compare-screens/timeline-montages.sh`; goldens are in
`feature/chat/src/test/screenshots/timeline-*`.

## Montages (web | android | diff)

| File | Web scenario | Android golden |
|---|---|---|
| `rest-<skin>-phone.png` / `-tablet.png` | `conversation-timeline` (five prompts, pinned to the newest) | `timeline-rest` |

6 skins × phone + tablet = 12 montages. The rail itself matches: 10dp pitch, 10dp resting ticks at
half opacity, the 16dp violet needle on the prompt nearest the reading line (the 4th here, as on the
web), and the scale line always drawn. The remaining differences are outside the rail:

- **Transcript layout** (phone): the web overlays the rail on the transcript. Android keeps T6.1's
  reserved 54dp column, so bubbles wrap earlier and rows sit lower (decision 2026-09-27).
- **Tablet crop**: the web docks the rail in the stage's 3.4rem gutter, outside the chat frame's
  wall. Android has no frame wall and reserves the same 54dp column at the well's start. The web
  crop starts at the gutter, so the frame wall shows on the web side only.
- **Jump to latest**: the web shot is taken scrolled a little off the bottom.

## States with no web reference

The seeder takes no hover or scrub shot, so these are goldens only, checked by eye against the web
CSS:

- `timeline-scrub`: a touch held on the 4th mark. It shows the bubble (mono time and `4 / 5`, the
  prompt in 2 lines at weight 660, the reply dot and reply), the magnified dashes (28/22/16/12/10)
  and the lit scale line. Phone and tablet, 6 skins; 1.3× font on Machine and Studio.
- `timeline-saved`: a saved copy whose last prompt has no reply. The bubble says "No reply in the
  saved copy" rather than "Agent reply pending…". Phone, 6 skins; 1.3× on Machine and Studio.

## Divergences from the web (deliberate)

- **Story-point limits** 270/320, the owner's wider bubble (decision 2026-09-27). The web uses
  220/260. The port (`helpers.ConversationStoryPoints`) stays faithful and takes the limits as
  parameters.
- **Display cleaning**: prompts, replies and attachment names go through `LabelText.clean`. Bidi
  controls and invisible code points are dropped, whitespace is collapsed, and the text is bounded
  at the same limits. The transcript bubble behind the `saved` shot draws the same prompt by the
  transcript's own rule (ta-blf, `SafeText` in core/designsystem): its RLO and PDF are visible `⟨U+202E⟩`
  tokens, so it reads "Fix the ⟨U+202E⟩parser⟨U+202C⟩ bug now". The web, and these goldens
  before ta-blf, drew "Fix the resrap bug now".
- **Saved copy** (T13.2): an empty reply reads "Agent reply pending…" only on a live copy.
- **Side**: the layout class picks the side (expanded = the web's desktop, rail on the left), not
  the web's 64rem media query.
- **Bubble backdrop**: the web blurs what is behind the 95% bubble. Compose cannot blur behind a
  node, so the bubble is composited over the well colour.
- **Wheel**: one wheel notch counts as Chrome's 100px `deltaY`, so it moves `round(100/36)` = 3
  prompts, as on the web.
- **Marks for TalkBack and keyboard**: each visible mark is a button (label "Jump to your message
  at HH:MM: …", "Current step" on the needle) whose node is one pitch (10dp) tall, like the web's
  2px buttons. For touch, the target is the rail's 54dp-wide scrubber, with an 18dp slop around
  each slot. Keyboard focus shows the bubble and the web's focus ring.
- **Needle when prompts are off screen**: the web picks the prompt nearest the reading line
  across the whole transcript. A lazy list only lays out what is on screen, so Android lets an
  on-screen prompt row win and otherwise takes the last prompt above the screen (or the first
  below). The needle can therefore sit one prompt behind the web's for part of a scroll: near the
  end of a reply longer than about 1.2 viewports, and when the only on-screen prompt is in the
  bottom fifth while the previous one ends just above the top. Jumps, scrubs and copy are
  unaffected; the window shifts by at most one slot.
