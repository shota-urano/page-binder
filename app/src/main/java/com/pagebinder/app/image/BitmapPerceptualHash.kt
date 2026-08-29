package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.Color

/** Computes a 64-bit pHash for approximate image comparison without modifying the source bitmap. */
object BitmapPerceptualHash {
    /**
     * Returns the pHash as 16 lowercase hexadecimal characters for storage in Page.perceptualHash.
     *
     * Scaling or pixel access failures are propagated to the caller. The source bitmap remains
     * untouched so the capture can be retried or reported as an image conversion failure.
     */
    fun calculate(bitmap: Bitmap): String {
        val sample =
            Bitmap.createScaledBitmap(
                bitmap,
                SAMPLE_SIZE,
                SAMPLE_SIZE,
                true,
            )
        try {
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            sample.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            val coefficients = lowFrequencyDct(pixels)
            val comparisonCoefficients = coefficients.drop(1).sorted()
            val median = comparisonCoefficients[comparisonCoefficients.size / 2]

            var hash = 0L
            coefficients.forEach { coefficient ->
                hash = hash shl 1
                if (coefficient > median) hash = hash or 1L
            }
            return java.lang.Long.toUnsignedString(hash, HEX_RADIX).padStart(HASH_HEX_LENGTH, '0')
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    /** Returns the Hamming distance between two 64-bit dHashes, in the range 0..64. */
    fun distance(
        first: String,
        second: String,
    ): Int = java.lang.Long.bitCount(first.toHashBits() xor second.toHashBits())

    /** Whether the hashes are close enough to warn that the latter page may be a duplicate. */
    fun isDuplicate(
        first: String,
        second: String,
    ): Boolean = distance(first, second) <= DUPLICATE_DISTANCE_THRESHOLD

    private fun String.toHashBits(): Long {
        require(length == HASH_HEX_LENGTH && all { it.isHexDigit() }) {
            "A perceptual hash must contain exactly $HASH_HEX_LENGTH hexadecimal characters"
        }
        return java.lang.Long.parseUnsignedLong(this, HEX_RADIX)
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Int.luminance(): Int =
        RED_LUMINANCE_WEIGHT * Color.red(this) +
            GREEN_LUMINANCE_WEIGHT * Color.green(this) +
            BLUE_LUMINANCE_WEIGHT * Color.blue(this)

    private fun lowFrequencyDct(pixels: IntArray): List<Double> {
        return buildList(LOW_FREQUENCY_COEFFICIENT_COUNT) {
            for (verticalFrequency in 0 until HASH_SIZE) {
                for (horizontalFrequency in 0 until HASH_SIZE) {
                    var coefficient = 0.0
                    for (y in 0 until SAMPLE_SIZE) {
                        val verticalCosine = cosineTable[y][verticalFrequency]
                        for (x in 0 until SAMPLE_SIZE) {
                            coefficient +=
                                pixels[y * SAMPLE_SIZE + x].luminance() *
                                cosineTable[x][horizontalFrequency] *
                                verticalCosine
                        }
                    }
                    add(coefficient)
                }
            }
        }
    }

    private const val SAMPLE_SIZE = 32
    private const val HASH_SIZE = 8
    private const val LOW_FREQUENCY_COEFFICIENT_COUNT = HASH_SIZE * HASH_SIZE
    private const val HASH_HEX_LENGTH = 16
    private const val HEX_RADIX = 16

    // Android Canvas book-page fixtures measured slight changes at [2, 0, 0] and unrelated page
    // layouts at [28, 30, 30]. Five leaves 3 bits of headroom above the observed duplicate maximum
    // and 23 bits below the observed unrelated-page minimum.
    private const val DUPLICATE_DISTANCE_THRESHOLD = 5

    private val cosineTable =
        Array(SAMPLE_SIZE) { position ->
            DoubleArray(HASH_SIZE) { frequency ->
                kotlin.math.cos(
                    Math.PI * (2 * position + 1) * frequency / (2.0 * SAMPLE_SIZE),
                )
            }
        }

    private const val RED_LUMINANCE_WEIGHT = 299
    private const val GREEN_LUMINANCE_WEIGHT = 587
    private const val BLUE_LUMINANCE_WEIGHT = 114
}
