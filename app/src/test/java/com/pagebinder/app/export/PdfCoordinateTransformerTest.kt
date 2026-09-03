package com.pagebinder.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfCoordinateTransformerTest {
    @Test
    fun `maps top-left OCR coordinates to bottom-left PDF coordinates`() {
        val transformer = create(rotation = 0)

        assertRect(
            expected = PdfRect(left = 40f, bottom = 140f, right = 120f, top = 180f),
            actual = transformer.map(SOURCE_RECT),
        )
        assertSize(width = 400f, height = 200f, actual = transformer.pageSize)
    }

    @Test
    fun `maps clockwise 90 degree rotation crop and scale with one matrix`() {
        val transformer = create(rotation = 90, crop = CROPPED, pageWidth = 100f)

        assertRect(
            expected = PdfRect(left = 0f, bottom = 200f, right = 100f, top = 280f),
            actual = transformer.map(OcrRect(left = 40f, top = 25f, right = 80f, bottom = 75f)),
        )
        assertSize(width = 100f, height = 320f, actual = transformer.pageSize)
    }

    @Test
    fun `maps clockwise 180 degree rotation crop and scale with one matrix`() {
        val transformer = create(rotation = 180, crop = CROPPED, pageWidth = 200f)

        assertRect(
            expected = PdfRect(left = 20f, bottom = 30f, right = 180f, top = 130f),
            actual = transformer.map(OcrRect(left = 60f, top = 25f, right = 140f, bottom = 75f)),
        )
        assertSize(width = 200f, height = 160f, actual = transformer.pageSize)
    }

    @Test
    fun `maps clockwise 270 degree rotation crop and scale with one matrix`() {
        val transformer = create(rotation = 270, crop = CROPPED, pageWidth = 150f)

        assertRect(
            expected = PdfRect(left = 0f, bottom = 300f, right = 150f, top = 420f),
            actual = transformer.map(OcrRect(left = 120f, top = 25f, right = 160f, bottom = 75f)),
        )
        assertSize(width = 150f, height = 480f, actual = transformer.pageSize)
    }

    @Test
    fun `uses the same matrix for image corners and OCR rectangles`() {
        val transformer = create(rotation = 90, crop = CROPPED, pageWidth = 100f)
        val ocrRect = OcrRect(left = 40f, top = 25f, right = 80f, bottom = 75f)

        val mappedRect = transformer.map(ocrRect)
        val mappedCorners =
            listOf(
                transformer.sourceToPdf.map(PdfPoint(ocrRect.left, ocrRect.top)),
                transformer.sourceToPdf.map(PdfPoint(ocrRect.right, ocrRect.top)),
                transformer.sourceToPdf.map(PdfPoint(ocrRect.right, ocrRect.bottom)),
                transformer.sourceToPdf.map(PdfPoint(ocrRect.left, ocrRect.bottom)),
            )

        assertEquals(mappedRect.left, mappedCorners.minOf { it.x }, TOLERANCE)
        assertEquals(mappedRect.bottom, mappedCorners.minOf { it.y }, TOLERANCE)
        assertEquals(mappedRect.right, mappedCorners.maxOf { it.x }, TOLERANCE)
        assertEquals(mappedRect.top, mappedCorners.maxOf { it.y }, TOLERANCE)
    }

    @Test
    fun `maps OCR edges with the same integer crop bounds as the bitmap derivative`() {
        assertPixelCropMapsToPdf(cropLeft = 0.123f, cropRight = 0.876f, sourceLeft = 12f, sourceRight = 88f)
        assertPixelCropMapsToPdf(cropLeft = 0.53f, cropRight = 1f, sourceLeft = 53f, sourceRight = 100f)
        assertPixelCropMapsToPdf(
            cropLeft = 0.129995f,
            cropRight = 0.870005f,
            sourceLeft = 12f,
            sourceRight = 88f,
        )
    }

    @Test
    fun `creates element placements and falls back to line or block placement`() {
        val transformer = create(rotation = 0)
        val blocks =
            listOf(
                OcrTextBlock(
                    text = "unused block",
                    rect = OcrRect(0f, 0f, 100f, 20f),
                    lines =
                        listOf(
                            OcrTextLine(
                                text = "unused line",
                                rect = OcrRect(0f, 0f, 100f, 10f),
                                elements = listOf(OcrTextElement("element", OcrRect(0f, 0f, 20f, 10f))),
                            ),
                            OcrTextLine("line", OcrRect(0f, 10f, 40f, 20f)),
                        ),
                ),
                OcrTextBlock("block", OcrRect(0f, 20f, 40f, 30f)),
            )

        val placements = transformer.createTextPlacements(blocks)

        assertEquals(listOf("element", "line", "block"), placements.map(PdfTextPlacement::text))
        assertRect(PdfRect(0f, 180f, 40f, 200f), placements.first().bounds)
    }

    @Test
    fun `rejects unsupported rotations and empty crops`() {
        assertThrows(IllegalArgumentException::class.java) { create(rotation = 45) }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedCrop(left = 0.5f, top = 0f, right = 0.5f, bottom = 1f)
        }
    }

    private fun create(
        rotation: Int,
        crop: NormalizedCrop = NormalizedCrop(),
        pageWidth: Float = 400f,
    ): PdfCoordinateTransformer =
        PdfCoordinateTransformer.create(
            sourceWidth = 200,
            sourceHeight = 100,
            rotationDegrees = rotation,
            crop = crop,
            pageWidth = pageWidth,
        )

    private fun assertRect(
        expected: PdfRect,
        actual: PdfRect,
    ) {
        assertEquals(expected.left, actual.left, TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, TOLERANCE)
        assertEquals(expected.right, actual.right, TOLERANCE)
        assertEquals(expected.top, actual.top, TOLERANCE)
    }

    private fun assertSize(
        width: Float,
        height: Float,
        actual: PdfPageSize,
    ) {
        assertEquals(width, actual.width, TOLERANCE)
        assertEquals(height, actual.height, TOLERANCE)
    }

    private fun assertPixelCropMapsToPdf(
        cropLeft: Float,
        cropRight: Float,
        sourceLeft: Float,
        sourceRight: Float,
    ) {
        val derivativeWidth = sourceRight - sourceLeft
        val transformer =
            PdfCoordinateTransformer.create(
                sourceWidth = 100,
                sourceHeight = 100,
                rotationDegrees = 0,
                crop = NormalizedCrop(left = cropLeft, right = cropRight),
                pageWidth = derivativeWidth,
            )

        assertRect(
            expected = PdfRect(left = 0f, bottom = 0f, right = derivativeWidth, top = 100f),
            actual = transformer.map(OcrRect(sourceLeft, 0f, sourceRight, 100f)),
        )
        assertSize(width = derivativeWidth, height = 100f, actual = transformer.pageSize)
    }

    companion object {
        private const val TOLERANCE = 0.0001f
        private val SOURCE_RECT = OcrRect(left = 20f, top = 10f, right = 60f, bottom = 30f)
        private val CROPPED = NormalizedCrop(left = 0.25f, top = 0.1f, right = 0.75f, bottom = 0.9f)
    }
}
