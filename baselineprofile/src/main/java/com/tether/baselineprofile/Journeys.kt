package com.tether.baselineprofile

import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** The app under test: the package of :app's applicationId. */
const val TARGET_PACKAGE = "com.tether.app"

private const val TAG = "TetherJourneys"
private const val WAIT_MS = 15_000L

/**
 * Instrumentation arguments the lead passes at run time (never committed, no default):
 *   -Pandroid.testInstrumentationRunnerArguments.tether.serverUrl=http://10.0.2.2:4290
 *   -Pandroid.testInstrumentationRunnerArguments.tether.password=<the isolated fake server's password>
 * Inside the emulator 127.0.0.1 is the emulator itself, so the host's server is 10.0.2.2.
 * With either one absent only the signed-out journeys run.
 */
data class SignInArgs(val serverUrl: String, val password: String)

fun signInArgs(): SignInArgs? {
    val args = InstrumentationRegistry.getArguments()
    val url = args.getString("tether.serverUrl")?.trim().orEmpty()
    val password = args.getString("tether.password").orEmpty()
    return if (url.isNotEmpty() && password.isNotEmpty()) SignInArgs(url, password) else null
}

/** Cold start: the launcher activity drawn and the first frame reported (also the signed-out journey's root). */
fun MacrobenchmarkScope.coldStart() {
    pressHome()
    startActivityAndWait()
}

/**
 * Signed-out journey, after [coldStart]: wait for the sign-in screen, then exercise its mode switch
 * (Password -> Pairing code -> Password) so the screen's recomposition paths are profiled too.
 */
fun MacrobenchmarkScope.signedOutJourney() {
    device.wait(Until.hasObject(By.desc("Server URL")), WAIT_MS)
    val pairing = device.findObject(By.desc(Pattern.compile("Pairing code(, selected)?")))
    if (pairing != null) {
        pairing.click()
        device.waitForIdle()
        device.findObject(By.desc(Pattern.compile("Password(, selected)?")))?.click()
        device.waitForIdle()
    }
}

/**
 * Signed-in journeys, after [coldStart], only when [signInArgs] is non-null: sign in against the
 * given server, open a session, scroll the transcript.
 * Selectors are the app's own accessibility descriptions (LoginScreen.kt "Server URL" /
 * "Dashboard password" / "Unlock Tether"; Topbar "Open sessions"; SessionRow / Overview rows).
 * When a step cannot be reached the journey fails loudly (the lead asked for these journeys by
 * passing credentials, so a silent skip would produce a cold-start-only profile unnoticed).
 */
fun MacrobenchmarkScope.signedInJourney(sign: SignInArgs) {
    signIn(sign)
    openSession()
    scrollTranscript()
}

private fun MacrobenchmarkScope.signIn(sign: SignInArgs) {
    val server = device.wait(Until.findObject(By.desc("Server URL")), WAIT_MS)
        ?: error("sign-in screen not shown (is the app already signed in? clear its data first)")
    server.click()
    server.text = sign.serverUrl
    device.waitForIdle()
    val password = device.wait(Until.findObject(By.desc("Dashboard password")), WAIT_MS)
        ?: error("no password field: the server offers no password sign-in")
    password.click()
    password.text = sign.password
    device.waitForIdle()
    val unlock = device.wait(Until.findObject(By.desc("Unlock Tether")), WAIT_MS)
        ?: error("no 'Unlock Tether' button")
    unlock.click()
    // Signed in once the shell's sessions affordance (phone Topbar) or an overview card shows.
    val landed = device.wait(
        Until.hasObject(By.desc(Pattern.compile("Open sessions|Open session: .*"))),
        WAIT_MS * 2,
    )
    check(landed) { "sign-in did not reach the shell within ${WAIT_MS * 2} ms" }
    Log.i(TAG, "signed in")
}

private fun MacrobenchmarkScope.openSession() {
    // Overview card first (the post-sign-in screen on a wide layout), else the sessions drawer.
    var target: UiObject2? = device.findObject(By.desc(Pattern.compile("Open session: .*")))
    if (target == null) {
        device.findObject(By.desc("Open sessions"))?.click()
        device.waitForIdle()
        // SessionRow's merged description: "<name>, ..." ending in a status and a relative time; the
        // row is the clickable node that is not an archive / drag handle.
        target = device.wait(
            Until.findObject(
                By.clickable(true).desc(Pattern.compile("(?!Archive |Hold and drag |Clear filters).+, (chat|terminal|.*ago|.*\\d+[smhd]).*")),
            ),
            WAIT_MS,
        )
    }
    checkNotNull(target) { "no session to open (does the server have at least one session?)" }
    target.click()
    device.waitForIdle()
    Log.i(TAG, "opened a session")
}

private fun MacrobenchmarkScope.scrollTranscript() {
    val list = device.wait(Until.findObject(By.scrollable(true)), WAIT_MS)
        ?: error("no scrollable transcript on screen")
    list.setGestureMargin(device.displayWidth / 5)
    repeat(3) {
        list.fling(Direction.UP)
        device.waitForIdle()
    }
    repeat(2) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
    Log.i(TAG, "scrolled the transcript")
}
