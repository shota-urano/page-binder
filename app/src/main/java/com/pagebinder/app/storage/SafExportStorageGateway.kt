package com.pagebinder.app.storage

import android.content.ContentResolver
import android.net.Uri
import com.pagebinder.app.domain.CompletedExportSource
import com.pagebinder.app.domain.ExportDestination
import com.pagebinder.app.domain.ExportStorageErrorCode
import com.pagebinder.app.domain.ExportStorageGateway
import com.pagebinder.app.domain.ExportStorageResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

class SafExportStorageGateway internal constructor(
    private val outputStreamOpener: SafOutputStreamOpener,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExportStorageGateway {
    constructor(
        contentResolver: ContentResolver,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(ContentResolverOutputStreamOpener(contentResolver), ioDispatcher)

    override suspend fun write(
        source: CompletedExportSource,
        destination: ExportDestination,
    ): ExportStorageResult =
        withContext(ioDispatcher) {
            if (source.byteCount < 0L) {
                return@withContext ExportStorageResult.Failed(ExportStorageErrorCode.SOURCE_INCOMPLETE)
            }

            try {
                val output =
                    outputStreamOpener.open(destination.uri)
                        ?: return@withContext ExportStorageResult.Failed(
                            ExportStorageErrorCode.DESTINATION_UNAVAILABLE,
                        )

                val bytesWritten =
                    output.use { target ->
                        source.openInputStream().use { input -> input.copyTo(target) }
                    }
                if (bytesWritten != source.byteCount) {
                    ExportStorageResult.Failed(ExportStorageErrorCode.SOURCE_INCOMPLETE)
                } else {
                    ExportStorageResult.Succeeded(bytesWritten)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                ExportStorageResult.Failed(ExportStorageErrorCode.DESTINATION_PERMISSION_DENIED)
            } catch (_: IOException) {
                ExportStorageResult.Failed(ExportStorageErrorCode.WRITE_FAILED)
            } catch (_: RuntimeException) {
                ExportStorageResult.Failed(ExportStorageErrorCode.WRITE_FAILED)
            }
        }
}

internal fun interface SafOutputStreamOpener {
    fun open(uri: String): OutputStream?
}

private class ContentResolverOutputStreamOpener(
    private val contentResolver: ContentResolver,
) : SafOutputStreamOpener {
    override fun open(uri: String): OutputStream? = contentResolver.openOutputStream(Uri.parse(uri), "w")
}

/** File-backed source restricted to the app-private exports-cache directory. */
class CompletedCacheExport private constructor(
    private val file: File,
    override val byteCount: Long,
) : CompletedExportSource {
    override fun openInputStream(): InputStream {
        check(file.isFile && file.length() == byteCount) { "Completed export changed before copy" }
        return file.inputStream()
    }

    companion object {
        fun open(
            exportsCacheDirectory: File,
            completedFile: File,
        ): CompletedCacheExport {
            val cacheDirectory = exportsCacheDirectory.canonicalFile
            val source = completedFile.canonicalFile
            require(source.isFile) { "Completed export must be a regular file" }
            require(source.parentFile == cacheDirectory) {
                "Completed export must be directly inside exports-cache"
            }
            return CompletedCacheExport(source, source.length())
        }
    }
}
