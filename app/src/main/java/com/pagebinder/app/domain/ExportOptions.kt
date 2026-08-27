package com.pagebinder.app.domain

import java.util.UUID

/**
 * 書き出すページの範囲（docs/specs/11-export.md §3.2「ページ範囲指定は MVP に含める（確定）」）。
 * ページ番号は 1 始まりで、両端を含む。
 */
sealed interface ExportPageRange {
    /** 書籍の全ページ */
    data object All : ExportPageRange

    /** [firstPage]〜[lastPage]（両端を含む） */
    data class Bounded(
        val firstPage: Int,
        val lastPage: Int,
    ) : ExportPageRange {
        init {
            require(firstPage >= 1) { "Page range must start at 1 or later" }
            require(lastPage >= firstPage) { "Page range must not end before it starts" }
        }
    }
}

/**
 * PDF の画質。
 *
 * **暫定値**: docs/design/11-export.md「未定事項」に「PDF画質の選択肢と既定値」とあり、
 * specs 側にも列挙が無い。素材に現れる「標準」を既定とし、上下1段ずつを置いた暫定の3段階。
 * 実際の解像度・圧縮率は PDF 実装（docs/specs/10-searchable-pdf.md）側で確定する。
 */
enum class ExportPdfQuality(val serializedName: String) {
    HIGH("high"),
    STANDARD("standard"),
    COMPACT("compact"),
}

/**
 * 書き出し画面が確定させた書き出し条件（docs/specs/11-export.md §3.2 手順1・4）。
 *
 * [fileName] は拡張子を含む最終的なファイル名（docs/specs/02-data-model.md §3.3 の命名）。
 * [destination] は SAF（ACTION_CREATE_DOCUMENT）で利用者が選んだ保存先。
 */
data class ExportOptions(
    val projectId: UUID,
    val type: ExportType,
    val fileName: String,
    val pageRange: ExportPageRange,
    val pdfQuality: ExportPdfQuality,
    val destination: ExportDestination,
) {
    init {
        require(fileName.isNotBlank()) { "Export file name must not be blank" }
        require(destination.uri.isNotBlank()) { "Export destination must not be blank" }
    }
}
