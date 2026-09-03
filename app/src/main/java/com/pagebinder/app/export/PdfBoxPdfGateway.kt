package com.pagebinder.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pagebinder.app.domain.ExportPdfQuality
import com.pagebinder.app.domain.PdfGateway
import com.pagebinder.app.domain.PdfInput
import com.pagebinder.app.domain.PdfMode
import com.pagebinder.app.domain.PdfPage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.Locale

/** PDFBox-Android implementation. All PDFBox types remain confined to the export package. */
class PdfBoxPdfGateway(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PdfGateway {
    private val applicationContext = context.applicationContext
    private val fontLoader = NotoSansJpPdfFont(applicationContext.assets)

    init {
        PDFBoxResourceLoader.init(applicationContext)
    }

    override suspend fun generate(
        input: PdfInput,
        mode: PdfMode,
        output: OutputStream,
        reportProgress: suspend (completedPages: Int, totalPages: Int) -> Unit,
    ) = withContext(ioDispatcher) {
        val pages = input.pages.validatedAndOrdered()
        val totalPages = pages.size
        reportProgress(0, totalPages)

        val intermediate = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, applicationContext.cacheDir)
        try {
            val toUnicodeCMap =
                createIntermediateDocument(
                    pages,
                    input.pdfQuality,
                    mode,
                    intermediate,
                    reportProgress,
                )
            currentCoroutineContext().ensureActive()
            writeCompletedDocument(intermediate, toUnicodeCMap, output)
            currentCoroutineContext().ensureActive()
        } finally {
            check(!intermediate.exists() || intermediate.delete()) {
                "Failed to delete intermediate PDF"
            }
        }
    }

    private suspend fun createIntermediateDocument(
        pages: List<PdfPage>,
        pdfQuality: ExportPdfQuality,
        mode: PdfMode,
        intermediate: File,
        reportProgress: suspend (completedPages: Int, totalPages: Int) -> Unit,
    ): String? {
        var toUnicodeCMap: String? = null
        PDDocument().use { document ->
            val font = if (mode == PdfMode.SEARCHABLE) fontLoader.load(document) else null
            val mappedText = StringBuilder()

            pages.forEachIndexed { index, pageInput ->
                currentCoroutineContext().ensureActive()
                val bitmap = pageInput.decodeBitmap()
                try {
                    val transformer =
                        PdfCoordinateTransformer.create(
                            sourceWidth = bitmap.width,
                            sourceHeight = bitmap.height,
                            rotationDegrees = 0,
                            pageWidth = bitmap.width.toFloat(),
                        )
                    val page =
                        PDPage(
                            PDRectangle(
                                transformer.pageSize.width,
                                transformer.pageSize.height,
                            ),
                        )
                    document.addPage(page)
                    val displayBitmap = bitmap.resizedFor(pdfQuality)
                    try {
                        val image = document.createDisplayImage(displayBitmap, pdfQuality)
                        PDPageContentStream(document, page).use { stream ->
                            stream.drawImage(image, 0f, 0f, transformer.pageSize.width, transformer.pageSize.height)
                            if (font != null) {
                                val placements = pageInput.textPlacements(transformer)
                                stream.drawInvisibleText(font, placements)
                                placements.forEach { mappedText.append(it.text) }
                            }
                        }
                    } finally {
                        if (displayBitmap !== bitmap) displayBitmap.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
                currentCoroutineContext().ensureActive()
                reportProgress(index + 1, pages.size)
            }

            if (font != null && mappedText.isNotEmpty()) {
                toUnicodeCMap = buildExactToUnicodeCMap(font, mappedText.toString())
            }
            currentCoroutineContext().ensureActive()
            document.save(intermediate)
        }
        return toUnicodeCMap
    }

    private fun writeCompletedDocument(
        intermediate: File,
        toUnicodeCMap: String?,
        output: OutputStream,
    ) {
        PDDocument.load(intermediate).use { document ->
            if (toUnicodeCMap != null) {
                val embeddedFont = document.findEmbeddedType0Font()
                installExactToUnicodeMap(document, embeddedFont, toUnicodeCMap)
            }
            document.save(output)
        }
    }

    private fun PdfPage.decodeBitmap(): Bitmap =
        image.openInputStream().use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Page image could not be decoded" }
        }

    /** Applies §3.6 only to the visible image layer; it never enlarges an image. */
    private fun Bitmap.resizedFor(quality: ExportPdfQuality): Bitmap {
        val maximumLongEdge = quality.maximumLongEdge
        val longEdge = maxOf(width, height)
        if (longEdge <= maximumLongEdge) return this

        val scale = maximumLongEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun PDDocument.createDisplayImage(
        bitmap: Bitmap,
        quality: ExportPdfQuality,
    ) = when (quality) {
        ExportPdfQuality.HIGH -> LosslessFactory.createFromImage(this, bitmap)
        ExportPdfQuality.STANDARD -> JPEGFactory.createFromImage(this, bitmap, STANDARD_JPEG_QUALITY)
        ExportPdfQuality.COMPACT -> JPEGFactory.createFromImage(this, bitmap, COMPACT_JPEG_QUALITY)
    }

    private val ExportPdfQuality.maximumLongEdge: Int
        get() =
            when (this) {
                ExportPdfQuality.HIGH -> HIGH_MAX_LONG_EDGE
                ExportPdfQuality.STANDARD -> STANDARD_MAX_LONG_EDGE
                ExportPdfQuality.COMPACT -> COMPACT_MAX_LONG_EDGE
            }

    private fun PdfPage.textPlacements(transformer: PdfCoordinateTransformer): List<PdfTextPlacement> {
        val original =
            ocrBlocksJson
                ?.let(PdfOcrBlocksJsonParser::parse)
                ?.let(transformer::createTextPlacements)
                .orEmpty()
        val selectedText = editedText ?: if (original.isEmpty()) fullText else null
        if (selectedText == null) return original
        if (selectedText.isEmpty()) return emptyList()

        val correctedLines = selectedText.lineSequence().filter(String::isNotEmpty).toList()
        if (correctedLines.size == original.size) {
            return correctedLines.zip(original) { text, placement -> placement.copy(text = text) }
        }
        val bounds =
            original.coveringBounds()
                ?: PdfRect(0f, 0f, transformer.pageSize.width, transformer.pageSize.height)
        return correctedLines.distributeWithin(bounds)
    }

    private fun List<PdfTextPlacement>.coveringBounds(): PdfRect? =
        takeIf(List<PdfTextPlacement>::isNotEmpty)?.let { placements ->
            PdfRect(
                left = placements.minOf { it.bounds.left },
                bottom = placements.minOf { it.bounds.bottom },
                right = placements.maxOf { it.bounds.right },
                top = placements.maxOf { it.bounds.top },
            )
        }

    private fun List<String>.distributeWithin(bounds: PdfRect): List<PdfTextPlacement> {
        if (isEmpty()) return emptyList()
        val lineHeight = bounds.height / size
        return mapIndexed { index, text ->
            val top = bounds.top - index * lineHeight
            PdfTextPlacement(
                text = text,
                bounds = PdfRect(bounds.left, top - lineHeight, bounds.right, top),
            )
        }
    }

    private fun PDPageContentStream.drawInvisibleText(
        font: PDType0Font,
        placements: List<PdfTextPlacement>,
    ) {
        if (placements.isEmpty()) return
        beginText()
        setRenderingMode(RenderingMode.NEITHER)
        placements.forEach { placement ->
            val text = placement.text.replace("\r", "").replace("\n", "")
            if (text.isEmpty() || placement.bounds.width <= 0f || placement.bounds.height <= 0f) return@forEach
            val fontSize = placement.bounds.height
            val naturalWidth = font.getStringWidth(text) / FONT_UNITS_PER_EM * fontSize
            if (naturalWidth <= 0f) return@forEach
            setFont(font, fontSize)
            setHorizontalScaling(placement.bounds.width / naturalWidth * PERCENT)
            setTextMatrix(Matrix.getTranslateInstance(placement.bounds.left, placement.bounds.bottom))
            showText(text)
        }
        endText()
    }

    private fun buildExactToUnicodeCMap(
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
                source.toCharArray().joinToString("") { character ->
                    character.code.toString(16).padStart(4, '0').uppercase(Locale.ROOT)
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

    private fun PDDocument.findEmbeddedType0Font(): PDType0Font =
        pages.asSequence()
            .flatMap { page -> page.resources.fontNames.asSequence().map(page.resources::getFont) }
            .filterIsInstance<PDType0Font>()
            .firstOrNull()
            ?: error("Searchable PDF has text but no embedded font")

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

    private fun List<PdfPage>.validatedAndOrdered(): List<PdfPage> {
        require(isNotEmpty()) { "A PDF requires at least one page" }
        require(all { it.sequence > 0 }) { "PDF page sequences must be positive" }
        require(map(PdfPage::sequence).distinct().size == size) { "PDF page sequences must be unique" }
        return sortedBy(PdfPage::sequence)
    }

    private companion object {
        const val TEMP_FILE_PREFIX = "pagebinder-pdf-"
        const val TEMP_FILE_SUFFIX = ".part"
        const val FONT_UNITS_PER_EM = 1_000f
        const val PERCENT = 100f
        const val MAX_CMAP_ENTRIES_PER_BLOCK = 100
        const val HIGH_MAX_LONG_EDGE = 3_840
        const val STANDARD_MAX_LONG_EDGE = 2_048
        const val COMPACT_MAX_LONG_EDGE = 1_280
        const val STANDARD_JPEG_QUALITY = 0.85f
        const val COMPACT_JPEG_QUALITY = 0.65f
    }
}
