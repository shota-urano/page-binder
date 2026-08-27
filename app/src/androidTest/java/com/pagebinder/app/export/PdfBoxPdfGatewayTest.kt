package com.pagebinder.app.export

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.domain.PdfImageSource
import com.pagebinder.app.domain.PdfInput
import com.pagebinder.app.domain.PdfMode
import com.pagebinder.app.domain.PdfPage
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PdfBoxPdfGatewayTest {
    @Test
    fun generatesSearchablePdfWithImageAndInvisibleJapaneseText() =
        runBlocking {
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<Pair<Int, Int>>()

            gateway.generate(
                input = PdfInput(listOf(page(2, validBlocksJson(SAMPLE_TEXT)), page(1, validBlocksJson("一頁目")))),
                mode = PdfMode.SEARCHABLE,
                output = output,
                reportProgress = { completed, total -> progress += completed to total },
            )

            PDDocument.load(ByteArrayInputStream(output.toByteArray())).use { document ->
                assertEquals(2, document.numberOfPages)
                assertEquals("一頁目$SAMPLE_TEXT", PDFTextStripper().getText(document).filterNot(Char::isWhitespace))
                assertTrue(document.pages.all { it.resources.xObjectNames.iterator().hasNext() })
                val page = document.getPage(0).mediaBox
                assertEquals(IMAGE_WIDTH.toFloat() / IMAGE_HEIGHT, page.width / page.height, TOLERANCE)
            }
            assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        }

    @Test
    fun imageOnlyPdfDoesNotDependOnTextLayerParsing() =
        runBlocking {
            val input = PdfInput(listOf(page(1, "not-json")))

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    gateway.generate(input, PdfMode.SEARCHABLE, ByteArrayOutputStream()) { _, _ -> }
                }
            }

            val imageOnly = ByteArrayOutputStream()
            gateway.generate(input, PdfMode.IMAGE_ONLY, imageOnly) { _, _ -> }

            PDDocument.load(ByteArrayInputStream(imageOnly.toByteArray())).use { document ->
                assertEquals(1, document.numberOfPages)
                assertTrue(document.getPage(0).resources.xObjectNames.iterator().hasNext())
                assertFalse(document.getPage(0).resources.fontNames.iterator().hasNext())
            }
        }

    @Test
    fun searchablePdfUsesEditedTextWhenStructuredCoordinatesAreUnavailable() =
        runBlocking {
            val output = ByteArrayOutputStream()
            val input =
                PdfInput(
                    listOf(
                        page(
                            sequence = 1,
                            blocksJson = null,
                            fullText = "修正前",
                            editedText = "修正後の本文",
                        ),
                    ),
                )

            gateway.generate(input, PdfMode.SEARCHABLE, output) { _, _ -> }

            PDDocument.load(ByteArrayInputStream(output.toByteArray())).use { document ->
                assertEquals("修正後の本文", PDFTextStripper().getText(document).trim())
            }
        }

    @Test
    fun cancellationDoesNotWriteIncompleteOutputOrLeaveTemporaryFile() =
        runBlocking {
            val output = ByteArrayOutputStream()
            val temporaryFilesBefore = gatewayTemporaryFiles()

            assertThrows(CancellationException::class.java) {
                runBlocking {
                    gateway.generate(
                        PdfInput(listOf(page(1, null), page(2, null))),
                        PdfMode.IMAGE_ONLY,
                        output,
                    ) { completed, _ ->
                        if (completed == 1) throw CancellationException("test cancellation")
                    }
                }
            }

            assertEquals(0, output.size())
            assertEquals(temporaryFilesBefore, gatewayTemporaryFiles())
        }

    private fun page(
        sequence: Int,
        blocksJson: String?,
        fullText: String? = null,
        editedText: String? = null,
    ): PdfPage =
        PdfPage(
            sequence = sequence,
            image = PdfImageSource { ByteArrayInputStream(imageBytes) },
            ocrBlocksJson = blocksJson,
            fullText = fullText,
            editedText = editedText,
        )

    private fun validBlocksJson(text: String): String =
        """
        {
          "schemaVersion": 1,
          "blocks": [{
            "index": 0,
            "text": "$text",
            "rect": {"left": 4, "top": 6, "right": 60, "bottom": 22},
            "lines": [{
              "index": 0,
              "text": "$text",
              "rect": {"left": 4, "top": 6, "right": 60, "bottom": 22},
              "elements": [{
                "index": 0,
                "text": "$text",
                "rect": {"left": 4, "top": 6, "right": 60, "bottom": 22}
              }]
            }]
          }]
        }
        """.trimIndent()

    private fun gatewayTemporaryFiles(): Set<String> =
        context.cacheDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("pagebinder-pdf-") && it.name.endsWith(".part") }
            .mapTo(mutableSetOf()) { it.absolutePath }

    private val gateway by lazy { PdfBoxPdfGateway(context, Dispatchers.IO) }

    private val imageBytes: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private companion object {
        const val IMAGE_WIDTH = 64
        const val IMAGE_HEIGHT = 96
        const val SAMPLE_TEXT = "日本語検索テスト"
        const val TOLERANCE = 0.0001f

        val context
            get() = InstrumentationRegistry.getInstrumentation().targetContext
    }
}
