package com.pagebinder.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.storage.FileImageStore
import com.pagebinder.app.storage.FileProjectFileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FileExportPageImageSourceTest {
    private val projectId = UUID.fromString("80000000-0000-0000-0000-000000000001")
    private val pageId = UUID.fromString("80000000-0000-0000-0000-000000000002")
    private lateinit var filesDirectory: File
    private lateinit var imageStore: FileImageStore

    @Before
    fun setUp() {
        filesDirectory = File(context.cacheDir, "export-image-source-${UUID.randomUUID()}")
        assertTrue(filesDirectory.mkdirs())
        imageStore = FileImageStore(filesDirectory)
        FileProjectFileStore(filesDirectory).create(projectId)
    }

    @After
    fun tearDown() {
        filesDirectory.deleteRecursively()
    }

    @Test
    fun editedImageIsSpooledPerPageAndRemovedWhenItsStreamCloses() {
        val page = page(rotation = 90)
        writeOriginal(page)

        val input = FileExportPageImageSource(imageStore).openEdited(page)
        val temporaryDirectory = filesDirectory.resolve("projects/$projectId/temp")
        assertEquals(1, temporaryDirectory.listFiles().orEmpty().size)

        input.use { stream ->
            val decoded = BitmapFactory.decodeStream(stream)
            assertNotNull(decoded)
            decoded!!.recycle()
        }

        assertTrue(temporaryDirectory.listFiles().isNullOrEmpty())
    }

    @Test
    fun retainsAtMostOneEncodedPageOfJavaHeapAcross100EditedExportReads() {
        val page = page(rotation = 90)
        val encodedPageBytes = writeNoisyOriginal(page)
        assertTrue(
            "The test page must be large enough to expose a full-buffer regression",
            encodedPageBytes >= MIN_PAGE_BYTES,
        )

        val source = FileExportPageImageSource(imageStore)
        val inputs = mutableListOf<InputStream>()
        val baselineBytes = usedJavaHeapBytes()
        try {
            repeat(PAGE_COUNT) { inputs += source.openEdited(page) }

            val retainedBytes = usedJavaHeapBytes() - baselineBytes
            val maximumRetainedBytes = encodedPageBytes * MAXIMUM_RETAINED_PAGE_BUFFERS
            assertTrue(
                "100 edited export reads retained $retainedBytes Java-heap bytes; " +
                    "the $maximumRetainedBytes-byte limit is two $encodedPageBytes-byte pages",
                retainedBytes <= maximumRetainedBytes,
            )

            // All 100 real page payloads are present on disk, not as Java byte arrays retained by
            // their InputStreams. This also guards the measurement's setup: a legacy in-memory
            // ByteArrayInputStream implementation leaves this directory empty.
            assertEquals(PAGE_COUNT, temporaryDerivativeFiles().size)
        } finally {
            inputs.forEach(InputStream::close)
        }
        assertTrue(temporaryDerivativeFiles().isEmpty())
    }

    private fun writeOriginal(page: Page) {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        try {
            imageStore.resolve(page.originalImagePath).outputStream().use { output ->
                BitmapImageCodec.write(bitmap, BitmapImageFormat.WEBP_LOSSLESS, output)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeNoisyOriginal(page: Page): Long {
        val pixels = IntArray(MEMORY_TEST_IMAGE_WIDTH * MEMORY_TEST_IMAGE_HEIGHT)
        var state = 0x13579bdf
        pixels.indices.forEach { index ->
            state = state * 1_103_515_245 + 12_345
            pixels[index] = Color.rgb(state ushr 16, state ushr 8, state)
        }
        val bitmap =
            Bitmap.createBitmap(
                pixels,
                MEMORY_TEST_IMAGE_WIDTH,
                MEMORY_TEST_IMAGE_HEIGHT,
                Bitmap.Config.ARGB_8888,
            )
        try {
            imageStore.resolve(page.originalImagePath).outputStream().use { output ->
                BitmapImageCodec.write(bitmap, BitmapImageFormat.WEBP_LOSSLESS, output)
            }
        } finally {
            bitmap.recycle()
        }
        return imageStore.resolve(page.originalImagePath).length()
    }

    private fun temporaryDerivativeFiles(): List<File> =
        filesDirectory.resolve("projects/$projectId/temp")
            .listFiles { file -> file.name.startsWith("export-page-") && file.name.endsWith(".webp") }
            .orEmpty()
            .toList()

    private fun usedJavaHeapBytes(): Long {
        Runtime.getRuntime().gc()
        Runtime.getRuntime().runFinalization()
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun page(rotation: Int) =
        Page(
            id = pageId,
            projectId = projectId,
            sequence = 1,
            originalImagePath = "projects/$projectId/images/$pageId.webp",
            width = 32,
            height = 24,
            rotation = rotation,
            crop = PageCrop(),
            capturedAt = Instant.EPOCH,
            contentHash = "content-hash",
            perceptualHash = "perceptual-hash",
            qualityState = PageQualityState.NORMAL,
            ocrState = PageOcrState.PENDING,
        )

    private companion object {
        const val PAGE_COUNT = 100
        const val MEMORY_TEST_IMAGE_WIDTH = 256
        const val MEMORY_TEST_IMAGE_HEIGHT = 256
        const val MIN_PAGE_BYTES = 128 * 1024L
        const val MAXIMUM_RETAINED_PAGE_BUFFERS = 2L

        val context
            get() = InstrumentationRegistry.getInstrumentation().targetContext
    }
}
