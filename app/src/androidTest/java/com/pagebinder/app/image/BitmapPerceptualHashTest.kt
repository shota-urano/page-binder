package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapPerceptualHashTest {
    @Test
    fun identicalImageFixturesHaveZeroDistanceAndAreDuplicates() {
        val first = pageFixture()
        val second = pageFixture()

        val firstHash = BitmapPerceptualHash.calculate(first)
        val secondHash = BitmapPerceptualHash.calculate(second)

        assertEquals(0, BitmapPerceptualHash.distance(firstHash, secondHash))
        assertTrue(BitmapPerceptualHash.isDuplicate(firstHash, secondHash))
        first.recycle()
        second.recycle()
    }

    @Test
    fun slightlyChangedImageFixturesHaveSmallDistanceAndAreDuplicates() {
        val original = pageFixture()
        val slightlyChanged = slightlyChangedPageFixture()

        val originalHash = BitmapPerceptualHash.calculate(original)
        val changedHash = BitmapPerceptualHash.calculate(slightlyChanged)
        val distance = BitmapPerceptualHash.distance(originalHash, changedHash)

        assertEquals(1, distance)
        assertTrue(BitmapPerceptualHash.isDuplicate(originalHash, changedHash))
        original.recycle()
        slightlyChanged.recycle()
    }

    @Test
    fun differentImageFixturesHaveLargeDistanceAndAreNotDuplicates() {
        val first = pageFixture()
        val different = differentPageFixture()

        val firstHash = BitmapPerceptualHash.calculate(first)
        val differentHash = BitmapPerceptualHash.calculate(different)
        val distance = BitmapPerceptualHash.distance(firstHash, differentHash)

        assertEquals(64, distance)
        assertFalse(BitmapPerceptualHash.isDuplicate(firstHash, differentHash))
        first.recycle()
        different.recycle()
    }

    @Test
    fun calculationDoesNotModifyOrRecycleSourceBitmap() {
        val source = slightlyChangedPageFixture()
        val pixelsBefore = source.pixels()

        val hash = BitmapPerceptualHash.calculate(source)

        assertTrue(hash.matches(Regex("[0-9a-f]{16}")))
        assertFalse(source.isRecycled)
        assertTrue(pixelsBefore.contentEquals(source.pixels()))
        source.recycle()
    }

    @Test
    fun invalidStoredHashIsReportedAsFailure() {
        assertThrows(IllegalArgumentException::class.java) {
            BitmapPerceptualHash.distance("not-a-hash", "0000000000000000")
        }
    }

    private fun pageFixture(): Bitmap =
        bitmapFixture { x, _ ->
            val luminance = BASE_LUMINANCE + x * LUMINANCE_STEP
            Color.rgb(luminance, luminance, luminance)
        }

    private fun slightlyChangedPageFixture(): Bitmap =
        bitmapFixture { x, y ->
            val luminance =
                if (x == CHANGED_COLUMN && y == CHANGED_ROW) {
                    CHANGED_LUMINANCE
                } else {
                    BASE_LUMINANCE + x * LUMINANCE_STEP
                }
            Color.rgb(luminance, luminance, luminance)
        }

    private fun differentPageFixture(): Bitmap =
        bitmapFixture { x, _ ->
            val luminance = BASE_LUMINANCE + (SAMPLE_WIDTH - 1 - x) * LUMINANCE_STEP
            Color.rgb(luminance, luminance, luminance)
        }

    private fun bitmapFixture(pixelAt: (x: Int, y: Int) -> Int): Bitmap {
        val pixels =
            IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT) { index ->
                pixelAt(index % SAMPLE_WIDTH, index / SAMPLE_WIDTH)
            }
        return Bitmap.createBitmap(pixels, SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { pixels ->
            getPixels(pixels, 0, width, 0, 0, width, height)
        }

    private companion object {
        const val SAMPLE_WIDTH = 9
        const val SAMPLE_HEIGHT = 8
        const val BASE_LUMINANCE = 20
        const val LUMINANCE_STEP = 20
        const val CHANGED_COLUMN = 4
        const val CHANGED_ROW = 3
        const val CHANGED_LUMINANCE = 200
    }
}
