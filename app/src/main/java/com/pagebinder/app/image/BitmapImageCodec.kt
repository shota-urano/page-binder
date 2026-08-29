package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class BitmapImageFormat {
    WEBP_LOSSLESS,
    PNG,
}

data class EncodedBitmapMetadata(
    val width: Int,
    val height: Int,
    val contentHash: String,
)

/**
 * Encodes captured bitmaps without changing the source bitmap.
 *
 * Stream lifetime and atomic file replacement belong to the caller. A successful return means
 * Android's encoder accepted the complete bitmap; failures are reported as [IOException].
 */
object BitmapImageCodec {
    @Throws(IOException::class)
    fun write(
        bitmap: Bitmap,
        format: BitmapImageFormat,
        output: OutputStream,
    ): EncodedBitmapMetadata {
        val contentHash = contentHash(bitmap)
        val compressed =
            bitmap.compress(
                format.compressFormat(),
                MAX_QUALITY,
                output,
            )
        if (!compressed) throw IOException("Bitmap encoding failed")

        return EncodedBitmapMetadata(
            width = bitmap.width,
            height = bitmap.height,
            contentHash = contentHash,
        )
    }

    @Throws(IOException::class)
    fun read(input: InputStream): Bitmap =
        BitmapFactory.decodeStream(input) ?: throw IOException("Image decoding failed")

    /** SHA-256 over dimensions and normalized ARGB pixels, independent of encoded file format. */
    fun contentHash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance(CONTENT_HASH_ALGORITHM)
        digest.update(
            ByteBuffer
                .allocate(Int.SIZE_BYTES * 2)
                .putInt(bitmap.width)
                .putInt(bitmap.height)
                .array(),
        )

        val pixels = IntArray(bitmap.width)
        val encodedRow = ByteBuffer.allocate(bitmap.width * Int.SIZE_BYTES)
        for (row in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
            encodedRow.clear()
            pixels.forEach(encodedRow::putInt)
            digest.update(encodedRow.array())
        }

        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun BitmapImageFormat.compressFormat(): Bitmap.CompressFormat =
        when (this) {
            BitmapImageFormat.WEBP_LOSSLESS -> webpLosslessFormat()
            BitmapImageFormat.PNG -> Bitmap.CompressFormat.PNG
        }

    @Suppress("DEPRECATION")
    private fun webpLosslessFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            // On Android 10 (the minimum SDK), WEBP quality 100 selects lossless encoding.
            Bitmap.CompressFormat.WEBP
        }

    private const val MAX_QUALITY = 100
    private const val CONTENT_HASH_ALGORITHM = "SHA-256"
}
