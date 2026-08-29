package com.pagebinder.app.ui.pageedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 回転・切り取り編集画面（docs/design/08-page-edit.md / docs/specs/08-page-editing.md §3.2）の ViewModel。
 *
 * 画面単位で不変 [PageEditUiState] を [StateFlow] で公開する（AGENTS.md ルール8）。
 * 読み書きは [PageRepository] 経由だけで、Room の型はここへ来ない。
 *
 * この画面の約束は3つ。
 * 1. 元画像には触れない。保存するのは rotation（0/90/180/270）と正規化された crop だけ（FR-IMG-007）
 * 2. 画像を変えたページの OCR は stale になる（[PageRepository] 側の責務。docs/specs/08-page-editing.md §3.3）
 * 3. 取り消しは直前1操作だけ（同 §3.4 確定）。保存前なら画面の中で1手戻し、保存後は
 *    [PageRepository.undoLastEdit] で保存済みの回転・切り取りを1手戻す
 */
class PageEditViewModel(
    private val pageId: UUID,
    private val pageRepository: PageRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PageEditUiState())
    val uiState: StateFlow<PageEditUiState> = mutableUiState.asStateFlow()

    /** 直前1操作の戻し先（docs/specs/08-page-editing.md §3.4）。深さは1で確定なので1件しか持たない */
    private var pendingUndo: PageEditUndo? = null

    /** つまみを掴んでいるあいだは true。1回のドラッグ全体で取り消し履歴を1件にするための印 */
    private var cropDragging = false

    init {
        load()
    }

    /** ページを読み直す。編集中の値は読み直しで捨てられるので、初回表示と再試行だけで呼ぶ */
    fun load() {
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            runCatching {
                val page = pageRepository.findById(pageId)
                val pageCount = page?.let { pageRepository.findByProject(it.projectId).size } ?: 0
                page to pageCount
            }.onSuccess { (page, pageCount) -> applyLoaded(page, pageCount) }
                .onFailure {
                    // 例外の内容はログへ出さない（画像パスが混ざりうる。AGENTS.md ルール6）
                    mutableUiState.update { it.copy(loading = false, loadFailed = true) }
                }
        }
    }

    private fun applyLoaded(
        page: Page?,
        projectPageCount: Int,
    ) {
        if (page == null) {
            mutableUiState.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        pendingUndo = null
        cropDragging = false
        mutableUiState.update {
            PageEditUiState(
                loading = false,
                loadFailed = false,
                pageId = page.id,
                pageSequence = page.sequence,
                projectPageCount = projectPageCount,
                savedRotation = page.rotation,
                savedCrop = page.crop,
                rotation = page.rotation,
                crop = page.crop,
            )
        }
    }

    /**
     * 「90°回転」。時計回りに90度ずつ回す（FR-EDT-004）。
     * crop は回転後の座標系で持つ定義なので、同じ紙面の場所を指したまま座標を移し替える。
     */
    fun onRotateClockwise() {
        val current = mutableUiState.value
        if (!current.canEdit) return
        pushUndo(current)
        mutableUiState.update {
            it.copy(
                rotation = (it.rotation + ROTATION_STEP) % FULL_TURN,
                crop = it.crop.rotatedClockwise(),
                message = null,
            )
        }
    }

    /**
     * 切り取り枠のつまみのドラッグ。[dx] / [dy] は表示中の画像に対する正規化された移動量で、
     * 画面側がピクセルから換算して渡す（保存値が端末の解像度に依存しないようにするため）。
     */
    fun onCropHandleDragged(
        handle: PageCropHandle,
        dx: Float,
        dy: Float,
    ) {
        val current = mutableUiState.value
        if (!current.canEdit) return
        if (!cropDragging) {
            // 1回のドラッグで履歴を1件にする。指を動かすたびに積むと直前1操作が1コマ分になってしまう
            pushUndo(current)
            cropDragging = true
        }
        mutableUiState.update { it.copy(crop = it.crop.movedBy(handle, dx, dy), message = null) }
    }

    /** 指を離した。次のドラッグは新しい1操作として履歴に積む */
    fun onCropDragFinished() {
        cropDragging = false
    }

    /** 「この書籍の全ページに同じ切り取りを適用」のチェック（FR-IMG-005/006） */
    fun onApplyToAllPagesChanged(applyToAll: Boolean) {
        mutableUiState.update { it.copy(applyCropToAllPages = applyToAll, message = null) }
    }

    /**
     * 「元に戻す」。直前1操作を取り消す（docs/specs/08-page-editing.md §3.4。多段undoはMVP対象外）。
     *
     * 直前1操作が保存前の編集（回転1回、またはドラッグ1回）なら画面の中で戻す。保存だったときは
     * 保存先の取り消し（[PageRepository.undoLastEdit]）を呼ぶ。回転・切り取りも取り消しの対象
     * （同 §3.4）なので、保存した後でも「元に戻す」を押せる状態を保つ。
     */
    fun onUndoRequested() {
        val current = mutableUiState.value
        if (!current.canUndo) return
        when (val undo = pendingUndo) {
            null -> return
            is PageEditUndo.Draft -> {
                pendingUndo = null
                cropDragging = false
                mutableUiState.update {
                    it.copy(
                        rotation = undo.rotation,
                        crop = undo.crop,
                        undoAvailable = false,
                        message = null,
                    )
                }
            }
            PageEditUndo.SavedEdit -> undoSavedEdit()
        }
    }

    /**
     * 保存済みの直前1操作を保存先ごと取り消し、画面を取り消し後の内容へ揃える。
     *
     * 取り消しの結果は保存先から読み直す。一括適用のときは同一書籍の全ページが戻るので、
     * 画面が持つ値を自前で組み立てると保存先とずれるため。
     */
    private fun undoSavedEdit() {
        mutableUiState.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val undone = runCatching { pageRepository.undoLastEdit() }
            val restored = undone.getOrNull() == true
            val stored = if (restored) runCatching { pageRepository.findById(pageId) }.getOrNull() else null
            pendingUndo = null
            cropDragging = false
            mutableUiState.update {
                val rotation = stored?.rotation ?: it.savedRotation
                val crop = stored?.crop ?: it.savedCrop
                it.copy(
                    saving = false,
                    savedRotation = rotation,
                    savedCrop = crop,
                    rotation = rotation,
                    crop = crop,
                    undoAvailable = false,
                    // 例外の内容はログへ出さない（AGENTS.md ルール6）
                    message = if (restored) PageEditMessage.EditUndone else PageEditMessage.UndoFailed,
                )
            }
        }
    }

    /**
     * 「保存」。一括適用にチェックがあるときは、影響するページ数を見せてから実行する
     * （docs/design/08-page-edit.md「未定事項: 一括適用を有効にして保存する際の確認」。
     * 全ページのOCRが再実行待ちになるので、対象件数を出してから確定させる）。
     */
    fun onSaveRequested() {
        val current = mutableUiState.value
        if (!current.canSave) return
        if (current.applyCropToAllPages) {
            mutableUiState.update { it.copy(bulkConfirmationVisible = true, message = null) }
        } else {
            save(applyToAllPages = false)
        }
    }

    fun onBulkApplyConfirmed() {
        if (!mutableUiState.value.bulkConfirmationVisible) return
        mutableUiState.update { it.copy(bulkConfirmationVisible = false) }
        save(applyToAllPages = true)
    }

    fun onBulkApplyDismissed() {
        mutableUiState.update { it.copy(bulkConfirmationVisible = false) }
    }

    /** × を押したときの破棄確認。未保存の変更があるときだけ画面側から呼ばれる */
    fun onDiscardRequested() {
        mutableUiState.update { it.copy(discardConfirmationVisible = true) }
    }

    fun onDiscardDismissed() {
        mutableUiState.update { it.copy(discardConfirmationVisible = false) }
    }

    fun onMessageDismissed() {
        mutableUiState.update { it.copy(message = null) }
    }

    /**
     * 非破壊属性の保存。元画像のファイルには一切触れない（FR-IMG-007）。
     *
     * 回転と切り取りは [PageRepository.updatePageEdit] へ1回で渡す。回転だけ書けて切り取りが失敗する
     * （逆も同じ）中途半端な保存が起きず、一括適用も含めて取り消し履歴が1件になるため
     * （docs/specs/08-page-editing.md §3.4）。
     *
     * 一括適用のときは同一書籍の全ページへ同じ crop が届く（FR-IMG-005/006）。回転は
     * ページごとの向きが違いうるので、開いているページにだけ適用する（一括の対象は切り取りだけ、
     * という素材の文言「全ページに同じ切り取りを適用」のとおり）。
     */
    private fun save(applyToAllPages: Boolean) {
        val current = mutableUiState.value
        val id = current.pageId ?: return
        val rotation = current.rotation
        val crop = current.crop
        mutableUiState.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            runCatching {
                pageRepository.updatePageEdit(
                    pageId = id,
                    rotation = rotation,
                    crop = crop,
                    cropScope = if (applyToAllPages) PageCropScope.PROJECT else PageCropScope.PAGE,
                )
            }.onSuccess { appliedPageCount ->
                // 保存も取り消しの対象（docs/specs/08-page-editing.md §3.4）。
                // 戻し先は保存先が持つので、ここでは「次の取り消しは保存先へ」だけを覚える
                pendingUndo = PageEditUndo.SavedEdit
                cropDragging = false
                mutableUiState.update {
                    it.copy(
                        saving = false,
                        savedRotation = rotation,
                        savedCrop = crop,
                        undoAvailable = true,
                        message =
                            if (applyToAllPages) {
                                PageEditMessage.SavedToAllPages(appliedPageCount)
                            } else {
                                PageEditMessage.Saved
                            },
                    )
                }
            }.onFailure {
                // 例外の内容はログへ出さない（AGENTS.md ルール6）。
                // 属性更新の失敗時は UI 状態を元に戻してエラーを出す（docs/specs/08-page-editing.md §6）。
                // 戻し先は保存先から読み直した内容。画面に未保存の編集が残ると、書けていない値を
                // 保存済みと思って閉じてしまう
                val stored = runCatching { pageRepository.findById(pageId) }.getOrNull()
                pendingUndo = null
                cropDragging = false
                mutableUiState.update {
                    val rotation = stored?.rotation ?: it.savedRotation
                    val crop = stored?.crop ?: it.savedCrop
                    it.copy(
                        saving = false,
                        message = PageEditMessage.SaveFailed,
                        savedRotation = rotation,
                        savedCrop = crop,
                        rotation = rotation,
                        crop = crop,
                        undoAvailable = false,
                    )
                }
            }
        }
    }

    /** 直前1操作の1手前を控える。深さ1なので、前の控えは捨てる（同 §3.4 確定） */
    private fun pushUndo(current: PageEditUiState) {
        pendingUndo = PageEditUndo.Draft(rotation = current.rotation, crop = current.crop)
        mutableUiState.update { it.copy(undoAvailable = true) }
    }

    /** 直前1操作の戻し方（docs/specs/08-page-editing.md §3.4）。元画像はここにも入らない */
    private sealed interface PageEditUndo {
        /** 保存前の編集。控えた値へ画面の中で戻す */
        data class Draft(
            val rotation: Int,
            val crop: PageCrop,
        ) : PageEditUndo

        /** 保存済みの編集。戻すのは [PageRepository.undoLastEdit] */
        data object SavedEdit : PageEditUndo
    }

    companion object {
        fun factory(
            pageId: UUID,
            pageRepository: PageRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PageEditViewModel(pageId, pageRepository) }
            }
    }
}

/** 回転の1操作あたりの角度（FR-EDT-004「90度単位」） */
private const val ROTATION_STEP = 90

private const val FULL_TURN = 360
