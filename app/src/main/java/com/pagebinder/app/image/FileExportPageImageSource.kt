package com.pagebinder.app.image

import android.graphics.BitmapFactory
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.export.ExportPageImageSource
import com.pagebinder.app.storage.FileImageStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 書き出し用にページ画像を開く実装（[ExportPageImageSource]）。
 *
 * 元画像は読み出すだけで、回転・切り取りは派生画像として作る（非破壊 — FR-IMG-007 / AGENTS.md ルール5）。
 * 派生画像の切り取り境界は [BitmapImageTransformer]（= [ImageCoordinateTransformer] の丸め規則）に委ねる。
 * 編集が無いページは再エンコードせず元ファイルをそのまま流す（画質を落とさないため）。
 */
class FileExportPageImageSource(
    private val imageStore: FileImageStore,
) : ExportPageImageSource {
    override fun openOriginal(page: Page): InputStream = imageStore.resolve(page.originalImagePath).inputStream()

    override fun openEdited(page: Page): InputStream {
        val sourceFile = imageStore.resolve(page.originalImagePath)
        if (page.rotation == 0 && page.crop == PageCrop()) return sourceFile.inputStream()

        val source =
            BitmapFactory.decodeFile(sourceFile.path)
                ?: throw IOException("Page image could not be decoded")
        try {
            val derivative = BitmapImageTransformer.transform(source, page.rotation, page.crop)
            try {
                val buffer = ByteArrayOutputStream()
                BitmapImageCodec.write(derivative, BitmapImageFormat.WEBP_LOSSLESS, buffer)
                return ByteArrayInputStream(buffer.toByteArray())
            } finally {
                if (!derivative.isRecycled) derivative.recycle()
            }
        } catch (_: OutOfMemoryError) {
            // 書き出し全体を落とさず、Export Engine が扱える失敗にして ExportRecord へ残す
            throw IOException("Page image could not be transformed")
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }
}
