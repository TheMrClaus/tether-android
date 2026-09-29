# T7.2 session controls vs the web reference

Montages (`web | android | diff`) built by `tools/compare-screens/controls-montages.sh` from the S0.4
web reference (scenario `idle-session`, phone 412×915 @DPR 2.625 and tablet 1280×800) and the composer
goldens `feature/chat/src/test/screenshots/composer-idle/*`, which T7.2 renders with the scenario's own
controls (the fake engine's "Opus (1M context)", Manual, no effort levels).

| Montage | What matches | Explained differences |
|---|---|---|
| `key-<skin>-phone.png` | Below 64rem of viewport the web folds Model / Effort / Mode / Auto into ONE flexible key in the well's toolbar (`.settings-sheet-trigger-combined`, globals.css:11936): the provider mark, the model name, the sliders glyph, the key face at 2.75rem with the key radius (Studio: graphite-raised, no edge); it takes the free width between attach and Send | (1) The web's toolbar also has the browser-pane key (T8.6), so the web key starts one key later and is narrower. (2) The web draws the provider mark inside a small glyph disc; the app draws the bare mark (ProviderLogo) at 14dp. (3) Glyph rasterization. |
| `row-<skin>-tablet.png` | From 64rem the web shows the row (`.chat-mode-row-live`) above the toolbar footer: Cpu icon + the Model pill (provider mark, name, chevron), Shield + the Mode pill ("Manual"), the flex hint in `--faint` ("Prompts before every gated tool (Bash, Write, Edit…)"); pills are the raised dropdown caps (`:root .chat-mode-select`: key face, key-side edge, lit top, 999px, 0.7rem/600) | (1) The web row also carries **Auto-continue** (v101 `set-auto-continue-on-limit`), which is T6.6's (auto-continue-on-limit), not in this task. (2) The app's row is taller: every pill keeps a 44dp touch target around the 1.9rem visual cap. (3) The browser-pane key (T8.6) in the footer; "1 token" vs "0 tokens" is the fixture's settled usage (as in `../composer`). |

States with goldens but no web counterpart (the S0.4 seeder never opens the sheet, a menu, or a
Codex / opencode-serve session's row):

- `controls-sheet` (phone, 6 skins): the session sheet's hub — Model / Effort / Mode / Fast rows
  (`.settings-sheet-row`: raised pill rows, muted icon, the value in words, chevron) and the model
  search. `controls-mode`: its Mode list (Auto in `--warning`, the violet check on Manual).
  `controls-confirm`: the confirmation before Auto (**Android addition**, see below).
  `controls-codex-panel`: the Codex provider-controls view (review + compaction, skills, hooks,
  apps, MCP health, rate limits with the % spelled out).
- `controls-opencode-row` (tablet, 6 skins): opencode-serve v2 — Model with the provider tag,
  Effort, Mode (Build), the Auto toggle ON (warning edge AND the word "Auto"), the warning hint, the
  Provider controls key. `controls-codex-row`: Codex v2's catalogs (Model / Effort / Mode), Auto off.
- 1.3× font: `controls-{sheet,confirm}-font-1.3x` (Machine, Studio).

## Deliberate divergences (logged for the reviewer)

1. **Confirmation before the most permissive posture.** The web switches a session to Auto
   (Claude/pi `bypassPermissions`, reasonix YOLO, opencode Auto, Codex Auto approve, a danger opencode
   agent) on one pick. The app asks first ("Turn on Auto?"): the confirm key is armed (T6.3 500 ms,
   re-armed when it moves) and refuses touches through an overlay, and the client refuses such a change
   unless the confirmation flag is set (`ControlResult.NeedsConfirmation`). Turning Auto OFF is never
   confirmed.
2. **Mode, Auto and Fast rows are armed** (T6.3/T6.4), in the row's menus and in the sheet.
3. **Provider-controls panels live in the session sheet** (hub row "Provider controls", and a
   "Provider controls" key at the end of the tablet row). The web keeps them in Settings → Advanced,
   which the app does not have yet (T10.1).
4. **Bare `/model`** opens the sheet's Model list; the web forwards it to the CLI as prompt text
   (slash passthrough is T7.3). `/model <arg>` matches the web: a listed match, else a plausible id
   (`looksLikeModelId`) pinned with the "not in the known list" notice, else refused.
5. **No pin/unpin affordance** on legacy models (the web's desktop select has one; its phone sheet
   does not). Pins set elsewhere are honoured (`groupModelOptions`).
6. **No Shift+Tab mode cycling**: the web offers it only to a fine pointer with hover; the app is a
   touch client.
7. The sheet is also used on a tablet narrower than 64rem (portrait), as the web's viewport rule does.
8. **Round 2 (security review, fail closed).** A stored mode the app does not know (a removed
   `dontAsk`, a newer CLI mode, an opencode agent not yet listed, an opencode approval policy other
   than none / "never") shows as "Unknown mode (value)" with a warning hint, the warning edge and
   the word "Unknown" on the phone key; the web shows it as Manual. An opencode agent outside
   `default` / `build` / `plan` is confirmed unless a source explicitly marks it safe (either source
   flagging it is enough), and shows "label (value)" when its label could pass for another agent's.
   Provider actions carry the catalog revision they were drawn from, and their keys are armed.
   Server-supplied names, hints and errors are cleaned (bidi / invisible characters, whitespace)
   and bounded. The confirmation names the session.
9. **Fast from 64rem**: the web's desktop row has no Fast control (it lives only in the phone
   sheet); the app's wide row adds a "Fast: Off/On" key that opens the same Fast list
   (`controls-unknown-row` shows it).

The diff is a review aid, not a gate (PLAN §5.3). The pixel gate is `verifyRoborazziDebug`.
