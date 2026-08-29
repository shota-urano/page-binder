package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BitmapImageCodecTest {
    @Test
    fun webpLosslessRoundTripPreservesEveryPixel() {
        val source = testBitmap()

        val (encoded, metadata) = encode(source, BitmapImageFormat.WEBP_LOSSLESS)
        val decoded = BitmapImageCodec.read(ByteArrayInputStream(encoded))

        assertEquals("RIFF", encoded.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WEBP", encoded.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals(source.width, metadata.width)
        assertEquals(source.height, metadata.height)
        assertPixelsEqual(source, decoded)
        assertEquals(metadata.contentHash, BitmapImageCodec.contentHash(decoded))

        source.recycle()
        decoded.recycle()
    }

    @Test
    fun pngCompatibilityOutputPreservesEveryPixel() {
        val source = testBitmap()

        val (encoded, metadata) = encode(source, BitmapImageFormat.PNG)
        val decoded = BitmapImageCodec.read(ByteArrayInputStream(encoded))

        assertArrayEquals(PNG_SIGNATURE, encoded.copyOfRange(0, PNG_SIGNATURE.size))
        assertPixelsEqual(source, decoded)
        assertEquals(metadata.contentHash, BitmapImageCodec.contentHash(decoded))

        source.recycle()
        decoded.recycle()
    }

    @Test
    fun contentHashIsFormatIndependentAndChangesWithPixelContent() {
        val source = testBitmap()
        val changed = testBitmap().apply { setPixel(width - 1, height - 1, Color.MAGENTA) }
        val (_, webpMetadata) = encode(source, BitmapImageFormat.WEBP_LOSSLESS)
        val (_, pngMetadata) = encode(source, BitmapImageFormat.PNG)

        assertEquals(webpMetadata.contentHash, pngMetadata.contentHash)
        assertTrue(webpMetadata.contentHash.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(webpMetadata.contentHash, BitmapImageCodec.contentHash(changed))

        source.recycle()
        changed.recycle()
    }

    @Test
    fun invalidEncodedInputIsReportedAsFailure() {
        assertThrows(IOException::class.java) {
            BitmapImageCodec.read(ByteArrayInputStream("not an image".toByteArray()))
        }
    }

    private fun encode(
        bitmap: Bitmap,
        format: BitmapImageFormat,
    ): Pair<ByteArray, EncodedBitmapMetadata> {
        val output = ByteArrayOutputStream()
        val metadata = BitmapImageCodec.write(bitmap, format, output)
        return output.toByteArray() to metadata
    }

    private fun assertPixelsEqual(
        expected: Bitmap,
        actual: Bitmap,
    ) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(actual.width * actual.height)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        assertArrayEquals(expectedPixels, actualPixels)
    }

    private fun testBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(TEST_WIDTH, TEST_HEIGHT, Bitmap.Config.ARGB_8888)
        for (y in 0 until TEST_HEIGHT) {
            for (x in 0 until TEST_WIDTH) {
                bitmap.setPixel(
                    x,
                    y,
                    Color.argb(
                        255,
                        (x * 43 + y * 11) % 256,
                        (x * 13 + y * 47) % 256,
                        (x * 31 + y * 23) % 256,
                    ),
                )
            }
        }
        return bitmap
    }

    private companion object {
        const val TEST_WIDTH = 11
        const val TEST_HEIGHT = 7
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
