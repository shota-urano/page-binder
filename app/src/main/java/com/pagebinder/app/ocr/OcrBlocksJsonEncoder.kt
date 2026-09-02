package com.pagebinder.app.ocr

import com.google.mlkit.vision.text.Text

internal object OcrBlocksJsonEncoder {
    fun encode(
        text: Text,
        mapper: OcrCoordinateMapper,
        blockOrder: List<Int> = text.textBlocks.indices.toList(),
    ): String =
        OcrBlocksJsonCodec.encode(
            OcrBlocksJson(
                blocks =
                    blockOrder.mapIndexed { blockIndex, sourceIndex ->
                        val block = text.textBlocks[sourceIndex]
                        OcrBlockJson(
                            index = blockIndex,
                            text = block.text,
                            rect =
                                mapper
                                    .toOriginal(block.boundingBoxOrEmpty().toPixelRect())
                                    .toJsonRect(),
                            lines =
                                block.lines.mapIndexed { lineIndex, line ->
                                    OcrLineJson(
                                        index = lineIndex,
                                        text = line.text,
                                        rect =
                                            mapper
                                                .toOriginal(line.boundingBoxOrEmpty().toPixelRect())
                                                .toJsonRect(),
                                        elements =
                                            line.elements.mapIndexed { elementIndex, element ->
                                                OcrElementJson(
                                                    index = elementIndex,
                                                    text = element.text,
                                                    rect =
                                                        mapper
                                                            .toOriginal(
                                                                element.boundingBoxOrEmpty().toPixelRect(),
                                                            ).toJsonRect(),
                                                )
                                            },
                                    )
                                },
                        )
                    },
            ),
        )

    private fun Text.TextBlock.boundingBoxOrEmpty() = boundingBox ?: android.graphics.Rect()

    private fun Text.Line.boundingBoxOrEmpty() = boundingBox ?: android.graphics.Rect()

    private fun Text.Element.boundingBoxOrEmpty() = boundingBox ?: android.graphics.Rect()

    private fun android.graphics.Rect.toPixelRect() = OcrPixelRect(left, top, right, bottom)

    private fun OcrPixelRect.toJsonRect() = OcrJsonRect(left, top, right, bottom)
}
