package com.pagebinder.app.export

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opens binary export content when the ZIP writer is ready to consume it. */
fun interface ExportContentSource {
    fun openStream(): InputStream
}

/** An original page image to be stored under its prescribed export name. */
data class ExportImage(
    val sequence: Int,
    val content: ExportContentSource,
)

/** All artifacts in the complete single-file export described by data-model section 3.3. */
data class CompleteZipInput(
    val sanitizedTitle: String,
    val artifactBaseName: String,
    val searchablePdf: ExportContentSource,
    val imagePdf: ExportContentSource,
    val markdown: String,
    val textFiles: List<GeneratedTextFile>,
    val images: List<ExportImage>,
    val manifestJson: String,
)

/** Packages export artifacts without depending on Android or storage framework types. */
object ExportZipPackager {
    /** Writes the `text_zip` format. Entry order is ascending page sequence. */
    suspend fun writeTextZip(
        textFiles: List<GeneratedTextFile>,
        output: OutputStream,
        reportProgress: suspend (completedEntries: Int, totalEntries: Int) -> Unit = { _, _ -> },
    ) {
        val entries = textEntries(textFiles)
        writeZip(entries, output, reportProgress)
    }

    /** Writes the `image_zip` format: original images followed by manifest.json. */
    suspend fun writeImageZip(
        images: List<ExportImage>,
        manifestJson: String,
        output: OutputStream,
        reportProgress: suspend (completedEntries: Int, totalEntries: Int) -> Unit = { _, _ -> },
    ) {
        val entries =
            imageEntries(images) +
                textEntry(MANIFEST_FILE_NAME, manifestJson)
        writeZip(entries, output, reportProgress)
    }

    /** Writes the complete section 3.3 directory tree for single-file-only destinations. */
    suspend fun writeCompleteZip(
        input: CompleteZipInput,
        output: OutputStream,
        reportProgress: suspend (completedEntries: Int, totalEntries: Int) -> Unit = { _, _ -> },
    ) {
        requirePathSegment(input.sanitizedTitle, "Sanitized title")
        requirePathSegment(input.artifactBaseName, "Artifact base name")

        val root = "${input.sanitizedTitle}/"
        val entries =
            listOf(
                binaryEntry("$root${input.artifactBaseName}.searchable.pdf", input.searchablePdf),
                binaryEntry("$root${input.artifactBaseName}.images.pdf", input.imagePdf),
                textEntry("$root${input.artifactBaseName}.md", input.markdown),
            ) +
                textEntries(input.textFiles, root) +
                imageEntries(input.images, root) +
                textEntry("$root$MANIFEST_FILE_NAME", input.manifestJson)

        writeZip(entries, output, reportProgress)
    }
}

private data class PendingZipEntry(
    val path: String,
    val content: ExportContentSource,
)

private fun textEntries(
    textFiles: List<GeneratedTextFile>,
    root: String = "",
): List<PendingZipEntry> {
    val pageFiles =
        textFiles.map { file ->
            val match = PAGE_TEXT_FILE_PATTERN.matchEntire(file.fileName)
            requireNotNull(match) { "Invalid page text file name: ${file.fileName}" }
            val sequence = match.groupValues[1].toInt()
            require(sequence >= 1) { "Page sequence must start at 1" }
            Triple(sequence, file.fileName, file.content)
        }
    require(pageFiles.map { it.first }.distinct().size == pageFiles.size) {
        "Page text file sequences must be unique"
    }
    return pageFiles.sortedBy { it.first }.map { (_, fileName, content) ->
        textEntry("${root}pages/$fileName", content)
    }
}

private fun imageEntries(
    images: List<ExportImage>,
    root: String = "",
): List<PendingZipEntry> {
    require(images.all { it.sequence in 1..MAX_FOUR_DIGIT_PAGE }) {
        "Image page sequence must be between 1 and $MAX_FOUR_DIGIT_PAGE"
    }
    require(images.map { it.sequence }.distinct().size == images.size) {
        "Image page sequences must be unique"
    }
    return images.sortedBy(ExportImage::sequence).map { image ->
        binaryEntry(
            "${root}images/page-${image.sequence.asPageNumber()}.webp",
            image.content,
        )
    }
}

private fun binaryEntry(
    path: String,
    content: ExportContentSource,
): PendingZipEntry = PendingZipEntry(path, content)

private fun textEntry(
    path: String,
    content: String,
): PendingZipEntry =
    PendingZipEntry(path) {
        content.byteInputStream(StandardCharsets.UTF_8)
    }

private suspend fun writeZip(
    entries: List<PendingZipEntry>,
    output: OutputStream,
    reportProgress: suspend (completedEntries: Int, totalEntries: Int) -> Unit,
) {
    require(entries.map { it.path }.distinct().size == entries.size) {
        "ZIP entry paths must be unique"
    }
    entries.forEach { requireSafeEntryPath(it.path) }

    ZipOutputStream(NonClosingOutputStream(output), StandardCharsets.UTF_8).use { zip ->
        reportProgress(0, entries.size.coerceAtLeast(1))
        entries.forEachIndexed { index, pending ->
            currentCoroutineContext().ensureActive()
            val entry = ZipEntry(pending.path).apply { time = DETERMINISTIC_ENTRY_TIME_MILLIS }
            zip.putNextEntry(entry)
            pending.content.openStream().use { input ->
                val buffer = ByteArray(ZIP_COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    zip.write(buffer, 0, count)
                }
            }
            zip.closeEntry()
            reportProgress(index + 1, entries.size.coerceAtLeast(1))
        }
    }
}

private class NonClosingOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    override fun write(byte: Int) = delegate.write(byte)

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = delegate.write(bytes, offset, length)

    override fun flush() = delegate.flush()

    override fun close() = flush()
}

private fun requirePathSegment(
    value: String,
    label: String,
) {
    require(value.isNotBlank() && value != "." && value != "..") { "$label must not be blank" }
    require('/' !in value && '\\' !in value) { "$label must be a single path segment" }
}

private fun requireSafeEntryPath(path: String) {
    require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path) {
        "ZIP entry path must be relative and use forward slashes: $path"
    }
    require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "ZIP entry path contains an unsafe segment: $path"
    }
}

private val PAGE_TEXT_FILE_PATTERN = Regex("page-(\\d{4})\\.txt")
private const val MANIFEST_FILE_NAME = "manifest.json"
private const val MAX_FOUR_DIGIT_PAGE = 9999
private const val DETERMINISTIC_ENTRY_TIME_MILLIS = 0L
private const val ZIP_COPY_BUFFER_SIZE = 8 * 1024
