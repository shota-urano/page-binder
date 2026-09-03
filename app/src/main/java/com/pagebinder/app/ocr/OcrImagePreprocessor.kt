package com.pagebinder.app.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import com.pagebinder.app.domain.OcrInput
import com.pagebinder.app.domain.OcrInputException
import com.pagebinder.app.image.ImageCoordinateTransformer
import com.pagebinder.app.image.ImagePoint
import com.pagebinder.app.image.ImageRect
import com.pagebinder.app.image.ImageSize
import com.pagebinder.app.image.enclosingPixelBounds
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

internal data class OcrPoint(
    val x: Float,
    val y: Float,
)

internal data class OcrPixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal class OcrCoordinateMapper(
    private val originalWidth: Int,
    private val originalHeight: Int,
    private val decodedWidth: Int,
    private val decodedHeight: Int,
    private val coordinates: ImageCoordinateTransformer,
    private val preparedWidth: Int,
    private val preparedHeight: Int,
) {
    private val pixelCropBounds = coordinates.pixelCropBounds

    fun toOriginal(rect: OcrPixelRect): OcrPixelRect {
        val corners =
            listOf(
                toOriginal(OcrPoint(rect.left.toFloat(), rect.top.toFloat())),
                toOriginal(OcrPoint(rect.right.toFloat(), rect.top.toFloat())),
                toOriginal(OcrPoint(rect.right.toFloat(), rect.bottom.toFloat())),
                toOriginal(OcrPoint(rect.left.toFloat(), rect.bottom.toFloat())),
            )
        val bounds =
            ImageRect(
                left = corners.minOf(OcrPoint::x),
                top = corners.minOf(OcrPoint::y),
                right = corners.maxOf(OcrPoint::x),
                bottom = corners.maxOf(OcrPoint::y),
            ).enclosingPixelBounds(ImageSize(originalWidth.toFloat(), originalHeight.toFloat()))
        return OcrPixelRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun toOriginal(point: OcrPoint): OcrPoint {
        val decoded =
            coordinates.pixelCroppedToSource.map(
                ImagePoint(
                    x = point.x * pixelCropBounds.width / preparedWidth,
                    y = point.y * pixelCropBounds.height / preparedHeight,
                ),
            )
        return OcrPoint(
            x = decoded.x * originalWidth / decodedWidth,
            y = decoded.y * originalHeight / decodedHeight,
        )
    }
}

internal data class PreparedOcrImage(
    val bitmap: Bitmap,
    val coordinateMapper: OcrCoordinateMapper,
)

internal object OcrImagePreprocessor {
    // Bounds decoded bitmap memory while retaining more detail than the final recognition image.
    private const val MAX_DECODED_DIMENSION = 4096
    private const val MAX_OCR_DIMENSION = 2048

    @Throws(OcrInputException::class)
    fun prepare(input: OcrInput): PreparedOcrImage =
        try {
            prepareValidated(input)
        } catch (error: OcrInputException) {
            throw error
        } catch (error: OutOfMemoryError) {
            throw OcrInputException("OCR image preprocessing ran out of memory", error)
        } catch (error: Exception) {
            throw OcrInputException("OCR image preprocessing failed", error)
        }

    private fun prepareValidated(input: OcrInput): PreparedOcrImage {
        val encoded =
            try {
                input.image.openInputStream().use { it.readBytes() }
            } catch (error: IOException) {
                throw OcrInputException("OCR image could not be read", error)
            }
        if (encoded.isEmpty()) throw OcrInputException("OCR image is empty")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw OcrInputException("OCR image could not be decoded")
        }

        val coordinates =
            ImageCoordinateTransformer.create(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                rotationDegrees = input.rotationDegrees,
                cropLeft = input.crop.left,
                cropTop = input.crop.top,
                cropRight = input.crop.right,
                cropBottom = input.crop.bottom,
            )
        val cropRect = coordinates.pixelCropBounds
        val sourceRegion =
            coordinates
                .rotatedToSource(
                    ImageRect(
                        cropRect.left.toFloat(),
                        cropRect.top.toFloat(),
                        cropRect.right.toFloat(),
                        cropRect.bottom.toFloat(),
                    ),
                ).toAndroidRect()
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(cropRect.width, cropRect.height)
            }
        val regionDecoder = BitmapRegionDecoder.newInstance(encoded, 0, encoded.size, false)
        var current =
            try {
                regionDecoder.decodeRegion(sourceRegion, options)
                    ?: throw OcrInputException("OCR image could not be decoded")
            } finally {
                regionDecoder.recycle()
            }

        try {
            if (input.rotationDegrees != 0) {
                current = current.replaceWithRotated(input.rotationDegrees)
            }
            val decodedCropWidth = current.width
            val decodedCropHeight = current.height
            val scale = minOf(1f, MAX_OCR_DIMENSION.toFloat() / max(decodedCropWidth, decodedCropHeight))
            if (scale < 1f) {
                current =
                    current.replaceWithScale(
                        width = max(1, (decodedCropWidth * scale).roundToInt()),
                        height = max(1, (decodedCropHeight * scale).roundToInt()),
                    )
            }
            return PreparedOcrImage(
                bitmap = current,
                coordinateMapper =
                    OcrCoordinateMapper(
                        originalWidth = bounds.outWidth,
                        originalHeight = bounds.outHeight,
                        decodedWidth = bounds.outWidth,
                        decodedHeight = bounds.outHeight,
                        coordinates = coordinates,
                        preparedWidth = current.width,
                        preparedHeight = current.height,
                    ),
            )
        } catch (error: OutOfMemoryError) {
            current.recycle()
            throw error
        } catch (error: RuntimeException) {
            current.recycle()
            throw error
        }
    }

    private fun sampleSize(
        width: Int,
        height: Int,
    ): Int {
        var result = 1
        while (max(width / result, height / result) > MAX_DECODED_DIMENSION) result *= 2
        return result
    }

    /** Rotation maps integer crop edges to exact integer source edges. */
    private fun ImageRect.toAndroidRect(): Rect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

    private fun Bitmap.replaceWithRotated(degrees: Int): Bitmap {
        val replacement =
            Bitmap.createBitmap(
                this,
                0,
                0,
                width,
                height,
                Matrix().apply { postRotate(degrees.toFloat()) },
                true,
            )
        if (replacement !== this) recycle()
        return replacement
    }

    private fun Bitmap.replaceWithScale(
        width: Int,
        height: Int,
    ): Bitmap {
        val replacement = Bitmap.createScaledBitmap(this, width, height, true)
        if (replacement !== this) recycle()
        return replacement
    }
}
