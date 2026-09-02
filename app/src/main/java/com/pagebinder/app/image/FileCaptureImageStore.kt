package com.pagebinder.app.image

import com.pagebinder.app.domain.ImageStore
import com.pagebinder.app.storage.FileImageStore
import java.io.File

/**
 * Compatibility wrapper for callers that still use the pre-storage-package class name.
 *
 * App-private image persistence is centralized in [FileImageStore].
 */
@Deprecated("Use FileImageStore from the storage package")
class FileCaptureImageStore(
    private val filesDirectory: File,
) : ImageStore by FileImageStore(filesDirectory)
