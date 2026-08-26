package com.pagebinder.app.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

class ExportZipPackagerTest {
    @Test
    fun `text zip contains page files in data model order with generated contents`() {
        val output = ByteArrayOutputStream()
        val generated =
            PageTextGenerator.generate(
                listOf(
                    TextExportPage(2, "original two", "edited two"),
                    TextExportPage(1, "one", null),
                ),
            )

        ExportZipPackager.writeTextZip(generated.reversed(), output)

        assertZip(
            output.toByteArray(),
            listOf(
                ExpectedEntry("pages/page-0001.txt", "one".utf8()),
                ExpectedEntry("pages/page-0002.txt", "edited two".utf8()),
            ),
        )
    }

    @Test
    fun `image zip contains original images followed by generated manifest`() {
        val output = ByteArrayOutputStream()
        val manifest = "{\"schemaVersion\":1}"

        ExportZipPackager.writeImageZip(
            images =
                listOf(
                    image(2, byteArrayOf(2, 2)),
                    image(1, byteArrayOf(1, 1)),
                ),
            manifestJson = manifest,
            output = output,
        )

        assertZip(
            output.toByteArray(),
            listOf(
                ExpectedEntry("images/page-0001.webp", byteArrayOf(1, 1)),
                ExpectedEntry("images/page-0002.webp", byteArrayOf(2, 2)),
                ExpectedEntry("manifest.json", manifest.utf8()),
            ),
        )
    }

    @Test
    fun `complete zip exactly matches data model section 3_3 paths order and contents`() {
        val output = ByteArrayOutputStream()
        val markdown =
            MarkdownGenerator.generate(
                listOf(TextExportPage(1, "recognized text", null)),
            )
        val textFiles =
            PageTextGenerator.generate(
                listOf(TextExportPage(1, "recognized text", null)),
            )
        val manifest = "{\n  \"schemaVersion\": 1\n}"

        ExportZipPackager.writeCompleteZip(
            CompleteZipInput(
                sanitizedTitle = "sample-book",
                artifactBaseName = "Sample Book",
                searchablePdf = bytes(byteArrayOf(10, 11)),
                imagePdf = bytes(byteArrayOf(20, 21)),
                markdown = markdown,
                textFiles = textFiles,
                images = listOf(image(1, byteArrayOf(30, 31))),
                manifestJson = manifest,
            ),
            output,
        )

        assertZip(
            output.toByteArray(),
            listOf(
                ExpectedEntry("sample-book/Sample Book.searchable.pdf", byteArrayOf(10, 11)),
                ExpectedEntry("sample-book/Sample Book.images.pdf", byteArrayOf(20, 21)),
                ExpectedEntry("sample-book/Sample Book.md", markdown.utf8()),
                ExpectedEntry("sample-book/pages/page-0001.txt", "recognized text".utf8()),
                ExpectedEntry("sample-book/images/page-0001.webp", byteArrayOf(30, 31)),
                ExpectedEntry("sample-book/manifest.json", manifest.utf8()),
            ),
        )
    }

    @Test
    fun `unsafe complete zip title is rejected before output is written`() {
        val output = ByteArrayOutputStream()
        val input =
            CompleteZipInput(
                sanitizedTitle = "../escape",
                artifactBaseName = "book",
                searchablePdf = bytes(byteArrayOf()),
                imagePdf = bytes(byteArrayOf()),
                markdown = "",
                textFiles = emptyList(),
                images = emptyList(),
                manifestJson = "{}",
            )

        assertThrows(IllegalArgumentException::class.java) {
            ExportZipPackager.writeCompleteZip(input, output)
        }
        assertEquals(0, output.size())
    }

    private fun assertZip(
        zipBytes: ByteArray,
        expected: List<ExpectedEntry>,
    ) {
        val actual = mutableListOf<ExpectedEntry>()
        ZipInputStream(ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                actual += ExpectedEntry(entry.name, zip.readBytes())
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        assertEquals(expected.map { it.path }, actual.map { it.path })
        expected.zip(actual).forEach { (expectedEntry, actualEntry) ->
            assertArrayEquals(expectedEntry.content, actualEntry.content)
        }
    }

    private fun image(
        sequence: Int,
        content: ByteArray,
    ): ExportImage = ExportImage(sequence, bytes(content))

    private fun bytes(content: ByteArray): ExportContentSource = ExportContentSource { ByteArrayInputStream(content) }

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    private data class ExpectedEntry(
        val path: String,
        val content: ByteArray,
    )
}
