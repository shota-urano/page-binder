package com.pagebinder.app.export

import com.pagebinder.app.domain.PdfPageTransform
import com.pagebinder.app.image.ImageAffineMatrix
import com.pagebinder.app.image.ImageCoordinateTransformer

/** A framework-independent affine matrix using x' = ax + cy + tx, y' = bx + dy + ty. */
internal data class PdfAffineMatrix(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val tx: Float,
    val ty: Float,
) {
    fun map(point: PdfPoint): PdfPoint =
        PdfPoint(
            x = a * point.x + c * point.y + tx,
            y = b * point.x + d * point.y + ty,
        )

    /** Returns a matrix that applies this transform and then [next]. */
    fun then(next: PdfAffineMatrix): PdfAffineMatrix =
        PdfAffineMatrix(
            a = next.a * a + next.c * b,
            b = next.b * a + next.d * b,
            c = next.a * c + next.c * d,
            d = next.b * c + next.d * d,
            tx = next.a * tx + next.c * ty + next.tx,
            ty = next.b * tx + next.d * ty + next.ty,
        )
}

internal data class PdfPoint(
    val x: Float,
    val y: Float,
)

/** Rectangle in source-image coordinates, whose origin is at the top left. */
internal data class OcrRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "OCR rectangle coordinates must be finite"
        }
        require(left <= right && top <= bottom) { "OCR rectangle must not be inverted" }
    }
}

/** Rectangle in PDF coordinates, whose origin is at the bottom left. */
internal data class PdfRect(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = top - bottom
}

/** Crop bounds normalized against the image after rotation. */
internal data class NormalizedCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "Crop coordinates must be finite"
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Crop coordinates must be normalized"
        }
        require(left < right && top < bottom) { "Crop must have a positive area" }
    }
}

internal data class PdfPageSize(
    val width: Float,
    val height: Float,
) {
    init {
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
            "PDF page dimensions must be finite and positive"
        }
    }
}

internal data class OcrTextElement(
    val text: String,
    val rect: OcrRect,
)

internal data class OcrTextLine(
    val text: String,
    val rect: OcrRect,
    val elements: List<OcrTextElement> = emptyList(),
)

internal data class OcrTextBlock(
    val text: String,
    val rect: OcrRect,
    val lines: List<OcrTextLine> = emptyList(),
)

internal data class PdfTextPlacement(
    val text: String,
    val bounds: PdfRect,
)

/**
 * Converts original-image/OCR coordinates to a ratio-preserving PDF page.
 *
 * The single [sourceToPdf] matrix applies clockwise rotation, crop translation, uniform page
 * scaling, and the top-left to bottom-left origin conversion. The raster image and every OCR
 * rectangle must use this same matrix.
 */
internal class PdfCoordinateTransformer private constructor(
    val pageSize: PdfPageSize,
    val sourceToPdf: PdfAffineMatrix,
) {
    fun map(point: PdfPoint): PdfPoint = sourceToPdf.map(point)

    fun map(rect: OcrRect): PdfRect {
        val corners =
            listOf(
                map(PdfPoint(rect.left, rect.top)),
                map(PdfPoint(rect.right, rect.top)),
                map(PdfPoint(rect.right, rect.bottom)),
                map(PdfPoint(rect.left, rect.bottom)),
            )
        return PdfRect(
            left = corners.minOf(PdfPoint::x),
            bottom = corners.minOf(PdfPoint::y),
            right = corners.maxOf(PdfPoint::x),
            top = corners.maxOf(PdfPoint::y),
        )
    }

    /** Uses element boxes when available, otherwise line boxes, then a block box as a last resort. */
    fun createTextPlacements(blocks: List<OcrTextBlock>): List<PdfTextPlacement> =
        buildList {
            blocks.forEach { block ->
                if (block.lines.isEmpty()) {
                    addPlacement(block.text, block.rect)
                } else {
                    block.lines.forEach { line ->
                        if (line.elements.isEmpty()) {
                            addPlacement(line.text, line.rect)
                        } else {
                            line.elements.forEach { element -> addPlacement(element.text, element.rect) }
                        }
                    }
                }
            }
        }

    private fun MutableList<PdfTextPlacement>.addPlacement(
        text: String,
        rect: OcrRect,
    ) {
        if (text.isNotEmpty()) add(PdfTextPlacement(text, map(rect)))
    }

    companion object {
        /**
         * Builds a page using [pageWidth] and derives its height to preserve the cropped image's
         * aspect ratio. Rotation is clockwise and crop coordinates refer to the rotated image.
         */
        fun create(
            sourceWidth: Int,
            sourceHeight: Int,
            rotationDegrees: Int,
            crop: NormalizedCrop = NormalizedCrop(),
            pageWidth: Float,
        ): PdfCoordinateTransformer {
            require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
            require(rotationDegrees in setOf(0, 90, 180, 270)) {
                "Rotation must be 0, 90, 180, or 270 degrees"
            }
            require(pageWidth.isFinite() && pageWidth > 0f) {
                "PDF page width must be finite and positive"
            }

            val imageTransform =
                ImageCoordinateTransformer.create(
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    rotationDegrees = rotationDegrees,
                    cropLeft = crop.left,
                    cropTop = crop.top,
                    cropRight = crop.right,
                    cropBottom = crop.bottom,
                )
            // Keep the OCR matrix aligned with the integer-bounded bitmap derivative.
            val croppedWidth = imageTransform.pixelCroppedSize.width
            val croppedHeight = imageTransform.pixelCroppedSize.height
            val scale = pageWidth / croppedWidth
            val pageSize = PdfPageSize(pageWidth, croppedHeight * scale)

            val croppedToPdf =
                PdfAffineMatrix(
                    a = scale,
                    b = 0f,
                    c = 0f,
                    d = -scale,
                    tx = 0f,
                    ty = pageSize.height,
                )
            val sourceToPdf = imageTransform.sourceToPixelCropped.toPdfMatrix().then(croppedToPdf)

            return PdfCoordinateTransformer(pageSize, sourceToPdf)
        }
    }
}

private fun ImageAffineMatrix.toPdfMatrix() = PdfAffineMatrix(a, b, c, d, tx, ty)

/**
 * Builds the matrix shared by the drawn raster and the OCR text layer (10-searchable-pdf §3.2).
 *
 * [editedImageWidth] is the pixel width of the already rotated/cropped derivative that gets drawn,
 * so the page keeps that derivative's ratio (10-searchable-pdf §3.5) while OCR rectangles still
 * enter in original-image coordinates.
 */
internal fun PdfPageTransform.toCoordinateTransformer(editedImageWidth: Int): PdfCoordinateTransformer =
    PdfCoordinateTransformer.create(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        rotationDegrees = rotationDegrees,
        crop =
            NormalizedCrop(
                left = crop.left,
                top = crop.top,
                right = crop.right,
                bottom = crop.bottom,
            ),
        pageWidth = editedImageWidth.toFloat(),
    )
