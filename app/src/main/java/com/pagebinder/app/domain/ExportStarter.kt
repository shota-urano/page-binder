package com.pagebinder.app.domain

/**
 * 書き出しの開始要求（docs/specs/11-export.md §3.2 手順4以降）。
 *
 * 実処理（形式別ジェネレータ・SAF出力・進捗）は `export/` の Export Engine が実装する
 * — pagebinder-gph.5 の範囲であり、本タスクでは実装しない。
 * 出力形式・ファイル名・保存先URI の引き渡しは書き出し画面（pagebinder-gph.6）で引数として追加する。
 */
fun interface ExportStarter {
    suspend fun startExport()
}
