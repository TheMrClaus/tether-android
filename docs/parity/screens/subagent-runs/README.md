# T6.4 sub-agent runs, spawned runs, background commands, todo bar vs the web reference

Montages (`web | android | diff`) built by `tools/compare-screens/subagent-montages.sh` from the S0.4 web
reference (scenario `subagent-runs-top`: the seeder's "Review with sub-agents" session, two Agent runs, one
done and one failed, the transcript scrolled to the top; phone 412×915 @DPR 2.625 and tablet 1280×800) and the
goldens in `feature/chat/src/test/screenshots/subrun-session/`. The fixture (`SubagentFixtures.web`) sends the
seeder's events (`parity-seed.mjs` 236-261) through the v128 reducer, so the 05:01 send times match. The
goldens render the tab strip over `ChatTranscript` in the same well as T6.1's (see `../chat/README.md`).

| Montage | Web scenario | What matches | Explained differences |
|---|---|---|---|
| `subagent-runs-<skin>-{phone,tablet}.png` | subagent-runs-top | the tab strip (Session selected: `--graphite-raised` with the 2px violet top edge, joined to the pane; each run tab: status glyph, the harness glyph circle, title, the mono meta "3 steps" / "1 step"; the failed run's glyph in `--danger`, its state also in words for TalkBack); the closed Subagents roster (bot glyph, "Subagents", the count chip, "1 failed" in mono); the `space-lg` gap below it; the bubbles; the collapsed "2 agents" activity group in `--danger` | (1) T6.1's reserved timeline rail column (54dp) makes the transcript narrower; the web draws its rail over the column. (2) The activity group's summary row is at least 44dp (T6.2), so everything below it sits lower. (3) Studio desktop: the web centres the transcript in a max-width column with the rail on its left (T6.1's transcript layout, not this surface). (4) Glyph rasterisation. |

States with goldens but no web counterpart (the fake engine cannot freeze a running run, a spawned CLI child,
a lifecycle-only Codex thread, background `!` commands or a todo list at a chosen point):

- `subrun-roster-open`: the roster expanded, one row per run (status glyph, harness glyph, title, the agent type
  right-aligned in mono, then the run's chips).
- `subrun-run`, `subrun-run-error`: a run's tab. Header (bot glyph, the 0.95rem/600 title, the agent-type chip,
  the status chip with its glyph, violet-edged while running, `--danger` on error; "N steps"), the identity chips
  ("usage not captured" dashed, its reason as the accessible description), "Task given to this sub-agent"
  (collapsed), one full-width tool card per tool step (the generic card: input, media, output), messages as
  0.86rem markdown, then "Result returned to the parent" / "Error returned to the parent".
- `subrun-spawned`: a spawned Codex child (issue #173) placed before the runs of the turn that spawned it, as on
  the web: "running", "codex · build · asked gpt-5", the "Live output" section with the log file chip,
  "Images handed to this agent · 1" and its picture (T6.2's `SpawnedRunMedia`, now mounted), then the captured
  output.
- `subrun-thread`: a Codex child known only from `subagent_activity` (issue #172): the dashed note ("codex ran
  this sub-agent in its own thread…"), agent path, thread id and "Lifecycle · started → completed"; no step count.
- `subrun-deck`, `subrun-deck-open`: the composer deck's todo bar (the current item, "1/3", closed; open: each
  item with its glyph, completed struck through, and its state in words, "DONE" / "IN PROGRESS" / "TO DO"; the
  pending ring is a hollow circle, never a colour) over the running background command (violet-edged, "running",
  the danger-washed Stop key).
- `subrun-chips`: finished background commands in the transcript at their launch point (by `startedAt`,
  between the turns): the 2px left edge (`--danger` when it failed), the command, "exit 0" / "signal SIGKILL"
  in words, "View output".
- `subrun-output`: a running command's output sheet (the 2px violet top edge, the command, "running", Stop,
  the 44dp close key; stdout in ink, stderr in `--warning`; "Full output: <log file>").

Tablet goldens: `subrun-session`, `subrun-run` (the desktop tab padding and 0.8rem labels). 1.3× font-scale
goldens: `subrun-session`, `subrun-run`, `subrun-deck-open`, `subrun-output` (Machine, Studio): the tab labels
ellipsize inside their 13rem maximum, the chips wrap.

Divergences from the web, on purpose:

- Stop is disabled, with its reason in its accessible name, when the app is offline (a saved copy), catching up,
  or the session is read-only or handed off (the server refuses `stop-command` on a read-only session anyway,
  `server.mjs` READ_ONLY_MUTATIONS). The web shows the key live. After a stop the client accepted, every Stop key
  for that command (the bar's and the sheet's: one latch per command, round 2) reads "Stopping…"; a refused stop
  leaves the keys as they were. Round 3: "accepted" only means queued, so the latch clears when the link drops,
  the session's liveness flips or the server changes, and lapses after 10s while the command still runs (the
  key then arms again); it is not saved, so switching sessions away and back starts clean.
- Round 2 (M1): the running rows are keyed by command, and a Stop key arms 500ms after it becomes usable (T6.3's
  I3 delay) and again after it moves more than 4dp in its window (a command finishing, the queue draining, the
  todo bar appearing), so a tap aimed at one row cannot stop the command that slid under the finger. Touches
  through an overlay are refused. The stop is bound to the server origin its row was drawn for. Round 3: the
  movement is measured from where the key stood when its arming began (a slow slide re-arms it too), and each
  Stop key's accessible name carries its command ("Stop <command>").
- The output sheet is anchored near the top of the window (the web centres it), so its head and Stop key stay
  put while short output grows the sheet downward.
- Command labels show their first non-blank line ("…" when there are more) inside a bidi isolate, with embedding,
  override and isolate controls removed.
- Every row of the running-commands bar, the Stop key, the finished chips, the todo bar's head, the roster rows,
  the tabs and the "+N more steps" key are at least 44dp tall (the web's command rows and chips are one text line).
- The output sheet draws the tail of the capture (the last 64,000 characters, with "… earlier output not shown
  here — the full output is in the log file") as a lazy list of lines, rebuilt off the main thread at most every
  250ms while the command streams, so a runaway command cannot lay out an unbounded text or rebuild it per chunk;
  the web draws every folded segment. Every line keeps its full 1.5 leading as a `<pre>`'s line boxes do (round 2
  re-recorded the 8 `subrun-output` goldens for this: the sheet is a few px taller). The client also caps every
  command's folded output at 256K characters in each tree it publishes (live folds, hydration, mirror rebuilds,
  snapshots; 4x the server's 64 KiB stream cap, so a conforming server never reaches it; the fold itself stays
  the web's, which has no cap), and a command it trimmed keeps reading "Live view truncated".
- A run tab follows new steps only while the reader is at its bottom (a hand drag upward stops it; reaching the end
  again resumes it), the transcript's rule; the web's panel has no follow logic of its own.
- A run tab is a lazy list, one row per step (the web renders the whole stream), and a sub-agent thread under
  its parent card still draws 50 steps at a time, now with a "+N more steps" / "+N earlier steps" key that draws
  the next 50 (ta-cqf); the thread opens by default when ANY step's result carries media, as the web.
- A denial's sub-agent link opens the run's tab and brings the refused step to the middle with the violet
  outline flash (`.subrun-entry-flash`, 2.2s; held, then cleared, with reduced motion).
