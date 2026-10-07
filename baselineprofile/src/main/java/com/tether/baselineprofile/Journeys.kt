package com.tether.baselineprofile

import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
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

/**
 * Cold start: the launcher activity drawn and the first frame reported (also the signed-out journey's root).
 * The notification permission is granted first (a no-op once granted, and `pm clear` between iterations
 * revokes it again), so the system "Allow Tether to send you notifications?" dialog the shell raises
 * after sign-in never covers the screen the journeys wait on.
 */
fun MacrobenchmarkScope.coldStart() {
    pressHome()
    grantNotifications()
    startActivityAndWait()
}

/** `pm grant` answers on stdout rather than throwing (e.g. an unknown package), so the output is only logged. */
private fun MacrobenchmarkScope.grantNotifications() {
    val out = device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.POST_NOTIFICATIONS").trim()
    if (out.isNotEmpty()) Log.i(TAG, "pm grant POST_NOTIFICATIONS: $out")
}

/** The permission controller's Allow button (resource id on AOSP/Google builds, text otherwise); not "Don\u2019t allow". */
private val ALLOW_BUTTON = By.res("com.android.permissioncontroller", "permission_allow_button")
private val ALLOW_BUTTON_BY_TEXT = By.text(Pattern.compile("Allow", Pattern.CASE_INSENSITIVE))

/** Taps "Allow" when a runtime-permission dialog is up; true if it did. */
private fun MacrobenchmarkScope.allowPermissionDialog(): Boolean {
    val allow = device.findObject(ALLOW_BUTTON) ?: device.findObject(ALLOW_BUTTON_BY_TEXT) ?: return false
    allow.click()
    device.waitForIdle()
    Log.i(TAG, "tapped Allow on a permission dialog")
    return true
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
    step("sign in") { signIn(sign) }
    step("open a session") { openSession() }
    step("scroll the transcript") { scrollTranscript() }
}

/**
 * Runs one journey step. Any failure leaves as an IllegalStateException that names the step, the
 * original exception class (UiAutomator's StaleObjectException and IOException carry no message, which
 * once surfaced as an empty failure in the test XML) and the labelled nodes on screen.
 */
private fun <T> MacrobenchmarkScope.step(name: String, block: () -> T): T = try {
    block()
} catch (e: Throwable) {
    val msg = e.message?.takeIf { it.isNotBlank() } ?: "(no message)"
    // fail() already appended the screen; any other exception (UiAutomator's own) gets it here.
    val tail = if ("| on screen:" in msg) "" else " | on screen: ${screenDump()}"
    throw IllegalStateException("[journey step: $name] ${e.javaClass.simpleName}: $msg$tail", e)
}

private fun MacrobenchmarkScope.fail(what: String): Nothing =
    throw IllegalStateException("$what | on screen: ${screenDump()}")

/** Up to 30 labelled nodes of the app: `class[desc|text]`, for failure messages. */
private fun MacrobenchmarkScope.screenDump(): String = try {
    device.findObjects(By.pkg(TARGET_PACKAGE)).asSequence()
        .mapNotNull { n ->
            val d = runCatching { n.contentDescription }.getOrNull()
            val t = runCatching { n.text }.getOrNull()
            if (d.isNullOrEmpty() && t.isNullOrEmpty()) null
            else "${n.className.substringAfterLast('.')}[${d.orEmpty()}|${t.orEmpty()}]"
        }
        .take(30).joinToString("; ").ifEmpty { "(no labelled nodes)" } + " ; ime=" + imeShown()
} catch (e: Throwable) {
    "(screen dump failed: ${e.javaClass.simpleName})"
}

private const val EDIT_TEXT = "android.widget.EditText"

/**
 * Types [value] into the text input whose accessibility description is [desc]. LoginScreen.kt puts that
 * description on the input well's outer Box (a non-focusable android.view.View: ACTION_SET_TEXT fails on it,
 * which UiObject2 only logs), while the editable BasicTextField is a child (android.widget.EditText) of it.
 * So: find the description's node (scrolling / closing the keyboard as needed), take its EditText descendant
 * (else the focused EditText after tapping it), set the text and read it back. [secret]: only the length is
 * compared (a password field reports its characters masked) and the value is never put in a message.
 */
private fun MacrobenchmarkScope.typeInto(desc: String, value: String, secret: Boolean) {
    var lastProblem = "never attempted"
    repeat(3) { attempt ->
        try {
            val well = findScrolling(By.desc(desc), WAIT_MS)
                ?: fail("no node with content-description \"$desc\" within $WAIT_MS ms")
            var edit = well.findObject(By.clazz(EDIT_TEXT))
            if (edit == null) {
                well.click()
                device.waitForIdle()
                edit = device.findObject(By.clazz(EDIT_TEXT).focused(true))
                    ?: fail("\"$desc\" has no EditText descendant and none took focus (well class ${well.className}, " +
                        "children ${well.childCount})")
            }
            edit.click()
            edit.text = value
            device.waitForIdle()
            val shown = edit.text.orEmpty()
            val ok = if (secret) shown.length == value.length else shown.trim() == value
            if (ok) return
            lastProblem = if (secret) "field shows ${shown.length} chars, typed ${value.length}"
            else "field shows \"$shown\", typed \"$value\""
        } catch (e: androidx.test.uiautomator.StaleObjectException) {
            lastProblem = "node went stale (${e.javaClass.simpleName}) on attempt ${attempt + 1}"
        }
    }
    fail("could not type into \"$desc\": $lastProblem")
}

private fun MacrobenchmarkScope.signIn(sign: SignInArgs) {
    if (device.wait(Until.hasObject(By.desc("Server URL")), WAIT_MS) != true) {
        fail("sign-in screen not shown (is the app already signed in? clear its data first)")
    }
    typeInto("Server URL", sign.serverUrl, secret = false)
    // The soft keyboard now covers the lower form, and the screen fetches the server's sign-in methods
    // (debounced) before the password field exists: typeInto closes the keyboard and scroll-searches.
    if (findScrolling(By.desc("Dashboard password"), WAIT_MS) == null) {
        fail("no password field: the server offers no password sign-in (or the form never scrolled to it)")
    }
    typeInto("Dashboard password", sign.password, secret = true)
    val unlock = findScrolling(By.desc("Unlock Tether"), WAIT_MS) ?: fail("no 'Unlock Tether' button")
    unlock.click()
    // Signed in once the shell's top bar shows. Its brand link ("Tether \u2014 Overview", Topbar.kt BrandLink) is
    // on every layout; "Open sessions" (drawer key, narrow only) and the Overview cards are not on all of
    // them. A permission dialog over the shell is dismissed while waiting (the grant above should make that
    // rare; this is the fallback).
    val shell = By.desc(Pattern.compile("Tether \u2014 Overview|Open sessions|Open session: .*"))
    val deadline = System.currentTimeMillis() + WAIT_MS * 2
    var landed = false
    while (!landed && System.currentTimeMillis() < deadline) {
        landed = device.wait(Until.hasObject(shell), 500L)
        if (!landed) allowPermissionDialog()
    }
    if (!landed) fail("sign-in did not reach the shell within ${WAIT_MS * 2} ms (wrong password, or a username is required?)")
    Log.i(TAG, "signed in")
}

/**
 * True while the soft keyboard is up (`pressBack` with it down would leave the activity, so it is checked
 * first). The IME is its own accessibility window, found by its package; `dumpsys input_method` is only a
 * fallback and guarded, because a throwing shell call here once ended the whole test with no message.
 */
private fun MacrobenchmarkScope.imeShown(): Boolean {
    if (device.hasObject(By.pkg(Pattern.compile(".*(inputmethod|keyboard).*", Pattern.CASE_INSENSITIVE)))) return true
    return runCatching { device.executeShellCommand("dumpsys input_method").contains("mInputShown=true") }.getOrDefault(false)
}

/**
 * Finds [selector], polling up to [timeoutMs]: between polls the soft keyboard is closed (Back, only if shown)
 * and the form is swiped up, since a control below the fold (or under the keyboard) is not in the
 * accessibility tree. Null when it never appears, so the caller fails loudly.
 */
private fun MacrobenchmarkScope.findScrolling(selector: BySelector, timeoutMs: Long): UiObject2? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        device.findObject(selector)?.let { return it }
        if (imeShown()) {
            device.pressBack()
            device.waitForIdle()
            continue
        }
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10, 20)
        device.waitForIdle()
        device.wait(Until.hasObject(selector), 500L)
    }
    return device.findObject(selector)
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
    if (target == null) fail("no session to open (does the server have at least one session?)")
    target.click()
    device.waitForIdle()
    Log.i(TAG, "opened a session")
}

private fun MacrobenchmarkScope.scrollTranscript() {
    val list = device.wait(Until.findObject(By.scrollable(true)), WAIT_MS)
        ?: fail("no scrollable transcript on screen")
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
