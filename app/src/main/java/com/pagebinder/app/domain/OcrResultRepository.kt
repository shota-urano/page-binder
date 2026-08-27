package com.pagebinder.app.domain

import java.util.UUID

/**
 * OCR結果の読み出しと手動修正の保存（docs/specs/09-ocr.md §5「OcrResultRepository: 結果保存・editedText 管理」）。
 *
 * 手動修正は [StoredOcrResult.editedText] へ入り、OCRエンジンが出した [StoredOcrResult.fullText] と
 * blocksJson は書き換えない（同 §3.5「元のOCR結果 fullText は保持」）。
 * 「元のOCR結果へ戻す」は editedText だけを破棄する操作で、この境界には fullText を変更する口を置かない。
 *
 * OCR結果そのものの生成・保存は [OcrJobRepository]（ワーカー側）の担当で、こちらは編集画面からの読み書き専用。
 */
interface OcrResultRepository {
    suspend fun findByPageId(pageId: UUID): StoredOcrResult?

    /**
     * 手動修正を editedText へ保存する。fullText・blocksJson には触れない。
     * 対象ページのOCR結果が無ければ false。
     */
    suspend fun saveEditedText(
        pageId: UUID,
        editedText: String,
    ): Boolean

    /** 「元のOCR結果へ戻す」。editedText を null に戻すだけで、fullText は残る */
    suspend fun clearEditedText(pageId: UUID): Boolean
}
