package com.tether.app.ui.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * T7.4 r2 (M1): picker results for tests. The app reads attachments only from a `content://` URI of
 * ANOTHER app's provider ([AttachmentUriPolicy]), so a test pick is served the way a picker's is: by
 * a provider of another installed package, which answers a file's name, size and bytes.
 */
object ForeignPicks {
    const val AUTHORITY = "com.example.picker.provider"
    const val PACKAGE = "com.example.picker"

    /** Registers the other package and its provider with the package manager and the resolver. */
    fun install(context: Context) {
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = PACKAGE
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = PACKAGE }
        })
        pm.addOrUpdateProvider(android.content.pm.ProviderInfo().apply {
            authority = AUTHORITY
            packageName = PACKAGE
            name = Provider::class.java.name
        })
        org.robolectric.Robolectric.setupContentProvider(Provider::class.java, AUTHORITY)
    }

    /** The URI the picker hands back for [file]. */
    fun uriFor(file: File): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("pick").appendQueryParameter("path", file.absolutePath).build()

    class Provider : ContentProvider() {
        private fun file(uri: Uri) = File(requireNotNull(uri.getQueryParameter("path")))
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            val f = file(uri)
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
}
