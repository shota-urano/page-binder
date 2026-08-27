package com.pagebinder.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrReadingOrderCorrectorTest {
    @Test
    fun `orders two-column horizontal fixture column by column from left to right`() {
        val fixture =
            listOf(
                horizontalBlock(index = 0, left = 20, top = 20),
                horizontalBlock(index = 1, left = 220, top = 20),
                horizontalBlock(index = 2, left = 20, top = 140),
                horizontalBlock(index = 3, left = 220, top = 140),
            )

        assertEquals(listOf(0, 2, 1, 3), OcrReadingOrderCorrector.order(fixture))
    }

    @Test
    fun `orders vertical fixture top to bottom and columns from right to left`() {
        val fixture =
            listOf(
                verticalBlock(index = 0, left = 80, top = 20),
                verticalBlock(index = 1, left = 220, top = 20),
                verticalBlock(index = 2, left = 80, top = 180),
                verticalBlock(index = 3, left = 220, top = 180),
            )

        assertEquals(listOf(1, 3, 0, 2), OcrReadingOrderCorrector.order(fixture))
    }

    @Test
    fun `keeps engine order when coordinates are missing`() {
        val fixture =
            listOf(
                horizontalBlock(index = 0, left = 200, top = 20),
                OcrReadingBlock(sourceIndex = 1, rect = OcrPixelRect(0, 0, 0, 0), lineRects = emptyList()),
            )

        assertEquals(listOf(0, 1), OcrReadingOrderCorrector.order(fixture))
    }

    private fun horizontalBlock(
        index: Int,
        left: Int,
        top: Int,
    ): OcrReadingBlock =
        OcrReadingBlock(
            sourceIndex = index,
            rect = OcrPixelRect(left, top, left + 140, top + 90),
            lineRects = listOf(OcrPixelRect(left, top, left + 140, top + 20)),
        )

    private fun verticalBlock(
        index: Int,
        left: Int,
        top: Int,
    ): OcrReadingBlock =
        OcrReadingBlock(
            sourceIndex = index,
            rect = OcrPixelRect(left, top, left + 40, top + 140),
            lineRects = listOf(OcrPixelRect(left, top, left + 30, top + 140)),
        )
}
