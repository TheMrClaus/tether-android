package com.tether.app.crash

/**
 * The last uncaught exception this device recorded (ta-otgf). Plain data: written by
 * [CrashRecordHandler] in a dying process, read back by the Health & Event Log dialog. It never
 * leaves the device unless the owner copies it.
 *
 * [message] and [stack] are already capped ([CrashTrace]); [stack] is the whole trace with its
 * causes and suppressed exceptions, `\n` line breaks.
 */
data class CrashRecord(
    val timeMs: Long,
    val versionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val androidSdk: Int,
    val thread: String,
    val exceptionClass: String,
    val message: String?,
    val stack: String,
)

/** What the app is, captured once at install: the handler runs in a dying process and asks nobody. */
data class CrashBuildInfo(
    val versionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val androidSdk: Int,
)
