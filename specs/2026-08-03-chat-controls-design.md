# Design: chat controls parity — mode/model selectors, slash menu, sub-agent tabs, IME fix

Date: 2026-08-03
Status: approved direction (user: "I want it exactly like the web version"), pending implementation plan

## Goal

Bring the Android app to parity with the mobile web chat controls, exactly as
the web behaves today, plus fix the IME layout bug. Four work items:

1. **Permission-mode selector** (Claude sessions only).
2. **Model selector**, including the `/model`-native **slash-command autocomplete**.
3. **Sub-agent tabs**: tab strip + transcript roster + per-run panel (full port).
4. **Keyboard gap fix**: composer must sit tight above the IME.

Ground truth (read in this order): `aidash/components/chat-view.tsx`,
`aidash/components/subagent-runs.tsx`, `aidash/components/subagent-run-model.mjs`,
`aidash/lib/protocol.ts` (`PERMISSION_MODE_OPTIONS`, `ModelOption`, `SlashCommandInfo`),
`aidash/lib/model-id.mjs`, and this repo's `specs/visual-spec.md` (§4 "Mode row"
is currently marked "May defer to v1.5" — this design implements it).

Non-goal: any server or wire change. Everything below is client-side; the
server already speaks `set-mode`, `set-model`, `session-controls`, and already
journals sub-agent threads. The Android reducer and protocol model already
carry every field needed (`TurnBlock.subagent`, `TurnUsage.modelUsages`,
`AgentSession.permissionMode`/`model`, `CliInventory.commands`,
`ServerMessage.SessionControls` parsing). **The reducer is untouched.**

## Item 1+2 — Mode row with permission-mode and model selectors

### Client layer

- `ClientMessage` gains two frames (both already in `specs/protocol-spec.md` §3,
  no spec change):
  - `SetModel(sessionId, model)` → `{"type":"set-model","sessionId":…,"model":…}`
  - `SessionControls(sessionId)` request → `{"type":"session-controls","sessionId":…}`
- `TetherClient` gains `setModel(sessionId, model)`, `requestSessionControls(sessionId)`,
  and `sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>>`
  keyed by session id. `RealTetherClient` currently **drops** the parsed
  `session-controls` reply (`handleFrame`) — route it into that map instead.
  `FakeTetherClient` implements the new members for previews/tests.
  (`setMode` already exists end-to-end.)
- Static `PermissionModeOptions` port (pure Kotlin, hand-synced comment pointing
  at `lib/protocol.ts`, same discipline as the rest of the protocol surface):

| value | label | hint | danger |
|---|---|---|---|
| `default` | Manual | Prompts before every gated tool (Bash, Write, Edit…) | |
| `acceptEdits` | Accept Edits | Auto-accepts file edits; still prompts for other tools | |
| `plan` | Plan | Researches and proposes a plan without making changes | |
| `dontAsk` | Locked | Denies tools that are not pre-approved; agent questions are denied instead of shown | |
| `bypassPermissions` | Auto | Runs everything without asking — including destructive commands | ✓ |

- Pure Kotlin ports in `protocol/reduce/` (unit-tested, no Compose imports):
  - `ModelId.kt` ← `lib/model-id.mjs` (`modelsDiverge`: normalize lowercase
    alphanumerics; equal-or-contained ⇒ not diverged; either side empty ⇒ false).
  - `SessionControlsModel.kt` ← the `pickerModels` / `composerCommandList` /
    `resolveModelArg` logic from `chat-view.tsx`:
    - `pickerModels(models, sessionModel)`: exactly one Default row (`value ""`
      or `"default"` both count; synthesize `{value:"", displayName:"Default",
      description:"The CLI's default model"}` when absent); label default rows
      `CLI Default (<sibling displayName, exact id match first, then
      normalized-containment via modelsDiverge> )` or plain `CLI Default`;
      when `sessionModel` is null and no row is `current`, mark the default row.
    - `activeModel(models, pickerModels, sessionModel)` for the chip label:
      `models.find(value == sessionModel) ?? models.find(current) ??
      pickerModels.find(current)`; fallback text `session.model ?: "Default"`.
    - `composerCommandList(advertised, controls)` ← `chat-view.tsx` lines 71–105:
      advertised list wins (replace semantics), enrich name-only entries from
      controls, `supported` = closed native allow-set {`model`} by name or alias;
      guarantee a `/model` row (`Switch the model for this session`, `[model]`,
      supported); sort supported-first then name.
    - `resolveModelArg(arg, models)`: exact value → exact displayName →
      substring either, all case-insensitive.

### UI (Composer, `session.provider == "claude"` only)

Mode row above the input row (visual-spec §4: `Mode` label + 28.8dp pill +
model chip). Drop-ups are Compose `DropdownMenu`s anchored to their pill —
anchored at the bottom of the screen they open upward, matching the web's
`dropUp`.

- **Mode**: ShieldCheck 14 + "Mode" label + pill showing the current option
  label (`session.permissionMode ?: "default"`; danger tint when the current
  option is `Auto`). Menu rows: label + hint verbatim, danger styling on
  `Auto`. Tap → `client.setMode(session.id, value)`, close.
  - The web also shows the current mode's hint inline next to the select; on
    the phone row that space does not exist (visual-spec §4 row omits it), so
    the hint lives in the menu rows only — same text, no information lost.
- **Model chip**: Cpu 13 + `activeModel` label. Tap →
  `client.requestSessionControls(session.id)` (refresh, exactly like
  `openModelPicker`) and open the picker panel: header (Cpu 13 + `Model` + ✕),
  empty state `Send a message first to load the available models.` when
  `pickerModels` is empty, else one row per model: `displayName` + Check 13 when
  active (`value == (session.model ?: "") || (session.model == null && current)`)
  + optional `description` second line. Tap → `client.setModel(session.id,
  model.value)`, notice (`Model reset to the CLI default.` for `""`/`"default"`,
  else `Model set to <displayName>.`), close, clear a `/model…` draft.
- **Slash menu** (drop-up above the input row, web `chat-slash-menu`):
  - `slashQuery` = draft starts with `/` and the remainder has no space → the
    name fragment; menu shows `composerCommandList` entries whose name or alias
    starts with the fragment; hidden while a turn is busy; dismissed state
    resets on any draft edit (web `menuDismissed` semantics).
  - Row: `/name` + mono `argumentHint`, `description`, right tag: `Tether`
    when supported, else `terminal only` (Terminal 11).
  - Tap a row (`acceptCommand`): `/model` (or alias) → open model picker, clear
    draft; unsupported → notice `/<name> isn’t available in Tether yet — run it
    from a terminal.`, clear draft; else draft becomes `/<name> `.
  - Send intercept (`runSlashCommand`): a bare `/…` draft with no attachments
    is never sent/queued — `/model` no-arg → picker + clear; `/model <arg>` →
    `resolveModelArg`; no match → notice `No model matches “<arg>”. Choose one
    from the list.` + open picker; known-unsupported → the terminal notice;
    unknown → `Unknown command “/<name>”. Type “/” to see what’s available.`
- **Notices**: web `flash()` renders an in-composer `chat-notice` row (Terminal
  icon + text, 6 s auto-dismiss). Port as exactly that — a composer row, not
  the global error toast.
- `session-controls` is requested when a Claude session's chat screen opens
  (cheap + idempotent server-side), mirroring the web's open-time effect.

## Item 3 — Sub-agent tabs (full port)

### Pure derivation: `protocol/reduce/SubagentRunModel.kt`

Line-faithful port of `subagent-run-model.mjs`:

- `isSubagentLauncher`: `kind == "tool" && name in {"Agent","Task"}`.
- `collectSubagentRuns(projection)`: every launcher block in `turnOrder` /
  block order → run: `runId = "$turnId::$blockId"`, `toolId`, `turnId`, 1-based
  `index`, `title` = trimmed `input.description` → `input.subagent_type` →
  `Sub-agent <index>`, `agentType`, `prompt` = `input.prompt`,
  `requestedModel`/`requestedEffort` (explicit input only), `status` =
  running / error / done (`done`/`isError` flags), `steps` = subagent order
  size, `usage`, `totalTokens` (sum of the four token fields) — **null, never
  0, when no usage was captured** (resumed sessions replay no child records;
  0 would assert "cost nothing"), `estimatedCostUSD` =
  `apportionedRunCostUSD(usage, turn.usage.modelUsages)` (share of the served
  model's cost by token share, clamped ≤ 1; null when not honestly computable —
  callers label it an estimate), `elapsedSeconds`, `thread`, `output`,
  `isError`.
- `subagentRunEntries(run, showThinking)`: thread order → entries, thinking
  only when pref on and text non-blank.
- `subagentRosterSummary(runs)`: total/running/errored/done; `tokens` and
  `estimatedCostUSD` null unless ≥1 measured run, summing measured runs only;
  `partial` = measured < total.

### Selection state

`TetherViewModel` owns `selectedRunIdBySession: StateFlow<Map<String, String?>>`
+ `selectRun(sessionId, runId)` (mirrors dashboard.tsx owning per-session
selection). The selected run is always resolved by lookup against the current
`collectSubagentRuns` list — a run that vanishes from a re-snapshot degrades
to the Session tab by itself; no reset effect.

### UI: `ui/chat/SubagentRuns.kt`

- **Tab strip** (`SubagentTabs`): between workspace header and transcript,
  only when runs exist. Horizontally scrolling row of tabs: `Session` first,
  then one per run. Run tab = status icon (running → spinning Loader, error →
  AlertTriangle, done → Check) + title + meta (`running` while running, else
  `N step(s)`); error tab adds error styling **and** text (never color alone).
  Selected = violet wash bg + violet-strong border (violet only for selected,
  per DESIGN.md). Session tab shows a `⟳ N` running-count badge when a run tab
  is active. Newly selected tab scrolls into view.
- **Transcript swap**: when a run tab is active the panel replaces **only the
  transcript items** — pending approval and question cards are extracted and
  rendered below the panel on every tab (web invariant: a blocking card must
  never be hidden behind a tab). The composer's waiting banners already render
  independently. Scroll on tab switch: running run follows newest; finished
  run parks at top.
- **Roster** (`SubagentRoster`): Session tab only, first transcript item.
  Collapsed by default; header Bot 14 + `Subagents` + count + summary
  (`N running` · `N failed` · `N tok` · `~$x`, only what's known) + partial
  note (`Totals cover <measured> of <total> runs — the rest have no captured
  usage.`). Rows (status icon + text, title, agentType, stats chips) select
  the run's tab.
- **Run panel** (`SubagentRunPanel`): header (Bot 15 + title; chips:
  agentType, status icon+text(+elapsed while running), `N step(s)`); stats
  chips (served model; `asked <requested>` only when it differs from served;
  `<effort> effort`; `N tok` or muted `usage not captured`; `~$x` estimate);
  collapsible `Task given to this sub-agent` prompt (markdown); entry stream
  (message → markdown; thinking → existing ThinkingCard, pref-gated; tool →
  compact tool card with running/error/done head, input summary, done-output —
  reusing the existing ToolCard building blocks); empty states (`Waiting for
  this sub-agent’s first step…` while running, `No step-by-step activity was
  recorded for this run.` otherwise); result section when finished and output
  non-empty (`Result returned to the parent` / `Error returned to the parent`,
  error styled).

The inline collapsible thread inside parent tool cards already exists in
`ToolCard.kt` (`SubagentThreadView`) and stays as-is.

## Item 4 — Keyboard gap fix

Root cause: `AndroidManifest.xml` sets no `windowSoftInputMode`; under enforced
edge-to-edge (targetSdk 36) the default `adjustPan` pans the window **and** the
composer's `imePadding()` pads it — a double offset that leaves a large blank
gap between the IME and the text field.

Fix: add `android:windowSoftInputMode="adjustResize"` to the `MainActivity`
element. `imePadding()` then owns IME placement alone and the composer sits
tight above the keyboard. No layout code changes.

## Testing

- `SubagentRunModelTest` (new): launcher detection incl. legacy `Task` name;
  title fallback chain; status mapping; runId shape; `steps`; usage
  null-not-0; apportioned cost (match by `model` then `canonicalModel`, share
  clamp, null cases); roster summary incl. `partial`; entries thinking filter.
- `ModelIdTest` (new): the cases from `aidash/tests/model-id.test.mjs`.
- `SessionControlsModelTest` (new): default-row synthesis + labeling,
  current-marking, `composerCommandList` merge/allow-set/sort/guaranteed-model,
  `resolveModelArg` precedence.
- `WireTest` (extend): `set-model` and `session-controls` request encoding
  (optional-field omission rules unchanged).
- `RealTetherClientTest` (extend): `session-controls` reply lands in
  `sessionControls` flow.
- `./gradlew test` and `assembleDebug` green; IME fix verified on a device or
  emulator (composer tight above keyboard, no gap, transcript resizes).
- `specs/visual-spec.md` updated: §4 mode row loses the "May defer to v1.5"
  note and gains the slash menu; new sub-agent tabs subsection.

## Out of scope

- Codex provider controls (`codex-controls` frame family) — separate surface.
- Permission mode on the new-session form (the web create dialog has it; the
  Android drawer form does not — separate change if wanted).
- `/model` alias-driven effort or other slash commands becoming Tether-native —
  the native allow-set stays exactly `{model}`.
- The web Inspector roster surface (no Inspector on the phone); the transcript
  roster above is the phone's roster, exactly as on mobile web.

## Files

New: `protocol/reduce/SubagentRunModel.kt`, `protocol/reduce/ModelId.kt`,
`protocol/reduce/SessionControlsModel.kt`, `ui/chat/SubagentRuns.kt`,
`ui/chat/ChatControls.kt` (mode row, drop-ups, slash menu, notice row),
`ui/chat/PermissionModes.kt` (the static option table — presentation metadata,
so it lives with the UI, hand-synced with `lib/protocol.ts`),
plus the unit tests above.

Modified: `protocol/ClientMessage.kt` (2 frames), `client/TetherClient.kt`,
`client/RealTetherClient.kt`, `client/FakeTetherClient.kt` (stubs/flows),
`ui/TetherViewModel.kt` (run selection state), `ui/chat/ChatScreen.kt` (tab
strip + transcript swap + roster item), `ui/chat/Composer.kt` (mode row, slash
menu, notice row, send intercept), `AndroidManifest.xml` (one attribute),
`specs/visual-spec.md`.
