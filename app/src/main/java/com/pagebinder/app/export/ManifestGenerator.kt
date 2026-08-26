package com.pagebinder.app.export

import java.time.Instant

data class ManifestProject(
    val title: String,
    val author: String?,
    val note: String?,
    val createdAt: Instant,
)

enum class ManifestOcrState(val serializedName: String) {
    PENDING("pending"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    STALE("stale"),
}

data class ManifestPage(
    val sequence: Int,
    val capturedAt: Instant,
    val ocrState: ManifestOcrState,
    val contentHash: String,
    val edited: Boolean,
)

data class ManifestInput(
    val appVersion: String,
    val project: ManifestProject,
    val exportedAt: Instant,
    val ocrEngineVersion: String,
    val pages: List<ManifestPage>,
)

/** Generates manifest.json without depending on persistence or OCR framework types. */
object ManifestGenerator {
    fun generate(input: ManifestInput): String {
        val pages = input.pages.validatedInSequenceOrder()
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"app\": { \"name\": \"PageBinder\", \"version\": ")
            appendJsonString(input.appVersion)
            append(" },\n")
            append("  \"project\": { \"title\": ")
            appendJsonString(input.project.title)
            append(", \"author\": ")
            appendNullableJsonString(input.project.author)
            append(", \"note\": ")
            appendNullableJsonString(input.project.note)
            append(", \"createdAt\": ")
            appendJsonString(input.project.createdAt.toString())
            append(", \"exportedAt\": ")
            appendJsonString(input.exportedAt.toString())
            append(" },\n")
            append("  \"ocrEngine\": { \"name\": \"mlkit-text-recognition-v2-japanese\", \"version\": ")
            appendJsonString(input.ocrEngineVersion)
            append(" },\n")
            append("  \"pages\": [")
            if (pages.isEmpty()) {
                append("]\n")
            } else {
                append('\n')
                pages.forEachIndexed { index, page ->
                    val pageNumber = page.sequence.asPageNumber()
                    append("    { \"sequence\": ${page.sequence}, \"imageFile\": \"images/page-$pageNumber.webp\", ")
                    append("\"textFile\": \"pages/page-$pageNumber.txt\", \"capturedAt\": ")
                    appendJsonString(page.capturedAt.toString())
                    append(", \"ocrState\": ")
                    appendJsonString(page.ocrState.serializedName)
                    append(", \"contentHash\": ")
                    appendJsonString(page.contentHash)
                    append(", \"edited\": ${page.edited} }")
                    if (index < pages.lastIndex) append(',')
                    append('\n')
                }
                append("  ]\n")
            }
            append('}')
        }
    }
}

private fun List<ManifestPage>.validatedInSequenceOrder(): List<ManifestPage> {
    require(all { it.sequence >= 1 }) { "Page sequence must start at 1" }
    require(map { it.sequence }.distinct().size == size) { "Page sequences must be unique" }
    return sortedBy(ManifestPage::sequence)
}

private fun StringBuilder.appendNullableJsonString(value: String?) {
    if (value == null) append("null") else appendJsonString(value)
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
