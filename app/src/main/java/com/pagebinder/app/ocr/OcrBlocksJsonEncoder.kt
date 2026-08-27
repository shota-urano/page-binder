package com.pagebinder.app.ocr

import com.google.mlkit.vision.text.Text
import org.json.JSONArray
import org.json.JSONObject

internal object OcrBlocksJsonEncoder {
    fun encode(
        text: Text,
        mapper: OcrCoordinateMapper,
    ): String =
        JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put(
                "blocks",
                JSONArray().apply {
                    text.textBlocks.forEachIndexed { blockIndex, block ->
                        put(
                            JSONObject()
                                .put("index", blockIndex)
                                .put("text", block.text)
                                .put(
                                    "rect",
                                    mapper
                                        .toOriginal(block.boundingBoxOrEmpty().toPixelRect())
                                        .toJson(),
                                )
                                .put(
                                    "lines",
                                    JSONArray().apply {
                                        block.lines.forEachIndexed { lineIndex, line ->
                                            put(
                                                JSONObject()
                                                    .put("index", lineIndex)
                                                    .put("text", line.text)
                                                    .put(
                                                        "rect",
                                                        mapper
                                                            .toOriginal(line.boundingBoxOrEmpty().toPixelRect())
                                                            .toJson(),
                                                    )
                                                    .put(
                                                        "elements",
                                                        JSONArray().apply {
                                                            line.elements.forEachIndexed { elementIndex, element ->
                                                                put(
                                                                    JSONObject()
                                                                        .put("index", elementIndex)
                                                                        .put("text", element.text)
                                                                        .put(
                                                                            "rect",
                                                                            mapper
                                                                                .toOriginal(
                                                                                    element
                                                                                        .boundingBoxOrEmpty()
                                                                                        .toPixelRect(),
                                                                                )
                                                                                .toJson(),
                                                                        ),
                                                                )
                                                            }
                                                        },
                                                    ),
                                            )
                                        }
                                    },
                                ),
                        )
                    }
                },
            ).toString()

    private fun Text.TextBlock.boundingBoxOrEmpty() = boundingBox ?: android.graphics.Rect()

    private fun Text.Line.boundingBoxOrEmpty() = boundingBox ?: android.graphics.Rect()

    private fun Text.Element.boundingBoxOrEmpty() = boundingBox ?: android.graphics.Rect()

    private fun android.graphics.Rect.toPixelRect() = OcrPixelRect(left, top, right, bottom)

    private fun OcrPixelRect.toJson(): JSONObject =
        JSONObject()
            .put("left", left)
            .put("top", top)
            .put("right", right)
            .put("bottom", bottom)

    private const val SCHEMA_VERSION = 1
}
