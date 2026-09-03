package com.pagebinder.app.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCoordinateTransformerTest {
    @Test
    fun `clockwise 90 degree coordinates round trip`() {
        assertRoundTrip(rotationDegrees = 90, expectedRotated = ImagePoint(39f, 37f))
    }

    @Test
    fun `clockwise 180 degree coordinates round trip`() {
        assertRoundTrip(rotationDegrees = 180, expectedRotated = ImagePoint(163f, 39f))
    }

    @Test
    fun `clockwise 270 degree coordinates round trip`() {
        assertRoundTrip(rotationDegrees = 270, expectedRotated = ImagePoint(61f, 163f))
    }

    @Test
    fun `cropped coordinates round trip`() {
        val transformer =
            ImageCoordinateTransformer.create(
                sourceWidth = 200,
                sourceHeight = 100,
                rotationDegrees = 90,
                cropLeft = 0.25f,
                cropTop = 0.1f,
                cropRight = 0.75f,
                cropBottom = 0.9f,
            )
        val source = ImageRect(left = 40f, top = 25f, right = 80f, bottom = 75f)

        val cropped = transformer.sourceToCropped(source)
        val restored = transformer.croppedToSource(cropped)

        assertRect(ImageRect(left = 0f, top = 20f, right = 50f, bottom = 60f), cropped)
        assertRect(source, restored)
        assertEquals(50f, transformer.croppedSize.width, TOLERANCE)
        assertEquals(160f, transformer.croppedSize.height, TOLERANCE)
    }

    @Test
    fun `clockwise 180 degree coordinates with crop round trip`() {
        val transformer =
            ImageCoordinateTransformer.create(
                sourceWidth = 200,
                sourceHeight = 100,
                rotationDegrees = 180,
                cropLeft = 0.1f,
                cropTop = 0.2f,
                cropRight = 0.8f,
                cropBottom = 0.9f,
            )
        val source = ImageRect(left = 50f, top = 30f, right = 100f, bottom = 70f)

        val cropped = transformer.sourceToCropped(source)
        val restored = transformer.croppedToSource(cropped)

        assertRect(ImageRect(left = 80f, top = 10f, right = 130f, bottom = 50f), cropped)
        assertRect(source, restored)
        assertEquals(140f, transformer.croppedSize.width, TOLERANCE)
        assertEquals(70f, transformer.croppedSize.height, TOLERANCE)
    }

    @Test
    fun `clockwise 270 degree coordinates with crop round trip`() {
        val transformer =
            ImageCoordinateTransformer.create(
                sourceWidth = 200,
                sourceHeight = 100,
                rotationDegrees = 270,
                cropLeft = 0.2f,
                cropTop = 0.1f,
                cropRight = 0.9f,
                cropBottom = 0.8f,
            )
        val source = ImageRect(left = 50f, top = 30f, right = 120f, bottom = 70f)

        val cropped = transformer.sourceToCropped(source)
        val restored = transformer.croppedToSource(cropped)

        assertRect(ImageRect(left = 10f, top = 60f, right = 50f, bottom = 130f), cropped)
        assertRect(source, restored)
        assertEquals(70f, transformer.croppedSize.width, TOLERANCE)
        assertEquals(140f, transformer.croppedSize.height, TOLERANCE)
    }

    @Test
    fun `encloses non integer crop edges with the derivative pixel bounds`() {
        assertPixelCrop(
            cropLeft = 0.123f,
            cropRight = 0.876f,
            expected = ImagePixelRect(left = 12, top = 0, right = 88, bottom = 100),
        )
        assertPixelCrop(
            cropLeft = 0.53f,
            cropRight = 1f,
            expected = ImagePixelRect(left = 53, top = 0, right = 100, bottom = 100),
        )
        assertPixelCrop(
            cropLeft = 0.129995f,
            cropRight = 0.870005f,
            expected = ImagePixelRect(left = 12, top = 0, right = 88, bottom = 100),
        )
    }

    private fun assertPixelCrop(
        cropLeft: Float,
        cropRight: Float,
        expected: ImagePixelRect,
    ) {
        val transformer =
            ImageCoordinateTransformer.create(
                sourceWidth = 100,
                sourceHeight = 100,
                rotationDegrees = 0,
                cropLeft = cropLeft,
                cropRight = cropRight,
            )

        assertEquals(expected, transformer.pixelCropBounds)
        assertEquals(expected.width.toFloat(), transformer.pixelCroppedSize.width, TOLERANCE)
        assertEquals(expected.height.toFloat(), transformer.pixelCroppedSize.height, TOLERANCE)
        assertEquals(expected.left.toFloat(), transformer.pixelCroppedToSource.map(ImagePoint(0f, 0f)).x, TOLERANCE)
    }

    private fun assertRoundTrip(
        rotationDegrees: Int,
        expectedRotated: ImagePoint,
    ) {
        val transformer =
            ImageCoordinateTransformer.create(
                sourceWidth = 200,
                sourceHeight = 100,
                rotationDegrees = rotationDegrees,
            )
        val source = ImagePoint(x = 37f, y = 61f)

        val rotated = transformer.sourceToCropped(source)
        val restored = transformer.croppedToSource(rotated)

        assertEquals(expectedRotated.x, rotated.x, TOLERANCE)
        assertEquals(expectedRotated.y, rotated.y, TOLERANCE)
        assertEquals(source.x, restored.x, TOLERANCE)
        assertEquals(source.y, restored.y, TOLERANCE)
    }

    private fun assertRect(
        expected: ImageRect,
        actual: ImageRect,
    ) {
        assertEquals(expected.left, actual.left, TOLERANCE)
        assertEquals(expected.top, actual.top, TOLERANCE)
        assertEquals(expected.right, actual.right, TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, TOLERANCE)
    }

    companion object {
        private const val TOLERANCE = 0.0001f
    }
}
