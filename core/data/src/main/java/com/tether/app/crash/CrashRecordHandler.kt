package com.tether.app.crash

import java.io.File

/**
 * ta-otgf: the default uncaught-exception handler that keeps the last crash on the device, then
 * lets the process die exactly as it would have.
 *
 * It writes the record (plain File I/O, no logcat of its own: the platform already prints FATAL),
 * and ALWAYS delegates to the handler that was the default at install, in `finally`, with the
 * same thread and throwable. It never exits or kills anything itself. Everything it does that can
 * throw, formatting and the clock included, is inside the `try`, and the `catch` takes every
 * Throwable (an OutOfMemoryError too). A crash inside this path, dispatched back to this handler on
 * the same thread, skips the write and still delegates.
 */
class CrashRecordHandler(
    private val store: CrashRecordStoreWriter,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val build: CrashBuildInfo,
    private val clock: () -> Long = System::currentTimeMillis,
) : Thread.UncaughtExceptionHandler {
    private val inside = ThreadLocal<Boolean>()

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (inside.get() != true) {
                inside.set(true)
                try {
                    store.write(record(thread, error))
                } finally {
                    inside.remove()
                }
            }
        } catch (_: Throwable) {
            // A record that cannot be written must not change how the process dies.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(thread: Thread, error: Throwable): CrashRecord = CrashRecord(
        timeMs = clock(),
        versionName = build.versionName,
        versionCode = build.versionCode,
        androidRelease = build.androidRelease,
        androidSdk = build.androidSdk,
        thread = clip(thread.name, MAX_NAME_BYTES),
        exceptionClass = clip(error.javaClass.name, MAX_NAME_BYTES),
        message = CrashTrace.capMessage(error.message),
        stack = CrashTrace.render(error),
    )

    private fun clip(text: String, maxBytes: Int): String = text.substring(0, CrashTrace.prefixLength(text, maxBytes))

    companion object {
        private const val MAX_NAME_BYTES = 512

        /**
         * Make a handler the process default, chaining to whatever is the default now (the platform's).
         * A second install in the same process (a repeated attach) does nothing.
         */
        fun install(store: CrashRecordStoreWriter, build: CrashBuildInfo): CrashRecordHandler? {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashRecordHandler) return null
            return CrashRecordHandler(store, previous, build).also { Thread.setDefaultUncaughtExceptionHandler(it) }
        }

        /** The app's file for the record under [noBackupFilesDir]. */
        fun fileIn(noBackupFilesDir: File): File = File(noBackupFilesDir, CrashRecordStore.FILE_NAME)
    }
}
