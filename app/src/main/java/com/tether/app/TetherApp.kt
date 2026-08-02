package com.tether.app

import android.app.Application
import com.tether.app.client.DataStoreSettings
import com.tether.app.client.RealTetherClient
import com.tether.app.ui.ClientLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Points the UI's ClientLocator at the real protocol client. */
class TetherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ClientLocator.factory = { context ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            RealTetherClient(
                settings = DataStoreSettings.create(context.filesDir, scope),
                httpClient = OkHttpClient(),
                scope = scope,
            )
        }
    }
}
