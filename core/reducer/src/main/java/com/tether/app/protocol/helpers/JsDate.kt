package com.tether.app.protocol.helpers

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * `new Date(ms)` read through a zone: TimeClip truncates toward zero, and |ms| > 8.64e15 is an
 * Invalid Date (null here — its getters are NaN on the web).
 */
internal fun jsDate(ms: Double, zone: ZoneId): ZonedDateTime? {
    if (!ms.isFinite() || abs(ms) > 8.64e15) return null
    return Instant.ofEpochMilli(ms.toLong()).atZone(zone)
}
