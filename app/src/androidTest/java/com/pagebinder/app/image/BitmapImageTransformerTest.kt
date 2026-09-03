package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pagebinder.app.domain.PageCrop
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BitmapImageTransformerTest {
    @Test
    fun appliesClockwiseRotationThenCrop() {
        val source = patternedBitmap(width = 6, height = 4)

        val transformed =
            BitmapImageTransformer.transform(
                source = source,
                rotationDegrees = 90,
                crop = PageCrop(left = 0.25f, top = 0f, right = 0.75f, bottom = 1f),
            )

        assertEquals(2, transformed.width)
        assertEquals(6, transformed.height)
        assertEquals(source.getPixel(0, 2), transformed.getPixel(0, 0))
        assertEquals(source.getPixel(0, 1), transformed.getPixel(1, 0))
        assertFalse(source.isRecycled)

        transformed.recycle()
        source.recycle()
    }

    @Test
    fun transformLeavesOriginalImageFileBytesUnchanged() {
        val cacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val originalFile = File.createTempFile("pagebinder-original-", ".png", cacheDir)
        val source = patternedBitmap(width = 8, height = 6)
        originalFile.outputStream().use { output ->
            check(source.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        source.recycle()
        val bytesBefore = originalFile.readBytes()
        val decoded = requireNotNull(BitmapFactory.decodeFile(originalFile.absolutePath))

        val transformed =
            BitmapImageTransformer.transform(
                source = decoded,
                rotationDegrees = 270,
                crop = PageCrop(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f),
            )

        assertArrayEquals(bytesBefore, originalFile.readBytes())
        assertFalse(decoded.isRecycled)
        assertEquals(8, decoded.width)
        assertEquals(6, decoded.height)

        transformed.recycle()
        decoded.recycle()
        check(originalFile.delete())
    }

    @Test
    fun transformUsesSharedIntegerBoundsForNonIntegerCropEdges() {
        val source = coordinateBitmap(width = 100, height = 100)

        listOf(
            CropCase(0.123f, 0.876f, ImagePixelRect(left = 12, top = 0, right = 88, bottom = 100)),
            CropCase(0.53f, 1f, ImagePixelRect(left = 53, top = 0, right = 100, bottom = 100)),
            CropCase(0.129995f, 0.870005f, ImagePixelRect(left = 12, top = 0, right = 88, bottom = 100)),
        ).forEach { (left, right, expectedBounds) ->
            val transformed =
                BitmapImageTransformer.transform(
                    source = source,
                    rotationDegrees = 0,
                    crop = PageCrop(left = left, right = right),
                )

            assertEquals(expectedBounds.width, transformed.width)
            assertEquals(expectedBounds.height, transformed.height)
            assertEquals(source.getPixel(expectedBounds.left, expectedBounds.top), transformed.getPixel(0, 0))
            assertEquals(
                source.getPixel(expectedBounds.right - 1, expectedBounds.top),
                transformed.getPixel(transformed.width - 1, 0),
            )
            assertEquals(
                source.getPixel(expectedBounds.left, expectedBounds.bottom - 1),
                transformed.getPixel(0, transformed.height - 1),
            )
            assertEquals(
                source.getPixel(expectedBounds.right - 1, expectedBounds.bottom - 1),
                transformed.getPixel(transformed.width - 1, transformed.height - 1),
            )
            transformed.recycle()
        }

        source.recycle()
    }

    private fun patternedBitmap(
        width: Int,
        height: Int,
    ): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setPixel(x, y, Color.rgb(x * 20, y * 30, (x + y) * 10))
                }
            }
        }

    private fun coordinateBitmap(
        width: Int,
        height: Int,
    ): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setPixel(x, y, Color.argb(255, x, y, 0))
                }
            }
        }

    private data class CropCase(
        val left: Float,
        val right: Float,
        val expectedBounds: ImagePixelRect,
    )
}
