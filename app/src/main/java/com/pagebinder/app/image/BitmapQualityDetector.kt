package com.pagebinder.app.image

import android.graphics.Bitmap

data class BitmapQualityResult(
    /** Mean BT.601 luma on the 0..255 scale. */
    val meanLuminance: Double,
    /** Population variance of BT.601 luma. */
    val luminanceVariance: Double,
    val isBlack: Boolean,
    val isSolidColor: Boolean,
) {
    /** Black and solid-color captures follow the same isolation path (FR-IMG-003). */
    val shouldIsolate: Boolean
        get() = isBlack || isSolidColor
}

/** Detects black or solid-color capture failures without modifying the source bitmap. */
object BitmapQualityDetector {
    /**
     * At most 8/255 mean luma tolerates small capture and encoder noise around a black frame.
     */
    const val BLACK_MEAN_LUMINANCE_THRESHOLD = 8.0

    /**
     * Variance at most 4 (standard deviation at most 2 luma levels) treats encoder noise as solid.
     */
    const val SOLID_LUMINANCE_VARIANCE_THRESHOLD = 4.0

    fun analyze(bitmap: Bitmap): BitmapQualityResult {
        var count = 0L
        var mean = 0.0
        var sumSquaredDeviations = 0.0
        val row = IntArray(bitmap.width)

        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (pixel in row) {
                val luminance = pixel.luminance()
                count += 1
                val delta = luminance - mean
                mean += delta / count
                val deltaFromUpdatedMean = luminance - mean
                sumSquaredDeviations += delta * deltaFromUpdatedMean
            }
        }

        val variance = sumSquaredDeviations / count
        return BitmapQualityResult(
            meanLuminance = mean,
            luminanceVariance = variance,
            isBlack = mean <= BLACK_MEAN_LUMINANCE_THRESHOLD,
            isSolidColor = variance <= SOLID_LUMINANCE_VARIANCE_THRESHOLD,
        )
    }

    private fun Int.luminance(): Double = bt601Luminance()
}
