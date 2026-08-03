# Tether Mobile UI — Compose Port Spec (pixel-faithful)

Ground truth: `aidash/app/globals.css` (tokens + component rules; the "MATERIAL LAYER" from ~line 4897 OVERRIDES base rules), `DESIGN.md`, `PRODUCT.md`, `components/{dashboard,chat-view,session-sidebar,topbar,workspace-header,turn-activity,markdown,chat-tool-render}.tsx`, `lib/format.ts`.
Root font 16px; rem values below are ×16.

## 1. THEMES

Four families (`TetherThemeFamily`): `tactile` (light), `night` (dark), `precision` (light), `machine` (dark, **the :root default — THE ORIGINAL "OLD SCHOOL" THEME, v1 priority**), plus a "system" choice (precision in light / machine in dark). Legacy stored value "quiet" → machine.
System bar / chrome color = the family's `--graphite`: tactile #e7e8e3 · night #171918 · precision #f2f4f3 · machine #111517.

### 1.1 MACHINE (default dark) — complete tokens
Structural (theme-invariant): space xs 4 / sm 8 / md 12 / lg 16 / xl 24 / 2xl 32 px; radius sm 6 / md 10 / lg 14 px; chat-bubble-max 86% (mobile 94%); chat-card-width 94% (mobile 100%); ease cubic-bezier(0.22,1,0.36,1); durations 140/200ms.

Surfaces: mineral #0b0f10 (app bg) · mineral-deep #070a0b (chat frame, tool cards, inputs) · graphite #111517 (topbar/sidebar/composer/dialogs) · graphite-raised #1b2225 (agent bubble, hover, pills) · slate #283135 (tracks, chips).
Seams: line #293236 · line-strong #454f53 · seam-lip #667277.
Text: white #eef2f3 (headings/selected/primary) · ink #d2d9db (body) · muted #9ba6aa · faint #808b8f.
Violet (ONLY focus/selected/waiting): violet #8b7ff0 · violet-strong #6f5fe8 · violet-deep #b4aaff · violet-wash #1c1a33 · focus-glow rgba(111,95,232,.3) · selection-bg #2f2a5c.
Status: running #5fd3d8 (icy cyan) · danger #e2685b · warning #e0a53a · danger-edge rgba(226,104,91,.5) · danger-wash #24100e.
Keys: key-face #1b2225 · key-face-hover #222a2e · key-face-deep #141a1c · key-side #05080a · accent #2e6d63 (TEAL primary) · accent-hover #357a6f · accent-deep #245a51 · accent-side #10322d · accent-ink #ffffff · accent-wash #15302c · brick #a83c31 (destructive) · brick-deep #bd4839 · brick-side #5f211a · brick-wash #2a1512 · amber #c98f2c · amber-wash #2b2210 · charcoal #2a3336 · charcoal-side #0c1113 · utility-ink #dfe6e8.
Light/shade: contact rgb(0,0,0) · lit-strong rgba(255,255,255,.09) · lit-soft .05 · lit-faint .03 · tint-rgb 255,255,255 · tint-boost 1. Tints: xs .015 / sm .03 / md .06 / lg .1 / line .12 (of white).
Shadows: shadow-key `0 2px 0 key-side, 0 3px 6px -2px rgba(0,0,0,.5)`; key-sm `0 1px 0 key-side, 0 2px 4px -1px rgba(0,0,0,.45)`; key-pressed `0 0 0 key-side, 0 1px 2px rgba(0,0,0,.4)`; raised `0 1px 2px rgba(0,0,0,.5)`; well = inset dark top shadow (recessed). press-travel 2px; radius-key 8px; key-slit 3px; key labels UPPERCASE, tracking 0.06em.
Overlays/transcript: scrim rgba(2,5,6,.7) · user-bubble-bg #1d3b37 / border #2d5b54 / ink #e4efed (TEAL-GREEN, NOT violet) · diff-add-bg #14291f / ink #86d3a3 · diff-del-bg #2d1614 / ink #eb8e82 · attention-border #7a5c1c / bg #211a0d / ink #e6b455 (approval card) · question-border #35506e / bg #111a24 / ink #8fb4dd · drop-overlay rgba(28,26,51,.86).
Base: app bg #0b0f10; body text #d2d9db; focus outline 2px #8b7ff0.

### 1.2 TACTILE (light) tokens
mineral #d6d8d3 · mineral-deep #eef0ea · graphite #e7e8e3 · graphite-raised #f0f1ec · slate #c9ccc3 · line #c0c3ba · line-strong #a6aa9f · white #23262b · ink #33373c · muted #4c514c · faint #575c53 · violet #5a4fb4 / strong #5747c8 / deep #4a3fa0 / wash #e5e3f3 · focus-glow rgba(87,71,200,.18) · selection #cfcbe8 · running #35693f · danger #a34f44 · warning #7d5c15 · key-face #eff0eb / hover #f6f7f2 / deep #e0e2db / side #b3b6ac · accent #67785a (sage) / hover #607052 / deep #5b6b4f / side #46543c / ink #fff / wash #b9c5ac · brick #a34f44 / deep #8e4238 / side #6f382f / wash #eddcd7 · amber #b8892e / wash #efe8d0 · charcoal #3f444a / side #24272b · utility-ink #eef0ea · contact rgb(45,48,42) · tint-boost 1.6 · press-travel 3px · radius-key 9.6px · key-slit 0 · tracking .05em · scrim rgba(45,48,42,.42) · user-bubble #b9c5ac / #9aa98c / #262a26 · diff-add #dbe6d2/#375833 · diff-del #ecd9d4/#83392f · attention #c8a24b/#efe8d0/#7d5c15 · question #93a2bd/#e4e9f1/#44608e · danger-wash #f1e3df · drop rgba(229,227,243,.85).

### 1.3 PRECISION (light) tokens
mineral #e0e5e5 · mineral-deep #eceff0 · graphite #f2f4f3 · graphite-raised #f8f9f9 · slate #d2d9da · line #cdd5d6 · line-strong #bcc4c6 · white #1b2428 · ink #2a343a · muted #4b585d · faint #59666b · violet #5546c9/#4b3cc4/#3d3299/wash #e7e4f8 · focus-glow rgba(75,60,196,.22) · selection #d5d0f2 · running #14707d · danger #b3392c · warning #8a6412 · key-face #f7f8f8/#fdfdfd/#e4e9e9/side #b9c2c4 · accent #3d7d6e/#366f62/#2f6558/side #255249/ink #fff/wash #dcebe6 · brick #b3392c/#9c3025/side #7d271e/wash #f7e3e0 · amber #c9962f/wash #f5edd9 · charcoal #3a464b/side #202a2e · utility-ink #f2f4f3 · contact rgb(27,36,40) · press-travel 2px · radius-key 8px · key-slit 3px · scrim rgba(27,36,40,.4) · user-bubble #cfe4dd/#93bdb0/#17302a · diff-add #d9ebe2/#1f5c43 · diff-del #f4dedb/#8d2f24 · attention #cba653/#f6efdc/#7d5a10 · question #9db1c9/#e7edf4/#3d5c85 · danger-wash #f6e4e1 · drop rgba(231,228,248,.86).

### 1.4 NIGHT (dark) tokens
mineral #121413 · mineral-deep #0b0d0c · graphite #171918 · graphite-raised #242725 · slate #31352f · line #2c302b · line-strong #454a42 · white #eceee6 · ink #d6d8cd · muted #9ba093 · faint #8d9284 · violet #8f83e6/#7264dd/#b3a9ff/wash #1f1c33 · focus-glow rgba(114,100,221,.3) · selection #322c57 · running #8fbf7a · danger #d4685a · warning #d3a04a · key-face #242725/#2c302d/#1b1e1c/side #080908 · accent #626e49 (olive)/#6c7952/#55603e/side #333a24/ink #f4f6ec/wash #242a1a · brick #9e4034/#b04a3d/side #5a231c/wash #2a1613 · amber #b8892e · charcoal #33372f · utility-ink #e2e4d9 · press-travel 3px · radius-key 9.6px · key-slit 0 · scrim rgba(6,7,6,.62) · user-bubble #2f3826/#4a5639/#e9ecdf · diff-add #1c2617/#a2c78c · diff-del #2b1614/#e08b7d · attention #6f5622/#221d0f/#d3a04a · question #3c4a63/#151a22/#9db2d1 · danger-wash #26110f · drop rgba(31,28,51,.86).

## 2. DESIGN RULES ("The Quiet Instrument")
1. Violet ONLY for keyboard focus, selected state, waiting-for-user, live primary indicators. Running = `running` color. NO gradients, glows, glass, decorative violet.
2. Status never by color alone — always icon/dot + TEXT.
3. Depth = adjacent tones + crisp 1px borders; corners 6px controls / 10px composed fields / 14px dialogs; keys 8px.
4. Type: Manrope (UI, weights 500–750, compact 610–720 for labels), JetBrains Mono (tool output, paths, metadata). Uppercase micro-labels tracking .04–.11em.
5. Motion 140–200ms; sanctioned ambient: 2s radar ping on waiting dots, spinners. Honor reduced motion.
6. 44dp touch targets everywhere (2.75rem).
7. Usage meters violet → warning ≥75% → danger ≥90%, always with printed %.
8. Mobile = single column; session rail becomes a DRAWER; chat frame edge-to-edge (no border/radius on mobile).

## 3. MOBILE LAYOUT (what the Android app replicates)
- Shell: topbar 52dp (+status bar inset) over the workspace. No bottom nav.
- Topbar (graphite bg, 1px bottom line #293236): hamburger Menu icon 20 (44dp target) · brand mark (18.4dp circle, 1px line-strong border, two 2.2×7dp violet bars rotated 32°) + "TETHER" 12.5sp w720 tracking .2em white · right: activity-log button (badge: danger circle, count, "9+") · lock/logout icon.
- Sidebar drawer: width min(320dp, 88vw), graphite, full height, left; scrim rgba(2,5,6,.7); slide 200ms. Header row "Sessions" + X (44dp).
- Drawer contents order: New session key (44dp, key-face raised key, Plus 17 + "NEW SESSION" uppercase 13.1sp w640 + kbd cap) · workspace switcher row (FolderOpen 17, "WORKSPACE" 9.3sp w700 caps faint, folder name 12.2sp, ChevronRight 15) + pin-project star (44dp; pinned → violet wash bg + violet star fill) · pinned PROJECTS list (rows 48dp, kbd index cap, name 12.5sp w640, path 9.9sp faint truncated-head, activity dot 8dp: active=running color, waiting=violet + 2s ping) · "SESSIONS" header 10.7sp w700 caps + count · filter well (only >5 sessions) · session rows · footer (violet 6.4dp dot + "Private runtime" 11.2sp; settings gear 17).
- SESSION ROW (min-height 68dp, grid 32dp glyph | copy | 16dp chevron, padding 8×12, radius 6):
  - Provider glyph: 32dp circle, key-face bg, 1px line-strong, mono 12.8sp w750, letter C/X/G/O/?.
  - Name 13.1sp w650 ellipsis; optional unseen dot 7dp violet; mode tag pill "CHAT" (violet wash when headless).
  - Status line 11.2sp: dot 6.4dp currentColor (active → 10.4dp spinner ring 1.5dp, 720ms; waiting → violet dot + waiting-ping 2s halo to 7.2dp) + text: ready→"Ready", active→"Active", waiting→"Needs you", exited→"Ended" + "· <relative>" faint. Colors: active #5fd3d8, waiting #8b7ff0, ready/exited #808b8f.
  - Digest line: violet "N new turns since you left" w600 + faint snippet.
  - Selected row: 1px violet-strong border + violet-wash bg + white text.
  - Ordering: updatedAt DESC; dedup live sessions by native id; filter by cwd; hide exited unless enabled.
- Workspace header (graphite, 1px bottom line): row1 = session name 15.2sp w680 + status badge + icon actions (44dp, icon-only on mobile): telemetry Gauge 16, pin Pin 16 (pinned → violet wash), end CircleStop 16 (brick treatment). Row2 = mono path 10.9sp faint + statusline (mono 10.6sp, tabular numerals, warning/danger tones at 75/90%).
- relativeTime: <60s "now", <60m "Nm", <24h "Nh", <7d "Nd", else "MMM d".

## 4. CHAT VIEW
- Scroll column, gap 12, padding 12/12/16 (mobile). Edge-to-edge on mineral-deep #070a0b background? NO — chat frame bg is mineral-deep; scroll content sits on it.
- Sticks to bottom within 80px; jump-to-latest pill (bottom-center, key-face pill, ArrowDown 15 + "Latest") when scrolled up.

### Bubbles (both sides are bubbles, max-width 94% mobile, padding 8×12, radius 10, text 14.4sp lh 1.55)
- USER (right-aligned): bg user-bubble-bg #1d3b37, 1px #2d5b54, text #e4efed, bottom-RIGHT radius 6. Attachment chips below text.
- AGENT (left-aligned): bg graphite-raised #1b2225, 1px line #293236, text ink #d2d9db, bottom-LEFT radius 6.
- Streaming (block not done): plain text pre-wrap + blinking caret 8×16dp violet (1s steps). Done: render markdown (paragraphs, headings w680, lists, inline code chips on tint, code blocks on mineral-deep 1px line radius 6 mono 12.8sp, blockquote 2px left line-strong, links violet underlined).
- Interrupted marker: CircleStop 12 + "interrupted" 10.9sp w600 faint.

### Thinking block (only when showThinking pref && text)
Collapsible, collapsed by default. Width 100% (mobile card width). 1px line, radius 10, bg tint-xs. Header: Brain 13 + "Thinking" 12.2sp muted, padding 8×12. Body 13.1sp lh 1.6 muted.

### Tool card
Width 100%, 1px line (running → line-strong; error → danger-edge), radius 10, bg mineral-deep, clipped. Header mono 12.8sp muted padding 8×12: status icon 14 (running→spinner/Loader 1s; error→AlertTriangle; done→Wrench) + tool name ink w600 + right-aligned status caps 11.5sp faint ("RUNNING · 12s" / "ERROR" in danger / "DONE").
Body: input summary; then output section (top 1px line, mono 12.2sp lh 1.5, pre-wrap, max-height 11rem=176dp mobile, scrollable, ink on tint-xs; truncate ~600 chars with …).
Diffs (Edit/Write inputs): file row mono 12.2sp + "EDIT/WRITE" tag pill; diff lines mono 12.2sp, gutter cell 1px right line, add rows bg #14291f text #86d3a3, del rows bg #2d1614 text #eb8e82, max-height 320dp.
Subagent thread (collapsible, open while parent running): "Subagent · N steps" head with Bot 13; body indented 12dp with 2px left line-strong; nested msgs 13.1sp; nested tool mini-cards mono 11.8sp with 12px icons.
AskUserQuestion tool blocks are NOT rendered as tool cards (the question card replaces them).

### Approval card (full transcript width — the loud waiting signal)
1px attention-border #7a5c1c, radius 10, bg attention-bg #211a0d, padding 12, gap 8. Head: AlertTriangle 15 attention-ink #e6b455 + "Approval needed" white 14.7sp. Body: "The agent wants to run `tool`." 13.6sp ink (tool name in mono chip on tint-md) · optional reason muted 12.8sp · optional "Working directory · `cwd`" / "Network · `proto://host`" · input summary · actions row (wrap, gap 8):
- Provider choices: permissionGrant → PRIMARY key (teal accent #2e6d63, white text, Check 15); no grant → SECONDARY key (key-face #1b2225, 1px key-side, ink text, Ban 15). **Codex "Allow once" = secondary/neutral, NEVER destructive.**
- Fallback: primary "Approve" (Check 15) + DENY as brick key #a83c31 white text (Ban 15).
- Keys: radius 8dp, uppercase legend tracking .06em, press = 2dp translate down, 44dp min height. Disabled after submit.

### Question card (AskUserQuestion)
1px question-border #35506e, radius 10, bg question-bg #111a24, padding 12, gap 12. Head: HelpCircle 15 #8fb4dd + "The agent needs your input" white 14.7sp. Per question: header caps 11.5sp muted tracking .06em · question 14.4sp ink · option chips (column, gap 4): key-face raised chip, padding 8×12 radius 6, label 13.8sp white w500, desc 12.5sp muted; selected → violet-strong border + violet-wash bg; NOT uppercase (provider content). Multi-select hint "Select all that apply." 11.8sp. Free-text "Other" well input. Single primary key "Submit answer" (Check 15) → "Answer sent"; disabled until all questions answered.

### Outcome / notices
- Outcome badge (when outcome != ok): row, AlertTriangle 13 + text 12.5sp, padding 4×8 radius 6 bg tint-xs. cancelled → "Turn interrupted" (muted); error → turn.error or "Turn ended with an error" (danger #e2685b); outcome_unknown → "Outcome unknown — the turn was interrupted before it finished (it may have partially applied)" (warning #e0a53a).
- Continuation marker: RotateCw 12 + "continued (background task finished)" 11.5sp muted op .8. Same style for api_retry "… — retrying (attempt N of M)".
- Permission denial card: 1px danger-edge, radius 10, mineral-deep bg; head mono 12.8sp danger: Ban 14 + tool name ink + "DENIED" right; explanation 12.8sp; target code chip clamped 4 lines.

### Composer (graphite bg, 1px top line, padding 8/8/8+nav-inset mobile)
DOM order: waiting banner (AlertTriangle 14 warning #e0a53a + "Waiting for your approval before the turn can continue." / HelpCircle + "Answer the agent's question above to continue." 12.8sp) · TurnActivity · queued messages · attachment chips · input row.
- TurnActivity (fixed-height rows 18.4dp, 12.5sp muted): row1 while run open: spinner 9.6dp + verb ink ellipsis ("Working…") + metrics "elapsed · N tokens" tabular, dot separators line-strong. Row2 always: "SESSION TOTAL" caps 9.9sp faint + total elapsed + total tokens. MUTED, never violet. Elapsed from run.startedAt vs event-ts-derived now (see protocol spec; on Android: anchor to last event ts + monotonic delta).
- Queued row: well bg mineral-deep, 1px line-strong + 2px LEFT violet-strong edge, radius 10; Loader 13 violet; editable text 13.6sp; X remove.
- Input row (gap 8, align bottom): attach key 44dp (Paperclip 18, key-face) · input well (min-height 44dp, radius 10, bg mineral-deep, 1px line-strong, inset well shadow, text 16sp ink, placeholder faint "Message the agent…" / busy: "The agent is working — your message will be queued and sent after this turn…"; focus → violet-strong border + 3dp focus-glow ring) · send/actions:
  - Idle: SEND key 44dp: teal accent #2e6d63 bg, 1px accent-side, white icon Send 18 (label hidden mobile), radius 8, shadow `0 3px 0 accent-side`, press travels 2dp, 3×~10dp white slit at left (op .55), subtle wear. Disabled 50%.
  - Busy: QUEUE key (same accent, Send 18, "Queue") + INTERRUPT key (brick #a83c31, 1px brick-side, white, CircleStop 18, shadow 0 2px 0 brick-side).
- Mode row (claude only, implemented): ShieldCheck 14 + "Mode" label + permission-mode pill (29dp height, key-face, radius pill, drop-up menu with label+hint rows, danger styling on "Auto") + right-aligned model chip (Cpu 13 + active model name, pill → model picker panel with Default-row synthesis / "CLI Default (<resolved>)" labeling / Check on active / empty state "Send a message first to load the available models."). Slash-command drop-up (claude only): rows "/name <argHint>" + description + "Tether" | "terminal only" tag; /model is Tether-native (picker + free-text resolution); unsupported/unknown flash an in-composer notice row (Terminal 14 + text, 6 s auto-dismiss).
- Sub-agent tabs (implemented): when a session has Agent/Task tool blocks, a tab strip sits between workspace header and transcript — "Session" tab (+ "N running" badge when a run tab is active) + one tab per run (status icon+text: spinner "running" / AlertTriangle "error" / Check + "N steps"; title; selected = violet-wash bg + violet-strong border; error tab = danger border + danger text). A run tab replaces ONLY the transcript with the run panel (Bot + title, agentType/status/steps chips, stats chips — served model, "asked X", effort, "N tok" | "usage not captured", ~$ est, collapsible "Task given to this sub-agent", step stream, "Result/Error returned to the parent"); the active turn's approval/question cards stay visible below the panel on every tab. The Session transcript opens with the collapsed "Subagents" roster (count + "N running · N failed · N tok · ~$" summary + partial-totals note; rows jump to tabs).

### Empty states
Not attached: Loader 18 + "Connecting to the session…". No history: "HEADLESS AGENT" section label 10.7sp caps faint w720 → "Send a message to start the conversation." 16.8sp ink w600 → hint 13.6sp muted.

### Error toast
Fixed bottom, min-height 56dp, grid icon|text|close, padding 12, 1px brick border, radius 6, bg danger-wash #24100e, white 12.5sp, AlertCircle 18 danger, X close 44dp.

## 5. TYPE & ICONS
- Fonts: Manrope Variable (UI) + JetBrains Mono Variable (code/paths/tool output). Bundle via Google Fonts static TTFs or downloadable fonts; weights 500/600/650/700 needed (map 610–750 to nearest).
- Icons: lucide (use `com.composables:icons-lucide` for Compose, or vector assets exported from lucide). Inventory: Activity, AlertCircle, AlertTriangle, ArrowDown, Ban, Bot, Brain, Check, ChevronRight, CircleStop, Clock, Copy, Cpu, FileText, Folder, FolderOpen, Gauge, HelpCircle, History, Loader, LogOut, Menu, Paperclip, Pencil, Pin, Plus, RotateCcw, RotateCw, Search, Send, Settings, ShieldCheck, Star, Terminal, Wrench, X.
- Size ladder (px≈sp): 9.3, 9.6, 9.9, 10.6, 10.7, 10.9, 11.2, 11.5, 11.8, 12.2, 12.5, 12.8, 13.1, 13.6, 13.8, 14.4, 14.7, 15.2, 16, 16.8.
- Spinners: ring 1.5dp currentColor with transparent top, 720ms linear; Loader icon rotation 1s linear.

## 6. COMPOSE MAPPING NOTES
- Implement tokens as an immutable `TetherTheme` data class (all colors/dims above) provided via CompositionLocal; four instances (machine/night/tactile/precision) + system resolution. Do NOT lean on Material3 ColorScheme for the custom surfaces (map a few basics for interop; keep components reading TetherTheme directly).
- Key/button treatment: draw side-shadow as an offset solid (translationY on press, shadow shrink), 1px border, uppercase label where specified.
- Wells: inner top shadow can be approximated with a subtle top-edge gradient overlay; do not skip the 1px line-strong border.
- Respect system bars: topbar padding = status bar inset; composer bottom padding = ime/nav inset (max(8dp, inset)); edge-to-edge with dark system bar icons per theme.
- Status must always render icon/dot + text.
