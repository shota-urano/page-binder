package com.pagebinder.app.image

import android.graphics.Bitmap

/** Computes a 64-bit pHash for approximate image comparison without modifying the source bitmap. */
object BitmapPerceptualHash {
    /**
     * Returns the pHash as 16 lowercase hexadecimal characters for storage in Page.perceptualHash.
     *
     * Scaling or pixel access failures are propagated to the caller. The source bitmap remains
     * untouched so the capture can be retried or reported as an image conversion failure.
     */
    fun calculate(bitmap: Bitmap): String {
        val grayscale = BitmapGrayscale.createLowResolution(bitmap)
        val coefficients = lowFrequencyDct(grayscale.luminances)
        val comparisonCoefficients = coefficients.drop(1).sorted()
        val median = comparisonCoefficients[comparisonCoefficients.size / 2]

        var hash = 0L
        coefficients.forEach { coefficient ->
            hash = hash shl 1
            if (coefficient > median) hash = hash or 1L
        }
        return java.lang.Long.toUnsignedString(hash, HEX_RADIX).padStart(HASH_HEX_LENGTH, '0')
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

    private fun lowFrequencyDct(luminances: IntArray): List<Double> {
        return buildList(LOW_FREQUENCY_COEFFICIENT_COUNT) {
            for (verticalFrequency in 0 until HASH_SIZE) {
                for (horizontalFrequency in 0 until HASH_SIZE) {
                    var coefficient = 0.0
                    for (y in 0 until LOW_RESOLUTION_SAMPLE_SIZE) {
                        val verticalCosine = cosineTable[y][verticalFrequency]
                        for (x in 0 until LOW_RESOLUTION_SAMPLE_SIZE) {
                            coefficient +=
                                luminances[y * LOW_RESOLUTION_SAMPLE_SIZE + x] *
                                cosineTable[x][horizontalFrequency] *
                                verticalCosine
                        }
                    }
                    add(coefficient)
                }
            }
        }
    }

    private const val HASH_SIZE = 8
    private const val LOW_FREQUENCY_COEFFICIENT_COUNT = HASH_SIZE * HASH_SIZE
    private const val HASH_HEX_LENGTH = 16
    private const val HEX_RADIX = 16

    // Android Canvas book-page fixtures measured slight changes at [2, 0, 0] and unrelated page
    // layouts at [28, 30, 30]. Five leaves 3 bits of headroom above the observed duplicate maximum
    // and 23 bits below the observed unrelated-page minimum.
    private const val DUPLICATE_DISTANCE_THRESHOLD = 5

    private val cosineTable =
        Array(LOW_RESOLUTION_SAMPLE_SIZE) { position ->
            DoubleArray(HASH_SIZE) { frequency ->
                kotlin.math.cos(
                    Math.PI * (2 * position + 1) * frequency /
                        (2.0 * LOW_RESOLUTION_SAMPLE_SIZE),
                )
            }
        }
}
