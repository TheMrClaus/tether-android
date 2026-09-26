package com.tether.app.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.tether.app.client.LocalNetworkAccess

/**
 * The platform side of [LocalNetworkAccess]. Local network protection is
 * enforced only on Android 17 (API 37, Build.VERSION_CODES.CINNAMON_BUN) and only
 * for apps targeting 37+. Below that, INTERNET implies local-network access and
 * the docs say not to request ACCESS_LOCAL_NETWORK
 * (developer.android.com/privacy-and-security/local-network-permission).
 */
class AndroidLocalNetworkAccess(context: Context) : LocalNetworkAccess {
    private val app = context.applicationContext

    override fun isRestricted(): Boolean = enforced(app) && !granted(app)

    companion object {
        /**
         * "android.permission.ACCESS_LOCAL_NETWORK" (API 37, dangerous, NEARBY_DEVICES
         * group). Requested only where [enforced] is true.
         */
        @SuppressLint("InlinedApi")
        const val PERMISSION: String = Manifest.permission.ACCESS_LOCAL_NETWORK

        /** True where the OS gates local-network traffic for this app, so asking is meaningful. */
        fun enforced(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
                context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.CINNAMON_BUN

        fun granted(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
                context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}
