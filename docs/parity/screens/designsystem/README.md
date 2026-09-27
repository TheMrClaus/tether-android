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
| `keys-footer-<skin>-phone.png` (4 instrument skins) | settings-general footer | face, side-wall, contact shadow, bevel, height (128px both), the machined execution slit on the primary key (Precision/Machine; `--key-slit: 0` in the ABS skins), the 17px check glyph | the web keys are ~7px (2.7dp) wider: key padding is identical (`0 var(--space-lg)`, globals.css 2297-2310), so the difference is the legend's advance width, ~0.2dp per glyph over 13 uppercase glyphs (Chromium positions glyphs at subpixel advances, Android's text stack at its own hinted advances); the diff's red is the resulting legend offset plus glyph-edge rasterization |

Studio's status badge and footer keys are restyled per screen (studio.css) and are compared in
the screen tasks. Found and fixed through these montages: the rocker legend is italic on the web
(the rocker is an `<i>`, so it inherits the UA `font-style: italic`, synthesized oblique).

The diff is a review aid, not a gate (PLAN §5.3): the pixel gate is `verifyRoborazziDebug`.
