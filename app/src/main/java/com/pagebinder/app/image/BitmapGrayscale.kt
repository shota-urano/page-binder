package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/** A fixed-size grayscale sample used by pHash and continuous-capture frame comparison. */
class LowResolutionGrayscaleImage internal constructor(
    internal val luminances: IntArray,
) {
    val width: Int
        get() = LOW_RESOLUTION_SAMPLE_SIZE

    val height: Int
        get() = LOW_RESOLUTION_SAMPLE_SIZE
}

/** Creates PageBinder's shared low-resolution grayscale representation without changing [source]. */
object BitmapGrayscale {
    fun createLowResolution(source: Bitmap): LowResolutionGrayscaleImage {
        check(!source.isRecycled) { "Source bitmap must not be recycled" }
        val sample =
            Bitmap.createScaledBitmap(
                source,
                LOW_RESOLUTION_SAMPLE_SIZE,
                LOW_RESOLUTION_SAMPLE_SIZE,
                true,
            )
        try {
            val pixels = IntArray(LOW_RESOLUTION_SAMPLE_SIZE * LOW_RESOLUTION_SAMPLE_SIZE)
            sample.getPixels(
                pixels,
                0,
                LOW_RESOLUTION_SAMPLE_SIZE,
                0,
                0,
                LOW_RESOLUTION_SAMPLE_SIZE,
                LOW_RESOLUTION_SAMPLE_SIZE,
            )
            return LowResolutionGrayscaleImage(
                pixels.mapToIntArray { pixel -> pixel.bt601Luminance().roundToInt() },
            )
        } finally {
            if (sample !== source) sample.recycle()
        }
    }
}

/** ITU-R BT.601 luma on the 0..255 scale. */
internal fun Int.bt601Luminance(): Double =
    (
        RED_LUMINANCE_WEIGHT * Color.red(this) +
            GREEN_LUMINANCE_WEIGHT * Color.green(this) +
            BLUE_LUMINANCE_WEIGHT * Color.blue(this)
    ) / LUMINANCE_WEIGHT_SCALE

private inline fun IntArray.mapToIntArray(transform: (Int) -> Int): IntArray =
    IntArray(size) { index -> transform(this[index]) }

internal const val LOW_RESOLUTION_SAMPLE_SIZE = 32

private const val RED_LUMINANCE_WEIGHT = 299
private const val GREEN_LUMINANCE_WEIGHT = 587
private const val BLUE_LUMINANCE_WEIGHT = 114
private const val LUMINANCE_WEIGHT_SCALE = 1_000.0
