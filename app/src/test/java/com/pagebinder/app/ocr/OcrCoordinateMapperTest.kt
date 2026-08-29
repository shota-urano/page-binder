package com.pagebinder.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrCoordinateMapperTest {
    @Test
    fun `maps rotation crop and shrink coordinates back to original pixels`() {
        val mapper =
            OcrCoordinateMapper(
                originalWidth = 100,
                originalHeight = 200,
                decodedWidth = 100,
                decodedHeight = 200,
                rotationDegrees = 90,
                cropLeft = 20,
                cropTop = 10,
                croppedWidth = 160,
                croppedHeight = 80,
                preparedWidth = 80,
                preparedHeight = 40,
            )

        assertEquals(
            OcrPixelRect(left = 10, top = 20, right = 90, bottom = 180),
            mapper.toOriginal(OcrPixelRect(left = 0, top = 0, right = 80, bottom = 40)),
        )
    }

    @Test
    fun `accounts for sampled decode and clamps recognition bounds`() {
        val mapper =
            OcrCoordinateMapper(
                originalWidth = 4000,
                originalHeight = 2000,
                decodedWidth = 2000,
                decodedHeight = 1000,
                rotationDegrees = 0,
                cropLeft = 0,
                cropTop = 0,
                croppedWidth = 2000,
                croppedHeight = 1000,
                preparedWidth = 1000,
                preparedHeight = 500,
            )

        assertEquals(
            OcrPixelRect(left = 0, top = 0, right = 4000, bottom = 2000),
            mapper.toOriginal(OcrPixelRect(left = -10, top = -10, right = 1010, bottom = 510)),
        )
    }

    @Test
    fun `maps 180 degree rotation with crop back to original pixels`() {
        val mapper =
            OcrCoordinateMapper(
                originalWidth = 100,
                originalHeight = 200,
                decodedWidth = 100,
                decodedHeight = 200,
                rotationDegrees = 180,
                cropLeft = 10,
                cropTop = 30,
                croppedWidth = 60,
                croppedHeight = 120,
                preparedWidth = 30,
                preparedHeight = 60,
            )

        assertEquals(
            OcrPixelRect(left = 40, top = 70, right = 80, bottom = 150),
            mapper.toOriginal(OcrPixelRect(left = 5, top = 10, right = 25, bottom = 50)),
        )
    }

    @Test
    fun `maps 270 degree rotation with crop back to original pixels`() {
        val mapper =
            OcrCoordinateMapper(
                originalWidth = 100,
                originalHeight = 200,
                decodedWidth = 100,
                decodedHeight = 200,
                rotationDegrees = 270,
                cropLeft = 30,
                cropTop = 15,
                croppedWidth = 120,
                croppedHeight = 60,
                preparedWidth = 60,
                preparedHeight = 30,
            )

        assertEquals(
            OcrPixelRect(left = 35, top = 50, right = 75, bottom = 130),
            mapper.toOriginal(OcrPixelRect(left = 10, top = 5, right = 50, bottom = 25)),
        )
    }
}
