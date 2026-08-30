package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
        val first = articlePageFixture()
        val second = articlePageFixture()

        val firstHash = BitmapPerceptualHash.calculate(first)
        val secondHash = BitmapPerceptualHash.calculate(second)

        assertEquals(0, BitmapPerceptualHash.distance(firstHash, secondHash))
        assertTrue(BitmapPerceptualHash.isDuplicate(firstHash, secondHash))
        first.recycle()
        second.recycle()
    }

    @Test
    fun slightlyChangedPageFixturesHaveSmallDistanceAndAreDuplicates() {
        val pairs =
            listOf(
                articlePageFixture() to articlePageFixture(withReaderMark = true),
                diagramPageFixture() to diagramPageFixture(withReaderMark = true),
                tablePageFixture() to tablePageFixture(withReaderMark = true),
            )

        val distances =
            pairs.map { (original, changed) ->
                val originalHash = BitmapPerceptualHash.calculate(original)
                val changedHash = BitmapPerceptualHash.calculate(changed)
                val distance = BitmapPerceptualHash.distance(originalHash, changedHash)
                assertTrue("reader-mark distance=$distance", distance in 0..5)
                assertTrue(BitmapPerceptualHash.isDuplicate(originalHash, changedHash))
                original.recycle()
                changed.recycle()
                distance
            }

        println("perceptual-hash slight-change distances=$distances")
        assertTrue("at least one local change must affect the hash", distances.any { it > 0 })
    }

    @Test
    fun realisticallyDifferentPageFixturesHaveLargeDistanceAndAreNotDuplicates() {
        val pages = listOf(articlePageFixture(), diagramPageFixture(), tablePageFixture())
        val namedHashes =
            listOf("article", "diagram", "table").zip(pages.map(BitmapPerceptualHash::calculate))

        val distances =
            namedHashes.flatMapIndexed { firstIndex, (firstName, firstHash) ->
                namedHashes.drop(firstIndex + 1).map { (secondName, secondHash) ->
                    val distance = BitmapPerceptualHash.distance(firstHash, secondHash)
                    assertTrue("$firstName/$secondName distance=$distance", distance > 5)
                    assertFalse(BitmapPerceptualHash.isDuplicate(firstHash, secondHash))
                    "$firstName/$secondName=$distance"
                }
            }

        println("perceptual-hash different-page distances=$distances")
        pages.forEach(Bitmap::recycle)
    }

    @Test
    fun calculationDoesNotModifyOrRecycleSourceBitmap() {
        val source = articlePageFixture(withReaderMark = true)
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

    private fun articlePageFixture(withReaderMark: Boolean = false): Bitmap =
        bookPage { canvas, ink, muted ->
            ink.textSize = 46f
            ink.isFakeBoldText = true
            canvas.drawText("A Quiet Morning", PAGE_LEFT, 110f, ink)
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
                canvas.drawText(line, PAGE_LEFT, 185f + index * 66f, ink)
            }
            canvas.drawRect(92f, 885f, 628f, 891f, muted)
            ink.textSize = 20f
            canvas.drawText("12", 345f, 1032f, ink)
            if (withReaderMark) {
                muted.style = Paint.Style.STROKE
                muted.strokeWidth = 3f
                canvas.drawCircle(650f, 447f, 10f, muted)
                canvas.drawLine(643f, 454f, 657f, 440f, muted)
                muted.style = Paint.Style.FILL
            }
        }

    private fun diagramPageFixture(withReaderMark: Boolean = false): Bitmap =
        bookPage { canvas, ink, muted ->
            ink.textSize = 42f
            ink.isFakeBoldText = true
            canvas.drawText("How Water Returns", PAGE_LEFT, 105f, ink)
            ink.isFakeBoldText = false
            ink.textSize = 23f
            canvas.drawText("Heat moves water through a cycle shared by land, sea, and air.", PAGE_LEFT, 158f, ink)

            muted.style = Paint.Style.STROKE
            muted.strokeWidth = 6f
            canvas.drawOval(RectF(95f, 245f, 315f, 420f), muted)
            canvas.drawRect(430f, 225f, 640f, 415f, muted)
            canvas.drawLine(315f, 330f, 430f, 270f, ink)
            canvas.drawLine(315f, 350f, 430f, 385f, ink)
            muted.style = Paint.Style.FILL
            ink.textSize = 28f
            canvas.drawText("OCEAN", 145f, 342f, ink)
            canvas.drawText("CLOUD", 477f, 325f, ink)

            listOf(
                "1  Sunlight warms the surface and water evaporates.",
                "2  Cooling air turns vapor into small droplets.",
                "3  Rain returns fresh water to rivers and soil.",
                "4  Streams carry it downhill and back to the ocean.",
            ).forEachIndexed { index, line ->
                canvas.drawText(line, 110f, 545f + index * 92f, ink)
            }
            canvas.drawRect(85f, 505f, 96f, 840f, muted)
            ink.textSize = 20f
            canvas.drawText("37", 345f, 1032f, ink)
            if (withReaderMark) {
                muted.strokeWidth = 4f
                canvas.drawLine(386f, 831f, 645f, 831f, muted)
            }
        }

    private fun tablePageFixture(withReaderMark: Boolean = false): Bitmap =
        bookPage { canvas, ink, muted ->
            ink.textSize = 44f
            ink.isFakeBoldText = true
            canvas.drawText("Field Notes", PAGE_LEFT, 108f, ink)
            ink.isFakeBoldText = false
            ink.textSize = 22f
            canvas.drawText("Observations collected along the northern trail", PAGE_LEFT, 155f, ink)

            val left = 75f
            val top = 235f
            val right = 645f
            val bottom = 785f
            muted.style = Paint.Style.STROKE
            muted.strokeWidth = 5f
            canvas.drawRect(left, top, right, bottom, muted)
            listOf(345f, 455f, 565f, 675f).forEach { y -> canvas.drawLine(left, y, right, y, muted) }
            listOf(255f, 460f).forEach { x -> canvas.drawLine(x, top, x, bottom, muted) }
            muted.style = Paint.Style.FILL
            canvas.drawRect(left, top, right, 345f, muted)
            ink.color = Color.WHITE
            ink.textSize = 24f
            canvas.drawText("TIME", 112f, 300f, ink)
            canvas.drawText("WEATHER", 282f, 300f, ink)
            canvas.drawText("BIRDS", 510f, 300f, ink)
            ink.color = Color.rgb(35, 38, 42)
            listOf(
                listOf("06:30", "fog", "3"),
                listOf("08:10", "clear", "7"),
                listOf("11:45", "wind", "4"),
                listOf("14:20", "rain", "1"),
            ).forEachIndexed { row, cells ->
                val y = 412f + row * 110f
                canvas.drawText(cells[0], 110f, y, ink)
                canvas.drawText(cells[1], 300f, y, ink)
                canvas.drawText(cells[2], 545f, y, ink)
            }
            canvas.drawRect(100f, 875f, 590f, 881f, muted)
            canvas.drawRect(100f, 918f, 495f, 924f, muted)
            ink.textSize = 20f
            canvas.drawText("58", 345f, 1032f, ink)
            if (withReaderMark) {
                muted.style = Paint.Style.STROKE
                muted.strokeWidth = 4f
                canvas.drawOval(RectF(535f, 702f, 588f, 746f), muted)
                muted.style = Paint.Style.FILL
            }
        }

    private fun bookPage(drawContent: (Canvas, Paint, Paint) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 249, 245))
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 38, 42) }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(130, 137, 142) }
        drawContent(canvas, ink, muted)
        return bitmap
    }

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { pixels ->
            getPixels(pixels, 0, width, 0, 0, width, height)
        }

    private companion object {
        const val PAGE_WIDTH = 720
        const val PAGE_HEIGHT = 1080
        const val PAGE_LEFT = 62f
    }
}
