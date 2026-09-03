package com.pagebinder.app.export

import com.pagebinder.app.domain.ExportDestination
import com.pagebinder.app.domain.ExportFailureCode
import com.pagebinder.app.domain.ExportOptions
import com.pagebinder.app.domain.ExportPdfQuality
import com.pagebinder.app.domain.ExportRecord
import com.pagebinder.app.domain.ExportRecordRepository
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportStorageErrorCode
import com.pagebinder.app.domain.ExportStorageGateway
import com.pagebinder.app.domain.ExportStorageResult
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.domain.PdfGateway
import com.pagebinder.app.domain.PdfInput
import com.pagebinder.app.domain.PdfMode
import com.pagebinder.app.storage.CompletedCacheExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

sealed interface ExportArtifact {
    val type: ExportType

    data class SearchablePdf(val input: PdfInput) : ExportArtifact {
        override val type = ExportType.SEARCHABLE_PDF
    }

    data class ImagePdf(val input: PdfInput) : ExportArtifact {
        override val type = ExportType.IMAGE_PDF
    }

    data class Markdown(val pages: List<TextExportPage>) : ExportArtifact {
        override val type = ExportType.MARKDOWN
    }

    data class TextZip(val pages: List<TextExportPage>) : ExportArtifact {
        override val type = ExportType.TEXT_ZIP
    }

    data class ImageZip(
        val images: List<ExportImage>,
        val manifestInput: ManifestInput,
    ) : ExportArtifact {
        override val type = ExportType.IMAGE_ZIP
    }
}

data class ExportRequest(
    val projectId: UUID,
    val destination: ExportDestination,
    val artifact: ExportArtifact,
    val pdfQuality: ExportPdfQuality = ExportPdfQuality.STANDARD,
) {
    /** Bridges the UI's confirmed export options into the export implementation. */
    constructor(
        options: ExportOptions,
        artifact: ExportArtifact,
    ) : this(
        projectId = options.projectId,
        destination = options.destination,
        artifact = artifact,
        pdfQuality = options.pdfQuality,
    )
}

enum class ExportPhase {
    QUEUED,
    GENERATING,
    WRITING,
}

sealed interface ExportEvent {
    val recordId: UUID

    data class Progress(
        override val recordId: UUID,
        val phase: ExportPhase,
        val completedUnits: Int,
        val totalUnits: Int,
    ) : ExportEvent

    data class Succeeded(
        override val recordId: UUID,
        val bytesWritten: Long,
    ) : ExportEvent

    data class Failed(
        override val recordId: UUID,
        val errorCode: String,
    ) : ExportEvent
}

fun interface ExportArtifactGenerator {
    suspend fun generate(
        artifact: ExportArtifact,
        outputFile: File,
        reportProgress: suspend (completedUnits: Int, totalUnits: Int) -> Unit,
    )
}

/**
 * Framework-independent dispatcher for the text and ZIP formats. PDF mode selection remains an
 * Export Engine responsibility and is delegated directly to PdfGateway there.
 */
class StandardExportArtifactGenerator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExportArtifactGenerator {
    override suspend fun generate(
        artifact: ExportArtifact,
        outputFile: File,
        reportProgress: suspend (completedUnits: Int, totalUnits: Int) -> Unit,
    ) = withContext(ioDispatcher) {
        reportProgress(0, artifact.totalUnits())
        when (artifact) {
            is ExportArtifact.SearchablePdf,
            is ExportArtifact.ImagePdf,
            -> error("PDF artifacts must be coordinated by ExportEngine")
            is ExportArtifact.Markdown ->
                writeMarkdown(artifact.pages, outputFile, reportProgress)
            is ExportArtifact.TextZip ->
                outputFile.outputStream().buffered().use { output ->
                    ExportZipPackager.writeTextZip(
                        PageTextGenerator.generate(artifact.pages),
                        output,
                        reportProgress,
                    )
                }
            is ExportArtifact.ImageZip ->
                outputFile.outputStream().buffered().use { output ->
                    requireManifestMatchesImages(artifact)
                    ExportZipPackager.writeImageZip(
                        artifact.images,
                        ManifestGenerator.generate(artifact.manifestInput),
                        output,
                        reportProgress,
                    )
                }
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun writeMarkdown(
        pages: List<TextExportPage>,
        outputFile: File,
        reportProgress: suspend (completedUnits: Int, totalUnits: Int) -> Unit,
    ) {
        val ordered = pages.validatedTextExportPages()
        val total = ordered.size.coerceAtLeast(1)
        outputFile.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            ordered.forEachIndexed { index, page ->
                currentCoroutineContext().ensureActive()
                if (index > 0) writer.write("\n\n---\n\n")
                writer.write("## Page ${page.sequence}\n\n${page.outputText}")
                reportProgress(index + 1, total)
            }
        }
        if (ordered.isEmpty()) reportProgress(total, total)
    }

    private fun requireManifestMatchesImages(artifact: ExportArtifact.ImageZip) {
        val imageSequences = artifact.images.map(ExportImage::sequence).sorted()
        val manifestSequences = artifact.manifestInput.pages.map(ManifestPage::sequence).sorted()
        require(imageSequences == manifestSequences) {
            "Manifest pages must exactly match exported images"
        }
    }
}

/** Coordinates generation, app-private temporary files, SAF writing, history, and cancellation. */
class ExportEngine(
    private val exportsCacheDirectory: File,
    private val recordCoordinator: ExportRecordCoordinator,
    private val storageGateway: ExportStorageGateway,
    private val pdfGateway: PdfGateway,
    private val artifactGenerator: ExportArtifactGenerator = StandardExportArtifactGenerator(),
) {
    fun export(request: ExportRequest): Flow<ExportEvent> =
        channelFlow {
            require(request.destination.uri.isNotBlank()) { "Destination URI must not be blank" }
            prepareCacheDirectory()

            val queued = recordCoordinator.enqueue(request.projectId, request.artifact.type)
            val partialFile = File(exportsCacheDirectory, "${queued.id}.part")
            val completedFile = File(exportsCacheDirectory, "${queued.id}.ready")
            var running: ExportRecord? = null
            var terminal = false
            var failureCode = ERROR_GENERATION_FAILED

            try {
                running = recordCoordinator.markRunning(queued.id, request.destination.uri)
                sendProgress(queued.id, ExportPhase.QUEUED, 0, 1)
                generateArtifact(request.artifact, request.pdfQuality, partialFile) { completed, total ->
                    require(total > 0 && completed in 0..total) { "Invalid export progress" }
                    sendProgress(queued.id, ExportPhase.GENERATING, completed, total)
                }
                check(partialFile.isFile) { "Generator did not produce an export" }
                moveToCompleted(partialFile, completedFile)

                sendProgress(queued.id, ExportPhase.WRITING, 0, 1)
                failureCode = ExportStorageErrorCode.WRITE_FAILED.serializedName
                when (
                    val result =
                        storageGateway.write(
                            CompletedCacheExport.open(exportsCacheDirectory, completedFile),
                            request.destination,
                        )
                ) {
                    is ExportStorageResult.Succeeded -> {
                        recordCoordinator.markSucceeded(queued.id)
                        terminal = true
                        send(ExportEvent.Succeeded(queued.id, result.bytesWritten))
                    }
                    is ExportStorageResult.Failed -> {
                        recordCoordinator.markFailed(queued.id, result.errorCode.serializedName)
                        terminal = true
                        send(ExportEvent.Failed(queued.id, result.errorCode.serializedName))
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    try {
                        cleanup(partialFile, completedFile)
                    } finally {
                        if (!terminal && running?.state == ExportState.RUNNING) {
                            recordCoordinator.markFailed(queued.id, ERROR_CANCELLED)
                        }
                    }
                }
                throw cancelled
            } catch (_: IOException) {
                fail(queued.id, failureCode)
            } catch (_: RuntimeException) {
                fail(queued.id, failureCode)
            } finally {
                cleanup(partialFile, completedFile)
            }
        }

    private suspend fun generateArtifact(
        artifact: ExportArtifact,
        pdfQuality: ExportPdfQuality,
        outputFile: File,
        reportProgress: suspend (completedUnits: Int, totalUnits: Int) -> Unit,
    ) {
        when (artifact) {
            is ExportArtifact.SearchablePdf ->
                generatePdf(
                    artifact.input.copy(pdfQuality = pdfQuality),
                    PdfMode.SEARCHABLE,
                    outputFile,
                    reportProgress,
                )
            is ExportArtifact.ImagePdf ->
                generatePdf(
                    artifact.input.copy(pdfQuality = pdfQuality),
                    PdfMode.IMAGE_ONLY,
                    outputFile,
                    reportProgress,
                )
            else -> artifactGenerator.generate(artifact, outputFile, reportProgress)
        }
    }

    private suspend fun generatePdf(
        input: PdfInput,
        mode: PdfMode,
        outputFile: File,
        reportProgress: suspend (completedUnits: Int, totalUnits: Int) -> Unit,
    ) {
        outputFile.outputStream().buffered().use { output ->
            pdfGateway.generate(input, mode, output, reportProgress)
        }
    }

    private suspend fun SendChannel<ExportEvent>.sendProgress(
        recordId: UUID,
        phase: ExportPhase,
        completed: Int,
        total: Int,
    ) {
        send(ExportEvent.Progress(recordId, phase, completed, total))
    }

    private suspend fun SendChannel<ExportEvent>.fail(
        recordId: UUID,
        errorCode: String,
    ) {
        recordCoordinator.markFailed(recordId, errorCode)
        send(ExportEvent.Failed(recordId, errorCode))
    }

    private fun prepareCacheDirectory() {
        check(exportsCacheDirectory.isDirectory || exportsCacheDirectory.mkdirs()) {
            "Unable to prepare exports-cache"
        }
    }

    private fun moveToCompleted(
        partialFile: File,
        completedFile: File,
    ) {
        check(!completedFile.exists() && partialFile.renameTo(completedFile)) {
            "Unable to complete temporary export"
        }
    }

    private fun cleanup(vararg files: File) {
        files.forEach { file ->
            if (file.exists() && !file.delete()) throw IOException("Unable to clean temporary export")
        }
    }
}

data class RetryableExport(
    val recordId: UUID,
    val projectId: UUID,
    val type: ExportType,
    val destination: ExportDestination?,
)

/** Reads durable queued/running records left behind when the previous process stopped. */
class InterruptedExportDetector(
    private val repository: ExportRecordRepository,
) {
    suspend fun detect(): List<RetryableExport> =
        repository.findIncomplete().map { record ->
            RetryableExport(
                recordId = record.id,
                projectId = record.projectId,
                type = record.type,
                destination = record.targetUri?.let(::ExportDestination),
            )
        }
}

private fun ExportArtifact.totalUnits(): Int =
    when (this) {
        is ExportArtifact.Markdown -> pages.size.coerceAtLeast(1)
        is ExportArtifact.TextZip -> pages.size.coerceAtLeast(1)
        is ExportArtifact.ImageZip -> (images.size + 1).coerceAtLeast(1)
        is ExportArtifact.SearchablePdf -> input.pages.size.coerceAtLeast(1)
        is ExportArtifact.ImagePdf -> input.pages.size.coerceAtLeast(1)
    }

private const val ERROR_CANCELLED = ExportFailureCode.CANCELLED
private const val ERROR_GENERATION_FAILED = ExportFailureCode.GENERATION_FAILED
