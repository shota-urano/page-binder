package com.pagebinder.app.spike.pdfbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class PdfBoxAndroidSpikeTest {
    @Before
    fun initializePdfBox() {
        PDFBoxResourceLoader.init(targetContext)
    }

    @Test
    fun generatesOnePageWithImageAndInvisibleJapaneseText() {
        val output = artifactFile(ONE_PAGE_FILE)
        createDocument(output, pageTexts = listOf(OCR_TEXT))

        PDDocument.load(output).use { document ->
            assertEquals(1, document.numberOfPages)
            assertEquals(OCR_TEXT, PDFTextStripper().getText(document).trim())
            assertTrue(document.getPage(0).resources.xObjectNames.iterator().hasNext())

            val embeddedFontNames =
                document.getPage(0).resources.fontNames
                    .map { document.getPage(0).resources.getFont(it) }
                    .filterIsInstance<PDType0Font>()
                    .map { it.name }
            assertTrue("Expected a subset font name", embeddedFontNames.any { '+' in it })
        }
    }

    @Test
    fun measuresOneHundredPageGeneration() {
        val output = artifactFile(HUNDRED_PAGE_FILE)
        val pageTexts = List(100, ::performanceOcrText)
        val metrics = createDocument(output, pageTexts)

        PDDocument.load(output).use { document ->
            assertEquals(100, document.numberOfPages)
            pageTexts.forEachIndexed { pageIndex, expected ->
                val stripper =
                    PDFTextStripper().apply {
                        startPage = pageIndex + 1
                        endPage = pageIndex + 1
                    }
                assertEquals(expected, stripper.getText(document).filterNot(Char::isWhitespace))
            }
        }
        assertTrue(uniqueCodePointCount(pageTexts) > 100)
        assertTrue(metrics.elapsedMs > 0)
        assertTrue(metrics.fileBytes > 0)

        artifactFile(METRICS_FILE).writeText(
            buildString {
                appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("api=${android.os.Build.VERSION.SDK_INT}")
                appendLine("pages=100")
                appendLine("elapsed_ms=${metrics.elapsedMs}")
                appendLine("baseline_pss_kb=${metrics.baselinePssKb}")
                appendLine("peak_pss_kb=${metrics.peakPssKb}")
                appendLine("peak_pss_delta_kb=${metrics.peakPssKb - metrics.baselinePssKb}")
                appendLine("peak_java_heap_bytes=${metrics.peakJavaHeapBytes}")
                appendLine("file_bytes=${metrics.fileBytes}")
                appendLine("unique_code_points=${uniqueCodePointCount(pageTexts)}")
                appendLine("normalized_text_sha256=${sha256(pageTexts.joinToString(""))}")
            },
        )
    }

    private fun createDocument(
        output: File,
        pageTexts: List<String>,
    ): Metrics {
        require(pageTexts.isNotEmpty())
        output.delete()
        val rawOutput = output.resolveSibling("${output.name}.raw")
        rawOutput.delete()
        forceGc()
        val memorySampler = MemorySampler()
        val startedAt = SystemClock.elapsedRealtime()
        lateinit var exactToUnicodeCmap: String

        try {
            PDDocument().use { document ->
                val font =
                    targetContext.assets.open(FONT_ASSET).use {
                        PDType0Font.load(document, it, true)
                    }

                pageTexts.forEachIndexed { pageIndex, pageText ->
                    val page = PDPage(PAGE_SIZE)
                    document.addPage(page)
                    val bitmap = createRepresentativePageImage(pageIndex)
                    val image = JPEGFactory.createFromImage(document, bitmap, JPEG_QUALITY, JPEG_DPI)
                    bitmap.recycle()

                    PDPageContentStream(document, page).use { stream ->
                        stream.drawImage(image, 0f, 0f, PAGE_SIZE.width, PAGE_SIZE.height)
                        stream.beginText()
                        stream.setFont(font, FONT_SIZE)
                        stream.setRenderingMode(RenderingMode.NEITHER)
                        pageText.chunked(TEXT_LINE_LENGTH).forEachIndexed { lineIndex, line ->
                            stream.setTextMatrix(
                                Matrix.getTranslateInstance(TEXT_X, TEXT_Y - lineIndex * TEXT_LINE_HEIGHT),
                            )
                            stream.showText(line)
                        }
                        stream.endText()
                    }
                }
                exactToUnicodeCmap = buildExactToUnicodeCmap(font, pageTexts.joinToString(""))
                document.save(rawOutput)
            }
            PDDocument.load(rawOutput).use { document ->
                val font =
                    document.getPage(0).resources.fontNames
                        .map { document.getPage(0).resources.getFont(it) }
                        .filterIsInstance<PDType0Font>()
                        .first()
                installExactToUnicodeMap(document, font, exactToUnicodeCmap)
                document.save(output)
            }
        } finally {
            memorySampler.close()
        }
        check(rawOutput.delete()) { "Failed to delete intermediate PDF" }

        return Metrics(
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            baselinePssKb = memorySampler.baselinePssKb,
            peakPssKb = memorySampler.peakPssKb.get(),
            peakJavaHeapBytes = memorySampler.peakJavaHeapBytes.get(),
            fileBytes = output.length(),
        )
    }

    private fun createRepresentativePageImage(pageIndex: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(247, 244, 236))
        val random = Random(REPRESENTATIVE_IMAGE_SEED + pageIndex)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(55, 61, 66)
            }
        paint.color = Color.rgb(225, 219, 205)
        canvas.drawRect(70f, 55f, IMAGE_WIDTH - 70f, 78f, paint)
        repeat(42) { line ->
            val y = 100f + line * 39f
            var x = 80f
            paint.color = Color.rgb(45 + random.nextInt(24), 45 + random.nextInt(24), 45 + random.nextInt(24))
            while (x < IMAGE_WIDTH - 100f) {
                val width = 24f + random.nextInt(105)
                canvas.drawRoundRect(x, y, minOf(x + width, IMAGE_WIDTH - 80f), y + 5f, 2f, 2f, paint)
                x += width + 8f + random.nextInt(20)
            }
        }
        if (pageIndex % 4 == 0) {
            repeat(180) {
                paint.color = Color.rgb(70 + random.nextInt(150), 70 + random.nextInt(150), 70 + random.nextInt(150))
                val x = 150f + random.nextInt(780)
                val y = 320f + random.nextInt(1040)
                val size = 4f + random.nextInt(30)
                canvas.drawRect(x, y, x + size, y + size, paint)
            }
        }
        return bitmap
    }

    private fun buildExactToUnicodeCmap(
        font: PDType0Font,
        text: String,
    ): String {
        val mappings = linkedMapOf<String, String>()
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val source = String(Character.toChars(codePoint))
            val encoded =
                font.encode(source).joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0').uppercase(Locale.ROOT)
                }
            val unicode =
                source.toCharArray().joinToString("") { char ->
                    char.code.toString(16).padStart(4, '0').uppercase(Locale.ROOT)
                }
            val previous = mappings.put(encoded, unicode)
            require(previous == null || previous == unicode) {
                "One embedded glyph maps to multiple OCR code points"
            }
            offset += Character.charCount(codePoint)
        }
        return buildString {
            appendLine("/CIDInit /ProcSet findresource begin")
            appendLine("12 dict begin")
            appendLine("begincmap")
            appendLine("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def")
            appendLine("/CMapName /Adobe-Identity-UCS def")
            appendLine("/CMapType 2 def")
            appendLine("1 begincodespacerange")
            appendLine("<0000> <FFFF>")
            appendLine("endcodespacerange")
            mappings.entries.chunked(MAX_CMAP_ENTRIES_PER_BLOCK).forEach { block ->
                appendLine("${block.size} beginbfchar")
                block.forEach { (encoded, unicode) -> appendLine("<$encoded> <$unicode>") }
                appendLine("endbfchar")
            }
            appendLine("endcmap")
            appendLine("CMapName currentdict /CMap defineresource pop")
            appendLine("end")
            appendLine("end")
        }
    }

    private fun installExactToUnicodeMap(
        document: PDDocument,
        font: PDType0Font,
        cmap: String,
    ) {
        val toUnicode = PDStream(document)
        toUnicode.createOutputStream(COSName.FLATE_DECODE).use { output ->
            output.write(cmap.toByteArray(Charsets.US_ASCII))
        }
        font.cosObject.setItem(COSName.TO_UNICODE, toUnicode)
    }

    private fun artifactFile(name: String): File {
        val directory =
            requireNotNull(targetContext.getExternalFilesDir(null))
                .resolve(ARTIFACT_DIRECTORY)
                .apply { mkdirs() }
        return directory.resolve(name)
    }

    private fun forceGc() {
        Runtime.getRuntime().gc()
        System.runFinalization()
    }

    private fun performanceOcrText(pageIndex: Int): String {
        val offset = pageIndex * PERFORMANCE_TEXT_OFFSET % JAPANESE_GLYPH_CORPUS.length
        val body =
            buildString {
                repeat(PERFORMANCE_TEXT_LENGTH) { characterIndex ->
                    append(JAPANESE_GLYPH_CORPUS[(offset + characterIndex) % JAPANESE_GLYPH_CORPUS.length])
                }
            }
        return "頁番号${pageIndex + 1}。$body。"
    }

    private fun uniqueCodePointCount(texts: List<String>): Int {
        val codePoints = mutableSetOf<Int>()
        texts.forEach { text ->
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                codePoints += codePoint
                offset += Character.charCount(codePoint)
            }
        }
        return codePoints.size
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private data class Metrics(
        val elapsedMs: Long,
        val baselinePssKb: Int,
        val peakPssKb: Int,
        val peakJavaHeapBytes: Long,
        val fileBytes: Long,
    )

    private class MemorySampler : Closeable {
        val baselinePssKb = currentPssKb()
        val peakPssKb = AtomicInteger(baselinePssKb)
        val peakJavaHeapBytes = AtomicLong(usedJavaHeapBytes())
        private val running = AtomicBoolean(true)
        private val worker =
            Thread(
                {
                    while (running.get()) {
                        sample()
                        SystemClock.sleep(MEMORY_SAMPLE_INTERVAL_MS)
                    }
                },
                "pdfbox-spike-memory-sampler",
            ).apply {
                isDaemon = true
                start()
            }

        override fun close() {
            running.set(false)
            worker.join()
            sample()
        }

        private fun sample() {
            val pssKb = currentPssKb()
            peakPssKb.updateAndGet { previous -> maxOf(previous, pssKb) }
            val heapBytes = usedJavaHeapBytes()
            peakJavaHeapBytes.updateAndGet { previous -> maxOf(previous, heapBytes) }
        }

        companion object {
            private fun currentPssKb(): Int = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss

            private fun usedJavaHeapBytes(): Long {
                val runtime = Runtime.getRuntime()
                return runtime.totalMemory() - runtime.freeMemory()
            }
        }
    }

    companion object {
        private const val FONT_ASSET = "fonts/NotoSansJP-wght.ttf"
        private const val ARTIFACT_DIRECTORY = "pdfbox-spike"
        private const val ONE_PAGE_FILE = "pdfbox-spike-one-page.pdf"
        private const val HUNDRED_PAGE_FILE = "pdfbox-spike-100-pages.pdf"
        private const val METRICS_FILE = "pdfbox-spike-metrics.txt"
        private const val OCR_TEXT = "日本語の横書き検索テスト。完全オフラインで文字列を確認します。"
        private const val JAPANESE_GLYPH_CORPUS =
            "一右雨円王音下火花貝学気九休玉金空月犬見五口校左三山子四糸字耳七車手十出女小上森人水正生青夕石赤千川先早草足村大男竹中虫町天田土二日" +
                "入年白八百文木本名目立力林六引羽雲園遠何科夏家歌画回会海絵外角楽活間丸岩顔汽記帰弓牛魚京強教近兄形計元言原戸古午後語工公広交光考行高" +
                "黄合谷国黒今才細作算止市矢姉思紙寺自時室社弱首秋週春書少場色食心新親図数星晴声西切雪船線前組走多太体台地池知茶昼長鳥朝直通弟店点電刀" +
                "冬当東答頭同道読内南肉馬売買麦半番父風分聞米歩母方北毎妹万明鳴毛門夜野友用曜来里理話"
        private const val IMAGE_WIDTH = 1080
        private const val IMAGE_HEIGHT = 1920
        private const val JPEG_QUALITY = 0.82f
        private const val JPEG_DPI = 150
        private const val MEMORY_SAMPLE_INTERVAL_MS = 10L
        private const val REPRESENTATIVE_IMAGE_SEED = 0x5042474CL
        private const val FONT_SIZE = 9f
        private const val TEXT_LINE_LENGTH = 40
        private const val TEXT_LINE_HEIGHT = 13f
        private const val TEXT_X = 48f
        private const val TEXT_Y = 720f
        private const val PERFORMANCE_TEXT_LENGTH = 120
        private const val PERFORMANCE_TEXT_OFFSET = 37
        private const val MAX_CMAP_ENTRIES_PER_BLOCK = 100
        private val PAGE_SIZE = PDRectangle(432f, 768f)

        private val targetContext
            get() = InstrumentationRegistry.getInstrumentation().targetContext
    }
}
