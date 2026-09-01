package com.pagebinder.app.data

import com.pagebinder.app.domain.ExportRecord
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.StoredOcrResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class EntityMappersTest {
    @Test
    fun `BookProject mapper round trips nullable fields and nanosecond timestamps`() {
        val entity =
            BookProjectEntity(
                id = UUID.fromString("10000000-0000-0000-0000-000000000001"),
                title = "Book",
                author = null,
                note = null,
                createdAt = Instant.parse("2026-09-01T00:00:00.123456789Z"),
                updatedAt = Instant.parse("2026-09-01T00:00:01.987654321Z"),
                deletedAt = null,
            )

        assertEquals(entity, entity.toDomain().toEntity())
        assertEquals(entity.toDomain(), entity.toDomain().toEntity().toDomain())
    }

    @Test
    fun `Page mapper round trips every state and valid rotation`() {
        PageQualityState.entries.forEach { qualityState ->
            PageOcrState.entries.forEach { ocrState ->
                listOf(0, 90, 180, 270).forEach { rotation ->
                    val entity =
                        PageEntity(
                            id = UUID.randomUUID(),
                            projectId = UUID.randomUUID(),
                            sequence = 1,
                            originalImagePath = "projects/id/images/page.webp",
                            width = 1,
                            height = Int.MAX_VALUE,
                            rotation = rotation,
                            cropLeft = 0f,
                            cropTop = 0f,
                            cropRight = 1f,
                            cropBottom = 1f,
                            capturedAt = Instant.parse("2026-09-01T00:00:00.123456789Z"),
                            contentHash = "content",
                            perceptualHash = "perceptual",
                            qualityState = qualityState,
                            ocrState = ocrState,
                        )

                    assertEquals(entity, entity.toDomain().toEntity())
                    assertEquals(entity.toDomain(), entity.toDomain().toEntity().toDomain())
                }
            }
        }
    }

    @Test
    fun `OcrResult mapper round trips nullable edited text and nanosecond timestamp`() {
        val domain =
            StoredOcrResult(
                pageId = UUID.fromString("20000000-0000-0000-0000-000000000002"),
                fullText = "全文",
                blocksJson = "{\"schemaVersion\":1,\"blocks\":[]}",
                editedText = null,
                engineVersion = "engine-1",
                sourceImageHash = "hash",
                processedAt = Instant.parse("2026-09-01T00:00:00.123456789Z"),
            )

        assertEquals(domain, domain.toEntity().toDomain())
        assertEquals(domain.toEntity(), domain.toEntity().toDomain().toEntity())
    }

    @Test
    fun `ExportRecord mapper round trips every type and state including nullable fields`() {
        ExportType.entries.forEach { type ->
            ExportState.entries.forEach { state ->
                val domain =
                    ExportRecord(
                        id = UUID.randomUUID(),
                        projectId = UUID.randomUUID(),
                        type = type,
                        targetUri = null,
                        state = state,
                        createdAt = Instant.parse("2026-09-01T00:00:00.123456789Z"),
                        completedAt = null,
                        errorCode = null,
                    )

                assertEquals(domain, domain.toEntity().toDomain())
                assertEquals(domain.toEntity(), domain.toEntity().toDomain().toEntity())
            }
        }
    }

    @Test
    fun `converters retain version 2 TEXT values for UUID Instant and persisted enums`() {
        val converters = PageBinderTypeConverters()
        val uuid = UUID.fromString("30000000-0000-0000-0000-000000000003")
        val instant = Instant.parse("2026-09-01T00:00:00.123456789Z")

        assertEquals(uuid.toString(), converters.uuidToString(uuid))
        assertEquals(uuid, converters.stringToUuid(uuid.toString()))
        assertEquals(instant.toString(), converters.instantToString(instant))
        assertEquals(instant, converters.stringToInstant(instant.toString()))
        PageQualityState.entries.forEach {
            assertEquals(it, converters.stringToPageQualityState(converters.pageQualityStateToString(it)))
        }
        PageOcrState.entries.forEach {
            assertEquals(
                it,
                converters.stringToPageOcrState(converters.pageOcrStateToString(it)),
            )
        }
        ExportType.entries.forEach {
            assertEquals(
                it,
                converters.stringToExportType(converters.exportTypeToString(it)),
            )
        }
        ExportState.entries.forEach {
            assertEquals(
                it,
                converters.stringToExportState(converters.exportStateToString(it)),
            )
        }
    }
}
