package com.pagebinder.app.export

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Parses the stable, framework-neutral blocksJson schema defined by 02-data-model. */
internal object PdfOcrBlocksJsonParser {
    fun parse(json: String): List<OcrTextBlock> =
        try {
            parseValidated(json)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid OCR blocks JSON", error)
        }

    private fun parseValidated(json: String): List<OcrTextBlock> {
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) {
            "Unsupported OCR blocks schema version"
        }
        return root.getJSONArray("blocks").mapObjects { block ->
            OcrTextBlock(
                text = block.getString("text"),
                rect = block.getJSONObject("rect").toOcrRect(),
                lines =
                    block.getJSONArray("lines").mapObjects { line ->
                        OcrTextLine(
                            text = line.getString("text"),
                            rect = line.getJSONObject("rect").toOcrRect(),
                            elements =
                                line.getJSONArray("elements").mapObjects { element ->
                                    OcrTextElement(
                                        text = element.getString("text"),
                                        rect = element.getJSONObject("rect").toOcrRect(),
                                    )
                                },
                        )
                    },
            )
        }
    }

    private fun JSONObject.toOcrRect(): OcrRect =
        OcrRect(
            left = getDouble("left").toFiniteFloat(),
            top = getDouble("top").toFiniteFloat(),
            right = getDouble("right").toFiniteFloat(),
            bottom = getDouble("bottom").toFiniteFloat(),
        )

    private fun Double.toFiniteFloat(): Float =
        toFloat().also { value -> require(value.isFinite()) { "OCR coordinate must be finite" } }

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { index -> transform(getJSONObject(index)) }

    private const val SCHEMA_VERSION = 1
}
