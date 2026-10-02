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

- **The rpId must equal the console's host** (ta-coik.1 r2, coordinator decision after the security
  review's F1). That is what a browser achieves against Tether, not a stricter rule: the server takes
  its rpId from the hostname of its own origin and accepts only that origin in `clientDataJSON` (tether
  `lib/passkeys.mjs` `resolveRelyingParty`, `verifyAuthentication`). A browser therefore signs in only
  with rpId == the console's own host. A page on another host, even one under the same parent domain,
  carries its own origin and is refused. The app's `android:apk-key-hash` origin is the same for every
  host, so the server cannot make that check for the app, and the app checks the host itself. **Exact
  host plus the server's origin check is what the browser achieves.** A registrable parent (for
  example `example.com` for `console.example.com`) and a sibling (`other.example.com`) are refused,
  at sign-in and in Settings > Devices > Add a passkey alike. Accepting a parent would let any host
  under the console's domain relay the console's challenge through the app, which no browser allows.
  The rpId is still parsed as a browser parses a host (UTS #46, punycode, lower case; a trailing dot
  matches only when both sides carry it), and Credential Manager is given that canonical spelling. An
  IP address is never an rpId, as in WebAuthn. No Public Suffix List is involved.
- **Offered wherever the web offers it.** The web has one sign-in screen and shows the passkey on it
  whatever else is shown (studio-login.tsx:41/99, retro-login.tsx:164), so the app shows the key on
  its Password path and its Pairing path alike. Retro's Enter on an empty line is the passkey on the
  code line too, and the line's accessible name says so.
- **Autofill (conditional) offer**, use-login-flow.ts:224-246: once a passkey is ready, the app asks
  for a challenge and the password field carries it as a pending Credential Manager request (Compose
  `semantics { credentialRequest }`, the web's `autocomplete="current-password webauthn"`), so the
  passkey is among the field's autofill suggestions. Once per address; an offer that goes unused, is
  dismissed or is superseded by the key stays quiet and is not re-armed; a picked passkey is an
  attempt from then on, with the web's outcomes. The offer is armed only from a sign-in reading taken
  for the address now typed: an edited address asks no challenge until its own probe has answered.
  A pick that lands while a password attempt is in flight goes ahead, as on the web (its conditional
  branch has no phase check). The password attempt is not cancelled either; each settles on its own.
  The first sign-in to be adopted wins (ta-coik.1 r3): a later 200 from the other attempt, or one that
  lands after a sign-out or a sign-in to another server, is not adopted, and its session is revoked on
  the server that minted it. The screen likewise keeps the newer attempt's outcome over an older
  attempt's late answer.

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

- **Same host, other port (security F3; a native-only gap, not browser parity)**: neither the rpId
  nor the Android origin carries a port, so another service on the console's host but on a different
  port (`https://console.example.com:8443` beside `https://console.example.com`) that the app is
  pointed at could fetch the console's challenge, have the app sign it, and relay the answer to the
  console. A browser closes this with the origin, which carries the port; the app has no field to bind
  a port to, and the exact-host rule cannot see it. https-only closes the plain-http variant, not this
  one. Run nothing untrusted on another port of a console's host.
- Real Credential Manager behaviour is not exercised on the JVM (the ceremony is a fake seam, and the
  autofill offer is checked down to the semantics node and the framework request it carries); the
  prompt and the autofill suggestion are the owner's device test (Android 15+ for the suggestion).
- Behind an SSO gateway, `/.well-known/assetlinks.json` and `/api/auth/passkey/login/*` must pass
  unauthenticated, or the app shows the gateway-refusal words.
- A debug APK works only when the server's `TETHER_ANDROID_CERT_FINGERPRINTS` names its signing
  certificate (that setting replaces the official fingerprint).
- A passkey created while Settings is closed mid-verify gets no message (the panel is gone); it may or
  may not be saved, and the Passkeys list shows which.
