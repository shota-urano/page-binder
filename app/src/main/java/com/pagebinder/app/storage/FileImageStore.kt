package com.pagebinder.app.storage

import android.graphics.Bitmap
import com.pagebinder.app.domain.CapturedFrame
import com.pagebinder.app.domain.ImageStore
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.StoredCaptureImage
import com.pagebinder.app.image.BitmapImageCodec
import com.pagebinder.app.image.BitmapImageFormat
import com.pagebinder.app.image.BitmapPerceptualHash
import com.pagebinder.app.image.BitmapQualityDetector
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Owns the app-private layout in data-model section 3.2 for original images and temporary files.
 *
 * Original images are written under `temp/` first, then renamed into `images/` only after the
 * encoder and image metadata calculations succeed. This class never exposes an operation that
 * overwrites or deletes an original image.
 */
class FileImageStore(
    private val filesDirectory: File,
) : ImageStore {
    override fun saveAtomically(
        projectId: UUID,
        pageId: UUID,
        frame: CapturedFrame,
    ): StoredCaptureImage {
        require(frame.argbPixels.size == frame.width * frame.height) { "Captured frame pixels do not match dimensions" }
        val bitmap = Bitmap.createBitmap(frame.argbPixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            val stored =
                saveOriginalAtomically(projectId, pageId) { output ->
                    val metadata = BitmapImageCodec.write(bitmap, BitmapImageFormat.WEBP_LOSSLESS, output)
                    EncodedCapture(
                        width = metadata.width,
                        height = metadata.height,
                        contentHash = metadata.contentHash,
                        perceptualHash = BitmapPerceptualHash.calculate(bitmap),
                        qualityState =
                            if (BitmapQualityDetector.analyze(bitmap).shouldIsolate) {
                                PageQualityState.BLACK
                            } else {
                                PageQualityState.NORMAL
                            },
                    )
                }
            return StoredCaptureImage(
                relativePath = stored.relativePath,
                width = stored.result.width,
                height = stored.result.height,
                contentHash = stored.result.contentHash,
                perceptualHash = stored.result.perceptualHash,
                qualityState = stored.result.qualityState,
                // CaptureOnePage uses this only to compensate a failed new DB insert. It is not
                // exposed by ImageStore, so it cannot be used to edit or delete stored originals.
                rollback = {
                    if (!stored.file.delete() && stored.file.exists()) {
                        throw IOException("Could not roll back newly captured image")
                    }
                },
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** Resolves a database relative path without allowing it to escape app-private storage. */
    fun resolve(relativePath: String): File {
        if (relativePath.isBlank() || File(relativePath).isAbsolute) {
            throw IOException("Image path must be relative")
        }
        val root = filesDirectory.canonicalFile
        val candidate = File(root, relativePath).canonicalFile
        if (!candidate.path.startsWith(root.path + File.separator)) {
            throw IOException("Image path escapes app storage")
        }
        return candidate
    }

    /** Removes only the contents of the project's `temp/` directory, never `images/`. */
    fun clearTemporaryFiles(projectId: UUID) {
        val temporaryDirectory = temporaryDirectory(projectId)
        if (!temporaryDirectory.exists()) return
        if (!temporaryDirectory.isDirectory) throw IOException("Project temp path is not a directory")

        val root = temporaryDirectory.toPath()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(
                    file: java.nio.file.Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: java.nio.file.Path,
                    failure: IOException?,
                ): FileVisitResult {
                    if (failure != null) throw failure
                    if (directory != root) Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    internal fun <T> saveOriginalAtomically(
        projectId: UUID,
        pageId: UUID,
        write: (OutputStream) -> T,
    ): StoredOriginalImage<T> {
        val imagesDirectory = imagesDirectory(projectId)
        val temporaryDirectory = temporaryDirectory(projectId)
        requireProjectDirectories(imagesDirectory, temporaryDirectory, exportsCacheDirectory(projectId))

        val imageFile = File(imagesDirectory, "$pageId.webp")
        if (imageFile.exists()) throw IOException("Original image already exists")
        val temporaryFile = File(temporaryDirectory, ".$pageId.capture")
        if (temporaryFile.exists()) throw IOException("Capture temporary file already exists")

        try {
            val result = temporaryFile.outputStream().buffered().use(write)
            // Both directories are within the same app-private project directory. renameTo maps to
            // the platform rename operation, publishing a complete file without copying it.
            if (!temporaryFile.renameTo(imageFile)) throw IOException("Could not publish captured image")
            return StoredOriginalImage(relativeImagePath(projectId, pageId), imageFile, result)
        } catch (failure: Exception) {
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                failure.addSuppressed(IOException("Could not remove incomplete temporary image"))
            }
            throw failure
        }
    }

    private fun imagesDirectory(projectId: UUID): File = File(projectDirectory(projectId), IMAGES_DIRECTORY)

    private fun temporaryDirectory(projectId: UUID): File = File(projectDirectory(projectId), TEMP_DIRECTORY)

    /** 書き出し前の一時出力先（docs/specs/02-data-model.md §3.2 `exports-cache/`）。Export Engine が使う */
    fun exportsCacheDirectory(projectId: UUID): File = File(projectDirectory(projectId), EXPORTS_CACHE_DIRECTORY)

    private fun projectDirectory(projectId: UUID): File =
        File(File(filesDirectory, PROJECTS_DIRECTORY), projectId.toString())

    private fun relativeImagePath(
        projectId: UUID,
        pageId: UUID,
    ): String = "$PROJECTS_DIRECTORY/$projectId/$IMAGES_DIRECTORY/$pageId.webp"

    private fun requireProjectDirectories(vararg directories: File) {
        if (directories.any { !it.isDirectory }) {
            throw IOException("Project file area is incomplete")
        }
    }

    private data class EncodedCapture(
        val width: Int,
        val height: Int,
        val contentHash: String,
        val perceptualHash: String,
        val qualityState: PageQualityState,
    )

    private companion object {
        const val PROJECTS_DIRECTORY = "projects"
        const val IMAGES_DIRECTORY = "images"
        const val TEMP_DIRECTORY = "temp"
        const val EXPORTS_CACHE_DIRECTORY = "exports-cache"
    }
}

internal data class StoredOriginalImage<T>(
    val relativePath: String,
    val file: File,
    val result: T,
)
