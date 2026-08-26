package com.pagebinder.app.domain

import java.io.InputStream

/** A fully generated app-private export that is ready to be copied to SAF. */
interface CompletedExportSource {
    val byteCount: Long

    fun openInputStream(): InputStream
}

data class ExportDestination(
    /** URI returned by the system ACTION_CREATE_DOCUMENT picker. */
    val uri: String,
)

sealed interface ExportStorageResult {
    data class Succeeded(val bytesWritten: Long) : ExportStorageResult

    data class Failed(val errorCode: ExportStorageErrorCode) : ExportStorageResult
}

enum class ExportStorageErrorCode(val serializedName: String) {
    SOURCE_INCOMPLETE("source_incomplete"),
    DESTINATION_UNAVAILABLE("destination_unavailable"),
    DESTINATION_PERMISSION_DENIED("destination_permission_denied"),
    WRITE_FAILED("write_failed"),
}

/**
 * Copies a completed temporary export to a URI selected by the system picker.
 *
 * Implementations do not search for, rename, replace, or delete same-named documents. Name
 * collision handling remains owned by ACTION_CREATE_DOCUMENT and its Document Provider.
 */
interface ExportStorageGateway {
    suspend fun write(
        source: CompletedExportSource,
        destination: ExportDestination,
    ): ExportStorageResult
}
