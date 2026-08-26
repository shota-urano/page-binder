package com.pagebinder.app.export

/** OCR text needed by text-based export formats. */
data class TextExportPage(
    val sequence: Int,
    val fullText: String,
    val editedText: String?,
) {
    val outputText: String
        get() = editedText ?: fullText
}

/** A generated page text file, before storage or ZIP packaging. */
data class GeneratedTextFile(
    val fileName: String,
    val content: String,
)

/** Generates the single Markdown artifact described by docs/specs/11-export.md section 3.1. */
object MarkdownGenerator {
    fun generate(pages: List<TextExportPage>): String =
        pages.validatedInSequenceOrder().joinToString(separator = "\n\n---\n\n") { page ->
            "## Page ${page.sequence}\n\n${page.outputText}"
        }
}

/** Generates page-scoped TXT artifacts described by docs/specs/02-data-model.md section 3.3. */
object PageTextGenerator {
    fun generate(pages: List<TextExportPage>): List<GeneratedTextFile> =
        pages.validatedInSequenceOrder().map { page ->
            GeneratedTextFile(
                fileName = "page-${page.sequence.asPageNumber()}.txt",
                content = page.outputText,
            )
        }
}

internal fun Int.asPageNumber(): String = toString().padStart(PAGE_NUMBER_WIDTH, '0')

private fun List<TextExportPage>.validatedInSequenceOrder(): List<TextExportPage> {
    require(all { it.sequence >= 1 }) { "Page sequence must start at 1" }
    require(map { it.sequence }.distinct().size == size) { "Page sequences must be unique" }
    return sortedBy(TextExportPage::sequence)
}

private const val PAGE_NUMBER_WIDTH = 4
