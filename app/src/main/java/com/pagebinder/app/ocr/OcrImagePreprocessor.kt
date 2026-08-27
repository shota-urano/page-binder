package com.pagebinder.app.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import com.pagebinder.app.domain.OcrCrop
import com.pagebinder.app.domain.OcrInput
import com.pagebinder.app.domain.OcrInputException
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
        val rotatedX = point.x * croppedWidth / preparedWidth + cropLeft
        val rotatedY = point.y * croppedHeight / preparedHeight + cropTop
        val decoded =
            when (rotationDegrees) {
                0 -> OcrPoint(rotatedX, rotatedY)
                90 -> OcrPoint(rotatedY, decodedHeight - rotatedX)
                180 -> OcrPoint(decodedWidth - rotatedX, decodedHeight - rotatedY)
                270 -> OcrPoint(decodedWidth - rotatedY, rotatedX)
                else -> error("Rotation was validated before coordinate mapping")
            }
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

        val rotatedWidth = if (input.rotationDegrees % 180 == 0) bounds.outWidth else bounds.outHeight
        val rotatedHeight = if (input.rotationDegrees % 180 == 0) bounds.outHeight else bounds.outWidth
        val cropRect = input.crop.toPixelRect(rotatedWidth, rotatedHeight)
        val sourceRegion = cropRect.toSourceRect(input.rotationDegrees, bounds.outWidth, bounds.outHeight)
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

    private fun OcrCrop.toPixelRect(
        width: Int,
        height: Int,
    ): OcrPixelRect {
        val pixelLeft = floor(left * width).toInt().coerceIn(0, width - 1)
        val pixelTop = floor(top * height).toInt().coerceIn(0, height - 1)
        val pixelRight = ceil(right * width).toInt().coerceIn(pixelLeft + 1, width)
        val pixelBottom = ceil(bottom * height).toInt().coerceIn(pixelTop + 1, height)
        return OcrPixelRect(pixelLeft, pixelTop, pixelRight, pixelBottom)
    }

    private fun OcrPixelRect.toSourceRect(
        rotationDegrees: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Rect =
        when (rotationDegrees) {
            0 -> Rect(left, top, right, bottom)
            90 -> Rect(top, sourceHeight - right, bottom, sourceHeight - left)
            180 -> Rect(sourceWidth - right, sourceHeight - bottom, sourceWidth - left, sourceHeight - top)
            270 -> Rect(sourceWidth - bottom, left, sourceWidth - top, right)
            else -> error("Rotation was validated before preprocessing")
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
