package com.tether.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tether.app.client.PairedDevice
import com.tether.app.client.Passkey
import com.tether.app.client.SecuritySession
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.util.elapsedLabel
import com.tether.app.ui.util.relativeTime
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable

/** Tags of the Devices panel's parts (below Notifications). */
object DevicesTags {
    const val Passkeys = "devices-passkeys"
    const val Sessions = "devices-sessions"
    const val Paired = "devices-paired"
    fun ownerNote(area: DevicesArea) = "devices-owner-note:${area.name}"
    fun checkAgain(area: DevicesArea) = "devices-check-again:${area.name}"
    fun line(area: DevicesArea) = "devices-line:${area.name}"
    const val DevicesChecking = "devices-checking"
    const val DevicesEmpty = "devices-empty"
    const val PasskeysChecking = "passkeys-checking"
    const val PasskeysEmpty = "passkeys-empty"
    const val PasskeysHttps = "passkeys-https"
    const val AddPasskey = "passkeys-add"
    const val PasskeyLabel = "passkeys-label"
    const val PasskeyUnavailable = "passkeys-unavailable"
    const val PasskeyNeedsHttps = "passkeys-needs-https"
    const val PasswordToggle = "passkeys-password"
    const val SessionsChecking = "sessions-checking"
    const val SignOutOthers = "sessions-sign-out-others"
    const val Pair = "devices-pair"
    const val PairHint = "devices-pair-hint"
    const val CodeCard = "pairing-code-card"
    const val CodeShown = "pairing-code"
    const val CodeCopy = "pairing-code-copy"
    const val CodeExpiry = "pairing-code-expiry"
    const val ConfirmSheet = "devices-confirm"
    const val ConfirmCancel = "devices-confirm-cancel"
    const val ConfirmGo = "devices-confirm-go"
    fun device(id: String) = "device:$id"
    fun deviceSelf(id: String) = "device-self:$id"
    fun revoke(id: String) = "device-revoke:$id"
    fun passkey(id: String) = "passkey:$id"
    fun rename(id: String) = "passkey-rename:$id"
    fun renameField(id: String) = "passkey-rename-field:$id"
    fun remove(id: String) = "passkey-remove:$id"
    fun session(id: String) = "session:$id"
    fun sessionCurrent(id: String) = "session-current:$id"
    fun signOut(id: String) = "session-sign-out:$id"
}

/** A confirmation the panel is asking (the web's three `<dialog>`s, their words as the web's). */
sealed interface DevicesConfirm {
    data class Revoke(val device: PairedDevice, val self: SelfMatch) : DevicesConfirm
    data class RemovePasskey(val passkey: Passkey) : DevicesConfirm
    data object SignOutOthers : DevicesConfirm
}

/**
 * The Devices panel's sections after Notifications: Passkeys and Signed-in sessions
 * (SignInSecuritySection), then Paired devices, in the web's order. Read once when the tab first
 * opens for this server. Signed out: the headings only.
 */
@Composable
internal fun DevicesSecuritySections(binding: DevicesBinding, narrow: Boolean) {
    val controller = binding.controller?.takeIf { it.origin != null }
    LaunchedEffect(controller) { controller?.open() }
    var confirm by remember(controller) { mutableStateOf<DevicesConfirm?>(null) }
    PasskeysSection(controller, binding, narrow, onConfirm = { confirm = it })
    SessionsSection(controller, binding, narrow, onConfirm = { confirm = it })
    PairedDevicesSection(controller, binding, narrow, onConfirm = { confirm = it })
    val pending = confirm
    if (controller != null && pending != null) {
        DevicesConfirmDialog(
            pending,
            onCancel = { confirm = null },
            onConfirm = {
                confirm = null
                when (pending) {
                    is DevicesConfirm.Revoke -> controller.revoke(pending.device, pending.self)
                    is DevicesConfirm.RemovePasskey -> controller.removePasskey(pending.passkey)
                    DevicesConfirm.SignOutOthers -> controller.revokeOtherSessions()
                }
            },
        )
    }
}

// ---- Passkeys ---------------------------------------------------------------------------------

@Composable
private fun PasskeysSection(controller: DevicesController?, binding: DevicesBinding, narrow: Boolean, onConfirm: (DevicesConfirm) -> Unit) {
    SettingsSection(DevicesCopy.PASSKEYS_TITLE, AnnotatedString(DevicesCopy.PASSKEYS_CAPTION), narrow, modifier = Modifier.testTag(DevicesTags.Passkeys)) {
        val c = controller ?: return@SettingsSection
        if (c.signedOut) {
            // r3: a line already given (a passkey created but not saved) stays above the signed-out line.
            c.securityLine?.let { LineView(it, DevicesTags.line(DevicesArea.Security)) }
            return@SettingsSection SignedOutLine()
        }
        val ownerNeeded = c.ownerNeeded(DevicesArea.Security)
        if (ownerNeeded) OwnerNeeded(c, DevicesArea.Security)
        c.securityLine?.let { LineView(it, DevicesTags.line(DevicesArea.Security)) }
        val view = c.passkeys
        val now = binding.now()
        val busy = c.securityBusy != null || ownerNeeded
        var renaming by rememberSaveable(c) { mutableStateOf<String?>(null) }
        view?.passkeys?.forEach { passkey ->
            key(passkey.id) {
                PasskeyRow(passkey, now, narrow, busy, renaming == passkey.id,
                    onRename = { renaming = passkey.id },
                    onRenamed = { text ->
                        renaming = null
                        c.renamePasskey(passkey, text)
                    },
                    onCancelRename = { renaming = null },
                    onRemove = { onConfirm(DevicesConfirm.RemovePasskey(passkey)) })
            }
        }
        if (view != null && view.passkeys.isEmpty()) MutedLine(DevicesCopy.PASSKEYS_EMPTY, DevicesTags.PasskeysEmpty)
        if (view == null && !ownerNeeded && c.securityLine == null) MutedLine(DevicesCopy.PASSKEYS_CHECKING, DevicesTags.PasskeysChecking, status = true)
        if (view != null && !view.passkeysUsable) MutedLine(DevicesCopy.PASSKEYS_NEED_HTTPS, DevicesTags.PasskeysHttps)
        // T10.5: sign-in-security.tsx's add row: a label and Add a passkey, which opens this phone's
        // passkey prompt (Credential Manager). The label is `rememberSaveable` (ta-coik.20: a rotation keeps it)
        // and clears once a passkey is added, as the web's does.
        var label by rememberSaveable(c) { mutableStateOf("") }
        LaunchedEffect(c.passkeysAdded) { if (c.passkeysAdded > 0) label = "" }
        val https = c.origin?.let(com.tether.app.client.PasskeyRules::ceremonyAllowed) == true
        val canAdd = view != null && view.passkeysUsable && c.authenticator.available && https && !busy
        val adding = c.securityBusy == DevicesAction.AddPasskey
        // The web's `settings-passkey-add`: stacked, the key full width, on a phone; one row when wide.
        val field: @Composable (Modifier) -> Unit = { m ->
            PasskeyLabelField(label, narrow, enabled = canAdd, onChange = { label = it }, onDone = { if (canAdd) c.addPasskey(label) }, modifier = m)
        }
        val addKey: @Composable (Modifier) -> Unit = { m ->
            TetherKey(
                onClick = { c.addPasskey(label) },
                enabled = canAdd,
                classes = KeyClasses.ButtonSecondary,
                label = if (adding) DevicesCopy.ADDING_PASSKEY else DevicesCopy.ADD_PASSKEY,
                icon = TetherIcons.Fingerprint,
                iconSize = 15.dp,
                modifier = m.testTag(DevicesTags.AddPasskey),
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (narrow) {
                field(Modifier.fillMaxWidth())
                addKey(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    field(Modifier.weight(1f))
                    addKey(Modifier)
                }
            }
            if (!c.authenticator.available) MutedLine(DevicesCopy.PASSKEY_UNAVAILABLE, DevicesTags.PasskeyUnavailable, rule = false)
            // r2 (security F1): an http console is never asked for a passkey from the phone.
            else if (!https) MutedLine(DevicesCopy.PASSKEY_NEEDS_HTTPS, DevicesTags.PasskeyNeedsHttps, rule = false)
        }
        if (view != null) {
            SettingsToggleRow(
                title = DevicesCopy.PASSWORD_TITLE,
                caption = DevicesRules.passwordNote(view),
                tip = null,
                checked = view.policy.passwordLoginEnabled,
                onToggle = { c.setPasswordLogin(!view.policy.passwordLoginEnabled) },
                narrow = narrow,
                enabled = DevicesRules.passwordToggleable(view) && !busy,
                modifier = Modifier.testTag(DevicesTags.PasswordToggle),
            )
        }
    }
}

@Composable
private fun PasskeyRow(
    passkey: Passkey,
    now: Long,
    narrow: Boolean,
    busy: Boolean,
    renaming: Boolean,
    onRename: () -> Unit,
    onRenamed: (String) -> Unit,
    onCancelRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val label = DevicesRules.label(passkey.label, "Passkey")
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.testTag(DevicesTags.passkey(passkey.id)),
        text = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (renaming) {
                    PasskeyRenameField(passkey, label, narrow, onRenamed, onCancelRename)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, color = t.ink, style = settingsText(type.ui, 14f, 650, lineHeight = 1.5f), modifier = Modifier.weight(1f, fill = false))
                        if (passkey.backedUp) Tag(DevicesCopy.SYNCED)
                    }
                }
                Text(
                    DevicesCopy.passkeyLine(relativeTime(passkey.createdAt, now), passkey.lastUsedAt.takeIf { it > 0 }?.let { relativeTime(it, now) }),
                    color = t.muted,
                    style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f),
                )
            }
        },
        control = { m ->
            val keys: @Composable (Modifier) -> Unit = { k ->
                TetherKey(
                    onClick = onRename,
                    classes = KeyClasses.ButtonSecondary,
                    icon = TetherIcons.Pencil,
                    iconSize = 14.dp,
                    enabled = !busy && passkey.actionable && !renaming,
                    contentDescription = DevicesCopy.renameLabel(label),
                    modifier = k.testTag(DevicesTags.rename(passkey.id)),
                )
                TetherKey(
                    onClick = onRemove,
                    classes = KeyClasses.ButtonSecondary,
                    label = DevicesCopy.REMOVE,
                    enabled = !busy && passkey.actionable,
                    contentDescription = DevicesCopy.removeLabel(label),
                    modifier = k.testTag(DevicesTags.remove(passkey.id)),
                )
            }
            if (narrow) Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) { keys(Modifier.fillMaxWidth()) }
            else Row(m, horizontalArrangement = Arrangement.spacedBy(8.dp)) { keys(Modifier) }
        },
    )
}

/**
 * sign-in-security.tsx's rename input: filled with the label, focused, committed on Done or when the
 * focus leaves (the web's blur), never while the activity is being recreated (a rotation is not a
 * blur). An unchanged or blank label sends nothing. `rememberSaveable` (ta-coik.20): a rotation keeps the edit.
 */
@Composable
private fun PasskeyRenameField(passkey: Passkey, shown: String, narrow: Boolean, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val t = LocalTetherTokens.current
    val activity = LocalContext.current.findActivity()
    var text by rememberSaveable(passkey.id) { mutableStateOf(com.tether.app.client.LabelText.withoutHidden(passkey.label)) }
    var focused by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val latest by rememberUpdatedState(text)
    val finish: () -> Unit = {
        if (!done) {
            done = true
            if (latest.trim().isEmpty() || latest.trim() == passkey.label) onCancel() else onDone(latest)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = serverFieldStyle(narrow)
    BasicTextField(
        value = text,
        onValueChange = { if (it.length <= com.tether.app.client.DeviceSecurityJson.MAX_LABEL_SENT) text = it },
        singleLine = true,
        textStyle = style.copy(color = t.ink),
        cursorBrush = SolidColor(t.violet),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DevicesTags.renameField(passkey.id))
            .semantics { contentDescription = DevicesCopy.renameLabel(shown) }
            .focusRequester(focus)
            .onFocusChanged { f ->
                if (focused && !f.isFocused && activity?.isChangingConfigurations != true) finish()
                focused = f.isFocused
            },
        decorationBox = { inner -> ServerFieldBox(true, focused, style, null, inner) },
    )
}

/** sign-in-security.tsx's `passkey-label-input`: at most the server's 64 characters; Done adds. */
@Composable
private fun PasskeyLabelField(text: String, narrow: Boolean, enabled: Boolean, onChange: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    var focused by remember { mutableStateOf(false) }
    // The web's `passkey-label-input` is the UI face, not the server fields' mono.
    val style = settingsText(LocalTetherTypography.current.ui, if (narrow) 16f else 13f, 400, lineHeight = 1.5f)
    BasicTextField(
        value = text,
        onValueChange = { if (it.length <= DevicesCopy.PASSKEY_LABEL_MAX) onChange(it) },
        enabled = enabled,
        singleLine = true,
        textStyle = style.copy(color = t.ink),
        cursorBrush = SolidColor(t.violet),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .testTag(DevicesTags.PasskeyLabel)
            .semantics { contentDescription = DevicesCopy.PASSKEY_LABEL }
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner -> ServerFieldBox(enabled, focused, style, if (text.isEmpty()) AnnotatedString(DevicesCopy.PASSKEY_LABEL_PLACEHOLDER) else null, inner) },
    )
}

// ---- Signed-in sessions -------------------------------------------------------------------------

@Composable
private fun SessionsSection(controller: DevicesController?, binding: DevicesBinding, narrow: Boolean, onConfirm: (DevicesConfirm) -> Unit) {
    SettingsSection(DevicesCopy.SESSIONS_TITLE, AnnotatedString(DevicesCopy.SESSIONS_CAPTION), narrow, modifier = Modifier.testTag(DevicesTags.Sessions)) {
        val c = controller ?: return@SettingsSection
        if (c.signedOut) return@SettingsSection
        val sessions = c.sessions
        val now = binding.now()
        val ownerNeeded = c.ownerNeeded(DevicesArea.Security)
        val busy = c.securityBusy != null || ownerNeeded
        sessions?.forEach { session -> key(session.id) { SessionRow(session, now, narrow, busy) { c.revokeSession(session) } } }
        if (sessions == null && !ownerNeeded && c.securityLine == null) MutedLine(DevicesCopy.SESSIONS_CHECKING, DevicesTags.SessionsChecking, status = true)
        val others = sessions.orEmpty().count { !it.current }
        TetherKey(
            onClick = { onConfirm(DevicesConfirm.SignOutOthers) },
            classes = KeyClasses.ButtonSecondary,
            label = DevicesCopy.SIGN_OUT_OTHERS,
            icon = TetherIcons.MonitorSmartphone,
            iconSize = 15.dp,
            enabled = !busy && others > 0,
            modifier = Modifier.padding(top = 16.dp).testTag(DevicesTags.SignOutOthers),
        )
    }
}

@Composable
private fun SessionRow(session: SecuritySession, now: Long, narrow: Boolean, busy: Boolean, onSignOut: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.testTag(DevicesTags.session(session.id)),
        text = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(DevicesCopy.method(session.method), color = t.ink, style = settingsText(type.ui, 14f, 650, lineHeight = 1.5f))
                    if (session.current) Tag(DevicesCopy.THIS_DEVICE, Modifier.testTag(DevicesTags.sessionCurrent(session.id)))
                }
                Text(
                    DevicesRules.userAgent(session.userAgent) + "\n" + DevicesCopy.sessionLine(relativeTime(session.createdAt, now), relativeTime(session.lastSeenAt, now)),
                    color = t.muted,
                    style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f),
                )
            }
        },
        control = if (session.current) null else { m ->
            TetherKey(
                onClick = onSignOut,
                classes = KeyClasses.ButtonSecondary,
                label = DevicesCopy.SIGN_OUT,
                icon = TetherIcons.LogOut,
                iconSize = 14.dp,
                enabled = !busy && session.actionable,
                contentDescription = DevicesCopy.SIGN_OUT_LABEL,
                modifier = m.testTag(DevicesTags.signOut(session.id)),
            )
        },
    )
}

// ---- Paired devices -----------------------------------------------------------------------------

@Composable
private fun PairedDevicesSection(controller: DevicesController?, binding: DevicesBinding, narrow: Boolean, onConfirm: (DevicesConfirm) -> Unit) {
    SettingsSection(DevicesCopy.DEVICES_TITLE, AnnotatedString(DevicesCopy.DEVICES_CAPTION), narrow, modifier = Modifier.testTag(DevicesTags.Paired), last = true) {
        val c = controller ?: return@SettingsSection
        if (c.signedOut) {
            c.devicesLine?.let { LineView(it, DevicesTags.line(DevicesArea.Devices)) } ?: SignedOutLine()
            return@SettingsSection
        }
        val ownerNeeded = c.ownerNeeded(DevicesArea.Devices)
        if (ownerNeeded) OwnerNeeded(c, DevicesArea.Devices)
        c.devicesLine?.takeIf { it.error }?.let { LineView(it, DevicesTags.line(DevicesArea.Devices)) }
        // The code card's own clock (the web's `now`): read on the hand-driven clock in the goldens.
        var now by remember { mutableLongStateOf(binding.now()) }
        c.shown?.let { shown -> key(shown.serial) { PairingCodeCard(c, shown, now, readNow = binding.now, onTick = { now = it }) } }
        val devices = c.devices
        val busy = c.devicesBusy != null || ownerNeeded
        devices?.forEach { device ->
            key(device.id) {
                val self = DevicesRules.selfMatch(c.signIn, devices, device)
                DeviceRow(device, self == SelfMatch.Yes, binding.now(), narrow, busy) { onConfirm(DevicesConfirm.Revoke(device, self)) }
            }
        }
        if (devices != null && devices.isEmpty()) MutedLine(DevicesCopy.DEVICES_EMPTY, DevicesTags.DevicesEmpty)
        if (devices == null && !ownerNeeded && c.devicesLine == null) MutedLine(DevicesCopy.DEVICES_CHECKING, DevicesTags.DevicesChecking, status = true)
        c.devicesLine?.takeIf { !it.error }?.let { LineView(it, DevicesTags.line(DevicesArea.Devices)) }
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TetherKey(
                onClick = { c.pair() },
                classes = KeyClasses.ButtonSecondary,
                label = if (c.devicesBusy == DevicesAction.Pair) DevicesCopy.PAIRING else DevicesCopy.PAIR,
                enabled = !busy,
                modifier = Modifier.testTag(DevicesTags.Pair),
            )
            val others = DevicesRules.otherPairings(c.pairings, now, c.shown?.expiresAt)
            MutedLine(DevicesCopy.pairHint(others), DevicesTags.PairHint, rule = false, small = true)
        }
    }
}

@Composable
private fun DeviceRow(device: PairedDevice, self: Boolean, now: Long, narrow: Boolean, busy: Boolean, onRevoke: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val label = DevicesRules.label(device.label, "Paired device")
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.testTag(DevicesTags.device(device.id)),
        text = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = t.ink, style = settingsText(type.ui, 14f, 650, lineHeight = 1.5f), modifier = Modifier.weight(1f, fill = false))
                    if (self) Tag(DevicesCopy.THIS_DEVICE, Modifier.testTag(DevicesTags.deviceSelf(device.id)))
                }
                Text(
                    DevicesCopy.pairedLine(relativeTime(device.createdAt, now), relativeTime(device.lastSeenAt, now)),
                    color = t.muted,
                    style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f),
                )
            }
        },
        control = { m ->
            TetherKey(
                onClick = onRevoke,
                classes = KeyClasses.ButtonSecondary,
                label = DevicesCopy.REVOKE,
                enabled = !busy && device.actionable,
                contentDescription = DevicesCopy.revokeLabel(label),
                modifier = m.testTag(DevicesTags.revoke(device.id)),
            )
        },
    )
}

/**
 * `.pairing-code-card`: the fresh code, shown ONCE, drawn at once as on the web (ta-coik.5): the
 * two blocks of four, one node read letter by letter (the web's `role="img"` label). Copy code as on
 * the web, onto a sensitive clip (see [PairingClipboard]). ta-coik.15: once it expires the code stays
 * on the card with Copy, and the countdown gives way to the expired line, as on the web
 * (paired-devices.tsx: only closing Settings forgets the code).
 */
@Composable
private fun PairingCodeCard(c: DevicesController, shown: ShownCode, now: Long, readNow: () -> Long, onTick: (Long) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val code = shown.code
    var copied by remember { mutableStateOf(false) }
    val left = DevicesRules.secondsLeft(shown.expiresAt, now)
    val expired = left <= 0
    LaunchedEffect(shown.serial, expired) {
        // One tick a second while the clock says the code has time left (bounded), as the web's
        // interval runs only while counting: the tick that finds it run out relaunches this as expired.
        if (expired) return@LaunchedEffect
        var ticks = 0L
        while (ticks++ < PAIRING_TICKS_MAX) {
            delay(1_000)
            val at = readNow()
            onTick(at)
            if (DevicesRules.secondsLeft(shown.expiresAt, at) <= 0) break
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Column(
        Modifier
            .testTag(DevicesTags.CodeCard)
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .cssSurface(RoundedCornerShape(8.dp), t.mineral, null, emptyList())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(DevicesCopy.CODE_LABEL, color = t.faint, style = settingsText(type.ui, 13f, 720))
        val big = settingsText(type.mono, 24f, 650, lineHeight = 1.3f, trackingEm = 0.12f)
        val plain = code.code.reveal()
        Text(
            plain.take(4) + "  " + plain.drop(4),
            color = t.white,
            style = big,
            modifier = Modifier
                .fillMaxWidth()
                .cssSurface(RoundedCornerShape(8.dp), t.graphite, CssBorder(1.dp, t.lineStrong), emptyList())
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag(DevicesTags.CodeShown)
                .clearAndSetSemantics { contentDescription = DevicesCopy.codeSpoken(plain) },
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight(700), color = t.ink)) { append(DevicesCopy.CODE_SHOWN_ONCE) }
                append(DevicesCopy.CODE_NOTE)
            },
            color = t.muted,
            style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TetherKey(
                onClick = { if (c.copyCode()) copied = true },
                classes = KeyClasses.ButtonSecondary,
                label = if (copied) DevicesCopy.COPIED else DevicesCopy.COPY,
                icon = if (copied) TetherIcons.Check else TetherIcons.Copy,
                iconSize = 15.dp,
                modifier = Modifier.testTag(DevicesTags.CodeCopy),
            )
            if (expired) {
                Row(
                    Modifier.weight(1f, fill = false).testTag(DevicesTags.CodeExpiry).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.size(14.dp))
                    Text(DevicesCopy.EXPIRED, color = t.attentionInk, style = settingsText(type.ui, 13f, 500, lineHeight = 1.5f))
                }
            } else {
                Row(Modifier.testTag(DevicesTags.CodeExpiry).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(TetherIcons.Timer, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
                    Text(DevicesCopy.expiresIn(elapsedLabel(left).ifEmpty { "0s" }), color = t.muted, style = settingsText(type.ui, 12f, 500, lineHeight = 1.5f))
                }
            }
        }
    }
}

/** The most countdown ticks one drawing runs (a code is good for five minutes; a later server's longer TTL re-draws). */
private const val PAIRING_TICKS_MAX = 3_600L

// ---- shared parts -------------------------------------------------------------------------------

/** The owner-grade refusal (tether #236 not deployed yet): said once per section, with the one way to ask again. */
@Composable
private fun OwnerNeeded(c: DevicesController, area: DevicesArea) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OwnerGradeNote(DevicesCopy.OWNER_NEEDED, Modifier.testTag(DevicesTags.ownerNote(area)))
        TetherKey(
            onClick = { c.refresh() },
            classes = KeyClasses.ButtonSecondary,
            label = DevicesCopy.CHECK_AGAIN,
            icon = TetherIcons.RefreshCw,
            iconSize = 14.dp,
            modifier = Modifier.testTag(DevicesTags.checkAgain(area)),
        )
    }
}

@Composable
private fun SignedOutLine() = MutedLine(DevicesCopy.SIGNED_OUT, DevicesTags.line(DevicesArea.Devices) + ":signed-out", status = true)

/** `.settings-warning` (`role="alert"`) or `.settings-devices-status` (`role="status"`): words and glyph, never colour alone. */
@Composable
private fun LineView(line: DevicesLine, tag: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = settingsText(type.ui, 13f, 400, lineHeight = 1.6f)
    if (line.error) {
        Row(
            Modifier
                .testTag(tag)
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
                .padding(bottom = 16.dp)
                .background(t.attentionBg, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.padding(top = 3.dp).size(14.dp))
            Text(line.text, color = t.attentionInk, style = style)
        }
    } else {
        Row(
            Modifier.testTag(tag).fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(TetherIcons.Check, contentDescription = null, tint = t.muted, modifier = Modifier.padding(top = 3.dp).size(14.dp))
            Text(line.text, color = t.muted, style = style)
        }
    }
}

@Composable
private fun MutedLine(text: String, tag: String, status: Boolean = false, rule: Boolean = true, small: Boolean = false) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth()) {
        if (rule) RowRule()
        Text(
            text,
            color = if (small) t.muted else t.faint,
            style = settingsText(type.ui, if (small) 12f else 13f, 400, lineHeight = 1.6f),
            modifier = Modifier
                .testTag(tag)
                .then(if (status) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
                .padding(vertical = if (rule) 16.dp else 0.dp),
        )
    }
}

/** `.passkey-tag`: a small outlined tag beside a title ("Synced", "This device"). */
@Composable
private fun Tag(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        color = t.muted,
        style = settingsText(type.ui, 11f, 650, lineHeight = 1.4f),
        modifier = modifier
            .cssSurface(RoundedCornerShape(6.dp), t.mineral, CssBorder(1.dp, t.line), emptyList())
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * The web's three confirmations (revoke a device, remove a passkey, sign out everywhere else) in its
 * confirm chrome, their words as the web's; the confirm key is the web's `button-danger` and acts
 * on the first tap (ta-coik.5: no app-only arm delay).
 */
@Composable
internal fun DevicesConfirmDialog(confirm: DevicesConfirm, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val (title, body, action) = when (confirm) {
        is DevicesConfirm.Revoke -> Triple(DevicesCopy.REVOKE_TITLE, DevicesRules.revokeBody(DevicesRules.label(confirm.device.label, "Paired device")), DevicesCopy.REVOKE_CONFIRM)
        is DevicesConfirm.RemovePasskey -> Triple(DevicesCopy.REMOVE_PASSKEY_TITLE, listOf(DevicesCopy.removePasskeyBody(DevicesRules.label(confirm.passkey.label, "Passkey"))), DevicesCopy.REMOVE_PASSKEY_CONFIRM)
        DevicesConfirm.SignOutOthers -> Triple(DevicesCopy.SIGN_OUT_OTHERS_TITLE, listOf(DevicesCopy.SIGN_OUT_OTHERS_BODY), DevicesCopy.SIGN_OUT_OTHERS)
    }
    TetherDialog(
        onDismiss = onCancel,
        title = title,
        footer = {
            TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel", modifier = Modifier.testTag(DevicesTags.ConfirmCancel))
            TetherKey(onClick = onConfirm, classes = KeyClasses.ButtonDanger, label = action, modifier = Modifier.testTag(DevicesTags.ConfirmGo))
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(DevicesTags.ConfirmSheet), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            body.forEach { TetherDialogText(it) }
        }
    }
}
