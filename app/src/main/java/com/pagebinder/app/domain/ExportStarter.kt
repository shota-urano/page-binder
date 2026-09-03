package com.pagebinder.app.domain

import kotlinx.coroutines.flow.Flow

/** 書き出しの進行段階（`export/` の Export Engine が報告する段階を domain 語彙で表したもの） */
enum class ExportProgressPhase {
    QUEUED,
    GENERATING,
    WRITING,
}

/** 書き出しの経過（docs/specs/11-export.md §3.2 手順5・6） */
sealed interface ExportProgressEvent {
    data class Progress(
        val phase: ExportProgressPhase,
        val completedUnits: Int,
        val totalUnits: Int,
    ) : ExportProgressEvent

    /** 保存先への書き込みが確定した（不完全なファイルは成功扱いしない — FR-EXP-007） */
    data object Succeeded : ExportProgressEvent

    /** [errorCode] は ExportRecord に記録される失敗コード（[ExportFailureCode] / [ExportStorageErrorCode]） */
    data class Failed(val errorCode: String) : ExportProgressEvent
}

/** Export Engine が保存系以外の失敗に付ける errorCode */
object ExportFailureCode {
    const val CANCELLED = "cancelled"
    const val GENERATION_FAILED = "generation_failed"

    /**
     * プロセス終了で取り残された書き出しを、再試行が成功したあとに終端させるときの理由。
     * 完了で確定していない書き出しは成功にできない（FR-EXP-007）ので failed 側で閉じる。
     */
    const val INTERRUPTED = "interrupted"
}

/**
 * 書き出しの開始要求（docs/specs/11-export.md §3.2 手順4以降）。
 *
 * 実処理（形式別ジェネレータ・SAF出力・進捗）は `export/` の Export Engine が実装する。
 * 書き出し画面はこの境界だけを見る（Room / PDFBox の型は越えてこない — AGENTS.md ルール4）。
 *
 * 返す [Flow] はコレクションを止めると書き出しをキャンセルする（ExportRecord は failed + cancelled）。
 */
fun interface ExportStarter {
    fun startExport(options: ExportOptions): Flow<ExportProgressEvent>
}
