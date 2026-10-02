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
- `login-passkey-pairing` (ta-coik.1): the same key on the app's Pairing path, with Studio's separator
  "or pair this device with a code" and Retro's "⏎ on an empty line = passkey" hint.

## As the browser does it (ta-coik.1)

- **The rpId rule is the browser's** (WebAuthn §5.1.3/§5.1.4 step 8, HTML "is a registrable domain
  suffix of or is equal to"): the server's host, or a parent of it that is not a public suffix. The
  check uses the Public Suffix List OkHttp already ships (`HttpUrl.topPrivateDomain()`, an asset of
  the `okhttp-android` artifact already in the build, private section included, so `co.uk` and
  `github.io` are refused as browsers refuse them). The rpId is parsed as a browser parses a host
  (UTS #46, punycode, lower case), and Credential Manager is given that canonical spelling. An IP
  address is never an rpId, as in WebAuthn. Why the app must apply the rule itself: a browser binds
  every ceremony to the page's origin, but the app's `android:apk-key-hash` origin is the same for
  every Tether server.
- **Offered wherever the web offers it.** The web has one sign-in screen and shows the passkey on it
  whatever else is shown (studio-login.tsx:41/99, retro-login.tsx:164), so the app shows the key on
  its Password path and its Pairing path alike. Retro's Enter on an empty line is the passkey on the
  code line too, and the line's accessible name says so.
- **Autofill (conditional) offer**, use-login-flow.ts:224-246: once a passkey is ready, the app asks
  for a challenge and the password field carries it as a pending Credential Manager request (Compose
  `semantics { credentialRequest }`, the web's `autocomplete="current-password webauthn"`), so the
  passkey is among the field's autofill suggestions. Once per address; an offer that goes unused, is
  dismissed or is superseded by the key stays quiet and is not re-armed; a picked passkey is an
  attempt from then on, with the web's outcomes.

## Platform limits (not app rules)

1. **https only**, as a browser requires a secure context. A browser also counts `http://localhost`
   as one, but that would not help here: Credential Manager verifies the rpId against
   `https://<rpId>/.well-known/assetlinks.json` and cannot do that for `localhost` (or an IP
   address), so no passkey can work at such an address on a phone. `https://localhost` is accepted by
   the rpId rule and fails at Credential Manager's assetlinks check. For an `http://` address no
   ceremony starts and nothing is sent; the login screen hides the key and the Devices panel draws it
   off, each with "Passkeys need an https:// address."
2. **The autofill offer needs Android 15 (API 35)**: the framework's pending credential request on an
   autofill node is new there. On Android 14 (the app's minimum) no challenge is asked for and the key
   and Retro's Enter remain, as a browser without conditional mediation offers only the button.

## Differences that are not restrictions

1. **The label field stops at 60 characters** (the web's `maxLength`); the server cuts at 64.
2. **The app's own words** for what only a phone meets: no passkey provider, no passkey on this phone,
   a wrong address, an unreadable answer, no prompt available, and a passkey created on the phone that
   the server did not save.
3. **An app passkey sign-in registers no FCM push** (the server's `fcm-register` needs a paired-device
   token), as a password sign-in today.
4. **Registration from the phone** is in-app, per the owner's 2026-10-02 decision that the app has the
   web's permissions (tether #236).

## Residual risks

- **Same-hostname relay**: neither the rpId nor the Android origin carries a port or a scheme, so any
  service on the same hostname, on any port, that the app is pointed at can relay an app passkey to the
  console. https-only closes the plain-http case, not this one. With a parent rpId (ta-coik.1) the same
  holds for any host under that parent, exactly as any page under that parent can use it in a browser.
- Real Credential Manager behaviour is not exercised on the JVM (the ceremony is a fake seam, and the
  autofill offer is checked down to the semantics node and the framework request it carries); the
  prompt and the autofill suggestion are the owner's device test (Android 15+ for the suggestion).
- Behind an SSO gateway, `/.well-known/assetlinks.json` and `/api/auth/passkey/login/*` must pass
  unauthenticated, or the app shows the gateway-refusal words.
- A debug APK works only when the server's `TETHER_ANDROID_CERT_FINGERPRINTS` names its signing
  certificate (that setting replaces the official fingerprint).
- A passkey created while Settings is closed mid-verify gets no message (the panel is gone); it may or
  may not be saved, and the Passkeys list shows which.
