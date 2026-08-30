package com.pagebinder.app.image

/** A top-left-origin exclusion rectangle in normalized image coordinates. */
data class NormalizedImageRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "Exclusion coordinates must be finite"
        }
        require(listOf(left, top, right, bottom).all { it in 0f..1f }) {
            "Exclusion coordinates must be normalized"
        }
        require(left < right && top < bottom) { "An exclusion region must have a positive area" }
    }

    internal fun containsPixelCenter(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val normalizedX = (x + PIXEL_CENTER_OFFSET) / width
        val normalizedY = (y + PIXEL_CENTER_OFFSET) / height
        return normalizedX >= left && normalizedX < right && normalizedY >= top && normalizedY < bottom
    }

    private companion object {
        const val PIXEL_CENTER_OFFSET = 0.5f
    }
}

/** Calculates mean absolute luma difference between low-resolution capture frames. */
object BitmapFrameDifference {
    /**
     * A distance at most 4.0 is considered stable by the continuous-capture state machine.
     *
     * Android Canvas page fixtures measured 0.0 for identical pages, 0.9375 for a status-only
     * battery/time change before exclusion, and 8.474609375 for realistic article/diagram pages.
     * Four lies between the observed incidental-region and different-page distances.
     */
    const val STABLE_DISTANCE_THRESHOLD = 4.0

    /**
     * Returns mean absolute BT.601 luma difference on the 0..255 scale.
     *
     * Exclusions use the same top-left-origin, normalized coordinate convention as image crops.
     * Pixels are selected by their centers so the result does not depend on source resolution.
     */
    fun distance(
        first: LowResolutionGrayscaleImage,
        second: LowResolutionGrayscaleImage,
        excludedRegions: List<NormalizedImageRegion> = emptyList(),
    ): Double {
        require(first.width == second.width && first.height == second.height) {
            "Frame dimensions must match"
        }

        var includedPixelCount = 0
        var absoluteDifferenceSum = 0L
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (excludedRegions.any { it.containsPixelCenter(x, y, first.width, first.height) }) {
                    continue
                }
                val index = y * first.width + x
                absoluteDifferenceSum +=
                    kotlin.math.abs(first.luminances[index] - second.luminances[index])
                includedPixelCount += 1
            }
        }

        require(includedPixelCount > 0) { "Exclusion regions must leave pixels to compare" }
        return absoluteDifferenceSum.toDouble() / includedPixelCount
    }
}
