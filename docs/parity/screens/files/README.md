# T11.1 workspace file browser vs the web reference

Montages (`web | android | diff`) built by `tools/compare-screens/files-montages.sh` from the S0.4
web reference (scenario `file-browser`: the idle session's Files key in the top bar, parity-app
listed, nothing selected; phone 412×915 @DPR 2.625, tablet 1280×800) and the goldens in
`feature/files/src/test/screenshots/files-list/`.

The goldens render `FileBrowserFrame` (the dialog case over the skin's scrim) on the skin's
`--mineral` floor, which stands in for the console behind the dialog. The fixture is the seeder's
project (`parity-seed.mjs` `seedProject`): `docs/`, `src/`, `package.json` (95 B), `README.md`
(65 B), every mtime at `FIXED_EPOCH` (Jan 1, 2026, 12:00 AM UTC), the same breadcrumb trail and the
session name "Summarize the README".

| Montage | mean \|Δ\| / px over tol. | What matches | Explained differences |
|---|---|---|---|
| `list-tactile-phone.png` | 2.93 / 2.99% | dialog inset (`100vw - 0.5rem`), header title/subtitle and the one-line clamp under 35rem, perforated header edge, `--graphite-raised` breadcrumb strip scrolling sideways from `/`, toolbar (Parent folder, "4 items", New folder / New file / Upload), uppercase column heads, the Modified column dropped under 35rem, 2.75rem rows with the web's icons, sizes and "Folder", the ⋮ keys | (1) The web shows the console (topbar, header) through the scrim; the golden shows the flat floor. (2) Manrope / JetBrains Mono rasterise about 1px differently in Android's text stack. (3) The subtitle's ellipsis falls one glyph later ("the …" vs "th…"). |
| `list-night-phone.png` | 2.09 / 1.63% | as above | as above |
| `list-precision-phone.png` | 2.91 / 3.04% | as above | as above |
| `list-machine-phone.png` | 2.30 / 2.50% | as above | as above |
| `list-studio-phone.png` | 3.46 / 3.14% | Studio's phone case (`100vw - 24px` × `100dvh - 32px`, 14px radius, no border, its literal shadow and scrim), 76px header with the 20px title, no perforation, `--graphite` 54px breadcrumbs with 44px crumbs, the `--mineral` sentence-case column heads, 54px rows at 14px inline padding | as above |
| `list-studio-dark-phone.png` | 3.47 / 3.45% | as Studio | as above |
| `list-<instrument skin>-tablet.png` | 1.43-2.21 / 1.38-2.10% | the desktop case (`min(88rem, 100vw - 1.5rem)` × `min(48rem, …)`), list pane at `0.85fr` of the grid (485dp) beside the `--mineral-deep` preview well, the Modified column ("Jan 1, 2026, 12:00 AM", `Intl` medium/short), the empty preview panel's glyph, heading and copy | the console behind the scrim; text rasterisation |
| `list-studio-tablet.png` | 6.46 / 5.37% | Studio's desktop case (`min(1400px, 100vw - 48px)` × `min(820px, 100dvh - 48px)`), 88px header, 20px list padding | the web's dark Studio sidebar shows through the scrim at the left edge (the whole red band in the diff); the dialog itself matches like the phone shot |
| `list-studio-dark-tablet.png` | 2.69 / 2.79% | as Studio | as above |

States with goldens but no web scenario (styled from `globals.css` 3348-3632 and `studio.css`
654-674 / 953-991): `files-text` (README.md in the `<pre>` well: mono 0.72rem/1.55, pre-wrap, `--line`
border; Studio 13px/1.75 borderless), `files-image` (a decoded PNG, `object-fit: contain`, radius and
raised shadow), `files-loading` ("Reading workspace…"), `files-empty`, `files-error` (the server's copy
and Try again), `files-unsupported` ("No safe inline preview"), `files-too-large` (the 1 MB text
limit), `files-upload-error` (the mutation banner), and the four sub-dialogs `files-actions`,
`files-name-prompt` (with the server's 409 copy), `files-delete` (folder wording) and
`files-destination` (the Move to… `.folder-dialog`). Phone: all 13 states × 6 skins; tablet: list,
text, image, destination × 6; 1.3× font scale: list, text, actions, name prompt (Machine, Studio).
At 1.3× the header subtitle, the toolbar and the rows keep their one-line clamps and nothing
overlaps.

## Deliberate differences from the web (native)

- **Video and SVG are not previewed in the app.** PLAN T11.1 scopes viewing to text/code/images.
  Video would need a player that carries the credential (none is in the app), and there is no SVG
  renderer without a web view or a new dependency. Both show an explained panel pointing at Save
  to device / Share. Follow-up bead: video + SVG preview.
- **Image preview is capped at 32 MB and sampled to 4096px a side.** The web's `<img>` has no cap.
  The app downloads the image to a scratch file (deleted as soon as it is decoded) and decodes a
  bounded bitmap, so a decompression bomb cannot exhaust memory.
- **Save to device… and Share… (files only) in the actions sheet.** PLAN T11.1 asks for
  "download/share out"; the web has neither. Save goes through the system "create document" picker
  and streams straight into the chosen document (512 MB cap; a failed save deletes the partial
  document). Share streams one copy into `cache/workspace-files/share/<random>/<sanitised name>` and
  hands it to the share sheet with a one-off read grant (`WorkspaceFileProvider`, not exported, one
  cache subfolder only).
- **Upload feedback.** The web gives none during a PUT and clears its own upload error when it
  re-lists (`loadDirectory` resets `mutationError`), so a refused upload is never seen. The app shows
  "Uploading “name”…" and keeps the last refusal on screen after the re-list.
- **Double-submit guard.** Confirm keys (Create/Rename, Delete, Move here/Copy here) disable while
  their request is in flight, so a double tap cannot send the same destructive request twice.
- **Delete is not red.** `.file-browser-action-danger { color: var(--danger) }` loses the cascade to
  `:root .button-secondary { color: var(--ink) }` (0,1,0 vs 0,2,0), so the web draws it in the key's
  ink. The app matches; the trash glyph and the confirm step carry the meaning.
- Every destructive op the web exposes already has an explicit step (delete confirm; move/copy
  through the picker's "… here" key), so no native-only confirmation was needed.

The diff is a review aid, not a gate (PLAN §5.3). The pixel gate is `verifyRoborazziDebug`.
