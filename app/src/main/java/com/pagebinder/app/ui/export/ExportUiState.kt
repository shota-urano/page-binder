package com.pagebinder.app.ui.export

import com.pagebinder.app.domain.ExportFailureCode
import com.pagebinder.app.domain.ExportPageRange
import com.pagebinder.app.domain.ExportPdfQuality
import com.pagebinder.app.domain.ExportProgressPhase
import com.pagebinder.app.domain.ExportStorageErrorCode
import com.pagebinder.app.domain.ExportType

/** ページ範囲の選び方（docs/design/11-export.md「ページ範囲」の行。展開後のUIは素材が無いため最小構成） */
enum class ExportPageRangeSelection {
    ALL,
    CUSTOM,
}

/** 「保存先を選んで書き出す」で開く SAF（ACTION_CREATE_DOCUMENT）の起動要求。1回で消費する */
data class ExportSafRequest(
    val suggestedFileName: String,
    val mimeType: String,
)

/** 進捗表示（docs/design/system/02-components.md「進捗」: 線形バー + パーセント + 内容 + キャンセル） */
data class ExportProgressUiState(
    val phase: ExportProgressPhase,
    val completedUnits: Int,
    val totalUnits: Int,
) {
    /** 0.0〜1.0。総数が未確定（0以下）のときは 0 とみなす */
    val fraction: Float
        get() = if (totalUnits <= 0) 0f else (completedUnits.toFloat() / totalUnits).coerceIn(0f, 1f)

    val percent: Int
        get() = (fraction * 100).toInt()
}

/** 失敗時の案内の種類（docs/specs/11-export.md §6 エラー処理） */
enum class ExportFailureGuidance {
    /** 利用者がキャンセルした */
    CANCELLED,

    /** PDF生成に失敗 → 画像PDF・Markdown へのフォールバックを案内する */
    PDF_FALLBACK,

    /** PDF以外の生成に失敗 */
    GENERATION_FAILED,

    /** 保存先が使えない（Google Drive 書き込み失敗を含む）→ 端末内保存への切替を案内する */
    DESTINATION_UNAVAILABLE,

    /** 書き込み自体の失敗 → 空き容量の確認と保存先変更を案内する */
    WRITE_FAILED,
}

/** 書き出しの結果表示（docs/design/11-export.md「未定事項」— 素材が無いため specs §6 から起こした） */
sealed interface ExportResultUiState {
    data object Succeeded : ExportResultUiState

    data class Failed(val guidance: ExportFailureGuidance) : ExportResultUiState
}

/**
 * 書き出し画面の UiState（docs/design/11-export.md）。
 *
 * 画面に出す値はすべてここから描く。モックのサンプル（書籍名・ページ数）は保持しない
 * （docs/design/system/03-principles.md「モック画像の読み方」）。
 */
data class ExportUiState(
    /** 書籍のページ数。ページ範囲の上限であり「すべて (1〜N)」の N */
    val pageCount: Int = 0,
    val format: ExportType = ExportType.SEARCHABLE_PDF,
    /** 拡張子を除いたファイル名。初期値は書籍タイトル */
    val fileName: String = "",
    val fileNameErrorVisible: Boolean = false,
    val pageRangeSelection: ExportPageRangeSelection = ExportPageRangeSelection.ALL,
    val pageRangeStartInput: String = "",
    val pageRangeEndInput: String = "",
    val pageRangeErrorVisible: Boolean = false,
    val pdfQuality: ExportPdfQuality = ExportPdfQuality.STANDARD,
    /** OCR が未処理・失敗のページ数（FR-EXP-009） */
    val ocrIncompletePageCount: Int = 0,
    /** 続行/中止を選ばせるダイアログの表示 */
    val ocrWarningDialogVisible: Boolean = false,
    /** 「続行」が選ばれたか。false の間は書き出しを開始しない（FR-EXP-009） */
    val ocrWarningAcknowledged: Boolean = false,
    val consent: ExportConsentUiState = ExportConsentUiState(),
    val safRequest: ExportSafRequest? = null,
    val progress: ExportProgressUiState? = null,
    val result: ExportResultUiState? = null,
) {
    /** 警告バナーを出すか（OCR未完了ページがある間は続行後も出したままにする） */
    val ocrWarningVisible: Boolean
        get() = ocrIncompletePageCount > 0

    /** PDF画質の行を見せるか。PDF以外の形式では意味を持たない（docs/design/11-export.md「インタラクション」） */
    val pdfQualityVisible: Boolean
        get() = format == ExportType.SEARCHABLE_PDF || format == ExportType.IMAGE_PDF

    /** 入力から解決したページ範囲。解決できない（入力が不正な）場合は null */
    val resolvedPageRange: ExportPageRange?
        get() =
            when (pageRangeSelection) {
                ExportPageRangeSelection.ALL -> if (pageCount >= 1) ExportPageRange.All else null
                ExportPageRangeSelection.CUSTOM -> boundedPageRange()
            }

    /** SAF に渡す拡張子付きファイル名（docs/specs/02-data-model.md §3.3） */
    val suggestedFileName: String
        get() = fileName.trim() + format.fileExtension

    /** 書き出し中は多重に開始させない */
    val exportInProgress: Boolean
        get() = progress != null

    /** OCR未完了の警告に対して「続行」がまだ選ばれていない状態か */
    val blockedByOcrWarning: Boolean
        get() = ocrIncompletePageCount > 0 && !ocrWarningAcknowledged

    /**
     * 書き出しを開始してよいか。
     * 権限確認（specs 12 §3.2）・OCR未完了の続行選択（FR-EXP-009）・入力の妥当性がすべて揃った時だけ true。
     */
    val canStartExport: Boolean
        get() =
            consent.canStartExport &&
                !blockedByOcrWarning &&
                !exportInProgress &&
                fileName.isNotBlank() &&
                resolvedPageRange != null

    private fun boundedPageRange(): ExportPageRange? {
        if (pageCount < 1) return null
        val first = pageRangeStartInput.trim().toIntOrNull() ?: return null
        val last = pageRangeEndInput.trim().toIntOrNull() ?: return null
        if (first < 1 || last > pageCount || last < first) return null
        return ExportPageRange.Bounded(first, last)
    }
}

/** docs/specs/02-data-model.md §3.3 の命名にそろえた拡張子 */
val ExportType.fileExtension: String
    get() =
        when (this) {
            ExportType.SEARCHABLE_PDF -> ".searchable.pdf"
            ExportType.IMAGE_PDF -> ".images.pdf"
            ExportType.MARKDOWN -> ".md"
            ExportType.TEXT_ZIP -> ".zip"
            ExportType.IMAGE_ZIP -> ".zip"
        }

/** SAF（ACTION_CREATE_DOCUMENT）へ渡す MIME タイプ */
val ExportType.safMimeType: String
    get() =
        when (this) {
            ExportType.SEARCHABLE_PDF, ExportType.IMAGE_PDF -> "application/pdf"
            ExportType.MARKDOWN -> "text/markdown"
            ExportType.TEXT_ZIP, ExportType.IMAGE_ZIP -> "application/zip"
        }

/** ExportRecord の errorCode を、利用者に見せる案内へ翻訳する（docs/specs/11-export.md §6） */
fun exportFailureGuidanceOf(
    errorCode: String,
    format: ExportType,
): ExportFailureGuidance =
    when (errorCode) {
        ExportFailureCode.CANCELLED -> ExportFailureGuidance.CANCELLED
        ExportFailureCode.GENERATION_FAILED ->
            if (format == ExportType.SEARCHABLE_PDF || format == ExportType.IMAGE_PDF) {
                ExportFailureGuidance.PDF_FALLBACK
            } else {
                ExportFailureGuidance.GENERATION_FAILED
            }
        ExportStorageErrorCode.DESTINATION_UNAVAILABLE.serializedName,
        ExportStorageErrorCode.DESTINATION_PERMISSION_DENIED.serializedName,
        -> ExportFailureGuidance.DESTINATION_UNAVAILABLE
        else -> ExportFailureGuidance.WRITE_FAILED
    }
