package com.tether.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.InMemorySettings
import com.tether.app.client.LoginResult
import com.tether.app.client.PasskeyAuthenticator
import com.tether.app.client.PasskeyCeremony
import com.tether.app.client.RealTetherClient
import com.tether.app.client.SignInRequirements
import com.tether.app.client.TetherClient
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T10.5: the passkey sign-in states on both surfaces, both skins, phone (412×915 @2.625) and tablet
 * (1280×800 @1): `login-passkey` the passkey offered above the password (Studio's key and "or continue
 * with your password", Retro's "› sign in with passkey" and its ⏎ hint), `-waiting` the ceremony
 * running ("Waiting for your passkey…" / "authenticating with passkey"), `-dismissed` the web's notice
 * after a closed prompt, `-pairing` (ta-coik.1) the passkey on the Pairing path. Timing-free: the probe and the sign-in are answered by hand (no network), the
 * clock is driven by hand, the focus is cleared (no caret), and the URL is a fixed example.
 */
enum class LoginPasskeyShot(val id: String) {
    Ready("login-passkey"),
    Waiting("login-passkey-waiting"),
    Dismissed("login-passkey-dismissed"),

    /** ta-coik.1: the passkey offered on the app's Pairing path too (Studio's key and separator, Retro's line and ⏎ hint). */
    Pairing("login-passkey-pairing"),
}

/** The real client for everything the screen reads, with the probe and the passkey sign-in answered by the shot. */
private class ShotClient(real: TetherClient, private val answer: CompletableDeferred<LoginResult>) : TetherClient by real {
    override suspend fun signInRequirements(baseUrl: String): SignInRequirements =
        SignInRequirements(usernameRequired = false, passwordLoginEnabled = true, passkeyCount = 2, passkeysUsable = true)

    override suspend fun passkeyLogin(baseUrl: String, passkeys: PasskeyAuthenticator): LoginResult = answer.await()
}

private object NeverPrompts : PasskeyAuthenticator {
    override val available: Boolean get() = true
    override suspend fun register(requestJson: String): PasskeyCeremony = throw AssertionError("a shot never prompts")
    override suspend fun authenticate(requestJson: String): PasskeyCeremony = throw AssertionError("a shot never prompts")
}

private fun ComposeContentTestRule.snapLogin(shot: LoginPasskeyShot, surface: LoginSurface, skin: TetherSkin, size: String, scope: CoroutineScope) {
    val answer = CompletableDeferred<LoginResult>()
    if (shot == LoginPasskeyShot.Dismissed) answer.complete(LoginResult.PasskeyDismissed)
    val client = ShotClient(RealTetherClient(settings = InMemorySettings(), httpClient = OkHttpClient(), scope = scope), answer)
    var focus: FocusManager? = null
    mainClock.autoAdvance = false
    setContent {
        focus = LocalFocusManager.current
        TetherTheme(skin.mode) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                LoginScreen(client = client, surface = surface, passkeys = NeverPrompts)
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription("Server URL"))).performTextReplacement("https://console.example.test")
    // The debounced probe, answered at once by the shot's client.
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    runOnIdle { focus?.clearFocus(force = true) }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    if (shot == LoginPasskeyShot.Pairing) {
        onNode(hasContentDescription("Pairing code", ignoreCase = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(600)
        waitForIdle()
        runOnIdle { focus?.clearFocus(force = true) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
    if (shot == LoginPasskeyShot.Waiting || shot == LoginPasskeyShot.Dismissed) {
        onNodeWithTag(LoginTags.Passkey, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }
    onRoot().captureRoboImage(
        "src/test/screenshots/${shot.id}/${surface.name.lowercase()}-${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

abstract class LoginPasskeyShotBase {
    @get:Rule val rule = createComposeRule()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class LoginPasskeyPhoneScreenshotTest(private val shot: LoginPasskeyShot, private val surface: LoginSurface, private val skin: TetherSkin) : LoginPasskeyShotBase() {
    @Test fun login() = rule.snapLogin(shot, surface, skin, "phone", scope)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}")
        fun params(): List<Array<Any>> = LoginPasskeyShot.entries.flatMap { s -> LoginSurface.entries.flatMap { f -> TetherSkin.entries.map { arrayOf<Any>(s, f, it) } } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class LoginPasskeyTabletScreenshotTest(private val shot: LoginPasskeyShot, private val surface: LoginSurface, private val skin: TetherSkin) : LoginPasskeyShotBase() {
    @Test fun login() = rule.snapLogin(shot, surface, skin, "tablet", scope)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}")
        fun params(): List<Array<Any>> = LoginPasskeyShot.entries.flatMap { s -> LoginSurface.entries.flatMap { f -> TetherSkin.entries.map { arrayOf<Any>(s, f, it) } } }
    }
}

/**
 * ta-coik.48: a 760dp-wide window (1:1), between the web's 700px stacking point and the 840dp the app
 * used to wait for: Studio's two panels side by side with the connection figure, both skins.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w760dp-h900dp-mdpi")
class LoginPasskeyMediumScreenshotTest(private val skin: TetherSkin) : LoginPasskeyShotBase() {
    @Test fun login() = rule.snapLogin(LoginPasskeyShot.Ready, LoginSurface.Studio, skin, "medium", scope)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
