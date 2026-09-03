package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.Matrix
import com.pagebinder.app.domain.PageCrop

/** Generates a rotated/cropped derivative without modifying or recycling [source]. */
object BitmapImageTransformer {
    fun transform(
        source: Bitmap,
        rotationDegrees: Int,
        crop: PageCrop = PageCrop(),
    ): Bitmap {
        check(!source.isRecycled) { "Source bitmap must not be recycled" }
        val coordinates =
            ImageCoordinateTransformer.create(
                sourceWidth = source.width,
                sourceHeight = source.height,
                rotationDegrees = rotationDegrees,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropRight = crop.right,
                cropBottom = crop.bottom,
            )
        var derivative: Bitmap? = null
        try {
            derivative = source.rotatedCopy(rotationDegrees)
            val bounds = coordinates.pixelCropBounds
            if (
                bounds.left == 0 &&
                bounds.top == 0 &&
                bounds.right == derivative.width &&
                bounds.bottom == derivative.height
            ) {
                return derivative
            }
            val cropped =
                Bitmap.createBitmap(
                    derivative,
                    bounds.left,
                    bounds.top,
                    bounds.right - bounds.left,
                    bounds.bottom - bounds.top,
                )
            if (cropped !== derivative) derivative.recycle()
            return cropped
        } catch (error: OutOfMemoryError) {
            derivative?.takeUnless(Bitmap::isRecycled)?.recycle()
            throw error
        } catch (error: RuntimeException) {
            derivative?.takeUnless(Bitmap::isRecycled)?.recycle()
            throw error
        }
    }

    private fun Bitmap.rotatedCopy(rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) {
            return requireNotNull(copy(config ?: Bitmap.Config.ARGB_8888, false)) {
                "Source bitmap could not be copied"
            }
        }
        return Bitmap.createBitmap(
            this,
            0,
            0,
            width,
            height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            false,
        )
    }
}
