package com.pagebinder.app.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import com.pagebinder.app.domain.OcrInput
import com.pagebinder.app.domain.OcrInputException
import com.pagebinder.app.image.ImageCoordinateTransformer
import com.pagebinder.app.image.ImagePixelRect
import com.pagebinder.app.image.ImagePoint
import com.pagebinder.app.image.ImageRect
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
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
    private val rotationDegrees: Int,
    private val cropLeft: Int,
    private val cropTop: Int,
    private val croppedWidth: Int,
    private val croppedHeight: Int,
    private val preparedWidth: Int,
    private val preparedHeight: Int,
) {
    private val rotatedWidth = if (rotationDegrees % 180 == 0) decodedWidth else decodedHeight
    private val rotatedHeight = if (rotationDegrees % 180 == 0) decodedHeight else decodedWidth

    private val coordinates =
        ImageCoordinateTransformer.create(
            sourceWidth = decodedWidth,
            sourceHeight = decodedHeight,
            rotationDegrees = rotationDegrees,
            cropLeft = cropLeft.toFloat() / rotatedWidth,
            cropTop = cropTop.toFloat() / rotatedHeight,
            cropRight = (cropLeft + croppedWidth).toFloat() / rotatedWidth,
            cropBottom = (cropTop + croppedHeight).toFloat() / rotatedHeight,
        )

    fun toOriginal(rect: OcrPixelRect): OcrPixelRect {
        val corners =
            listOf(
                toOriginal(OcrPoint(rect.left.toFloat(), rect.top.toFloat())),
                toOriginal(OcrPoint(rect.right.toFloat(), rect.top.toFloat())),
                toOriginal(OcrPoint(rect.right.toFloat(), rect.bottom.toFloat())),
                toOriginal(OcrPoint(rect.left.toFloat(), rect.bottom.toFloat())),
            )
        return OcrPixelRect(
            left = floor(corners.minOf(OcrPoint::x)).toInt().coerceIn(0, originalWidth),
            top = floor(corners.minOf(OcrPoint::y)).toInt().coerceIn(0, originalHeight),
            right = ceil(corners.maxOf(OcrPoint::x)).toInt().coerceIn(0, originalWidth),
            bottom = ceil(corners.maxOf(OcrPoint::y)).toInt().coerceIn(0, originalHeight),
        )
    }

    private fun toOriginal(point: OcrPoint): OcrPoint {
        val decoded =
            coordinates.croppedToSource(
                ImagePoint(
                    x = point.x * croppedWidth / preparedWidth,
                    y = point.y * croppedHeight / preparedHeight,
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
        val cropRect = coordinates.enclosingPixelCrop().toOcrRect()
        val sourceRegion =
            coordinates
                .rotatedToSource(
                    ImageRect(
                        cropRect.left.toFloat(),
                        cropRect.top.toFloat(),
                        cropRect.right.toFloat(),
                        cropRect.bottom.toFloat(),
                    ),
                ).toAndroidRect(bounds.outWidth, bounds.outHeight)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(cropRect.right - cropRect.left, cropRect.bottom - cropRect.top)
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
                        rotationDegrees = input.rotationDegrees,
                        cropLeft = cropRect.left,
                        cropTop = cropRect.top,
                        croppedWidth = cropRect.right - cropRect.left,
                        croppedHeight = cropRect.bottom - cropRect.top,
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

    private fun ImagePixelRect.toOcrRect() = OcrPixelRect(left, top, right, bottom)

    private fun ImageRect.toAndroidRect(
        sourceWidth: Int,
        sourceHeight: Int,
    ): Rect {
        val pixelLeft = floor(left).toInt().coerceIn(0, sourceWidth - 1)
        val pixelTop = floor(top).toInt().coerceIn(0, sourceHeight - 1)
        val pixelRight = ceil(right).toInt().coerceIn(pixelLeft + 1, sourceWidth)
        val pixelBottom = ceil(bottom).toInt().coerceIn(pixelTop + 1, sourceHeight)
        return Rect(pixelLeft, pixelTop, pixelRight, pixelBottom)
    }

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
