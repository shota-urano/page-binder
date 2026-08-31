package com.pagebinder.app.image

import android.graphics.Bitmap
import com.pagebinder.app.domain.CaptureImageStore
import com.pagebinder.app.domain.CapturedFrame
import com.pagebinder.app.domain.StoredCaptureImage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Stores a newly captured original as WebP lossless via temp-write then rename.  It never touches
 * an existing original image; [StoredCaptureImage.rollback] removes only this failed new capture.
 */
class FileCaptureImageStore(
    private val filesDirectory: File,
) : CaptureImageStore {
    override fun saveAtomically(
        projectId: UUID,
        pageId: UUID,
        frame: CapturedFrame,
    ): StoredCaptureImage {
        require(frame.argbPixels.size == frame.width * frame.height) { "Captured frame pixels do not match dimensions" }
        val imagesDirectory =
            File(File(File(filesDirectory, PROJECTS_DIRECTORY), projectId.toString()), IMAGES_DIRECTORY)
        if (!imagesDirectory.isDirectory && !imagesDirectory.mkdirs()) {
            throw IOException("Could not create capture image directory")
        }
        val imageFile = File(imagesDirectory, "$pageId.webp")
        val temporaryFile = File(imagesDirectory, ".$pageId.capture")
        val bitmap = Bitmap.createBitmap(frame.argbPixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            val metadata =
                FileOutputStream(temporaryFile).use { output ->
                    BitmapImageCodec.write(bitmap, BitmapImageFormat.WEBP_LOSSLESS, output)
                }
            val perceptualHash = BitmapPerceptualHash.calculate(bitmap)
            if (!temporaryFile.renameTo(imageFile)) throw IOException("Could not publish captured image")
            return StoredCaptureImage(
                relativePath = "$PROJECTS_DIRECTORY/$projectId/$IMAGES_DIRECTORY/${imageFile.name}",
                width = metadata.width,
                height = metadata.height,
                contentHash = metadata.contentHash,
                perceptualHash = perceptualHash,
                qualityState =
                    if (BitmapQualityDetector.analyze(bitmap).shouldIsolate) {
                        com.pagebinder.app.domain.PageQualityState.BLACK
                    } else {
                        com.pagebinder.app.domain.PageQualityState.NORMAL
                    },
                rollback = {
                    if (!imageFile.delete() && imageFile.exists()) {
                        throw IOException("Could not roll back captured image")
                    }
                },
            )
        } catch (failure: Exception) {
            temporaryFile.delete()
            throw failure
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val PROJECTS_DIRECTORY = "projects"
        const val IMAGES_DIRECTORY = "images"
    }
}
