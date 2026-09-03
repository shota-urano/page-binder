package com.pagebinder.app.export

import com.pagebinder.app.domain.Page
import java.io.InputStream

/**
 * 書き出しがページ画像を開く境界（docs/specs/11-export.md §2 入力）。
 *
 * 元画像は非破壊で保持したまま読み出すだけで、上書き・削除の口は置かない（FR-IMG-007 / AGENTS.md ルール5）。
 * Bitmap を使う派生画像の生成は `image/` の実装が持ち、この境界には Android の型を出さない。
 */
interface ExportPageImageSource {
    /** 元画像そのまま（image_zip は「元画像 + manifest.json」— 同 §3.1） */
    fun openOriginal(page: Page): InputStream

    /** 回転・切り取りを適用した派生画像（PDF は編集後の見た目で出す — docs/specs/10-searchable-pdf.md §3） */
    fun openEdited(page: Page): InputStream
}
