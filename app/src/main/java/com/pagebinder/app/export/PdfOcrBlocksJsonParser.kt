package com.pagebinder.app.export

import com.pagebinder.app.ocr.OcrBlocksJsonCodec
import com.pagebinder.app.ocr.OcrJsonRect

/** Parses the stable, framework-neutral blocksJson schema defined by 02-data-model. */
internal object PdfOcrBlocksJsonParser {
    fun parse(json: String): List<OcrTextBlock> =
        OcrBlocksJsonCodec.decode(json).blocks.map { block ->
            OcrTextBlock(
                text = block.text,
                rect = block.rect.toOcrRect(),
                lines =
                    block.lines.map { line ->
                        OcrTextLine(
                            text = line.text,
                            rect = line.rect.toOcrRect(),
                            elements =
                                line.elements.map { element ->
                                    OcrTextElement(
                                        text = element.text,
                                        rect = element.rect.toOcrRect(),
                                    )
                                },
                        )
                    },
            )
        }

    private fun OcrJsonRect.toOcrRect(): OcrRect =
        OcrRect(
            left = left.toFloat(),
            top = top.toFloat(),
            right = right.toFloat(),
            bottom = bottom.toFloat(),
        )
}
