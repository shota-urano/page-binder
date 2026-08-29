package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapFrameDifferenceTest {
    @Test
    fun lowResolutionConversionUsesBt601Grayscale() {
        val source =
            Bitmap.createBitmap(
                intArrayOf(Color.rgb(100, 150, 200)),
                1,
                1,
                Bitmap.Config.ARGB_8888,
            )

        val black =
            Bitmap.createBitmap(
                intArrayOf(Color.BLACK),
                1,
                1,
                Bitmap.Config.ARGB_8888,
            )
        val grayscale = BitmapGrayscale.createLowResolution(source)

        assertEquals(32, grayscale.width)
        assertEquals(32, grayscale.height)
        assertEquals(
            141.0,
            BitmapFrameDifference.distance(
                grayscale,
                BitmapGrayscale.createLowResolution(black),
            ),
            TOLERANCE,
        )
        source.recycle()
        black.recycle()
    }

    @Test
    fun identicalPageFramesHaveZeroDistance() {
        val first = articlePageFixture()
        val second = articlePageFixture()

        val distance = frameDistance(first, second)

        println("frame-difference identical-page distance=$distance")
        assertEquals(0.0, distance, TOLERANCE)
        first.recycle()
        second.recycle()
    }

    @Test
    fun realisticallyDifferentPageFramesExceedStableThreshold() {
        val article = articlePageFixture()
        val diagram = diagramPageFixture()

        val distance = frameDistance(article, diagram)

        println(
            "frame-difference different-page distance=$distance " +
                "threshold=${BitmapFrameDifference.STABLE_DISTANCE_THRESHOLD}",
        )
        assertTrue(
            "different-page distance=$distance",
            distance > BitmapFrameDifference.STABLE_DISTANCE_THRESHOLD,
        )
        article.recycle()
        diagram.recycle()
    }

    @Test
    fun changeInsideExcludedRegionDoesNotAffectDistance() {
        val first = articlePageFixture(statusText = "09:41")
        val second = articlePageFixture(statusText = "10:28")
        val firstFrame = BitmapGrayscale.createLowResolution(first)
        val secondFrame = BitmapGrayscale.createLowResolution(second)

        val distanceWithoutExclusion = BitmapFrameDifference.distance(firstFrame, secondFrame)
        val distanceWithExclusion =
            BitmapFrameDifference.distance(
                firstFrame,
                secondFrame,
                excludedRegions = listOf(STATUS_BAR_REGION),
            )

        println(
            "frame-difference status-only without-exclusion=$distanceWithoutExclusion " +
                "with-exclusion=$distanceWithExclusion",
        )
        assertTrue("the status change must be observable", distanceWithoutExclusion > 0.0)
        assertEquals(0.0, distanceWithExclusion, TOLERANCE)
        first.recycle()
        second.recycle()
    }

    @Test
    fun excludingEverySampleIsReportedAsInvalid() {
        val page = articlePageFixture()
        val frame = BitmapGrayscale.createLowResolution(page)

        assertThrows(IllegalArgumentException::class.java) {
            BitmapFrameDifference.distance(
                frame,
                frame,
                excludedRegions = listOf(NormalizedImageRegion(0f, 0f, 1f, 1f)),
            )
        }
        page.recycle()
    }

    private fun frameDistance(
        first: Bitmap,
        second: Bitmap,
    ): Double =
        BitmapFrameDifference.distance(
            BitmapGrayscale.createLowResolution(first),
            BitmapGrayscale.createLowResolution(second),
        )

    private fun articlePageFixture(statusText: String = "09:41"): Bitmap =
        bookPage(statusText) { canvas, ink, muted ->
            ink.textSize = 46f
            ink.isFakeBoldText = true
            canvas.drawText("A Quiet Morning", PAGE_LEFT, 150f, ink)
            ink.isFakeBoldText = false
            ink.textSize = 25f
            listOf(
                "The station opened before sunrise, while the town was still asleep.",
                "A single train waited beside the platform under the pale winter sky.",
                "Mina checked the folded map and followed the river toward the old bridge.",
                "The streets grew narrower, and handwritten signs appeared above each door.",
                "At the square she found the bookshop exactly where her note had promised.",
                "Inside, tall shelves divided the room into passages of dust and warm light.",
                "The owner placed a blue volume on the desk without asking her name.",
                "Between its pages was a photograph of the same bridge, taken years ago.",
                "She turned it over and read the short message written across the back.",
                "Outside, the first train crossed the river and the town began to wake.",
            ).forEachIndexed { index, line ->
                canvas.drawText(line, PAGE_LEFT, 220f + index * 66f, ink)
            }
            canvas.drawRect(92f, 915f, 628f, 921f, muted)
            ink.textSize = 20f
            canvas.drawText("12", 345f, 1032f, ink)
        }

    private fun diagramPageFixture(): Bitmap =
        bookPage("09:41") { canvas, ink, muted ->
            ink.textSize = 42f
            ink.isFakeBoldText = true
            canvas.drawText("How Water Returns", PAGE_LEFT, 150f, ink)
            ink.isFakeBoldText = false
            ink.textSize = 23f
            canvas.drawText("Heat moves water through a cycle shared by land, sea, and air.", PAGE_LEFT, 205f, ink)

            muted.style = Paint.Style.STROKE
            muted.strokeWidth = 8f
            canvas.drawOval(RectF(80f, 280f, 315f, 475f), muted)
            canvas.drawRect(430f, 260f, 655f, 470f, muted)
            canvas.drawLine(315f, 360f, 430f, 300f, ink)
            canvas.drawLine(315f, 390f, 430f, 440f, ink)
            muted.style = Paint.Style.FILL
            ink.textSize = 28f
            canvas.drawText("OCEAN", 140f, 385f, ink)
            canvas.drawText("CLOUD", 485f, 370f, ink)

            listOf(
                "1  Sunlight warms the surface and water evaporates.",
                "2  Cooling air turns vapor into small droplets.",
                "3  Rain returns fresh water to rivers and soil.",
                "4  Streams carry it downhill and back to the ocean.",
            ).forEachIndexed { index, line ->
                canvas.drawText(line, 105f, 590f + index * 92f, ink)
            }
            canvas.drawRect(78f, 548f, 91f, 890f, muted)
            ink.textSize = 20f
            canvas.drawText("37", 345f, 1032f, ink)
        }

    private fun bookPage(
        statusText: String,
        drawContent: (Canvas, Paint, Paint) -> Unit,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 249, 245))
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 38, 42) }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(130, 137, 142) }

        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), STATUS_BAR_BOTTOM, muted)
        val statusPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 25f
                isFakeBoldText = true
            }
        canvas.drawText(statusText, 28f, 45f, statusPaint)
        statusPaint.style = Paint.Style.STROKE
        statusPaint.strokeWidth = 4f
        canvas.drawRect(570f, 7f, 690f, 57f, statusPaint)
        statusPaint.style = Paint.Style.FILL
        val batteryRight = if (statusText == "09:41") 594f else 674f
        canvas.drawRect(577f, 12f, batteryRight, 52f, statusPaint)
        drawContent(canvas, ink, muted)
        return bitmap
    }

    private companion object {
        const val PAGE_WIDTH = 720
        const val PAGE_HEIGHT = 1080
        const val PAGE_LEFT = 62f
        const val STATUS_BAR_BOTTOM = 64f
        const val TOLERANCE = 0.000_001

        val STATUS_BAR_REGION = NormalizedImageRegion(0f, 0f, 1f, 0.1f)
    }
}
