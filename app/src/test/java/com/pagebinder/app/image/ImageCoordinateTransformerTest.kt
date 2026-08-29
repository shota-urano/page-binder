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
