package com.pagebinder.app.export

import com.pagebinder.app.domain.PdfPage

/** Places OCR text with the same page transform as the visible raster (10-searchable-pdf §3.2). */
internal object PdfTextLayer {
    fun placements(
        page: PdfPage,
        transformer: PdfCoordinateTransformer,
    ): List<PdfTextPlacement> {
        val original =
            page.ocrBlocksJson
                ?.let(PdfOcrBlocksJsonParser::parse)
                ?.let(transformer::createTextPlacements)
                .orEmpty()
        val selectedText = page.editedText ?: if (original.isEmpty()) page.fullText else null
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
}
