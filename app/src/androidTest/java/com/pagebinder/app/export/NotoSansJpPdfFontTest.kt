package com.pagebinder.app.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class NotoSansJpPdfFontTest {
    @Before
    fun initializePdfBox() {
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun embedsOnlyUsedNotoSansJpGlyphsAsSubset() {
        val sourceFontBytes = context.assets.open(NotoSansJpPdfFont.FONT_ASSET_PATH).use { it.readBytes() }
        val generatedPdf =
            ByteArrayOutputStream().use { output ->
                PDDocument().use { document ->
                    val page = PDPage()
                    document.addPage(page)
                    val font = NotoSansJpPdfFont(context.assets).load(document)

                    assertTrue("Font must be configured for subsetting", font.willBeSubset())
                    PDPageContentStream(document, page).use { stream ->
                        stream.beginText()
                        stream.setFont(font, 12f)
                        stream.newLineAtOffset(36f, 720f)
                        stream.showText(SAMPLE_TEXT)
                        stream.endText()
                    }
                    document.save(output)
                }
                output.toByteArray()
            }

        PDDocument.load(ByteArrayInputStream(generatedPdf)).use { document ->
            val embeddedFont =
                document.getPage(0).resources.fontNames
                    .map { document.getPage(0).resources.getFont(it) }
                    .filterIsInstance<PDType0Font>()
                    .single()
            val embeddedFontFile = embeddedFont.fontDescriptor.fontFile2

            assertTrue("Generated font must be embedded", embeddedFont.isEmbedded)
            assertTrue(
                "Expected a six-letter PDF subset prefix but was ${embeddedFont.name}",
                SUBSET_FONT_NAME.matches(embeddedFont.name),
            )
            assertNotNull("Embedded TrueType font stream is missing", embeddedFontFile)
            val embeddedFontBytes = embeddedFontFile.createInputStream().use { it.readBytes() }
            assertTrue(
                "Embedded font should be smaller than the bundled full font",
                embeddedFontBytes.size < sourceFontBytes.size,
            )
        }
    }

    @Test
    fun bundlesNotoSansJpOpenFontLicense() {
        val license =
            context.assets.open(NotoSansJpPdfFont.LICENSE_ASSET_PATH).bufferedReader().use {
                it.readText()
            }

        assertTrue(license.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(license.contains("Copyright 2014-2021 Adobe"))
        assertEquals(OFL_SHA256, license.toByteArray().sha256())
    }

    private fun ByteArray.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val SAMPLE_TEXT = "日本語ABC123"
        private const val OFL_SHA256 = "1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9"
        private val SUBSET_FONT_NAME = Regex("^[A-Z]{6}\\+NotoSansJP(?:-.+)?$")

        private val context
            get() = InstrumentationRegistry.getInstrumentation().targetContext
    }
}
