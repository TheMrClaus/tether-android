package com.tether.app.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tether.app.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * H5: the FCM registration token (firebase-messaging keeps it in the
 * `com.google.android.gms.appid` SharedPreferences) and the Firebase project
 * binding (`tether_push_firebase`) never leave the device in a cloud backup or a
 * device transfer, under either backup rule set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushBackupRulesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** (section, domain, path) for every <exclude> in the compiled rules file. */
    private fun excludes(xml: Int): Set<Triple<String, String, String>> {
        val parser = context.resources.getXml(xml)
        val out = mutableSetOf<Triple<String, String, String>>()
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer", "full-backup-content" -> section = parser.name
                "exclude" -> out += Triple(section, parser.getAttributeValue(null, "domain"), parser.getAttributeValue(null, "path"))
            }
        }
        return out
    }

    private val fcmTokenStore = "com.google.android.gms.appid.xml"

    /** The accepted Firebase project and its server origin (FirebaseClientConfigStore). */
    private val firebaseBinding = "${FirebaseClientConfigStore.FILE}.xml"

    @Test
    fun theFcmTokenStoreIsExcludedFromCloudBackupAndDeviceTransfer() {
        val rules = excludes(R.xml.data_extraction_rules)
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertTrue("$section: $rules", Triple(section, "sharedpref", fcmTokenStore) in rules)
        }
    }

    @Test
    fun theFcmTokenStoreIsExcludedFromFullBackup() {
        val rules = excludes(R.xml.backup_rules)
        assertTrue(rules.toString(), Triple("full-backup-content", "sharedpref", fcmTokenStore) in rules)
    }

    @Test
    fun theFirebaseBindingIsExcludedEverywhere() {
        val extraction = excludes(R.xml.data_extraction_rules)
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertTrue("$section: $extraction", Triple(section, "sharedpref", firebaseBinding) in extraction)
        }
        val full = excludes(R.xml.backup_rules)
        assertTrue(full.toString(), Triple("full-backup-content", "sharedpref", firebaseBinding) in full)
    }
}
