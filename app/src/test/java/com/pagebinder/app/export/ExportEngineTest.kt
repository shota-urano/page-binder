package com.pagebinder.app.export

import com.pagebinder.app.domain.CompletedExportSource
import com.pagebinder.app.domain.ExportDestination
import com.pagebinder.app.domain.ExportOptions
import com.pagebinder.app.domain.ExportPageRange
import com.pagebinder.app.domain.ExportPdfQuality
import com.pagebinder.app.domain.ExportRecord
import com.pagebinder.app.domain.ExportRecordRepository
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportStorageGateway
import com.pagebinder.app.domain.ExportStorageResult
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.domain.PdfGateway
import com.pagebinder.app.domain.PdfImageSource
import com.pagebinder.app.domain.PdfInput
import com.pagebinder.app.domain.PdfMode
import com.pagebinder.app.domain.PdfPage
import com.pagebinder.app.domain.PdfPageTransform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipInputStream

class ExportEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val recordId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val projectId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    private val now = Instant.parse("2026-08-26T04:05:06Z")

    @Test
    fun `cancellation marks production record failed and removes partial cache file`() =
        runTest {
            val cache = temporaryFolder.newFolder("exports-cache")
            val repository = InMemoryExportRecordRepository()
            val generationStarted = CompletableDeferred<Unit>()
            val engine =
                engine(
                    cache = cache,
                    repository = repository,
                    generator =
                        ExportArtifactGenerator { _, outputFile, _ ->
                            outputFile.writeText("partial export")
                            generationStarted.complete(Unit)
                            awaitCancellation()
                        },
                )

            val collection = launch { engine.export(markdownRequest()).collect {} }
            generationStarted.await()
            collection.cancelAndJoin()

            val record = repository.findById(recordId)
            assertEquals(ExportState.FAILED, record?.state)
            assertEquals("cancelled", record?.errorCode)
            assertEquals(now, record?.completedAt)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `markdown is generated written and reported through production engine`() =
        runTest {
            val cache = temporaryFolder.newFolder("exports-cache-success")
            val repository = InMemoryExportRecordRepository()
            val storage = RecordingStorageGateway()
            val engine =
                ExportEngine(
                    exportsCacheDirectory = cache,
                    recordCoordinator = coordinator(repository),
                    storageGateway = storage,
                    pdfGateway = unusedPdfGateway(),
                    artifactGenerator = StandardExportArtifactGenerator(Dispatchers.Unconfined),
                )

            val events = engine.export(markdownRequest()).toList()

            assertEquals("## Page 1\n\nedited", storage.content)
            assertEquals(ExportType.MARKDOWN, repository.findById(recordId)?.type)
            assertEquals(ExportState.SUCCEEDED, repository.findById(recordId)?.state)
            assertTrue(events.any { it is ExportEvent.Progress && it.phase == ExportPhase.GENERATING })
            assertTrue(events.last() is ExportEvent.Succeeded)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `markdown reports intermediate progress for every production page`() =
        runTest {
            val cache = temporaryFolder.newFolder("exports-cache-markdown-progress")
            val repository = InMemoryExportRecordRepository()
            val engine =
                ExportEngine(
                    exportsCacheDirectory = cache,
                    recordCoordinator = coordinator(repository),
                    storageGateway = RecordingStorageGateway(),
                    pdfGateway = unusedPdfGateway(),
                    artifactGenerator = StandardExportArtifactGenerator(Dispatchers.Unconfined),
                )
            val request =
                ExportRequest(
                    projectId,
                    ExportDestination("content://provider/document/redacted"),
                    ExportArtifact.Markdown(
                        listOf(
                            TextExportPage(1, "one", null),
                            TextExportPage(2, "two", null),
                            TextExportPage(3, "three", null),
                        ),
                    ),
                )

            val generating =
                engine.export(request).toList().filterIsInstance<ExportEvent.Progress>()
                    .filter { it.phase == ExportPhase.GENERATING }

            assertEquals(listOf(0, 1, 2, 3), generating.map { it.completedUnits }.distinct())
            assertTrue(generating.all { it.totalUnits == 3 })
        }

    @Test
    fun `engine selects searchable and image only PdfGateway modes with page progress`() =
        runTest {
            val cases =
                listOf(
                    Triple(PdfMode.SEARCHABLE, ExportPdfQuality.HIGH) { input: PdfInput ->
                        ExportArtifact.SearchablePdf(input)
                    },
                    Triple(PdfMode.IMAGE_ONLY, ExportPdfQuality.COMPACT) { input: PdfInput ->
                        ExportArtifact.ImagePdf(input)
                    },
                )

            cases.forEachIndexed { index, (expectedMode, expectedQuality, artifact) ->
                val cache = temporaryFolder.newFolder("exports-cache-pdf-$index")
                val repository = InMemoryExportRecordRepository()
                val gateway = RecordingPdfGateway()
                val storage = RecordingStorageGateway()
                val input = pdfInput(3)
                val engine =
                    ExportEngine(
                        cache,
                        coordinator(repository),
                        storage,
                        gateway,
                        StandardExportArtifactGenerator(Dispatchers.Unconfined),
                    )

                val events =
                    engine.export(
                        ExportRequest(
                            ExportOptions(
                                projectId = projectId,
                                type =
                                    if (expectedMode == PdfMode.SEARCHABLE) {
                                        ExportType.SEARCHABLE_PDF
                                    } else {
                                        ExportType.IMAGE_PDF
                                    },
                                fileName = "book.pdf",
                                pageRange = ExportPageRange.All,
                                pdfQuality = expectedQuality,
                                destination = ExportDestination("content://provider/document/redacted"),
                            ),
                            artifact(input),
                        ),
                    ).toList()
                val generating =
                    events.filterIsInstance<ExportEvent.Progress>()
                        .filter { it.phase == ExportPhase.GENERATING }

                assertEquals(expectedMode, gateway.mode)
                assertEquals(expectedQuality, gateway.pdfQuality)
                assertEquals(listOf(1, 2, 3), gateway.sequences)
                assertEquals("pdf-$expectedMode", storage.content)
                assertEquals(listOf(0, 1, 2, 3), generating.map { it.completedUnits })
                assertTrue(events.last() is ExportEvent.Succeeded)
            }
        }

    @Test
    fun `PdfGateway failure is propagated and incomplete cache is removed`() =
        runTest {
            val cache = temporaryFolder.newFolder("exports-cache-pdf-failure")
            val repository = InMemoryExportRecordRepository()
            val failingGateway =
                PdfGateway { _, _, output, reportProgress ->
                    reportProgress(0, 2)
                    output.write("partial".toByteArray())
                    reportProgress(1, 2)
                    throw IOException("PDF generation failed")
                }
            val engine =
                ExportEngine(
                    cache,
                    coordinator(repository),
                    RecordingStorageGateway(),
                    failingGateway,
                    StandardExportArtifactGenerator(Dispatchers.Unconfined),
                )

            val events =
                engine.export(
                    ExportRequest(
                        projectId,
                        ExportDestination("content://provider/document/redacted"),
                        ExportArtifact.SearchablePdf(pdfInput(2)),
                    ),
                ).toList()

            assertEquals("generation_failed", (events.last() as ExportEvent.Failed).errorCode)
            assertEquals(ExportState.FAILED, repository.findById(recordId)?.state)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `image zip generates matching manifest internally and reports every entry`() =
        runTest {
            val cache = temporaryFolder.newFolder("exports-cache-image-zip")
            val repository = InMemoryExportRecordRepository()
            val storage = RecordingStorageGateway()
            val images =
                listOf(
                    ExportImage(2) { ByteArrayInputStream(byteArrayOf(2)) },
                    ExportImage(1) { ByteArrayInputStream(byteArrayOf(1)) },
                )
            val manifestInput = manifestInput(1, 2)
            val engine =
                ExportEngine(
                    cache,
                    coordinator(repository),
                    storage,
                    unusedPdfGateway(),
                    StandardExportArtifactGenerator(Dispatchers.Unconfined),
                )

            val events =
                engine.export(
                    ExportRequest(
                        projectId,
                        ExportDestination("content://provider/document/redacted"),
                        ExportArtifact.ImageZip(images, manifestInput),
                    ),
                ).toList()
            val generating =
                events.filterIsInstance<ExportEvent.Progress>()
                    .filter { it.phase == ExportPhase.GENERATING }
            val zipEntries = linkedMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(requireNotNull(storage.bytes))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    zipEntries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            assertEquals(
                listOf("images/page-0001.webp", "images/page-0002.webp", "manifest.json"),
                zipEntries.keys.toList(),
            )
            assertEquals(ManifestGenerator.generate(manifestInput), zipEntries["manifest.json"])
            assertEquals(listOf(0, 1, 2, 3), generating.map { it.completedUnits }.distinct())
            assertTrue(generating.all { it.totalUnits == 3 })
        }

    @Test
    fun `image zip rejects manifest pages that do not match exported images`() =
        runTest {
            val cache = temporaryFolder.newFolder("exports-cache-image-zip-mismatch")
            val repository = InMemoryExportRecordRepository()
            val storage = RecordingStorageGateway()
            val engine =
                ExportEngine(
                    cache,
                    coordinator(repository),
                    storage,
                    unusedPdfGateway(),
                    StandardExportArtifactGenerator(Dispatchers.Unconfined),
                )

            val events =
                engine.export(
                    ExportRequest(
                        projectId,
                        ExportDestination("content://provider/document/redacted"),
                        ExportArtifact.ImageZip(
                            images = listOf(ExportImage(1) { ByteArrayInputStream(byteArrayOf(1)) }),
                            manifestInput = manifestInput(2),
                        ),
                    ),
                ).toList()

            assertEquals("generation_failed", (events.last() as ExportEvent.Failed).errorCode)
            assertEquals(ExportState.FAILED, repository.findById(recordId)?.state)
            assertNull(storage.bytes)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `unfinished records become retry candidates after process restart`() =
        runTest {
            val repository = InMemoryExportRecordRepository()
            val queued = record(recordId, ExportState.QUEUED, null, ExportType.TEXT_ZIP)
            val runningId = UUID.fromString("10000000-0000-0000-0000-000000000003")
            val running =
                record(
                    runningId,
                    ExportState.RUNNING,
                    "content://provider/document/redacted",
                    ExportType.IMAGE_ZIP,
                )
            repository.insert(queued)
            repository.insert(running)
            repository.insert(
                record(
                    UUID.fromString("10000000-0000-0000-0000-000000000004"),
                    ExportState.SUCCEEDED,
                    "content://provider/document/done",
                    ExportType.MARKDOWN,
                ),
            )

            val candidates = InterruptedExportDetector(repository).detect()

            assertEquals(listOf(recordId, runningId), candidates.map(RetryableExport::recordId))
            assertNull(candidates.first().destination)
            assertEquals(ExportDestination("content://provider/document/redacted"), candidates.last().destination)
        }

    private fun engine(
        cache: File,
        repository: ExportRecordRepository,
        generator: ExportArtifactGenerator,
    ) = ExportEngine(
        exportsCacheDirectory = cache,
        recordCoordinator = coordinator(repository),
        storageGateway = RecordingStorageGateway(),
        pdfGateway = unusedPdfGateway(),
        artifactGenerator = generator,
    )

    private fun coordinator(repository: ExportRecordRepository) =
        ExportRecordCoordinator(
            repository = repository,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            newId = { recordId },
        )

    private fun markdownRequest() =
        ExportRequest(
            projectId = projectId,
            destination = ExportDestination("content://provider/document/redacted"),
            artifact =
                ExportArtifact.Markdown(
                    listOf(TextExportPage(sequence = 1, fullText = "original", editedText = "edited")),
                ),
        )

    private fun pdfInput(pageCount: Int) =
        PdfInput(
            (1..pageCount).map { sequence ->
                PdfPage(
                    sequence = sequence,
                    image = PdfImageSource { ByteArrayInputStream(byteArrayOf(sequence.toByte())) },
                    transform = PdfPageTransform(sourceWidth = 1, sourceHeight = 1),
                    ocrBlocksJson = "[]",
                    fullText = "page $sequence",
                    editedText = null,
                )
            },
        )

    private fun manifestInput(vararg sequences: Int) =
        ManifestInput(
            appVersion = "1.0",
            project = ManifestProject("Book", null, null, now),
            exportedAt = now,
            ocrEngineVersion = "test",
            pages =
                sequences.map { sequence ->
                    ManifestPage(
                        sequence,
                        now,
                        ManifestOcrState.SUCCEEDED,
                        "hash-$sequence",
                        false,
                    )
                },
        )

    private fun unusedPdfGateway() = PdfGateway { _, _, _, _ -> error("Unexpected PDF generation") }

    private fun record(
        id: UUID,
        state: ExportState,
        targetUri: String?,
        type: ExportType,
    ) = ExportRecord(
        id = id,
        projectId = projectId,
        type = type,
        targetUri = targetUri,
        state = state,
        createdAt = now,
        completedAt = if (state == ExportState.SUCCEEDED) now else null,
        errorCode = null,
    )

    private class InMemoryExportRecordRepository : ExportRecordRepository {
        private val records = linkedMapOf<UUID, ExportRecord>()

        override suspend fun insert(record: ExportRecord) {
            check(records.putIfAbsent(record.id, record) == null)
        }

        override suspend fun findById(id: UUID): ExportRecord? = records[id]

        override suspend fun findIncomplete(): List<ExportRecord> =
            records.values.filter { it.state == ExportState.QUEUED || it.state == ExportState.RUNNING }

        override suspend fun compareAndSet(
            expected: ExportRecord,
            updated: ExportRecord,
        ): Boolean {
            if (records[expected.id] != expected) return false
            records[expected.id] = updated
            return true
        }
    }

    private class RecordingStorageGateway : ExportStorageGateway {
        var bytes: ByteArray? = null
            private set
        val content: String?
            get() = bytes?.toString(Charsets.UTF_8)

        override suspend fun write(
            source: CompletedExportSource,
            destination: ExportDestination,
        ): ExportStorageResult {
            val generated = source.openInputStream().use { it.readBytes() }
            bytes = generated
            return ExportStorageResult.Succeeded(generated.size.toLong())
        }
    }

    private class RecordingPdfGateway : PdfGateway {
        var mode: PdfMode? = null
            private set
        var pdfQuality: ExportPdfQuality? = null
            private set
        var sequences: List<Int> = emptyList()
            private set

        override suspend fun generate(
            input: PdfInput,
            mode: PdfMode,
            output: java.io.OutputStream,
            reportProgress: suspend (completedPages: Int, totalPages: Int) -> Unit,
        ) {
            this.mode = mode
            pdfQuality = input.pdfQuality
            sequences = input.pages.map(PdfPage::sequence)
            reportProgress(0, input.pages.size)
            input.pages.forEachIndexed { index, _ -> reportProgress(index + 1, input.pages.size) }
            output.write("pdf-$mode".toByteArray())
        }
    }
}
