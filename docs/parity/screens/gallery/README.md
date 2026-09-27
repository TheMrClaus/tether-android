# T3.4 Component Gallery (debug only)

The in-app catalogue of the design system (PLAN §7 Phase 3), and the anchor for screenshot tests.

## Reaching it

Debug installs get a second launcher icon, **Tether Gallery**, or:

```
adb shell am start -n com.tether.app/.gallery.GalleryActivity
```

Code: `app/src/debug/java/com/tether/app/gallery/` (`GalleryActivity`, `ComponentGallery.kt` for the
screen and switcher, `GallerySections.kt` for the primitives, `GalleryCatalog.kt` for type, icons,
logos, launcher and the golden registry). The manifest entry is in `app/src/debug/AndroidManifest.xml`.
It lives in the app's `debug` source set, not in a module, because the launcher-icon section needs
the app's own resources (`@drawable/ic_launcher_*`, `@mipmap/ic_launcher`), and a library module
cannot depend on the application.

## What it shows

Controls at the top: **family** (Tactile Console / Precision Machine / Studio) × **mode** (Light /
Dark / System), so all six skins (plus System); **font scale** 1× / 1.3× / 2× (applied to the
sections); a **reduced-motion** rocker (defaults to the device's "Remove animations"); a jump chip
for each section.

| Section | Content |
|---|---|
| Keys | every web class set (`button-primary`, `button-secondary`, `button-primary button-danger`, `button-secondary chat-approval-deny`, `chat-send`, `chat-send chat-interrupt`, `end-session`, `new-session-button`, `chat-attach-btn`, `chat-jump`, `icon-button`) in rest · pressed · focus · disabled; latched, small, circle cap, icon send, authored legend; a live key |
| Wells | live input, value, focus, disabled; plate, plate focus |
| Seams | horizontal seam, perf divider, vertical seam |
| Chips | rest, open, focus, live toggle, leading glyph, disabled |
| Status pills | active, waiting, ready, exited, history |
| Select | live `TetherSelect` (row and field), trigger states, menu (selected, roving focus, tag, danger, disabled), menu opening up |
| Dialog / Sheet | a live modal plus the static surface on the skin's scrim (sheet rows rest / pressed / focus) |
| Expandable | clamped and counted, open, fits (no toggle), generic block |
| Rocker | live, off, on, focus, disabled on |
| Spinner and ping | spinner ring, loader, dot, 2s waiting ping; static frame under reduced motion |
| Typography | the T3.2 roles (body, chatBody with inline code, composerInput, screenTitle, displayTitle, markdown h3 to h5, listTitle, codeBlock, timestamp, numeral, sectionLabel, statusLabel, keyLabel), each with its web selector and what it resolves to in the skin |
| Icons (136) | every lucide-react name the web imports (`TetherIcons.byWebName`); deprecated aliases are marked `*` and drawn as their canonical glyph |
| Provider logos | the `LOGO_MARKS` providers (claude, codex, opencode) in a 2rem circle at 58% plus the letter fallbacks (gemini, reasonix, pi, acp, empty id gives `?`); bare marks at 24 and 48dp; the brand mark and wordmark |
| Launcher icon | the background, foreground and monochrome layers at the full 108dp (dashed box = the 72dp viewport); adaptive masked as circle, squircle, rounded, teardrop and square; themed icon (monochrome layer on representative Material You light and dark tints); `@mipmap/ic_launcher` / `_round` drawn by the platform's `AdaptiveIconDrawable` |

The provider circle is a neutral stand-in: the `.provider-glyph` material belongs to the sidebar task.
The Material You tints are representative, not the device's dynamic palette.

## Goldens

`app/src/testDebug/screenshots/gallery/<section>/<skin>-phone.png`, 7 sections × 6 skins = 42, at
phone size (`w412dp-h915dp-420dpi`, the web's 412×915 @2.625), rendered from the gallery's own
section composables (`GalleryGoldens`) by `GalleryScreenshotTest`:

`typography-1`, `typography-2`, `icons-1`, `icons-2`, `icons-3`, `provider-logos`, `launcher-icon`.

The icons and type roles are split so each part fits a phone-height window. The primitive sections
get no gallery goldens: the T3.3 boards in `core/designsystem/src/test/screenshots/` already cover
them (the same states, all six skins, plus tablet, reduced motion and 1.3× font scale), and a second
copy would only duplicate those goldens. The launcher-icon golden is the first screenshot coverage
of the adaptive icon (T3.5 verifier note).

```
./gradlew :app:recordRoborazziDebug   # after an intended visual change; review the PNG diff
./gradlew :app:verifyRoborazziDebug   # the gate: any changed pixel fails
```

## Never in release

- Structural: all gallery code, resources and its manifest entry are in `app/src/debug`.
- `./gradlew :app:verifyGalleryNotInRelease` (wired into `check`) scans the release variant's
  compiled classes (dirs and jars, via the AGP variant API) for the `com.tether.app.gallery`
  package, and the release merged manifest for the gallery. It needs no signing secrets. A probe
  class put in `src/main` under that package turns it red.
- `ComponentGalleryTest` asserts the other side: in debug the activity is declared, exported and a
  launcher entry, and it starts.

Release unit tests are not an option here: AGP 9 creates host tests for the debug build type only.
