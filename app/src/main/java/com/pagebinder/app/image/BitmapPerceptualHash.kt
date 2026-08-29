package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.Color

/** Computes a 64-bit dHash for approximate image comparison without modifying the source bitmap. */
object BitmapPerceptualHash {
    /**
     * Returns the dHash as 16 lowercase hexadecimal characters for storage in Page.perceptualHash.
     *
     * Scaling or pixel access failures are propagated to the caller. The source bitmap remains
     * untouched so the capture can be retried or reported as an image conversion failure.
     */
    fun calculate(bitmap: Bitmap): String {
        val sample =
            Bitmap.createScaledBitmap(
                bitmap,
                SAMPLE_WIDTH,
                SAMPLE_HEIGHT,
                true,
            )
        try {
            val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
            sample.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)

            var hash = 0L
            for (y in 0 until SAMPLE_HEIGHT) {
                val rowStart = y * SAMPLE_WIDTH
                for (x in 0 until HASH_WIDTH) {
                    hash = hash shl 1
                    if (pixels[rowStart + x].luminance() > pixels[rowStart + x + 1].luminance()) {
                        hash = hash or 1L
                    }
                }
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

    private const val HASH_WIDTH = 8
    private const val SAMPLE_WIDTH = HASH_WIDTH + 1
    private const val SAMPLE_HEIGHT = 8
    private const val HASH_HEX_LENGTH = 16
    private const val HEX_RADIX = 16

    // The specification leaves this to implementation-time measurement. Android fixture
    // calibration gives distance 1 for a localized page change and 64 for a different page;
    // 5 keeps the former duplicate while leaving a wide boundary before the latter.
    private const val DUPLICATE_DISTANCE_THRESHOLD = 5

    private const val RED_LUMINANCE_WEIGHT = 299
    private const val GREEN_LUMINANCE_WEIGHT = 587
    private const val BLUE_LUMINANCE_WEIGHT = 114
}
