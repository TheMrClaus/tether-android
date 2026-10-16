package com.tether.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v144 (ta-j3h9): the credential dialog's states, seeded synchronously (the state built here, no tap, the clock driven by
 * hand) and captured with their own window. The values typed are obviously fake; the dots are what a field draws.
 * `-password` D1, `-password-mismatch` D2 with the note, `-password-sending` D3 (the spinner frozen),
 * `-password-weak` D5, `-token-ready` D2 token, `-token-bad-current` D5 (Current emptied and focused),
 * `-token-clear` the danger key, `-no-reply` D6.
 */
enum class SecretShot(val id: String, val mode: SecretMode) {
    Password("settings-secret-password", SecretMode.PasswordSet),
    PasswordMismatch("settings-secret-password-mismatch", SecretMode.PasswordSet),
    PasswordSending("settings-secret-password-sending", SecretMode.PasswordSet),
    PasswordWeak("settings-secret-password-weak", SecretMode.PasswordSet),
    TokenReady("settings-secret-token-ready", SecretMode.TokenSet),
    TokenBadCurrent("settings-secret-token-bad-current", SecretMode.TokenSet),
    TokenClear("settings-secret-token-clear", SecretMode.TokenClear),
    NoReply("settings-secret-no-reply", SecretMode.TokenSet),
    ;

    /** The field the web focuses in this state (spec 4.3); null = none (the shot is not about focus). */
    val focused: SecretField? get() = when (this) {
        Password -> SecretField.Current
        PasswordWeak -> SecretField.New
        TokenBadCurrent -> SecretField.Current
        else -> null
    }

    fun seed(): ServerSecretsState {
        val s = ServerSecretsState()
        s.open(mode)
        val d = s.dialog!!
        val fake = "FAKE-demo-pass"
        when (this) {
            Password -> Unit
            PasswordMismatch -> { d.current = fake; d.next = "FAKE-new-password"; d.confirm = "FAKE-new-passwor"; d.focus = null }
            PasswordSending -> { d.current = fake; d.next = "FAKE-new-password"; d.confirm = "FAKE-new-password"; d.sending = true; d.focus = null }
            PasswordWeak -> {
                d.current = fake; d.next = "FAKE-8ch"; d.confirm = "FAKE-8ch"
                d.error = "The new password must be at least 12 characters in production (got 8)."
                d.requestFocus(SecretField.New)
            }
            TokenReady -> { d.current = fake; d.next = "FAKE-token"; d.focus = null }
            TokenBadCurrent -> {
                d.next = "FAKE-token"
                d.error = "That isn't the current password. Nothing was changed."
                d.requestFocus(SecretField.Current)
            }
            TokenClear -> { d.current = fake; d.focus = null }
            NoReply -> { d.current = fake; d.next = "FAKE-token"; d.error = com.tether.app.client.ServerSecretOutcome.NO_REPLY_MESSAGE; d.focus = null }
        }
        return s
    }
}

private fun AndroidComposeTestRule<*, ComponentActivity>.snapSecret(shot: SecretShot, skin: TetherSkin, size: String, name: String = shot.id, fontScaled: SecretShot = shot) {
    mainClock.autoAdvance = false
    setContent {
        TetherTheme(skin.mode) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                val secrets = androidx.compose.runtime.remember { fontScaled.seed() }
                ServerSecretDialog(secrets.dialog!!, secrets, ServerFixtures.binding())
            }
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    // Robolectric has no IME and the dialog's own focus request races the first frame: put the ring where the web does.
    fontScaled.focused?.let { f ->
        onNodeWithTag(ServerSecretTags.field(f), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        mainClock.advanceTimeBy(700)
        waitForIdle()
    }
    captureScreenRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ServerSecretPhoneScreenshotTest(private val shot: SecretShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun secret() = rule.snapSecret(shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        // No Reply is shot in Studio light only (the spec's table).
        fun params(): List<Array<Any>> = SecretShot.entries.flatMap { s ->
            TetherSkin.entries.filter { s != SecretShot.NoReply || it == TetherSkin.Studio }.map { arrayOf<Any>(s, it) }
        }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ServerSecretTabletScreenshotTest(private val shot: SecretShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun secret() = rule.snapSecret(shot, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(SecretShot.Password, it) }
    }
}

/** 1.3x: the password dialog filled with a mismatch (D2); the Authentication rows as `-auth`. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ServerSecretFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun dialog() = rule.snapSecret(SecretShot.PasswordMismatch, skin, "phone", name = "settings-secret-password-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** 2.0x, Studio light only: the title wraps and the footer wraps, a weak refusal in frame. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class ServerSecretFont2ScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun dialog() = rule.snapSecret(SecretShot.PasswordWeak, skin, "phone", name = "settings-secret-password-font-2.0x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(arrayOf<Any>(TetherSkin.Studio))
    }
}

/** The Authentication rows at 1.3x (both skins) and 2.0x (Studio light only): the tag wraps, phone keys go one per line. */
abstract class AuthFontShotBase(private val skin: TetherSkin, private val label: String) : SettingsShotBase() {
    // The token keys (the Clear key is the lowest) are scrolled fully into frame: at 2.0x they stack one per line below the fold.
    @Test fun settings() = rule.snapSettings(
        store, SettingsShot.AdvancedAuth, skin, "phone", name = "settings-advanced-auth-font-$label",
        ensureVisible = ServerSecretTags.clear(com.tether.app.client.ServerSetting.ProxyToken),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SettingsAuthFont13ScreenshotTest(skin: TetherSkin) : AuthFontShotBase(skin, "1.3x") {
    companion object { @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) } }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class SettingsAuthFont20ScreenshotTest(skin: TetherSkin) : AuthFontShotBase(skin, "2.0x") {
    companion object { @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params(): List<Array<Any>> = listOf(arrayOf<Any>(TetherSkin.Studio)) }
}
