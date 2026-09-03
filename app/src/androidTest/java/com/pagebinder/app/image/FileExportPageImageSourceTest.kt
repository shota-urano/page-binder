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
        val context
            get() = InstrumentationRegistry.getInstrumentation().targetContext
    }
}
