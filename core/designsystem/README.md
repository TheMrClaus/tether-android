# :core:designsystem — primitives and their screenshot workflow

Theme, generated tokens, type roles and the **material-layer primitives** every screen builds
from. Each primitive mirrors a web rule set in `~/git/tether` at `PARITY_BASE` (the KDoc cites the
`globals.css` / `studio.css` lines and the component file).

| Primitive | File | Web source |
|---|---|---|
| Keys (`TetherKey`, `resolveKey`: the key's web CLASS SET run through `KeyRules`, the globals.css + studio.css rules that paint keys, by specificity then source order; wear, slit) | `ui/components/Keys.kt`, `KeyStyle.kt`, `Wear.kt` | globals.css material layer (key system), studio.css |
| CSS box model (`cssSurface`, `focusRing`) | `Material.kt` | every `box-shadow` list, `:focus-visible` |
| Wells (`TetherWell`, `TetherInputWell`) | `Wells.kt` | recessed wells |
| Seams, perforated divider | `Seams.kt` | parting lines, `--perf-dots` |
| Status pill, spinners, 2s radar ping | `Indicators.kt` | `.status-badge`, `waiting-ping`, `.activity-spinner` |
| Chips | `Chips.kt` | `.draft-chip` / `.model-selector-chip` |
| tether-select (trigger + menu) | `Select.kt` | components/tether-select.tsx |
| Dialogs, action sheet | `Dialogs.kt`, `Sheets.kt` | `.confirm-dialog`, `.attach-sheet` |
| Expandable block | `Expandable.kt` | components/expandable-block.tsx |
| Settings rocker | `Rocker.kt` | rocker switches |
| Haptics moment map | `Haptics.kt` | the web's single `navigator.vibrate(7)` + native key moments |

**Scoped tokens.** `TokenScope` + `ProvideTokenScope` (`theme/TokenScope.kt`, T5.1) mirror a CSS rule
that redeclares custom properties for everything inside an element (studio.css `.session-sidebar`
gives the Studio rail its own ink-blue palette). A scope is a `SkinTokens.copy(...)` of exactly the
properties the rule sets. Derived properties keep their `:root` values, as in CSS, and everything
reading `LocalTetherTokens` inside the scope (keys included) resolves against it. Surfaces the web
renders outside the element (dialogs) are composed outside the scope. `TetherKey` also takes optional
`contentArrangement` / `contentPadding` / `trailing` (New session's left-aligned legend and `N` cap).
Their defaults leave every existing key unchanged.

**Layout class.** `currentLayoutClass()` (PLAN D10): below WindowSizeClass "expanded" (840dp) is
the web's mobile layout — the sheet docks, the expand toggle is 44dp / 0.74rem.

**Reduced motion.** `LocalReducedMotion` is true when Android's "Remove animations" sets the
animator duration scale to 0 (`isReducedMotion`), the web's `prefers-reduced-motion`. Ambient
motion renders its static frame; transitions become state jumps (`snap()`).

**Known approximations** (all < 1px or documented in the KDoc): CSS blur → Skia sigma conversion;
spread on small radii; a disabled key fades per draw call (`ModulateAlpha`) rather than as one
offscreen group, so its outer side-wall is not clipped (the rocker, which has overlapping parts,
does draw offscreen).

## Screenshot tests (Roborazzi 1.75.0, JVM)

- Boards: `src/test/java/.../components/screenshots/PrimitiveBoards.kt` — one composable per
  primitive laying out its visual states (rest / pressed / focus / disabled / selected / open…).
  Pressed and focus are held with a real `PressInteraction` / `FocusInteraction`.
- Tests: `PrimitiveScreenshotTest.kt` — every board × both Studio skins (light, dark) at phone
  (`w412dp-h915dp-420dpi`, the web's 412×915 @2.625), the sheet and dialog also at tablet
  (`w1280dp-h800dp-mdpi`) with the expandable block, the indicators under reduced motion, and keys /
  chips / expandable at 1.3× font scale (Studio light + dark; a 1600dp-tall window so the grown board
  is captured whole). The clock is paused and
  advanced 600ms, so ambient motion is captured at a fixed frame.
- Goldens: `src/test/screenshots/<primitive>/<skin>-<size>.png`, checked in.

```
./gradlew :core:designsystem:recordRoborazziDebug   # after an intended visual change; review the PNG diff
./gradlew :core:designsystem:verifyRoborazziDebug   # the gate (also wired into `check`): any changed pixel fails
```

`verifyRoborazziDebug` compares exactly (`changeThreshold = 0`); on failure the compare images are
in `build/outputs/roborazzi/`. Add a state: extend the board, record, look at every skin's PNG.

## Comparing with the web

`tools/compare-screens/CompareScreens.java` (JDK single-file, no dependencies) diffs two PNGs and
builds side-by-side montages `web | android | diff`. Both captures are 2.625 px/dp on a phone, so
crops compare 1:1. `tools/compare-screens/designsystem-montages.sh [web-screens-dir]` rebuilds the
montages in `docs/parity/screens/designsystem/` (see its README for what the differences are).
