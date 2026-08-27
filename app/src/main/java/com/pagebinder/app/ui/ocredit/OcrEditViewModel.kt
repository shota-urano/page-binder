package com.pagebinder.app.ui.ocredit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.OcrQueue
import com.pagebinder.app.domain.OcrResultRepository
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.domain.StoredOcrResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * OCR編集画面（docs/design/10-ocr-edit.md / docs/specs/09-ocr.md §3.5）の ViewModel。
 *
 * 画面単位で不変 [OcrEditUiState] を [StateFlow] で公開する（AGENTS.md ルール8）。
 * 取得・保存は [PageRepository] / [OcrResultRepository] / [OcrQueue] 経由だけで、Room・ML Kit の型はここへ来ない。
 *
 * この画面の約束は2つ。
 * 1. 手動修正は editedText へ入り、OCRエンジンの fullText は書き換えない
 * 2. 「元のOCR結果へ戻す」は editedText を破棄するだけ
 *
 * どちらも [OcrResultRepository] の口が editedText しか触らないことで担保していて、
 * この ViewModel は [OcrEditUiState.originalText] を読み取り専用として扱う。
 */
class OcrEditViewModel(
    private val pageId: UUID,
    private val pageRepository: PageRepository,
    private val ocrResultRepository: OcrResultRepository,
    private val ocrQueue: OcrQueue,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(OcrEditUiState())
    val uiState: StateFlow<OcrEditUiState> = mutableUiState.asStateFlow()

    init {
        load()
    }

    /**
     * ページとOCR結果を読み直す。編集中の下書きは読み直しで捨てられるので、
     * 呼ぶのは初回表示と読み込み失敗からの再試行だけにしている。
     */
    fun load() {
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            runCatching { pageRepository.findById(pageId) to ocrResultRepository.findByPageId(pageId) }
                .onSuccess { (page, result) -> applyLoaded(page, result) }
                .onFailure {
                    // 例外の内容はログへ出さない（画像パス・OCR全文が混ざりうる。AGENTS.md ルール6）
                    mutableUiState.update { it.copy(loading = false, loadFailed = true) }
                }
        }
    }

    private fun applyLoaded(
        page: Page?,
        result: StoredOcrResult?,
    ) {
        if (page == null) {
            mutableUiState.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        val original = result?.fullText.orEmpty()
        val draft = result?.editedText ?: original
        mutableUiState.update { current ->
            current.copy(
                loading = false,
                loadFailed = false,
                pageId = page.id,
                pageSequence = page.sequence,
                rotation = page.rotation,
                crop = page.crop,
                ocrState = page.ocrState,
                resultAvailable = result != null,
                originalText = original,
                savedEditedText = result?.editedText,
                draftText = draft,
                message = null,
                search = current.search.searching(draft),
            )
        }
    }

    /** 本文の手動修正。保存するまでは下書きのままで、editedText は動かない */
    fun onTextChange(text: String) {
        mutableUiState.update { current ->
            if (!current.resultAvailable) {
                current
            } else {
                current.copy(draftText = text, message = null, search = current.search.searching(text))
            }
        }
    }

    /** 「保存」。下書きを editedText へ書き、fullText には触れない（docs/specs/09-ocr.md §3.5） */
    fun onSaveRequested() {
        val state = mutableUiState.value
        if (!state.canSave) return
        val draft = state.draftText
        mutableUiState.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val saved = runCatching { ocrResultRepository.saveEditedText(pageId, draft) }.getOrDefault(false)
            mutableUiState.update { current ->
                if (saved) {
                    current.copy(saving = false, savedEditedText = draft, message = OcrEditMessage.SAVED)
                } else {
                    current.copy(saving = false, message = OcrEditMessage.SAVE_FAILED)
                }
            }
        }
    }

    /** 「元のOCR結果へ戻す」。修正を捨てる破壊操作なので確認を挟む（docs/design/system/03-principles.md） */
    fun onRevertRequested() {
        if (!mutableUiState.value.canRevert) return
        mutableUiState.update { it.copy(revertDialogVisible = true, message = null) }
    }

    fun onRevertDismissed() {
        mutableUiState.update { it.copy(revertDialogVisible = false) }
    }

    /** 確認後の破棄。editedText だけを捨て、fullText はそのまま残る */
    fun onRevertConfirmed() {
        val state = mutableUiState.value
        if (!state.canRevert) {
            mutableUiState.update { it.copy(revertDialogVisible = false) }
            return
        }
        mutableUiState.update { it.copy(saving = true, revertDialogVisible = false, message = null) }
        viewModelScope.launch {
            val cleared = runCatching { ocrResultRepository.clearEditedText(pageId) }.getOrDefault(false)
            mutableUiState.update { current ->
                if (cleared) {
                    current.copy(
                        saving = false,
                        savedEditedText = null,
                        draftText = current.originalText,
                        message = OcrEditMessage.REVERTED,
                        search = current.search.searching(current.originalText),
                    )
                } else {
                    current.copy(saving = false, message = OcrEditMessage.REVERT_FAILED)
                }
            }
        }
    }

    /**
     * 「OCR再実行」。キューへ載せるだけで、この画面は結果を待たない（docs/specs/09-ocr.md §3.2）。
     * 予約できたら状態表示を待機へ進め、利用者が再実行の受理を確認できるようにする。
     */
    fun onRerunRequested() {
        val state = mutableUiState.value
        if (!state.canRerun || state.saving) return
        viewModelScope.launch {
            val queued = runCatching { ocrQueue.enqueue(pageId) }.getOrDefault(false)
            mutableUiState.update { current ->
                if (queued) {
                    current.copy(ocrState = PageOcrState.PENDING, message = OcrEditMessage.RERUN_QUEUED)
                } else {
                    current.copy(message = OcrEditMessage.RERUN_FAILED)
                }
            }
        }
    }

    /** 検索の開閉。閉じるときは検索語と一致位置を捨てる */
    fun onSearchToggled() {
        mutableUiState.update { current ->
            if (current.search.visible) {
                current.copy(search = OcrEditSearchUiState())
            } else {
                current.copy(search = current.search.copy(visible = true))
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        mutableUiState.update { current ->
            current.copy(search = current.search.copy(query = query, visible = true).searching(current.draftText))
        }
    }

    fun onSearchNext() {
        stepSearch(1)
    }

    fun onSearchPrevious() {
        stepSearch(-1)
    }

    private fun stepSearch(delta: Int) {
        mutableUiState.update { current ->
            val count = current.search.matchCount
            if (count == 0) {
                current
            } else {
                val next = ((current.search.currentIndex + delta) % count + count) % count
                current.copy(search = current.search.copy(currentIndex = next))
            }
        }
    }

    fun onZoomIn() {
        mutableUiState.update {
            it.copy(zoomPercent = (it.zoomPercent + ZOOM_STEP_PERCENT).coerceAtMost(MAX_ZOOM_PERCENT))
        }
    }

    fun onZoomOut() {
        mutableUiState.update {
            it.copy(zoomPercent = (it.zoomPercent - ZOOM_STEP_PERCENT).coerceAtLeast(MIN_ZOOM_PERCENT))
        }
    }

    /** 分割ハンドルのドラッグ。どちらのペインも潰れないように上下限で止める */
    fun onSplitRatioChange(ratio: Float) {
        if (!ratio.isFinite()) return
        mutableUiState.update { it.copy(splitRatio = ratio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)) }
    }

    fun onMessageShown() {
        mutableUiState.update { it.copy(message = null) }
    }

    companion object {
        fun factory(
            pageId: UUID,
            pageRepository: PageRepository,
            ocrResultRepository: OcrResultRepository,
            ocrQueue: OcrQueue,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { OcrEditViewModel(pageId, pageRepository, ocrResultRepository, ocrQueue) }
            }
    }
}

/**
 * いまの検索語で [text] の一致位置を数え直す。
 * 一致が減って現在位置が範囲外になったら先頭へ戻す（件数表示と選択位置がずれないようにする）。
 */
private fun OcrEditSearchUiState.searching(text: String): OcrEditSearchUiState {
    if (query.isEmpty()) return copy(matches = emptyList(), currentIndex = 0)
    val matches = mutableListOf<IntRange>()
    var from = 0
    while (from <= text.length) {
        val hit = text.indexOf(query, startIndex = from, ignoreCase = true)
        if (hit < 0) break
        matches += hit until (hit + query.length)
        from = hit + query.length
    }
    return copy(matches = matches, currentIndex = if (currentIndex < matches.size) currentIndex else 0)
}
