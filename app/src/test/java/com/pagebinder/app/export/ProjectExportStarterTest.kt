package com.pagebinder.app.export

import com.pagebinder.app.domain.BookProject
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.BookProjectSort
import com.pagebinder.app.domain.BookProjectSummary
import com.pagebinder.app.domain.CompletedExportSource
import com.pagebinder.app.domain.ExportDestination
import com.pagebinder.app.domain.ExportOptions
import com.pagebinder.app.domain.ExportPageRange
import com.pagebinder.app.domain.ExportPdfQuality
import com.pagebinder.app.domain.ExportProgressEvent
import com.pagebinder.app.domain.ExportRecord
import com.pagebinder.app.domain.ExportRecordRepository
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportStorageGateway
import com.pagebinder.app.domain.ExportStorageResult
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.domain.OcrResultRepository
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.domain.PdfGateway
import com.pagebinder.app.domain.PdfInput
import com.pagebinder.app.domain.PdfMode
import com.pagebinder.app.domain.PdfPageTransform
import com.pagebinder.app.domain.StoredOcrResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/**
 * 合成層が書き出し画面へ渡す [ProjectExportStarter] を、リポジトリから成果物までの1本で見る
 * （docs/specs/11-export.md §2 入力・§3.1 出力形式）。
 */
class ProjectExportStarterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val projectId = UUID.fromString("40000000-0000-0000-0000-000000000001")
    private val pageIds = List(3) { index -> UUID.fromString("40000000-0000-0000-0000-00000000010$index") }
    private val storage = RecordingStorageGateway()
    private val records = InMemoryExportRecordRepository()

    @Test
    fun `ページ範囲の指定が Markdown へ反映され editedText が優先される`() =
        runTest {
            val starter =
                starter(
                    pages = pages(),
                    ocrResults =
                        mapOf(
                            pageIds[0] to ocr(pageIds[0], fullText = "one"),
                            pageIds[1] to ocr(pageIds[1], fullText = "two", editedText = "修正した2ページ"),
                        ),
                )

            val events =
                starter
                    .startExport(options(ExportType.MARKDOWN, ExportPageRange.Bounded(2, 3)))
                    .toList()

            // 3ページ目にOCR結果が無くてもページ境界とページ番号は出す
            assertEquals("## Page 2\n\n修正した2ページ\n\n---\n\n## Page 3\n\n", storage.content)
            assertTrue(events.last() is ExportProgressEvent.Succeeded)
            assertEquals(ExportState.SUCCEEDED, records.only().state)
            assertEquals(ExportType.MARKDOWN, records.only().type)
        }

    @Test
    fun `検索可能PDF は編集適用後の画像とOCRテキストを受け取る`() =
        runTest {
            val pdfGateway = RecordingPdfGateway()
            val starter =
                starter(
                    pages = pages(),
                    ocrResults =
                        mapOf(
                            pageIds[0] to ocr(pageIds[0], fullText = "one"),
                            pageIds[1] to ocr(pageIds[1], fullText = "two"),
                            pageIds[2] to ocr(pageIds[2], fullText = "three"),
                        ),
                    pdfGateway = pdfGateway,
                )

            starter.startExport(options(ExportType.SEARCHABLE_PDF, ExportPageRange.All)).toList()

            assertEquals(PdfMode.SEARCHABLE, pdfGateway.mode)
            assertEquals(listOf(1, 2, 3), pdfGateway.sequences)
            assertEquals(listOf("edited-1", "edited-2", "edited-3"), pdfGateway.imageContents)
            assertEquals("one", pdfGateway.fullTexts.first())
            assertEquals(listOf("blocks-1", "blocks-2", "blocks-3"), pdfGateway.blocksJson)
            assertEquals(
                listOf(
                    PdfPageTransform(sourceWidth = 100, sourceHeight = 200),
                    PdfPageTransform(sourceWidth = 100, sourceHeight = 200),
                    PdfPageTransform(
                        sourceWidth = 100,
                        sourceHeight = 200,
                        rotationDegrees = 90,
                        crop = PageCrop(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f),
                    ),
                ),
                pdfGateway.transforms,
            )
        }

    @Test
    fun `書き出し画面が選んだ PDF画質 が PDF生成まで届く`() =
        runTest {
            val pdfGateway = RecordingPdfGateway()
            val starter = starter(pages = pages(), pdfGateway = pdfGateway)

            starter
                .startExport(options(ExportType.IMAGE_PDF, ExportPageRange.All, ExportPdfQuality.HIGH))
                .toList()

            assertEquals(ExportPdfQuality.HIGH, pdfGateway.pdfQuality)
            assertEquals(PdfMode.IMAGE_ONLY, pdfGateway.mode)
            // 画像PDFはテキスト層に依存しない（docs/specs/11-export.md §3.1）
            assertEquals(listOf(null, null, null), pdfGateway.blocksJson)
        }

    @Test
    fun `範囲にページが無ければ書き出しを始めず失敗を1件返す`() =
        runTest {
            val starter = starter(pages = pages())

            val events =
                starter
                    .startExport(options(ExportType.MARKDOWN, ExportPageRange.Bounded(9, 9)))
                    .toList()

            assertEquals(
                listOf(ExportProgressEvent.Failed("generation_failed")),
                events,
            )
            assertTrue(records.isEmpty())
        }

    private fun starter(
        pages: List<Page>,
        ocrResults: Map<UUID, StoredOcrResult> = emptyMap(),
        pdfGateway: PdfGateway = RecordingPdfGateway(),
    ): ProjectExportStarter {
        val cache = temporaryFolder.newFolder("exports-cache-${UUID.randomUUID()}")
        return ProjectExportStarter(
            bookProjectRepository = FakeBookProjectRepository(project()),
            pageRepository = FakePageRepository(pages),
            ocrResultRepository = FakeOcrResultRepository(ocrResults),
            pageImageSource = FakePageImageSource(),
            exportsCacheDirectory = { cache },
            recordCoordinator = ExportRecordCoordinator(records),
            storageGateway = storage,
            pdfGateway = pdfGateway,
            appVersion = "0.1.0",
        )
    }

    private fun options(
        type: ExportType,
        pageRange: ExportPageRange,
        pdfQuality: ExportPdfQuality = ExportPdfQuality.STANDARD,
    ) = ExportOptions(
        projectId = projectId,
        type = type,
        fileName = "book.md",
        pageRange = pageRange,
        pdfQuality = pdfQuality,
        destination = ExportDestination("content://provider/document/redacted"),
    )

    private fun project() =
        BookProject(
            id = projectId,
            title = "書籍",
            author = null,
            note = null,
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
            deletedAt = null,
        )

    /** 3ページ目だけ回転・切り取り済み（非破壊編集あり） */
    private fun pages(): List<Page> =
        pageIds.mapIndexed { index, pageId ->
            Page(
                id = pageId,
                projectId = projectId,
                sequence = index + 1,
                originalImagePath = "projects/$projectId/images/$pageId.webp",
                width = 100,
                height = 200,
                rotation = if (index == 2) 90 else 0,
                crop = if (index == 2) PageCrop(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f) else PageCrop(),
                capturedAt = CREATED_AT,
                contentHash = "hash-${index + 1}",
                perceptualHash = "phash-${index + 1}",
                qualityState = PageQualityState.NORMAL,
                ocrState = PageOcrState.SUCCEEDED,
            )
        }

    private fun ocr(
        pageId: UUID,
        fullText: String,
        editedText: String? = null,
    ) = StoredOcrResult(
        pageId = pageId,
        fullText = fullText,
        blocksJson = "blocks-${pageIds.indexOf(pageId) + 1}",
        editedText = editedText,
        engineVersion = "mlkit:test",
        sourceImageHash = "hash",
        processedAt = CREATED_AT,
    )

    private class FakePageImageSource : ExportPageImageSource {
        override fun openOriginal(page: Page): InputStream =
            ByteArrayInputStream("original-${page.sequence}".toByteArray())

        override fun openEdited(page: Page): InputStream = ByteArrayInputStream("edited-${page.sequence}".toByteArray())
    }

    private class FakeOcrResultRepository(
        private val results: Map<UUID, StoredOcrResult>,
    ) : OcrResultRepository {
        override suspend fun findByPageId(pageId: UUID): StoredOcrResult? = results[pageId]

        override suspend fun saveEditedText(
            pageId: UUID,
            editedText: String,
        ): Boolean = error("Not used by export")

        override suspend fun clearEditedText(pageId: UUID): Boolean = error("Not used by export")
    }

    private class FakePageRepository(
        private val pages: List<Page>,
    ) : PageRepository {
        override suspend fun insert(page: Page) = error("Not used by export")

        override suspend fun findById(id: UUID): Page? = pages.firstOrNull { it.id == id }

        override suspend fun findByProject(projectId: UUID): List<Page> =
            pages.filter { it.projectId == projectId }.shuffled()

        override fun observeByProject(projectId: UUID): Flow<List<Page>> = flow { emit(findByProject(projectId)) }

        override suspend fun reorder(
            projectId: UUID,
            orderedPageIds: List<UUID>,
        ) = error("Not used by export")

        override suspend fun delete(
            projectId: UUID,
            pageIds: Set<UUID>,
        ) = error("Not used by export")

        override suspend fun deleteResolvingDuplicates(
            projectId: UUID,
            pageIds: Set<UUID>,
            resolvedDuplicatePageIds: Set<UUID>,
        ) = error("Not used by export")

        override suspend fun updateRotation(
            pageId: UUID,
            rotation: Int,
        ) = error("Not used by export")

        override suspend fun updateCrop(
            pageId: UUID,
            crop: PageCrop,
        ) = error("Not used by export")

        override suspend fun updatePageEdit(
            pageId: UUID,
            rotation: Int,
            crop: PageCrop,
            cropScope: PageCropScope,
        ): Int = error("Not used by export")

        override suspend fun undoLastEdit(): Boolean = error("Not used by export")
    }

    private class FakeBookProjectRepository(
        private val project: BookProject,
    ) : BookProjectRepository {
        override suspend fun create(
            title: String,
            author: String?,
            note: String?,
        ): BookProject = error("Not used by export")

        override suspend fun findById(id: UUID): BookProject? = project.takeIf { it.id == id }

        override suspend fun findSummaryById(id: UUID): BookProjectSummary? = error("Not used by export")

        override fun observeSummaryById(id: UUID): Flow<BookProjectSummary?> = error("Not used by export")

        override suspend fun update(
            id: UUID,
            title: String,
            author: String?,
            note: String?,
        ): BookProject = error("Not used by export")

        override suspend fun listActive(sort: BookProjectSort): List<BookProjectSummary> = error("Not used by export")

        override suspend fun searchActive(
            query: String,
            sort: BookProjectSort,
        ): List<BookProjectSummary> = error("Not used by export")

        override suspend fun listTrash(): List<BookProjectSummary> = error("Not used by export")

        override suspend fun moveToTrash(id: UUID): BookProject = error("Not used by export")

        override suspend fun restore(id: UUID): BookProject = error("Not used by export")

        override suspend fun deletePermanently(id: UUID) = error("Not used by export")

        override suspend fun purgeExpiredTrash(): Int = error("Not used by export")
    }

    private class RecordingStorageGateway : ExportStorageGateway {
        var content: String? = null
            private set

        override suspend fun write(
            source: CompletedExportSource,
            destination: ExportDestination,
        ): ExportStorageResult {
            val bytes = source.openInputStream().use { it.readBytes() }
            content = bytes.toString(Charsets.UTF_8)
            return ExportStorageResult.Succeeded(bytes.size.toLong())
        }
    }

    private class RecordingPdfGateway : PdfGateway {
        var mode: PdfMode? = null
            private set
        var sequences: List<Int> = emptyList()
            private set
        var imageContents: List<String> = emptyList()
            private set
        var fullTexts: List<String?> = emptyList()
            private set
        var blocksJson: List<String?> = emptyList()
            private set
        var transforms: List<PdfPageTransform> = emptyList()
            private set
        var pdfQuality: ExportPdfQuality? = null
            private set

        override suspend fun generate(
            input: PdfInput,
            mode: PdfMode,
            output: OutputStream,
            reportProgress: suspend (completedPages: Int, totalPages: Int) -> Unit,
        ) {
            this.mode = mode
            sequences = input.pages.map { it.sequence }
            imageContents =
                input.pages.map { page ->
                    page.image.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
                }
            fullTexts = input.pages.map { it.fullText }
            blocksJson = input.pages.map { it.ocrBlocksJson }
            transforms = input.pages.map { it.transform }
            pdfQuality = input.pdfQuality
            output.write("pdf".toByteArray())
            reportProgress(input.pages.size, input.pages.size)
        }
    }

    private class InMemoryExportRecordRepository : ExportRecordRepository {
        private val stored = linkedMapOf<UUID, ExportRecord>()

        fun only(): ExportRecord = stored.values.single()

        fun isEmpty(): Boolean = stored.isEmpty()

        override suspend fun insert(record: ExportRecord) {
            check(stored.putIfAbsent(record.id, record) == null)
        }

        override suspend fun findById(id: UUID): ExportRecord? = stored[id]

        override suspend fun findIncomplete(): List<ExportRecord> =
            stored.values.filter { it.state == ExportState.QUEUED || it.state == ExportState.RUNNING }

        override suspend fun compareAndSet(
            expected: ExportRecord,
            updated: ExportRecord,
        ): Boolean {
            if (stored[expected.id] != expected) return false
            stored[expected.id] = updated
            return true
        }
    }

    private companion object {
        val CREATED_AT: Instant = Instant.parse("2026-09-02T00:00:00Z")
    }
}
