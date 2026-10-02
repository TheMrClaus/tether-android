# Passkeys: Add a passkey and Sign in with a passkey (T10.5)

Web reference: tether `887c222` (scenario `settings-devices`, captured at `97af028`); server shapes
from tether `90fbb9f`. Montages rebuilt by `tools/compare-screens/passkeys-montages.sh`; goldens in
`feature/settings/src/test/screenshots/settings-devices-passkey-*` and
`feature/auth/src/test/screenshots/login-passkey*`.

## Montages (web | android | diff)

| File | Web scenario | Android golden |
|---|---|---|
| `add-passkey-<skin>-phone.png` / `-tablet.png` | `settings-devices` (no passkey yet, the add row ready) | `settings-devices-passkey-empty` |

2 skins × phone + tablet = 4 montages. Remaining differences:

- **Placeholder**: "Label (e.g. this phone)" for the web's "this laptop" (the app is a phone).
- **Text rasterisation**: Manrope renders slightly wider on Android; rows sit 1–2 px apart.
- **Web Push / Notifications**: the web reference shows its own Web Push row above Passkeys; the
  app's Notifications section is T12's, and the crop starts at the Passkeys heading.

## States with no web reference

The web seed registers no passkey and runs no ceremony, so these are goldens only, checked by eye
against `components/sign-in-security.tsx` and `components/login/{studio,retro}-login.tsx`:

- `settings-devices-passkey-adding` ("Adding…", every key held), `-duplicate` (the web's words for an
  authenticator that already has one), `-added` (the notice and the new row after the re-read).
- `login-passkey` (the key, or Retro's menu line and ⏎ hint), `-waiting` (the ceremony running),
  `-dismissed` (the web's notice after a closed prompt), on both surfaces, both skins, phone and tablet.

## Divergences from the web

1. **The rpId must equal the server's host exactly**, as ASCII (a browser also accepts a parent domain).
   A browser binds every ceremony to the page's origin; the app's `android:apk-key-hash` origin is the
   same for every Tether server, so the app refuses options naming any other relying party.
2. **https only.** No ceremony starts and nothing is sent for an `http://` address, loopback included
   (the web allows `localhost`). The login screen hides the key and the Devices panel draws it off,
   each with "Passkeys need an https:// address."
3. **No autofill (conditional) passkey offer** at sign-in: the key only (Retro keeps Enter on an empty
   password line, and its accessible name says so, as retro-login.tsx does).
4. **The passkey key is on the Password path only**, not on the app-only Pairing path.
5. **The label field stops at 60 characters** (the web's `maxLength`); the server cuts at 64.
6. **The app's own words** for what only a phone meets: no passkey provider, no passkey on this phone,
   a wrong address, an unreadable answer, no prompt available, and a passkey created on the phone that
   the server did not save.
7. **An app passkey sign-in registers no FCM push** (the server's `fcm-register` needs a paired-device
   token), as a password sign-in today.
8. **Registration from the phone** is in-app, per the owner's 2026-10-02 decision that the app has the
   web's permissions (tether #236).

## Residual risks

- **Same-hostname relay**: neither the rpId nor the Android origin carries a port or a scheme, so any
  service on the same hostname, on any port, that the app is pointed at can relay an app passkey to the
  console. https-only closes the plain-http case, not this one.
- Real Credential Manager behaviour is not exercised on the JVM (the ceremony is a fake seam); it is the
  owner's device test.
- Behind an SSO gateway, `/.well-known/assetlinks.json` and `/api/auth/passkey/login/*` must pass
  unauthenticated, or the app shows the gateway-refusal words.
- A debug APK works only when the server's `TETHER_ANDROID_CERT_FINGERPRINTS` names its signing
  certificate (that setting replaces the official fingerprint).
- A passkey created while Settings is closed mid-verify gets no message (the panel is gone); it may or
  may not be saved, and the Passkeys list shows which.
