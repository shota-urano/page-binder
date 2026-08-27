package com.pagebinder.app.ui.pagelist

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** 並べ替え対象1件の枠（ビューポート座標） */
data class PageReorderBounds(
    val index: Int,
    val offset: Offset,
    val size: Size,
)

/**
 * 並べ替えが必要とする最小限のレイアウト情報。
 * グリッド（2次元）とリスト（縦のみ）の当たり判定の差をここで吸収する。
 */
interface PageReorderLayout {
    /** 表示中の [index] 番目の枠。画面外なら null */
    fun boundsAt(index: Int): PageReorderBounds?

    /** [position] を含む枠。どの枠にも入っていなければ null */
    fun boundsAt(position: Offset): PageReorderBounds?
}

/** グリッドは縦横どちらもセル幅で区切られるので、2次元の内包判定にする */
fun LazyGridState.asPageReorderLayout(): PageReorderLayout =
    object : PageReorderLayout {
        override fun boundsAt(index: Int): PageReorderBounds? =
            layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == index }
                ?.let { info ->
                    PageReorderBounds(
                        index = info.index,
                        offset = Offset(info.offset.x.toFloat(), info.offset.y.toFloat()),
                        size = Size(info.size.width.toFloat(), info.size.height.toFloat()),
                    )
                }

        override fun boundsAt(position: Offset): PageReorderBounds? =
            layoutInfo.visibleItemsInfo
                .firstOrNull { info ->
                    position.x >= info.offset.x &&
                        position.x <= info.offset.x + info.size.width &&
                        position.y >= info.offset.y &&
                        position.y <= info.offset.y + info.size.height
                }?.let { boundsAt(it.index) }
    }

/** リストは行が画面幅いっぱいなので、縦位置だけで判定する */
fun LazyListState.asPageReorderLayout(): PageReorderLayout =
    object : PageReorderLayout {
        override fun boundsAt(index: Int): PageReorderBounds? =
            layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == index }
                ?.let { info ->
                    PageReorderBounds(
                        index = info.index,
                        offset = Offset(0f, info.offset.toFloat()),
                        size = Size(0f, info.size.toFloat()),
                    )
                }

        override fun boundsAt(position: Offset): PageReorderBounds? =
            layoutInfo.visibleItemsInfo
                .firstOrNull { info ->
                    position.y >= info.offset && position.y <= info.offset + info.size
                }?.let { boundsAt(it.index) }
    }

/**
 * ドラッグ並べ替えの進行状態（docs/specs/08-page-editing.md §3.2 FR-EDT-002）。
 *
 * 指の移動量だけをここで持ち、並び自体は [onMove] で UiState 側を動かす。
 * 表示は「ドラッグ開始位置＋移動量」と「入れ替え後の実座標」の差を打ち消してつまみに追従させる。
 *
 * 画面外へ運ぶ自動スクロールは持たない。ビューポートに見えている範囲での入れ替えだけを扱う
 * （素材・仕様に自動スクロールの規定が無く、見えている位置へ落とす操作で FR-EDT-002 を満たすため）。
 */
@Stable
class PageReorderState(
    private val layout: PageReorderLayout,
    private val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    private val onFinished: () -> Unit,
) {
    private var draggingIndex by mutableStateOf<Int?>(null)
    private var dragStartOffset by mutableStateOf(Offset.Zero)
    private var dragAmount by mutableStateOf(Offset.Zero)
    private var draggingSize by mutableStateOf(Size.Zero)

    fun onDragStart(index: Int) {
        val bounds = layout.boundsAt(index) ?: return
        draggingIndex = index
        dragStartOffset = bounds.offset
        draggingSize = bounds.size
        dragAmount = Offset.Zero
    }

    fun onDrag(delta: Offset) {
        val fromIndex = draggingIndex ?: return
        dragAmount += delta
        val center =
            dragStartOffset + dragAmount +
                Offset(draggingSize.width / 2f, draggingSize.height / 2f)
        val target = layout.boundsAt(center) ?: return
        if (target.index == fromIndex) return
        onMove(fromIndex, target.index)
        draggingIndex = target.index
    }

    /** 指を離した／ジェスチャが取り消された。並びは動かしたぶんが残っているのでそのまま確定させる */
    fun onDragEnd() {
        if (draggingIndex == null) return
        draggingIndex = null
        dragStartOffset = Offset.Zero
        dragAmount = Offset.Zero
        draggingSize = Size.Zero
        onFinished()
    }

    fun isDragging(index: Int): Boolean = index == draggingIndex

    /** ドラッグ中セルの表示ずれ。入れ替え後の実座標との差を打ち消して指に追従させる */
    fun offsetFor(index: Int): Offset {
        if (index != draggingIndex) return Offset.Zero
        val bounds = layout.boundsAt(index) ?: return Offset.Zero
        return dragStartOffset + dragAmount - bounds.offset
    }
}

/**
 * [PageReorderState] を覚える。
 *
 * コールバックは [rememberUpdatedState] 越しに読む。UiState が変わるたびに新しいラムダが渡っても
 * 状態を作り直さない（作り直すとドラッグ中に掴んでいる位置を見失う）。
 */
@Composable
fun rememberPageReorderState(
    layout: PageReorderLayout,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onFinished: () -> Unit,
): PageReorderState {
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnFinished by rememberUpdatedState(onFinished)
    return remember(layout) {
        PageReorderState(
            layout = layout,
            onMove = { fromIndex, toIndex -> currentOnMove(fromIndex, toIndex) },
            onFinished = { currentOnFinished() },
        )
    }
}
