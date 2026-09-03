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

/**
 * The non-destructive rotation and crop already baked into [PdfPage.image].
 *
 * [PdfPage.ocrBlocksJson] keeps original-image coordinates (02-data-model §3.4) while the image is
 * the rotated/cropped derivative, so the PDF implementation needs the original size plus the edit
 * to drive one shared transformation matrix (10-searchable-pdf §3.2). Rotation is clockwise and
 * crop bounds are normalized against the rotated image (07-image-quality §3.4).
 */
data class PdfPageTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int = 0,
    val crop: PageCrop = PageCrop(),
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "PDF source dimensions must be positive" }
        require(rotationDegrees in VALID_PAGE_ROTATIONS) {
            "PDF page rotation must be 0, 90, 180, or 270 degrees"
        }
    }
}

/** Framework-independent input required by the eventual PDF implementation. */
data class PdfPage(
    val sequence: Int,
    val image: PdfImageSource,
    val transform: PdfPageTransform,
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
