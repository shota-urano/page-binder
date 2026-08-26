package com.pagebinder.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextExportGeneratorsTest {
    @Test
    fun `markdown has numbered page headings and explicit boundaries in sequence order`() {
        val pages =
            listOf(
                TextExportPage(sequence = 2, fullText = "second page", editedText = null),
                TextExportPage(sequence = 1, fullText = "first page", editedText = null),
            )

        val markdown = MarkdownGenerator.generate(pages)

        assertEquals(
            """
            ## Page 1

            first page

            ---

            ## Page 2

            second page
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `markdown and text files prefer edited text even when it is empty`() {
        val pages =
            listOf(
                TextExportPage(sequence = 1, fullText = "original", editedText = "corrected"),
                TextExportPage(sequence = 2, fullText = "must not appear", editedText = ""),
            )

        assertEquals(
            "## Page 1\n\ncorrected\n\n---\n\n## Page 2\n\n",
            MarkdownGenerator.generate(pages),
        )
        assertEquals(
            listOf(
                GeneratedTextFile("page-0001.txt", "corrected"),
                GeneratedTextFile("page-0002.txt", ""),
            ),
            PageTextGenerator.generate(pages),
        )
    }

    @Test
    fun `text file names use four digit zero-padded page references`() {
        val files =
            PageTextGenerator.generate(
                listOf(TextExportPage(sequence = 42, fullText = "text", editedText = null)),
            )

        assertEquals(listOf(GeneratedTextFile("page-0042.txt", "text")), files)
    }

    @Test
    fun `duplicate page sequences are rejected to prevent file collisions`() {
        val pages =
            listOf(
                TextExportPage(sequence = 1, fullText = "one", editedText = null),
                TextExportPage(sequence = 1, fullText = "another", editedText = null),
            )

        assertThrows(IllegalArgumentException::class.java) { PageTextGenerator.generate(pages) }
    }
}
