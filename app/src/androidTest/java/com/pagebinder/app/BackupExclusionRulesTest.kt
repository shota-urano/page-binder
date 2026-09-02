package com.pagebinder.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class BackupExclusionRulesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fullBackupRulesExcludeWorkingImagesAndDatabase() {
        assertRequiredExclusions(exclusionsIn(R.xml.backup_rules))
    }

    @Test
    fun dataExtractionRulesExcludeWorkingImagesAndDatabaseFromBothTransferModes() {
        val exclusionsByMode = extractionExclusionsByMode(R.xml.data_extraction_rules)

        assertEquals(setOf("cloud-backup", "device-transfer"), exclusionsByMode.keys)
        exclusionsByMode.values.forEach(::assertRequiredExclusions)
    }

    @Test
    fun manifestReferencesBackupExclusionRuleResources() {
        assertEquals(
            R.xml.backup_rules,
            manifestApplicationAttributeResourceId("fullBackupContent"),
        )
        assertEquals(
            R.xml.data_extraction_rules,
            manifestApplicationAttributeResourceId("dataExtractionRules"),
        )
    }

    private fun assertRequiredExclusions(exclusions: Set<BackupExclusion>) {
        // FileImageStore stores originals below files/projects/{projectId}/images/.
        assertTrue("working images must be excluded", BackupExclusion("file", "projects") in exclusions)
        // PageBinderApplication opens Room with the pagebinder.db name.
        assertTrue("Room database must be excluded", BackupExclusion("database", "pagebinder.db") in exclusions)
    }

    private fun exclusionsIn(resourceId: Int): Set<BackupExclusion> {
        val parser = context.resources.getXml(resourceId)
        val exclusions = mutableSetOf<BackupExclusion>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                exclusions += parser.exclusion()
            }
        }
        return exclusions
    }

    private fun extractionExclusionsByMode(resourceId: Int): Map<String, Set<BackupExclusion>> {
        val parser = context.resources.getXml(resourceId)
        val exclusionsByMode = mutableMapOf<String, MutableSet<BackupExclusion>>()
        var mode: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "cloud-backup" || parser.name == "device-transfer") {
                        mode = parser.name
                        exclusionsByMode.getOrPut(mode) { mutableSetOf() }
                    } else if (parser.name == "exclude") {
                        exclusionsByMode.getValue(checkNotNull(mode)) += parser.exclusion()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == mode) mode = null
                }
            }
        }
        return exclusionsByMode
    }

    private fun manifestApplicationAttributeResourceId(attributeName: String): Int =
        context.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "application") {
                    return parser.getAttributeResourceValue(ANDROID_NAMESPACE, attributeName, 0)
                }
            }
            error("application element is missing from AndroidManifest.xml")
        }

    private fun XmlPullParser.exclusion(): BackupExclusion =
        BackupExclusion(
            domain = getAttributeValue(null, "domain"),
            path = getAttributeValue(null, "path"),
        )

    private data class BackupExclusion(
        val domain: String,
        val path: String,
    )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
