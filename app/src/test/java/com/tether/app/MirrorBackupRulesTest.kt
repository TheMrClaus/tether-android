package com.tether.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * T13.1 (SYNC_DESIGN §8.4, `BackupRulesTest`): the journal mirror (session transcripts) is
 * excluded from cloud backup AND device transfer, and from fullBackupContent, so the rule
 * cannot silently regress. (The wrapped data key lives in noBackupFilesDir, which no backup
 * ever includes.)
 */
class MirrorBackupRulesTest {
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

    private fun includes(parent: Element): Int = parent.getElementsByTagName("include").length

    private val databaseDomain = "database" to "."

    @Test
    fun cloudBackupAndDeviceTransferExcludeTheMirror() {
        val rules = parse(File("src/main/res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.getElementsByTagName(section).item(0) as? Element
            assertNotNull("missing <$section>", element)
            assertTrue("<$section> must exclude the whole database domain", databaseDomain in excludes(element!!))
            // An <include> would turn the section into an allow-list and change what "." means.
            assertTrue("<$section> must not switch to include rules", includes(element) == 0)
        }
    }

    @Test
    fun fullBackupContentExcludesTheMirror() {
        val rules = parse(File("src/main/res/xml/backup_rules.xml"))
        assertTrue(databaseDomain in excludes(rules))
        assertTrue(includes(rules) == 0)
    }
}
