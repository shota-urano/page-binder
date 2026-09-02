package com.pagebinder.app.ocr

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val OCR_BLOCKS_SCHEMA_VERSION = 1

/**
 * Framework-neutral representation of the blocksJson schema in
 * docs/specs/02-data-model.md §3.4.
 */
internal data class OcrBlocksJson(
    val schemaVersion: Int = OCR_BLOCKS_SCHEMA_VERSION,
    val blocks: List<OcrBlockJson>,
)

internal data class OcrBlockJson(
    val index: Int,
    val text: String,
    val rect: OcrJsonRect,
    val lines: List<OcrLineJson>,
)

internal data class OcrLineJson(
    val index: Int,
    val text: String,
    val rect: OcrJsonRect,
    val elements: List<OcrElementJson>,
)

internal data class OcrElementJson(
    val index: Int,
    val text: String,
    val rect: OcrJsonRect,
)

/** Pixel rectangle with a top-left origin in the original image. */
internal data class OcrJsonRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Serializes and deserializes the stable OCR structured-result schema. */
internal object OcrBlocksJsonCodec {
    fun encode(value: OcrBlocksJson): String {
        require(value.schemaVersion == OCR_BLOCKS_SCHEMA_VERSION) {
            "Unsupported OCR blocks schema version"
        }
        return value.toJson().toString()
    }

    fun decode(json: String): OcrBlocksJson =
        try {
            JSONObject(json).toOcrBlocksJson()
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid OCR blocks JSON", error)
        }

    private fun OcrBlocksJson.toJson(): JSONObject =
        JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("blocks", JSONArray().apply { blocks.forEach { put(it.toJson()) } })

    private fun OcrBlockJson.toJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("rect", rect.toJson())
            .put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })

    private fun OcrLineJson.toJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("rect", rect.toJson())
            .put("elements", JSONArray().apply { elements.forEach { put(it.toJson()) } })

    private fun OcrElementJson.toJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("rect", rect.toJson())

    private fun OcrJsonRect.toJson(): JSONObject =
        JSONObject()
            .put("left", left)
            .put("top", top)
            .put("right", right)
            .put("bottom", bottom)

    private fun JSONObject.toOcrBlocksJson(): OcrBlocksJson {
        val schemaVersion = getInt("schemaVersion")
        require(schemaVersion == OCR_BLOCKS_SCHEMA_VERSION) { "Unsupported OCR blocks schema version" }
        return OcrBlocksJson(
            schemaVersion = schemaVersion,
            blocks = getJSONArray("blocks").mapObjects { it.toOcrBlockJson() },
        )
    }

    private fun JSONObject.toOcrBlockJson(): OcrBlockJson =
        OcrBlockJson(
            index = getInt("index"),
            text = getString("text"),
            rect = getJSONObject("rect").toOcrJsonRect(),
            lines = getJSONArray("lines").mapObjects { it.toOcrLineJson() },
        )

    private fun JSONObject.toOcrLineJson(): OcrLineJson =
        OcrLineJson(
            index = getInt("index"),
            text = getString("text"),
            rect = getJSONObject("rect").toOcrJsonRect(),
            elements = getJSONArray("elements").mapObjects { it.toOcrElementJson() },
        )

    private fun JSONObject.toOcrElementJson(): OcrElementJson =
        OcrElementJson(
            index = getInt("index"),
            text = getString("text"),
            rect = getJSONObject("rect").toOcrJsonRect(),
        )

    private fun JSONObject.toOcrJsonRect(): OcrJsonRect =
        OcrJsonRect(
            left = getInt("left"),
            top = getInt("top"),
            right = getInt("right"),
            bottom = getInt("bottom"),
        )

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { index -> transform(getJSONObject(index)) }
}
