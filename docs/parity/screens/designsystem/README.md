# T3.3 primitives vs the web reference

Montages (`web | android | diff`) built by `tools/compare-screens/designsystem-montages.sh` from
the S0.4 web reference (Chromium headless, 412×915 @DPR 2.625) and the Roborazzi goldens in
`core/designsystem/src/test/screenshots/` (412dp @420dpi = 2.625 px/dp). The web shots are whole
screens, so only primitives that appear in a seeded scenario can be compared here; the rest are
covered by their goldens and are compared screen by screen in the Phase 4 screen tasks.

| Montage | Web scenario | What matches | Explained differences |
|---|---|---|---|
| `rocker-<skin>-phone.png` (6 skins) | settings-general | frame, well, cap geometry to the pixel (65.6×28.8dp; Studio 40×24), depressed-half fill, Studio's cap over the still-present legend layer | legend glyph edges (font rasterization); background around the frame (web: dialog panel, android: board page) |
| `status-pill-<skin>-phone.png` (4 instrument skins) | idle-session header | height, border, fill, dot, tracking | android pill is ~4px (1.5dp) narrower: JetBrains Mono advance widths rasterize slightly tighter than Chromium's subpixel-positioned text; stroke weight at 0.6rem differs by hinting |
| `keys-web-<skin>-phone.png` (6 skins) | approval-pending (Approve `button-primary`, Deny `button-secondary chat-approval-deny`, jump `chat-jump`); streaming (Interrupt `chat-send chat-interrupt`, paperclip `chat-attach-btn`, header End session `end-session`) | every class set's face, border, bevels, side-wall, contact shadow, slit and wear per skin — notably Studio's Interrupt is the flat BLUE `.chat-send` key (its tie with `.chat-interrupt` goes to studio.css, loaded later), Studio's Deny is flat brick on 0.625rem, Studio's End session stays raised, the paperclip is Studio's flat graphite-raised square | (1) background around each key: the web keys sit on the approval card / composer / header panels, the board on `--mineral`, which is most of the red in the tactile/precision diffs; (2) the header End session is 2.35rem (37.6dp) tall on the web (`:root .end-session { min-height: 2.35rem }`, globals.css:11196), the app key keeps its 44dp minimum — a session-header sizing rule for that screen's task, not the key material; (3) legend advance widths and glyph rasterization as below |
| `keys-footer-<skin>-phone.png` (6 skins) | settings-general footer | face, side-wall, contact shadow, bevel, height (128px both), the machined execution slit on the primary key (Precision/Machine; `--key-slit: 0` in the ABS skins), the 17px check glyph | the web keys are ~7px (2.7dp) wider: key padding is identical (`0 var(--space-lg)`, globals.css 2297-2310), so the difference is the legend's advance width, ~0.2dp per glyph over 13 uppercase glyphs (Chromium positions glyphs at subpixel advances, Android's text stack at its own hinted advances); the diff's red is the resulting legend offset plus glyph-edge rasterization |

Studio's footer keys (`keys-footer-studio*`): flat `--graphite` secondary with its `--line-strong`
edge and flat accent primary, both 0.625rem; they match except for glyph rasterization. Studio's
status badge is restyled per screen (studio.css) and is compared in the screen task.

`:hover` is not modelled: a touch press in Chromium also sets `:hover`, and Studio's hover rules
(studio.css 268-270, e.g. `.chat-send:hover:not(:disabled)`, (0,4,0) and later than the globals
`:active` rules) would repaint a held Studio primary/send key `--accent-hover` without shadow. The
program's pressed state is `:active` alone; if touch-hover should be part of it, that is a product
decision recorded for the coordinator. Found and fixed through these montages: the rocker legend is italic on the web
(the rocker is an `<i>`, so it inherits the UA `font-style: italic`, synthesized oblique).

The diff is a review aid, not a gate (PLAN §5.3): the pixel gate is `verifyRoborazziDebug`.
