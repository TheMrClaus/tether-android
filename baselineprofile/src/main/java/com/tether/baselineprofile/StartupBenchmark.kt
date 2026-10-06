package com.tether.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start timing of the app's `benchmarkRelease` build (the release build, R8-minified, with
 * whatever profile is checked in). "Before vs after" is the same build under two compilation modes:
 * [noProfile] (CompilationMode.None: nothing precompiled) against [baselineProfile]
 * (CompilationMode.Partial(Require): the profile in the APK is installed and compiled; the run FAILS
 * if the APK carries none, so a missing profile cannot read as a pass).
 * Signed-out cold start only (it needs no server): the metric is time to initial display.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun noProfile() = startup(CompilationMode.None())

    @Test
    fun baselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    private companion object {
        const val ITERATIONS = 15
    }
}
