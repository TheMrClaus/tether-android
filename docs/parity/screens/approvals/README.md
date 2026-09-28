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
  choice exists), the warning-coloured "Confirm the complete permission expansion shown above." box, Allow all
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
- "Allow selected" with every requested box ticked IS the full expansion, so it needs the same confirmation as
  "Allow all"; the confirmation box therefore shows for a `subset` choice too (the web: `exact` only).
- Tapjacking: a touch that arrives through another window drawn over the app (`FLAG_WINDOW_IS_OBSCURED` /
  `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`) is dropped on every card control, and a card's controls stay disabled for
  500 ms after it becomes answerable or its request changes. The web has neither (a browser has no such signal).
- Card state (ticks, confirmation, picks, page) survives scrolling and re-creation and is bound to the exact
  request (its fingerprint): a re-raised request with the same id starts over. After process death a decision
  may be made again (the in-memory ledger died with the process); a decision sent on a socket that then
  dropped says "Sent before the connection dropped — delivery unconfirmed" and is never sent again.

The diff is a review aid, not a gate (PLAN §5.3). The pixel gate is `verifyRoborazziDebug`.
