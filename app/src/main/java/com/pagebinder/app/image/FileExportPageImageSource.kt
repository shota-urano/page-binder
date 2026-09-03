package com.pagebinder.app.image

import android.graphics.BitmapFactory
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.export.ExportPageImageSource
import com.pagebinder.app.storage.FileImageStore
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 書き出し用にページ画像を開く実装（[ExportPageImageSource]）。
 *
 * 元画像は読み出すだけで、回転・切り取りは派生画像として作る（非破壊 — FR-IMG-007 / AGENTS.md ルール5）。
 * 派生画像の切り取り境界は [BitmapImageTransformer]（= [ImageCoordinateTransformer] の丸め規則）に委ねる。
 * 編集が無いページは再エンコードせず元ファイルをそのまま流す（画質を落とさないため）。編集済み
 * ページは派生 WebP を `temp/` へスプールしてからファイルストリームとして返す。これにより
 * `ByteArrayOutputStream` でページ全体をヒープへ保持せず、ストリームを閉じた時点で派生ファイルも
 * 解放される。
 */
class FileExportPageImageSource(
    private val imageStore: FileImageStore,
) : ExportPageImageSource {
    override fun openOriginal(page: Page): InputStream = imageStore.resolve(page.originalImagePath).inputStream()

    override fun openEdited(page: Page): InputStream {
        val sourceFile = imageStore.resolve(page.originalImagePath)
        if (page.rotation == 0 && page.crop == PageCrop()) return sourceFile.inputStream()

        var derivativeFile: File? = null
        var streamOwnsDerivative = false
        val source = BitmapFactory.decodeFile(sourceFile.path) ?: throw IOException("Page image could not be decoded")
        try {
            val derivative = BitmapImageTransformer.transform(source, page.rotation, page.crop)
            try {
                derivativeFile =
                    imageStore.createTemporaryDerivativeFile(
                        projectId = page.projectId,
                        prefix = TEMPORARY_DERIVATIVE_PREFIX,
                        suffix = TEMPORARY_DERIVATIVE_SUFFIX,
                    )
                derivativeFile.outputStream().buffered().use { output ->
                    BitmapImageCodec.write(derivative, BitmapImageFormat.WEBP_LOSSLESS, output)
                }
                val input = DeleteOnCloseInputStream(requireNotNull(derivativeFile))
                streamOwnsDerivative = true
                return input
            } finally {
                if (!derivative.isRecycled) derivative.recycle()
            }
        } catch (_: OutOfMemoryError) {
            // 書き出し全体を落とさず、Export Engine が扱える失敗にして ExportRecord へ残す
            throw IOException("Page image could not be transformed")
        } finally {
            if (!source.isRecycled) source.recycle()
            if (!streamOwnsDerivative) derivativeFile?.let(::deleteIfPresent)
        }
    }

    /** Deletes its one-page spool file exactly when the export consumer releases the stream. */
    private class DeleteOnCloseInputStream(
        private val temporaryFile: File,
    ) : FilterInputStream(temporaryFile.inputStream()) {
        override fun close() {
            var closeFailure: IOException? = null
            try {
                super.close()
            } catch (failure: IOException) {
                closeFailure = failure
            }
            try {
                deleteIfPresent(temporaryFile)
            } catch (failure: IOException) {
                if (closeFailure == null) closeFailure = failure else closeFailure.addSuppressed(failure)
            }
            closeFailure?.let { throw it }
        }
    }

    private companion object {
        const val TEMPORARY_DERIVATIVE_PREFIX = "export-page-"
        const val TEMPORARY_DERIVATIVE_SUFFIX = ".webp"

        fun deleteIfPresent(file: File) {
            if (file.exists() && !file.delete()) throw IOException("Could not remove temporary page image")
        }
    }
}
