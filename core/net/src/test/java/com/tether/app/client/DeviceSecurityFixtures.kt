package com.tether.app.client

/** T10.4: bodies shaped as tether 887c222 server.mjs answers the Devices routes. Every code here is a sentinel or obviously fake. */
object DeviceSecurityFixtures {
    /** A pairing code the tests look for everywhere it must not be (the server's alphabet, 8 long). */
    const val CODE = "SNTL7Q9Z"

    const val DEVICES_JSON = """{"devices":[
        {"id":"a1b2c3d4e5f60718","label":"Pixel 8","createdAt":1759390000000,"lastSeenAt":1759399000000},
        {"id":"0f1e2d3c4b5a6978","label":"Tablet","createdAt":1759300000000,"lastSeenAt":1759310000000}],
      "pairings":[{"label":"Paired device","createdAt":1759399900000,"expiresAt":1759400200000}]}"""

    val DEVICES = DevicesList(
        devices = listOf(
            PairedDevice("a1b2c3d4e5f60718", "Pixel 8", 1759390000000, 1759399000000),
            PairedDevice("0f1e2d3c4b5a6978", "Tablet", 1759300000000, 1759310000000),
        ),
        pairings = listOf(OutstandingPairing("Paired device", 1759399900000, 1759400200000)),
    )

    const val PAIR_JSON = """{"code":"$CODE","expiresAt":1759400300000}"""

    const val PASSKEYS_JSON = """{"passkeys":[
        {"id":"cred-AbC_123","label":"Laptop","createdAt":1759000000000,"lastUsedAt":1759390000000,"deviceType":"multiDevice","backedUp":true,"transports":["internal"]},
        {"id":"cred-XyZ_789","label":"YubiKey","createdAt":1758000000000,"lastUsedAt":0,"deviceType":"singleDevice","backedUp":false,"transports":["usb"]}],
      "passwordLoginEnabled":true,"policySource":"stored","passkeysUsable":true,"rpId":"console.example.test"}"""

    val PASSKEYS = PasskeysView(
        passkeys = listOf(
            Passkey("cred-AbC_123", "Laptop", 1759000000000, 1759390000000, backedUp = true),
            Passkey("cred-XyZ_789", "YubiKey", 1758000000000, 0, backedUp = false),
        ),
        policy = PasswordPolicy(true, PasskeyPolicySource.Stored),
        passkeysUsable = true,
    )

    const val SESSIONS_JSON = """{"sessions":[
        {"id":"5e55a1d0000000000000000000000001","method":"password","parentId":null,"audience":null,"createdAt":1759390000000,"lastSeenAt":1759399000000,"expiresAt":1760000000000,"userAgent":"Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0","current":false},
        {"id":"5e55a1d0000000000000000000000002","method":"app-passkey","parentId":null,"audience":null,"createdAt":1759380000000,"lastSeenAt":1759398000000,"expiresAt":1760000000000,"userAgent":"okhttp/5.4.0","current":true}]}"""

    val SESSIONS = listOf(
        SecuritySession("5e55a1d0000000000000000000000001", SessionMethod.Password, 1759390000000, 1759399000000, "Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0", current = false),
        SecuritySession("5e55a1d0000000000000000000000002", SessionMethod.AppPasskey, 1759380000000, 1759398000000, "okhttp/5.4.0", current = true),
    )

    /** The 403 a phone sign-in gets before tether #236 is deployed (887c222), and the wording after it. */
    const val OWNER_REFUSAL_887 = """{"error":"This needs an owner sign-in (password or passkey in a browser)."}"""
    const val OWNER_REFUSAL_236 = """{"error":"This needs an owner sign-in (password, passkey, the SSO gateway or the paired Tether app)."}"""
}
