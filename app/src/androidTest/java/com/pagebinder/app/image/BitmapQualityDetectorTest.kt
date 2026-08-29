package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapQualityDetectorTest {
    @Test
    fun blackFixtureIsIsolated() {
        val fixture = solidFixture(Color.BLACK)

        val result = BitmapQualityDetector.analyze(fixture)

        assertEquals(0.0, result.meanLuminance, TOLERANCE)
        assertEquals(0.0, result.luminanceVariance, TOLERANCE)
        assertTrue(result.isBlack)
        assertTrue(result.shouldIsolate)
        fixture.recycle()
    }

    @Test
    fun solidColorFixtureIsIsolatedWithoutBeingBlack() {
        val fixture = solidFixture(Color.rgb(40, 120, 220))

        val result = BitmapQualityDetector.analyze(fixture)

        assertFalse(result.isBlack)
        assertEquals(0.0, result.luminanceVariance, TOLERANCE)
        assertTrue(result.isSolidColor)
        assertTrue(result.shouldIsolate)
        fixture.recycle()
    }

    @Test
    fun normalImageFixtureIsNotIsolated() {
        val fixture = normalPageFixture()

        val result = BitmapQualityDetector.analyze(fixture)

        assertTrue(result.meanLuminance > BitmapQualityDetector.BLACK_MEAN_LUMINANCE_THRESHOLD)
        assertTrue(result.luminanceVariance > BitmapQualityDetector.SOLID_LUMINANCE_VARIANCE_THRESHOLD)
        assertFalse(result.isBlack)
        assertFalse(result.isSolidColor)
        assertFalse(result.shouldIsolate)
        fixture.recycle()
    }

    @Test
    fun noisyNearBlackFixtureIsDetectedByMeanLuminance() {
        val fixture =
            bitmapFixture { x, _ ->
                if (x % 2 == 0) Color.BLACK else Color.rgb(16, 16, 16)
            }

        val result = BitmapQualityDetector.analyze(fixture)

        assertEquals(BitmapQualityDetector.BLACK_MEAN_LUMINANCE_THRESHOLD, result.meanLuminance, TOLERANCE)
        assertTrue(result.luminanceVariance > BitmapQualityDetector.SOLID_LUMINANCE_VARIANCE_THRESHOLD)
        assertTrue(result.isBlack)
        assertFalse(result.isSolidColor)
        assertTrue(result.shouldIsolate)
        fixture.recycle()
    }

    private fun solidFixture(color: Int): Bitmap = bitmapFixture { _, _ -> color }

    private fun normalPageFixture(): Bitmap =
        bitmapFixture { x, y ->
            when {
                y < FIXTURE_HEIGHT / 4 -> Color.rgb(240, 240, 232)
                x in 3..12 && y % 4 == 0 -> Color.rgb(24, 24, 24)
                x > FIXTURE_WIDTH * 3 / 4 -> Color.rgb(48, 92, 180)
                else -> Color.WHITE
            }
        }

    private fun bitmapFixture(pixelAt: (x: Int, y: Int) -> Int): Bitmap {
        val pixels =
            IntArray(FIXTURE_WIDTH * FIXTURE_HEIGHT) { index ->
                pixelAt(index % FIXTURE_WIDTH, index / FIXTURE_WIDTH)
            }
        return Bitmap.createBitmap(pixels, FIXTURE_WIDTH, FIXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val FIXTURE_WIDTH = 16
        const val FIXTURE_HEIGHT = 12
        const val TOLERANCE = 0.000_001
    }
}
