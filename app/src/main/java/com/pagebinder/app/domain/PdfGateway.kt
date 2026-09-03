package com.pagebinder.app.domain

import java.io.InputStream
import java.io.OutputStream

enum class PdfMode {
    SEARCHABLE,
    IMAGE_ONLY,
}

/** Opens a page image after its non-destructive rotation and crop have been applied. */
fun interface PdfImageSource {
    fun openInputStream(): InputStream
}

/** Framework-independent input required by the eventual PDF implementation. */
data class PdfPage(
    val sequence: Int,
    val image: PdfImageSource,
    val ocrBlocksJson: String?,
    val fullText: String?,
    val editedText: String?,
)

data class PdfInput(
    val pages: List<PdfPage>,
    /** Display-layer quality. OCR text coordinates are independent of this setting. */
    val pdfQuality: ExportPdfQuality = ExportPdfQuality.STANDARD,
)

/**
 * Generates a PDF stream in the requested mode without exposing PDFBox types.
 *
 * Implementations report 0 before processing and then each completed page through N. They must
 * remain cancellable and must not close [output]. IMAGE_ONLY must not depend on OCR text-layer
 * generation succeeding.
 */
fun interface PdfGateway {
    suspend fun generate(
        input: PdfInput,
        mode: PdfMode,
        output: OutputStream,
        reportProgress: suspend (completedPages: Int, totalPages: Int) -> Unit,
    )
}
