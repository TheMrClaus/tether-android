# T4.3: statusline, session dial, context gauge, telemetry readings, wrap-up badge

Native ports of `components/session-statusline.tsx`, `session-dial.tsx`, `context-gauge.tsx`,
`telemetry-readings.tsx` and the v127 wrap-up badge (tether @ PARITY_BASE `7d65611`; these files and
their CSS are unchanged through v130). Code: `feature/shell/src/main/java/com/tether/app/ui/statusline/`.

## What is where

| Piece | Kotlin | Web source |
|---|---|---|
| Mapping layer (pure): `usageTone`, `percentReading`, `contextReading`, `contextSnapshot*`, `windowReading`, `gitDivergence`, `taskReading`, `wrapUpReading` | `TelemetryReadings.kt` | telemetry-readings.tsx:17-217 |
| Statusline segments + container-query ranks, dial reading, gauge reading | `StatusSegments.kt` | session-statusline.tsx:64-138, globals.css 1861-1883, session-dial.tsx:14-20, context-gauge.tsx:39-44 |
| `token_progress` / `usage` to the values shown: `settledTurnTokens`, `runTokens`, `sessionTokenTotal`, `tokenLabel` | `TurnTokenReadings.kt` | turn-activity.tsx:66-80, 104-117, 169-173 |
| `SessionStatusline` (entry point) | `SessionStatusline.kt` | session-statusline.tsx:140-170, globals.css 1800-1883 |
| `SessionDial` (entry point) | `SessionDial.kt` | session-dial.tsx, globals.css 3273-3286, 11177-11190 |
| `ContextGauge` (entry point) | `ContextGauge.kt` | context-gauge.tsx, globals.css 1930-1986, 8975-8990; studio.css 362-366 |
| `WrapUpNotice` / `WrapUpPill` (entry points) | `WrapUpNotice.kt` | inspector.tsx:112-130, 520-523; globals.css 11993-12007 |
| `UsageTrack` (statusline bar, meter) | `UsageTrack.kt` | telemetry-readings.tsx:40-52; globals.css 4481-4501, 9135-9140, 11579-11588; studio.css 850-851 |

Every number goes through the faithful `com.tether.app.protocol.helpers.Format` (the 807-case
lib/format.ts port), never the divergent `designsystem ui/util/Format.kt`. For example, 1250 prints
"1.3K" and 999 950 prints "1M".

## Where the web places each one (for the shell that hosts them, T4.1 / T4.2)

- **Context gauge**: in the workspace header's right-hand rail (`.workspace-actions`), after the
  dial and before the `…` Session-links key (workspace-header.tsx:122). On narrow layouts it is the
  **telemetry drawer's handle**: tap opens the telemetry sheet ("Session details"), tap again closes
  it. `pressed` = sheet open. The "Telemetry" label is hidden below 48rem and shown from 48rem
  (`showLabel`).
- **Session dial**: first item of the same rail (workspace-header.tsx:116). **Hidden below 48rem**
  (globals.css 3273), so the phone shell does not show it. Host it with
  `active = status != "exited"` and `stoppedAt = endedAt`.
- **Statusline**: inside the header's **Session links popover** (the `…` key), the last item of
  `.workspace-meta-row` under the path, resume-command and Tether-id rows (workspace-header.tsx:93,
  globals.css 11861-11879). It wraps in the popover: right-aligned on phones (`Arrangement.End`),
  left-aligned from 48rem (`Arrangement.Start`). It is hidden at 100rem and wider, where the
  inspector column shows the readings.
- **Wrap-up badge**: the statusline's rank-0 segment ("Limit  Wrapping up", built automatically).
  `WrapUpNotice` goes in the inspector / telemetry sheet, where it replaces the generic rate-limit
  notice while the allowance is running (inspector.tsx:520).

```kotlin
val metrics = TelemetryMetrics.from(session.metrics)
val state = client.projectionTrees.value[session.id]?.let(::SessionView)
ContextGauge(metrics, pressed = telemetryOpen, onClick = { telemetryOpen = !telemetryOpen }, showLabel = expanded)
SessionDial(session.startedAt, session.endedAt, active = session.status != "exited")   // expanded only
SessionStatusline(metrics, state, horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.End)
WrapUpNotice(state)   // in the telemetry sheet / inspector
```

## Behaviour notes

- A reading that was not reported is `null`, and its segment or row is omitted. The statusline
  renders nothing when it has no reading at all.
- Priority: wrap-up > context (or its transcript snapshot) > 5h > weekly > current task. Segments
  drop from the tail by the strip's width in rem (8.5 / 11.5 / 15.5 / 22), and the bar hides at 9rem
  or less. On a 412dp phone the popover strip is 21.75rem wide, so the task segment does not show,
  exactly as on the web. The rem thresholds scale with font scale, like the text they were measured from.
- Tones: warning at 75%, danger at 90%, and always beside the printed value. Only the context
  segment (fill level) and the wrap-up segment are toned. The 5h and weekly segments are never toned
  in the strip, which matches the web.
- The wrap-up expires on its own at `resetsAt` (a timer, as on the web). The reducer drops it at
  every turn boundary.
- Reduced motion: the usage-track fill transition becomes a jump (`usage-track-motion` vs
  `usage-track-reduced-motion` goldens, one frame after 0% to 80%). The dial's once-a-second tick is
  content, not animation, so it keeps ticking, as on the web.
- TalkBack: each statusline segment reads its full reading (the web `title`), and the context
  segment also exposes progress info. The gauge reads "Context N% full · …" (else "Session
  telemetry"), is a toggle with "Pressed" / "Not pressed", and has a 44dp minimum target. The dial
  reads "Session elapsed time HH:MM:SS". The wrap-up notice is a polite live region.

## Montages (`montages.sh`)

The seeded web scenarios carry **no session metrics** and **never open the Session-links popover**.
So the only states with a web reference are the gauge with no reading (phone) and the tablet header
rail (dial + labelled gauge). The other states (statusline, tones, wrap-up, needle levels) are built
from the CSS and verified by the goldens and unit tests.

| Montage | mean abs. diff | Explanation |
|---|---|---|
| `context-gauge-<skin>-phone.png` row 1 (rest, streaming) | 0.21-0.30 / 255 | Matches (sub-pixel arc anti-aliasing only). |
| row 2 (open, session-details) | 9-19 / 255 | The web capture shows the gauge **hovered**: Playwright's pointer stays over the key it tapped, so `:root .telemetry-button:hover` (a raised key face; Studio: `--graphite-raised`) wins over `.is-open`. Native touch has no hover (the T3.3 decision "pressed = `:active` only"). So Android shows the real open state: `--violet-wash` plate, or flat in Studio. |
| `header-rail-<skin>-tablet.png` | 7.4-8.7 / 255 | Plate size, well and dot match. The residual is a ≤1px horizontal offset of the gauge and label, plus mono/Manrope glyph rasterization at 1 px/dp (the known Skia-vs-Chromium text difference). |

Regenerate: `docs/parity/screens/statusline/montages.sh [web-screens-dir]` after
`./gradlew :feature:shell:recordRoborazziDebug`.

## Known gaps

- `SessionMetrics.contextSnapshotAt` (issue #164) is not decoded by the Android wire model
  (`:core:protocol` `SessionMetrics`). The snapshot path is implemented and tested, but
  `TelemetryMetrics.from` leaves it null until the model gains the field (a one-line additive change
  outside T4.3's files).
- The existing chat header (`ChatScreen.kt`, which T4.1 replaces) and the composer's token labels
  (`Composer.kt`, `SubagentRuns.kt`) still use the divergent `ui/util` helpers. They are header or
  composer surfaces outside this task's files. `tokenLabel` / `sessionTokenTotal` here are the
  faithful replacements.
