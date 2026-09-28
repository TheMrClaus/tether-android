# T6.3 approvals, questions and denials vs the web reference

Montages (`web | android | diff`) built by `tools/compare-screens/approval-montages.sh` from the S0.4 web
reference (the seeded `approval-pending` scenario: "Add a version file." → a Write approval, phone 412×915
@DPR 2.625 and tablet 1280×800) and the goldens in `feature/chat/src/test/screenshots/approval-write/`. The
goldens render `ChatTranscript` alone in the same well as T6.1's (see `../chat/README.md`). The fixture
(`ApprovalFixtures.write`) sends the seeder's events through the v128 reducer, so the 07:01 send times match.

| Montage | Web scenario | What matches | Explained differences |
|---|---|---|---|
| `approval-pending-<skin>-{phone,tablet}.png` | approval-pending | the running Write call in its open run above the card; the card (`--attention-bg` under `--attention-border`, `space-lg` padding, the lit edge and floating shadow; Studio flat 0.875rem, 1.25rem padding); the head (triangle in `--attention-ink`, "Approval needed" 0.98rem/700); "The agent wants to run `Write`." with its UA paragraph margins; the call's input as the Write diff (path + NEW / OVERWRITE tag, + rows); the Approve (primary) and Deny (the brick `chat-approval-deny` key) fallback pair | (1) The timeline rail column (T6.1: 54dp reserved) makes the cards narrower; the web's rail marker is the violet dash drawn over its card. (2) The group summary row is at least 44dp tall (T6.2 touch target), so everything below it sits lower. (3) The web phone shot shows its Jump-to-latest key over the empty space; the golden is in follow mode. (4) Glyph rasterisation. |

States with goldens but no web counterpart. The fake engine emits only a plain `approval_request`
(parity-corpus/screens/web/manifest.json: `question-pending` is unsupported, and no step emits provider
choices, permission grants or denials), so these are built from the reducer corpus's event shapes:

- `approval-choices`: a Codex command's provider choices (Allow once / Allow for this session / Decline, all
  neutral keys with the ban glyph because none carries a `permissionGrant`, as `chat-view.tsx:1283-1296`),
  the reason, `Working directory · …` and `Network · https://…` lines, the input as pretty JSON.
- `approval-grants`: the T6.3 permission paths. The "Requested permission expansion" fieldset, a checkbox per
  requested read and write path and for network access (all ticked at first, editable only when a `subset`
  choice exists), the warning-coloured "Confirm these permissions: …" box (round 4: it names what is ticked), Allow all
  (exact, disabled until confirmed), Allow selected (subset: disabled with nothing ticked, and, with every box
  ticked, until confirmed, see below), Deny. Round 2 re-recorded these 8 goldens: with everything ticked at
  first, Allow selected now renders disabled.
- `approval-locked`: a saved copy (not connected): the card renders, every key is disabled, and the reason is
  in words, "Connect to answer. This is a saved copy." (SYNC_DESIGN §4.2).
- `approval-sent`: after the operator's tap: keys disabled and "Decision sent. Waiting for the agent."
- `question`: page 1 of 2 ("Question 1 of 2"), header, question, option keys with descriptions, the "Other
  (type your own answer)…" field, Skip and Next (disabled until the page is answered).
- `question-validation`: page 2 after Submit with nothing picked: "Answer each highlighted question, or choose
  Skip to leave it unanswered." The web's `.is-unanswered` rule names `--attention`, a token no skin defines,
  so it computes to padding only (no rule); the port does the same.
- `answered`: the v104 "You answered" record in the AskUserQuestion tool's slot (content-sized, neutral
  `--border` on `--tint-sm`, "(no selection)" in muted italics for an empty answer, the free-text response),
  and a second record whose tool block is gone, trailing its turn.
- `denials`: both activity groups opened (a denial anchored to a call inside a collapsed group is hidden with
  it, as inside the web's `<details>`): a sub-agent's Read refused inside its Agent run (the run's title is a
  link to its tab), the main agent's abort ("Tool permission request failed: …", the `error` beats the
  generic copy), and the homeless WebFetch below the transcript. Each shows who ran it, the copy, the refused
  target (FILE / COMMAND …, clamped to 4 lines) and the quotable `reasonCode · toolId`.

Tablet goldens: `approval-write`, `question`, `denials` (the denial card is 94% wide there; approval and
question cards span the column). 1.3× font-scale goldens: `approval-grants`, `question`, `denials`
(Machine, Studio).

Divergences from the web, on purpose:

- A card is disabled, with its reason in words, when the session is read-only or handed off, when the app is
  offline (a saved copy) or has not yet had this connection's snapshot ("Catching up…"), or when an answer is
  already on record for a question. The web shows these cards live. See the security notes in the T6.3 bead.
- "Decision sent" / "Answer sent" status lines: the web only disables the keys (status never colour alone).
- The denial's sub-agent link and the permission checkboxes are at least 44dp tall (touch targets), which
  adds space around the link.
- Provider and agent text on a card is cut at 4,000 characters for display (the answer keys keep the full
  question text). The web does not cut.
- Every permission-granting choice needs a confirmation (coordinator decision, round 4; the web asks only
  for "exact"): the box reads "Confirm these permissions: read …; write …; network access." and names exactly
  what is ticked. It is never saved and clears whenever the card is re-created or anything is ticked or
  unticked, so it is always made on the card on screen, after the last change. "Allow all" also needs every
  box ticked (it grants the full request, so the confirmation has to have named all of it). A path listed
  twice in a request is one permission (unticking either row unticks it). The confirmation is bound to the
  exact state it was made in, and a grant key re-reads the ticks at the moment of the tap, so an untick
  landing in the same instant as the tap (two fingers) sends nothing.
- Paths on a grant card are shown quoted; cut in the middle (the first 60 and the last 99 characters stay, so
  a trailing `/../..` that decides the scope is always visible); and with every character that could hide,
  reorder, fake a space or fake the quotes written out as `\uXXXX`. That covers the control, format,
  separator, surrogate, private-use and unassigned categories, spaces other than U+0020, variation
  selectors, Hangul fillers, the curly quotes and the backslash. So no path can pose as part of the sentence
  around it (the web shows them raw). The grant itself carries the raw path. Round 5 re-recorded the 8
  `approval-grants` goldens for the quotes. Round 7 adds: default-ignorable code points, the braille blank,
  every Pi/Pf quote and quote look-alike and the ellipsis are escaped too; each shown path (and each context
  value) is its own bidi island (FSI…PDI), so right-to-left letters cannot reorder the separators around it; a
  path with a `.` or `..` segment is never cut and carries "(contains relative segments (..))"; and the reason,
  working directory and network host lines go through the same escaping (the working directory quoted like a
  path). Round 7 re-recorded `approval-grants` (the isolation marks shift the label's line breaks) and
  `approval-choices` (the quoted working directory), 14 goldens.
- The confirmation only ever refers to words that were on screen: ticking it counts only if the ticks have not
  changed since the card was drawn, so a tick change, the confirmation and a grant key in the same instant
  send nothing; after the redraw the operator confirms the set now shown.
- A question card's Submit, Next and Skip decide only on the selection that was drawn: a pick or text change
  in the same instant (a second finger) makes the tap do nothing, and an option or Other field of a page that
  is no longer shown ignores input. The operator sees the change and taps again.
- Tapjacking: a touch that arrives through another window drawn over the app (`FLAG_WINDOW_IS_OBSCURED` /
  `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`) is dropped on every card control, and a card's controls stay disabled for
  500 ms after it becomes answerable or its request changes. The web has neither (a browser has no such signal).
- Card state is bound to the exact request and kept per app window, not per row. Its identity is the
  request's card fingerprint (the canonical request, its turn and its session, without the server origin); the
  lazy row's key carries it, so a request re-raised under the same id with other content is a new card that
  starts fully ticked, unconfirmed and on page 1, whether it changed on screen, off screen or across a restore.
  The ticks, a question's page, picks (by label), "Other" text (capped at 4,000 characters) and skips live in
  one saved store held by the shell above the phone / expanded layout switch, by index (never the server's
  text), so they survive a scroll, a tab switch, a rotation or window resize across 840dp, a session switch, a
  link drop and reconnect to the same server, backgrounding and a configuration change. The confirmation is
  never saved (above). If a record is lost (the store keeps the newest 64 per kind), the card comes back fully
  ticked AND unconfirmed. After process death a decision may be made again (the in-memory ledger died with the
  process); a decision sent on a socket that then dropped says "Sent before the connection dropped — delivery
  unconfirmed" and is never sent again. A question page change re-arms the 500 ms delay, and a touch refused
  because of an overlay says so: "A screen overlay is blocking this card."
- Question answers are built by the client, not by the card: the card sends which options (by label) and
  what "Other" text per question; the client checks every index against the request and builds the answer
  strings exactly as the web's `buildQuestionAnswers` does (the conformance test compares them). An "Other"
  field drops line breaks, as an HTML text input does.

The diff is a review aid, not a gate (PLAN §5.3). The pixel gate is `verifyRoborazziDebug`.
