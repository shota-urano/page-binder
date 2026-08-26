package com.pagebinder.app.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ManifestGeneratorTest {
    @Test
    fun `manifest matches data model schema and export file conventions`() {
        val manifest =
            ManifestGenerator.generate(
                ManifestInput(
                    appVersion = "0.1.0",
                    project =
                        ManifestProject(
                            title = "A \"quoted\" title",
                            author = null,
                            note = "line 1\nline 2",
                            createdAt = Instant.parse("2026-08-25T01:02:03Z"),
                        ),
                    exportedAt = Instant.parse("2026-08-26T04:05:06Z"),
                    ocrEngineVersion = "16.0.1",
                    pages =
                        listOf(
                            ManifestPage(
                                sequence = 2,
                                capturedAt = Instant.parse("2026-08-25T02:00:00Z"),
                                ocrState = ManifestOcrState.FAILED,
                                contentHash = "hash-2",
                                edited = false,
                            ),
                            ManifestPage(
                                sequence = 1,
                                capturedAt = Instant.parse("2026-08-25T01:00:00Z"),
                                ocrState = ManifestOcrState.SUCCEEDED,
                                contentHash = "hash-1",
                                edited = true,
                            ),
                        ),
                ),
            )

        assertEquals(
            """
            {
              "schemaVersion": 1,
              "app": { "name": "PageBinder", "version": "0.1.0" },
              "project": { "title": "A \"quoted\" title", "author": null, "note": "line 1\nline 2", "createdAt": "2026-08-25T01:02:03Z", "exportedAt": "2026-08-26T04:05:06Z" },
              "ocrEngine": { "name": "mlkit-text-recognition-v2-japanese", "version": "16.0.1" },
              "pages": [
                { "sequence": 1, "imageFile": "images/page-0001.webp", "textFile": "pages/page-0001.txt", "capturedAt": "2026-08-25T01:00:00Z", "ocrState": "succeeded", "contentHash": "hash-1", "edited": true },
                { "sequence": 2, "imageFile": "images/page-0002.webp", "textFile": "pages/page-0002.txt", "capturedAt": "2026-08-25T02:00:00Z", "ocrState": "failed", "contentHash": "hash-2", "edited": false }
              ]
            }
            """.trimIndent(),
            manifest,
        )
    }

    @Test
    fun `manifest supports an empty pages array`() {
        val manifest =
            ManifestGenerator.generate(
                ManifestInput(
                    appVersion = "0.1.0",
                    project =
                        ManifestProject(
                            title = "empty",
                            author = "author",
                            note = null,
                            createdAt = Instant.EPOCH,
                        ),
                    exportedAt = Instant.EPOCH,
                    ocrEngineVersion = "engine",
                    pages = emptyList(),
                ),
            )

        assertEquals(true, manifest.contains("\"pages\": []"))
    }
}
