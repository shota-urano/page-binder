package com.pagebinder.app.export

import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PdfPageTransform
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies that decoded blocksJson coordinates use the integer-bounded derivative image transform. */
class PdfPageCoordinateTransformTest {
    @Test
    fun `blocksJson text placements match rotated and cropped derivative pixel bounds`() {
        assertPlacement(
            rotationDegrees = 90,
            sourceRect = OcrRect(left = 40f, top = 25f, right = 80f, bottom = 75f),
            expected = PdfRect(left = 0f, bottom = 100f, right = 50f, top = 140f),
            editedImageWidth = 50,
        )
        assertPlacement(
            rotationDegrees = 180,
            sourceRect = OcrRect(left = 60f, top = 25f, right = 140f, bottom = 75f),
            expected = PdfRect(left = 10f, bottom = 15f, right = 90f, top = 65f),
            editedImageWidth = 100,
        )
        assertPlacement(
            rotationDegrees = 270,
            sourceRect = OcrRect(left = 120f, top = 25f, right = 160f, bottom = 75f),
            expected = PdfRect(left = 0f, bottom = 100f, right = 50f, top = 140f),
            editedImageWidth = 50,
        )
    }

    @Test
    fun `blocksJson text placement uses enclosing non integer crop bounds`() {
        val transform =
            PdfPageTransform(
                sourceWidth = 200,
                sourceHeight = 100,
                rotationDegrees = 90,
                crop = PageCrop(left = 0.123f, top = 0.1f, right = 0.876f, bottom = 0.9f),
            )
        val placement =
            placements(
                transform = transform,
                sourceRect = OcrRect(left = 20f, top = 12f, right = 21f, bottom = 13f),
                editedImageWidth = 76,
            )

        assertRect(PdfRect(left = 75f, bottom = 159f, right = 76f, top = 160f), placement.bounds)
    }

    private fun assertPlacement(
        rotationDegrees: Int,
        sourceRect: OcrRect,
        expected: PdfRect,
        editedImageWidth: Int,
    ) {
        val transform =
            PdfPageTransform(
                sourceWidth = 200,
                sourceHeight = 100,
                rotationDegrees = rotationDegrees,
                crop = PageCrop(left = 0.25f, top = 0.1f, right = 0.75f, bottom = 0.9f),
            )
        val placement = placements(transform, sourceRect, editedImageWidth)

        assertRect(expected, placement.bounds)
    }

    /** The same element rectangle produced when [PdfOcrBlocksJsonParser] decodes blocksJson. */
    private fun placements(
        transform: PdfPageTransform,
        sourceRect: OcrRect,
        editedImageWidth: Int,
    ): PdfTextPlacement =
        transform
            .toCoordinateTransformer(editedImageWidth)
            .createTextPlacements(
                listOf(
                    OcrTextBlock(
                        text = "element",
                        rect = sourceRect,
                        lines =
                            listOf(
                                OcrTextLine(
                                    text = "element",
                                    rect = sourceRect,
                                    elements = listOf(OcrTextElement("element", sourceRect)),
                                ),
                            ),
                    ),
                ),
            ).single()

    private fun assertRect(
        expected: PdfRect,
        actual: PdfRect,
    ) {
        assertEquals(expected.left, actual.left, TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, TOLERANCE)
        assertEquals(expected.right, actual.right, TOLERANCE)
        assertEquals(expected.top, actual.top, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
