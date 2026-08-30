package com.pagebinder.app.image

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.pagelist.PageThumbnailRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class FilePageThumbnailLoader(
    private val filesDirectory: File,
    private val pageRepository: PageRepository,
) : PageThumbnailLoader {
    override suspend fun load(request: PageThumbnailRequest) =
        withContext(Dispatchers.IO) {
            val page = pageRepository.findById(request.pageId) ?: return@withContext null
            val sourceFile = safeChildFile(filesDirectory, page.originalImagePath)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = calculateSampleSize(bounds.outWidth, request.targetWidthPx)
                }
            val source = BitmapFactory.decodeFile(sourceFile.path, options) ?: return@withContext null
            try {
                BitmapImageTransformer
                    .transform(source, request.rotation, request.crop)
                    .asImageBitmap()
            } finally {
                if (!source.isRecycled) source.recycle()
            }
        }
}

private fun safeChildFile(
    root: File,
    relativePath: String,
): File {
    if (relativePath.startsWith(File.separator)) throw IOException("Page image path must be relative")
    val canonicalRoot = root.canonicalFile
    val candidate = File(canonicalRoot, relativePath).canonicalFile
    if (!candidate.path.startsWith(canonicalRoot.path + File.separator)) {
        throw IOException("Page image path escapes app storage")
    }
    return candidate
}

private fun calculateSampleSize(
    sourceWidth: Int,
    targetWidth: Int,
): Int {
    var sample = 1
    val safeTarget = targetWidth.coerceAtLeast(1)
    while (sourceWidth / (sample * 2) >= safeTarget) sample *= 2
    return sample
}
