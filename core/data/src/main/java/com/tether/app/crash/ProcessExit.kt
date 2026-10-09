package com.tether.app.crash

/** One system record of this app's process ending, a plain copy of `ApplicationExitInfo`. */
data class ProcessExit(
    val timeMs: Long,
    val reason: Int,
    val importance: Int,
    val status: Int,
    val pid: Int,
    val description: String?,
)
