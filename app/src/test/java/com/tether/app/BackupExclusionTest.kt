package com.tether.app

import com.tether.app.client.DataStoreSettings
import com.tether.app.ui.prefs.DraftStore
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * T14.3 finding (3): allowBackup defaulted to true with no rules, so the device
 * token / session cookie could reach cloud backup or a device-to-device
 * transfer. Proves, on the MERGED manifest AGP hands to unit tests, that both
 * rule files are referenced, and that they exclude exactly the paths
 * [DataStoreSettings] writes credentials to (a rename there breaks this test).
 */
class BackupExclusionTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun mergedManifest(): File {
        val props = Properties()
        val stream = javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")
        assertNotNull("AGP test_config.properties missing (isIncludeAndroidResources)", stream)
        stream!!.use { props.load(it) }
        val path = props.getProperty("android_merged_manifest")
        assertNotNull(path)
        return File(path).also { assertTrue("merged manifest not found at $it", it.isFile) }
    }

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)
        .documentElement

    private fun excludes(parent: Element): Set<Pair<String, String>> {
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()
    }

    private val required = setOf(
        "file" to DataStoreSettings.CREDENTIALS_DIR,
        "file" to DataStoreSettings.SETTINGS_FILE,
        "file" to "${DataStoreSettings.SETTINGS_FILE}.tmp",
        // T7.1: unsent composer drafts are user content (preferencesDataStore keeps them in files/datastore/).
        "file" to "datastore/${DraftStore.FILE_NAME}",
        "file" to "datastore/${DraftStore.FILE_NAME}.tmp",
        // ta-v4e1: UI prefs (server origins, workspace paths, session ids); the web's localStorage is never backed up.
        "file" to "datastore/tether_ui_prefs.preferences_pb",
        "file" to "datastore/tether_ui_prefs.preferences_pb.tmp",
    )

    @Test
    fun mergedManifestReferencesBothRuleFiles() {
        val app = parse(mergedManifest()).getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(androidNs, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(androidNs, "fullBackupContent"))
    }

    @Test
    fun cloudBackupAndDeviceTransferExcludeTheCredentialFiles() {
        val rules = parse(File("src/main/res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.getElementsByTagName(section).item(0) as? Element
            assertNotNull("missing <$section>", element)
            assertTrue("<$section> must exclude $required, has ${excludes(element!!)}", excludes(element).containsAll(required))
        }
    }

    @Test
    fun fullBackupContentExcludesTheCredentialFiles() {
        val rules = parse(File("src/main/res/xml/backup_rules.xml"))
        assertTrue(excludes(rules).containsAll(required))
    }
}
