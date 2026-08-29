package com.pagebinder.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.domain.OcrCrop
import com.pagebinder.app.domain.OcrImageSource
import com.pagebinder.app.domain.OcrInput
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@RunWith(AndroidJUnit4::class)
class MlKitOcrGatewayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val typeface by lazy { Typeface.createFromAsset(context.assets, FONT_ASSET) }

    @Test
    fun fixtureProducesSchemaVersionOneBlocksInOriginalImageCoordinates() =
        runBlocking {
            val upright = fixtureBitmap()
            val original = upright.rotate(270)
            upright.recycle()
            val encoded = original.toPng()
            val originalWidth = original.width
            val originalHeight = original.height
            original.recycle()

            val output =
                MlKitOcrGateway().use { gateway ->
                    gateway.recognize(
                        OcrInput(
                            image = OcrImageSource { ByteArrayInputStream(encoded) },
                            rotationDegrees = 90,
                            crop = OcrCrop(left = 0.05f, top = 0.05f, right = 0.95f, bottom = 0.95f),
                        ),
                    )
                }

            assertTrue(output.fullText.contains("PageBinder"))
            assertEquals("mlkit-text-recognition-v2-japanese:16.0.1", output.engineVersion)
            assertTrue(output.sourceImageHash.matches(Regex("[0-9a-f]{64}")))

            val root = JSONObject(output.blocksJson)
            assertEquals(1, root.getInt("schemaVersion"))
            val blocks = root.getJSONArray("blocks")
            assertFalse(blocks.length() == 0)
            blocks.assertIndexedSchema(originalWidth, originalHeight)
        }

    @Test
    fun highResolutionInputIsShrunkWithoutChangingAspectRatio() {
        val source = Bitmap.createBitmap(3000, 1000, Bitmap.Config.ARGB_8888)
        Canvas(source).drawColor(Color.WHITE)
        val encoded = source.toPng()
        source.recycle()

        val prepared =
            OcrImagePreprocessor.prepare(
                OcrInput(image = OcrImageSource { ByteArrayInputStream(encoded) }),
            )
        try {
            assertEquals(2048, prepared.bitmap.width)
            assertEquals(683, prepared.bitmap.height)
        } finally {
            prepared.bitmap.recycle()
        }
    }

    @Test
    fun smallCropOfLargeImageKeepsCropResolutionBeforeOcrShrink() {
        val encoded = solidGrayscalePng(width = 8192, height = 8192)

        val prepared =
            OcrImagePreprocessor.prepare(
                OcrInput(
                    image = OcrImageSource { ByteArrayInputStream(encoded) },
                    crop = OcrCrop(left = 0.45f, top = 0.45f, right = 0.55f, bottom = 0.55f),
                ),
            )
        try {
            assertEquals(820, prepared.bitmap.width)
            assertEquals(820, prepared.bitmap.height)
        } finally {
            prepared.bitmap.recycle()
        }
    }

    @Test
    fun preprocessingApplies180DegreeRotationThenNonDefaultCrop() {
        val source = patternedBitmap(width = 10, height = 8)
        val encoded = source.toPng()

        val prepared =
            OcrImagePreprocessor.prepare(
                OcrInput(
                    image = OcrImageSource { ByteArrayInputStream(encoded) },
                    rotationDegrees = 180,
                    crop = OcrCrop(left = 0.2f, top = 0.25f, right = 0.8f, bottom = 0.75f),
                ),
            )
        try {
            assertEquals(6, prepared.bitmap.width)
            assertEquals(4, prepared.bitmap.height)
            assertEquals(source.getPixel(7, 5), prepared.bitmap.getPixel(0, 0))
            assertEquals(source.getPixel(2, 2), prepared.bitmap.getPixel(5, 3))
        } finally {
            prepared.bitmap.recycle()
            source.recycle()
        }
    }

    @Test
    fun preprocessingApplies270DegreeRotationThenNonDefaultCrop() {
        val source = patternedBitmap(width = 10, height = 8)
        val encoded = source.toPng()

        val prepared =
            OcrImagePreprocessor.prepare(
                OcrInput(
                    image = OcrImageSource { ByteArrayInputStream(encoded) },
                    rotationDegrees = 270,
                    crop = OcrCrop(left = 0.25f, top = 0.2f, right = 0.75f, bottom = 0.8f),
                ),
            )
        try {
            assertEquals(4, prepared.bitmap.width)
            assertEquals(6, prepared.bitmap.height)
            assertEquals(source.getPixel(7, 2), prepared.bitmap.getPixel(0, 0))
            assertEquals(source.getPixel(2, 5), prepared.bitmap.getPixel(3, 5))
        } finally {
            prepared.bitmap.recycle()
            source.recycle()
        }
    }

    private fun JSONArray.assertIndexedSchema(
        originalWidth: Int,
        originalHeight: Int,
    ) {
        for (blockIndex in 0 until length()) {
            val block = getJSONObject(blockIndex)
            assertEquals(blockIndex, block.getInt("index"))
            block.requireTextAndRect(originalWidth, originalHeight)
            val lines = block.getJSONArray("lines")
            assertFalse(lines.length() == 0)
            for (lineIndex in 0 until lines.length()) {
                val line = lines.getJSONObject(lineIndex)
                assertEquals(lineIndex, line.getInt("index"))
                line.requireTextAndRect(originalWidth, originalHeight)
                val elements = line.getJSONArray("elements")
                assertFalse(elements.length() == 0)
                for (elementIndex in 0 until elements.length()) {
                    val element = elements.getJSONObject(elementIndex)
                    assertEquals(elementIndex, element.getInt("index"))
                    element.requireTextAndRect(originalWidth, originalHeight)
                }
            }
        }
    }

    private fun JSONObject.requireTextAndRect(
        originalWidth: Int,
        originalHeight: Int,
    ) {
        assertTrue(getString("text").isNotEmpty())
        val rect = getJSONObject("rect")
        val left = rect.getInt("left")
        val top = rect.getInt("top")
        val right = rect.getInt("right")
        val bottom = rect.getInt("bottom")
        assertTrue(left in 0..originalWidth)
        assertTrue(right in left..originalWidth)
        assertTrue(top in 0..originalHeight)
        assertTrue(bottom in top..originalHeight)
    }

    private fun fixtureBitmap(): Bitmap =
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                    color = Color.BLACK
                    textSize = 64f
                    typeface = this@MlKitOcrGatewayTest.typeface
                }
            canvas.drawText("PageBinder OCR 構造化結果", 120f, 500f, paint)
            canvas.drawText("日本語の文字座標を保存します", 120f, 640f, paint)
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

    private fun Bitmap.rotate(degrees: Int): Bitmap =
        Bitmap.createBitmap(
            this,
            0,
            0,
            width,
            height,
            Matrix().apply { postRotate(degrees.toFloat()) },
            true,
        )

    private fun Bitmap.toPng(): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }

    private fun solidGrayscalePng(
        width: Int,
        height: Int,
    ): ByteArray {
        val compressed =
            ByteArrayOutputStream().also { bytes ->
                DeflaterOutputStream(bytes).use { deflater ->
                    val row = ByteArray(width + 1) { 0xff.toByte() }.apply { this[0] = 0 }
                    repeat(height) { deflater.write(row) }
                }
            }.toByteArray()
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(PNG_SIGNATURE)
                output.writePngChunk(
                    "IHDR",
                    ByteArrayOutputStream().use { headerBytes ->
                        DataOutputStream(headerBytes).use { header ->
                            header.writeInt(width)
                            header.writeInt(height)
                            header.write(byteArrayOf(8, 0, 0, 0, 0))
                        }
                        headerBytes.toByteArray()
                    },
                )
                output.writePngChunk("IDAT", compressed)
                output.writePngChunk("IEND", ByteArray(0))
            }
            bytes.toByteArray()
        }
    }

    private fun DataOutputStream.writePngChunk(
        type: String,
        data: ByteArray,
    ) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(data.size)
        write(typeBytes)
        write(data)
        val crc =
            CRC32().apply {
                update(typeBytes)
                update(data)
            }
        writeInt(crc.value.toInt())
    }

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 1800
        const val FONT_ASSET = "fonts/NotoSansJP-wght.ttf"
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
