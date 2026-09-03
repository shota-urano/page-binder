package com.pagebinder.app.image

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A framework-independent point in image coordinates whose origin is at the top left. */
data class ImagePoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Image point coordinates must be finite" }
    }
}

/** An axis-aligned rectangle in image coordinates whose origin is at the top left. */
data class ImageRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "Image rectangle coordinates must be finite"
        }
        require(left <= right && top <= bottom) { "Image rectangle must not be inverted" }
    }
}

data class ImageSize(
    val width: Float,
    val height: Float,
) {
    init {
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
            "Image dimensions must be finite and positive"
        }
    }
}

/** Integer crop bounds with an exclusive right and bottom edge. */
data class ImagePixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left < right && top < bottom) { "Image pixel rectangle must have a positive area" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Affine matrix using x' = ax + cy + tx and y' = bx + dy + ty. */
data class ImageAffineMatrix(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val tx: Float,
    val ty: Float,
) {
    fun map(point: ImagePoint): ImagePoint =
        ImagePoint(
            x = a * point.x + c * point.y + tx,
            y = b * point.x + d * point.y + ty,
        )

    /** Returns a matrix that applies this transform and then [next]. */
    fun then(next: ImageAffineMatrix): ImageAffineMatrix =
        ImageAffineMatrix(
            a = next.a * a + next.c * b,
            b = next.b * a + next.d * b,
            c = next.a * c + next.c * d,
            d = next.b * c + next.d * d,
            tx = next.a * tx + next.c * ty + next.tx,
            ty = next.b * tx + next.d * ty + next.ty,
        )

    fun inverse(): ImageAffineMatrix {
        val determinant = a * d - b * c
        check(determinant != 0f) { "Image transform must be invertible" }
        return ImageAffineMatrix(
            a = d / determinant,
            b = -b / determinant,
            c = -c / determinant,
            d = a / determinant,
            tx = (c * ty - d * tx) / determinant,
            ty = (b * tx - a * ty) / determinant,
        )
    }
}

/**
 * The shared definition of PageBinder's non-destructive image coordinates.
 *
 * Clockwise rotation is applied first. Crop bounds are then interpreted as normalized coordinates
 * of that rotated image. [sourceToCropped] and [croppedToSource] are exact inverses for the
 * continuous normalized crop.
 *
 * Actual bitmap derivatives can only use integer edges. [pixelCropBounds] is the single source
 * of truth for converting the normalized crop to those edges: it encloses the continuous crop
 * with floor/ceil rounding. Display, OCR, and PDF generation must use
 * [sourceToPixelCropped] / [pixelCroppedToSource] when they refer to the generated derivative.
 */
class ImageCoordinateTransformer private constructor(
    val sourceSize: ImageSize,
    val rotatedSize: ImageSize,
    val cropBounds: ImageRect,
    val sourceToRotated: ImageAffineMatrix,
    val rotatedToSource: ImageAffineMatrix,
    val sourceToCropped: ImageAffineMatrix,
    val croppedToSource: ImageAffineMatrix,
) {
    val croppedSize = ImageSize(cropBounds.right - cropBounds.left, cropBounds.bottom - cropBounds.top)

    /**
     * Integer bounds of the bitmap derivative; right and bottom are exclusive.
     *
     * This is deliberately the only definition of crop-edge rounding. Values within two ULPs of
     * an integer are first snapped to that integer, which removes multiplication representation
     * noise without changing valid non-integer crop edges.
     */
    val pixelCropBounds: ImagePixelRect = cropBounds.enclosingPixelBounds(rotatedSize)

    /** Pixel dimensions of the derivative cropped with [pixelCropBounds]. */
    val pixelCroppedSize =
        ImageSize(
            width = (pixelCropBounds.right - pixelCropBounds.left).toFloat(),
            height = (pixelCropBounds.bottom - pixelCropBounds.top).toFloat(),
        )

    /** Maps source coordinates to the actual integer-bounded bitmap derivative. */
    val sourceToPixelCropped: ImageAffineMatrix =
        sourceToRotated.then(
            ImageAffineMatrix(
                a = 1f,
                b = 0f,
                c = 0f,
                d = 1f,
                tx = -pixelCropBounds.left.toFloat(),
                ty = -pixelCropBounds.top.toFloat(),
            ),
        )

    /** Inverse of [sourceToPixelCropped]. */
    val pixelCroppedToSource: ImageAffineMatrix = sourceToPixelCropped.inverse()

    fun sourceToCropped(point: ImagePoint): ImagePoint = sourceToCropped.map(point)

    fun croppedToSource(point: ImagePoint): ImagePoint = croppedToSource.map(point)

    fun sourceToRotated(point: ImagePoint): ImagePoint = sourceToRotated.map(point)

    fun rotatedToSource(point: ImagePoint): ImagePoint = rotatedToSource.map(point)

    fun sourceToCropped(rect: ImageRect): ImageRect = sourceToCropped.mapBounds(rect)

    fun croppedToSource(rect: ImageRect): ImageRect = croppedToSource.mapBounds(rect)

    fun rotatedToSource(rect: ImageRect): ImageRect = rotatedToSource.mapBounds(rect)

    companion object {
        fun create(
            sourceWidth: Int,
            sourceHeight: Int,
            rotationDegrees: Int,
            cropLeft: Float = 0f,
            cropTop: Float = 0f,
            cropRight: Float = 1f,
            cropBottom: Float = 1f,
        ): ImageCoordinateTransformer {
            require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
            require(rotationDegrees in setOf(0, 90, 180, 270)) {
                "Rotation must be 0, 90, 180, or 270 degrees"
            }
            require(listOf(cropLeft, cropTop, cropRight, cropBottom).all(Float::isFinite)) {
                "Crop coordinates must be finite"
            }
            require(listOf(cropLeft, cropTop, cropRight, cropBottom).all { it in 0f..1f }) {
                "Crop coordinates must be normalized"
            }
            require(cropLeft < cropRight && cropTop < cropBottom) { "Crop must have a positive area" }

            val sourceSize = ImageSize(sourceWidth.toFloat(), sourceHeight.toFloat())
            val rotatedSize =
                if (rotationDegrees % 180 == 0) {
                    sourceSize
                } else {
                    ImageSize(sourceSize.height, sourceSize.width)
                }
            val cropBounds =
                ImageRect(
                    left = cropLeft * rotatedSize.width,
                    top = cropTop * rotatedSize.height,
                    right = cropRight * rotatedSize.width,
                    bottom = cropBottom * rotatedSize.height,
                )
            val sourceToRotated = rotationMatrix(rotationDegrees, sourceSize)
            val sourceToCropped =
                sourceToRotated.then(
                    ImageAffineMatrix(1f, 0f, 0f, 1f, -cropBounds.left, -cropBounds.top),
                )
            return ImageCoordinateTransformer(
                sourceSize = sourceSize,
                rotatedSize = rotatedSize,
                cropBounds = cropBounds,
                sourceToRotated = sourceToRotated,
                rotatedToSource = sourceToRotated.inverse(),
                sourceToCropped = sourceToCropped,
                croppedToSource = sourceToCropped.inverse(),
            )
        }

        private fun rotationMatrix(
            degrees: Int,
            sourceSize: ImageSize,
        ): ImageAffineMatrix =
            when (degrees) {
                0 -> ImageAffineMatrix(1f, 0f, 0f, 1f, 0f, 0f)
                90 -> ImageAffineMatrix(0f, 1f, -1f, 0f, sourceSize.height, 0f)
                180 -> ImageAffineMatrix(-1f, 0f, 0f, -1f, sourceSize.width, sourceSize.height)
                270 -> ImageAffineMatrix(0f, -1f, 1f, 0f, 0f, sourceSize.width)
                else -> error("Rotation was validated before matrix creation")
            }
    }
}

private fun ImageAffineMatrix.mapBounds(rect: ImageRect): ImageRect {
    val corners =
        listOf(
            map(ImagePoint(rect.left, rect.top)),
            map(ImagePoint(rect.right, rect.top)),
            map(ImagePoint(rect.right, rect.bottom)),
            map(ImagePoint(rect.left, rect.bottom)),
        )
    return ImageRect(
        left = corners.minOf(ImagePoint::x),
        top = corners.minOf(ImagePoint::y),
        right = corners.maxOf(ImagePoint::x),
        bottom = corners.maxOf(ImagePoint::y),
    )
}

/**
 * The sole float-rectangle to pixel-boundary rounding rule. In particular, this defines the
 * normalized crop edges used by every bitmap derivative.
 */
internal fun ImageRect.enclosingPixelBounds(size: ImageSize): ImagePixelRect {
    val width = size.width.toInt()
    val height = size.height.toInt()
    val left = floor(snapPixelEdge(left)).toInt().coerceIn(0, width - 1)
    val top = floor(snapPixelEdge(top)).toInt().coerceIn(0, height - 1)
    val right = ceil(snapPixelEdge(right)).toInt().coerceIn(left + 1, width)
    val bottom = ceil(snapPixelEdge(bottom)).toInt().coerceIn(top + 1, height)
    return ImagePixelRect(left, top, right, bottom)
}

/** Snaps only representational error, not meaningful sub-pixel crop edges. */
private fun snapPixelEdge(value: Float): Float {
    val nearestInteger = value.roundToInt().toFloat()
    val tolerance = maxOf(Math.ulp(value), Math.ulp(nearestInteger)) * 2f
    return if (abs(value - nearestInteger) <= tolerance) nearestInteger else value
}
