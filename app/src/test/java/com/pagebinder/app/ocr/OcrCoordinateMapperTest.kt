package com.pagebinder.app.ocr

import com.pagebinder.app.image.ImageCoordinateTransformer
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrCoordinateMapperTest {
    @Test
    fun `maps rotation crop and shrink coordinates back to original pixels`() {
        val mapper =
            mapper(
                originalWidth = 100,
                originalHeight = 200,
                decodedWidth = 100,
                decodedHeight = 200,
                rotationDegrees = 90,
                cropLeft = 0.1f,
                cropTop = 0.1f,
                cropRight = 0.9f,
                cropBottom = 0.9f,
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
            mapper(
                originalWidth = 4000,
                originalHeight = 2000,
                decodedWidth = 2000,
                decodedHeight = 1000,
                rotationDegrees = 0,
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
            mapper(
                originalWidth = 100,
                originalHeight = 200,
                decodedWidth = 100,
                decodedHeight = 200,
                rotationDegrees = 180,
                cropLeft = 0.1f,
                cropTop = 0.15f,
                cropRight = 0.7f,
                cropBottom = 0.75f,
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
            mapper(
                originalWidth = 100,
                originalHeight = 200,
                decodedWidth = 100,
                decodedHeight = 200,
                rotationDegrees = 270,
                cropLeft = 0.15f,
                cropTop = 0.15f,
                cropRight = 0.75f,
                cropBottom = 0.75f,
                preparedWidth = 60,
                preparedHeight = 30,
            )

        assertEquals(
            OcrPixelRect(left = 35, top = 50, right = 75, bottom = 130),
            mapper.toOriginal(OcrPixelRect(left = 10, top = 5, right = 50, bottom = 25)),
        )
    }

    @Test
    fun `maps OCR coordinates through the exact integer crop edges`() {
        assertFullCropMapsToOriginal(cropLeft = 0.123f, cropRight = 0.876f, expectedLeft = 12, expectedRight = 88)
        assertFullCropMapsToOriginal(cropLeft = 0.53f, cropRight = 1f, expectedLeft = 53, expectedRight = 100)
        assertFullCropMapsToOriginal(
            cropLeft = 0.129995f,
            cropRight = 0.870005f,
            expectedLeft = 12,
            expectedRight = 88,
        )
    }

    private fun assertFullCropMapsToOriginal(
        cropLeft: Float,
        cropRight: Float,
        expectedLeft: Int,
        expectedRight: Int,
    ) {
        val coordinates =
            ImageCoordinateTransformer.create(
                sourceWidth = 100,
                sourceHeight = 100,
                rotationDegrees = 0,
                cropLeft = cropLeft,
                cropRight = cropRight,
            )
        val mapper =
            OcrCoordinateMapper(
                originalWidth = 100,
                originalHeight = 100,
                decodedWidth = 100,
                decodedHeight = 100,
                coordinates = coordinates,
                preparedWidth = coordinates.pixelCropBounds.width,
                preparedHeight = coordinates.pixelCropBounds.height,
            )

        assertEquals(
            OcrPixelRect(left = expectedLeft, top = 0, right = expectedRight, bottom = 100),
            mapper.toOriginal(
                OcrPixelRect(
                    left = 0,
                    top = 0,
                    right = coordinates.pixelCropBounds.width,
                    bottom = coordinates.pixelCropBounds.height,
                ),
            ),
        )
    }

    private fun mapper(
        originalWidth: Int,
        originalHeight: Int,
        decodedWidth: Int,
        decodedHeight: Int,
        rotationDegrees: Int,
        cropLeft: Float = 0f,
        cropTop: Float = 0f,
        cropRight: Float = 1f,
        cropBottom: Float = 1f,
        preparedWidth: Int,
        preparedHeight: Int,
    ): OcrCoordinateMapper =
        OcrCoordinateMapper(
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            decodedWidth = decodedWidth,
            decodedHeight = decodedHeight,
            coordinates =
                ImageCoordinateTransformer.create(
                    sourceWidth = decodedWidth,
                    sourceHeight = decodedHeight,
                    rotationDegrees = rotationDegrees,
                    cropLeft = cropLeft,
                    cropTop = cropTop,
                    cropRight = cropRight,
                    cropBottom = cropBottom,
                ),
            preparedWidth = preparedWidth,
            preparedHeight = preparedHeight,
        )
}
