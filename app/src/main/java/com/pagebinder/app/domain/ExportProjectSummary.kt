package com.pagebinder.app.domain

import java.util.UUID

/**
 * 書き出し画面が必要とする書籍プロジェクトの要約（docs/specs/11-export.md §2 入力）。
 *
 * 書籍詳細画面（docs/design/03-book-detail.md）から書き出し画面へ渡される。
 * BookProject / Page / OcrResult のリポジトリが入ったら、そこから組み立てて渡す
 * （画面がデータソースへ直接触らない — AGENTS.md ルール8）。
 */
data class ExportProjectSummary(
    val projectId: UUID,
    /** 書籍タイトル。ファイル名の初期値に使う（ログへ出さない — AGENTS.md ルール6） */
    val title: String,
    val pageCount: Int,
    /** OCR が未処理・失敗のページ数（FR-EXP-009 の警告件数） */
    val ocrIncompletePageCount: Int,
) {
    init {
        require(pageCount >= 0) { "Page count must not be negative" }
        require(ocrIncompletePageCount in 0..pageCount) {
            "Incomplete OCR page count must be within the project page count"
        }
    }
}
