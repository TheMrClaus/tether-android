package com.tether.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's Baseline Profile (run by `:app:generateReleaseBaselineProfile`, see README.md).
 * Cold start always; the signed-in journeys (open a session, scroll the transcript) only when the
 * run passes `tether.serverUrl` and `tether.password` instrumentation arguments (see [signInArgs]).
 * Nothing credential-like is committed, not even a default.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val sign = signInArgs()
        rule.collect(
            packageName = TARGET_PACKAGE,
            // Enough warm-up iterations for the journey to settle; stable-iterations stays default.
            includeInStartupProfile = true,
        ) {
            coldStart()
            if (sign == null) signedOutJourney() else signedInJourney(sign)
        }
    }
}
