package com.pagebinder.app.ui.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.BookProjectSummary
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.domain.InterruptedExport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CancellationException

data class MoveToTrashConfirmationUiState(
    val title: String,
    val pageCount: Int,
    val storageBytes: Long,
)

/**
 * 前回のプロセスが残した未完了の書き出しの提示（docs/specs/11-export.md §3.2 末尾
 * 「アプリ強制終了後、未完了の書き出しを検出して再試行できる」）。
 *
 * [recordId] と [format] は再試行の対象になる最も古い未完了レコード、[count] は残っている件数。
 * 未完了レコードが複数あるときは古い順に1件ずつ再試行するので、1件解消するたびに [count] が減り、
 * 次のレコードが再試行対象になる。
 */
data class InterruptedExportUiState(
    val recordId: UUID,
    val format: ExportType,
    val count: Int,
)

enum class BookDetailOperationError {
    LOAD,
    MOVE_TO_TRASH,
    OCR_BATCH,
}

data class BookDetailUiState(
    val loading: Boolean = true,
    val title: String = "",
    val author: String? = null,
    val note: String? = null,
    val pageCount: Int = 0,
    val ocrCompletedCount: Int = 0,
    /** OCRの順番待ち（実行待ち＋実行中）。0 でなければ進捗を出す */
    val awaitingOcrCount: Int = 0,
    val ocrErrorCount: Int = 0,
    val storageBytes: Long = 0,
    val moveToTrashConfirmation: MoveToTrashConfirmationUiState? = null,
    val operationInProgress: Boolean = false,
    val operationError: BookDetailOperationError? = null,
    val movedToTrash: Boolean = false,
    val queuedOcrCount: Int? = null,
    val interruptedExport: InterruptedExportUiState? = null,
) {
    /** 1ページも無い書籍には書き出す成果物が無い（docs/specs/11-export.md §2 入力）。 */
    val exportAvailable: Boolean get() = !loading && pageCount > 0

    /**
     * OCRの進捗を出すか（pagebinder-1sd）。
     *
     * 待ちが1件でもある間だけ出す。「N件のOCRを予約しました」と「OCR完了 N」の間が
     * 数分空くことがあり、進捗が無いと予約が通ったのか分からないため。
     */
    val ocrInProgress: Boolean get() = !loading && awaitingOcrCount > 0

    /**
     * 進捗の分母。予約した件数ではなく書籍のOCR対象ページ数を使う。
     * 予約件数を分母にすると、進行中に撮ったページが分母へ入らず途中でつじつまが合わなくなる。
     */
    val ocrTargetCount: Int get() = ocrCompletedCount + awaitingOcrCount + ocrErrorCount

    /** 0除算を避けた進捗率。ゲージは [ocrInProgress] のときだけ描くので分母は必ず正になる */
    val ocrProgress: Float
        get() = if (ocrTargetCount == 0) 0f else ocrCompletedCount.toFloat() / ocrTargetCount
}

class BookDetailViewModel(
    private val projectId: UUID,
    private val repository: BookProjectRepository,
    private val enqueueProjectOcr: suspend (UUID) -> Int,
    private val findInterruptedExports: suspend (UUID) -> List<InterruptedExport>,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = mutableUiState.asStateFlow()
    private var summaryJob: Job? = null
    private var interruptedExportJob: Job? = null

    /** ごみ箱へ移動した直後の「書籍が消えた」通知を、読み込み失敗と取り違えないための印。 */
    private var movingToTrash = false

    init {
        load()
    }

    /**
     * 未完了の書き出しを検出して提示する（docs/specs/11-export.md §3.2 末尾）。
     *
     * 書籍詳細を開くたび（[load] のたび）に検出し直し、提示は毎回その結果に置き換える。
     * 提示を消してよいのは未完了レコードが実際に解消されたときだけで、「再試行を押した」ことでは
     * 消さない — 書き出し画面から戻る・SAF を閉じるだけならレコードは queued / running のまま
     * 残っており、再試行の導線を失わせてはならない。
     * レコードが終端（succeeded / failed）へ達すると検出から外れ、この代入で提示が消える。
     */
    private fun detectInterruptedExports() {
        // 開き直しが重なっても、古い検出結果が新しい提示を上書きしないように1本だけ走らせる
        interruptedExportJob?.cancel()
        interruptedExportJob =
            viewModelScope.launch {
                val interrupted =
                    try {
                        findInterruptedExports(projectId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 例外の内容はログへ出さない（保存URIが混ざりうる — AGENTS.md ルール6）
                        // 検出できなかったときは前回の提示をそのまま残す（勝手に消さない）
                        return@launch
                    }
                // 再試行は古い順に1件ずつ。先頭が今回の対象で、count は残っている件数
                val oldest = interrupted.firstOrNull()
                mutableUiState.update { state ->
                    state.copy(
                        interruptedExport =
                            oldest?.let {
                                InterruptedExportUiState(
                                    recordId = it.recordId,
                                    format = it.type,
                                    count = interrupted.size,
                                )
                            },
                    )
                }
            }
    }

    /**
     * 統計を Repository の Flow で購読し直す。
     *
     * 一度読みでは撮影オーバーレイでページが増えても書籍詳細が古い統計のまま残り、書き出しが
     * 無効に見えてしまう。購読にすることで、画面を離れずにページ追加・削除が反映される。
     */
    fun load() {
        summaryJob?.cancel()
        mutableUiState.update { it.copy(loading = true, operationError = null) }
        detectInterruptedExports()
        summaryJob =
            viewModelScope.launch {
                repository
                    .observeSummaryById(projectId)
                    .catch { failure ->
                        if (failure is CancellationException) throw failure
                        mutableUiState.update {
                            it.copy(loading = false, operationError = BookDetailOperationError.LOAD)
                        }
                    }.collect(::onSummary)
            }
    }

    private fun onSummary(summary: BookProjectSummary?) {
        if (summary == null || summary.project.deletedAt != null) {
            // 自分の操作でごみ箱へ入れた直後は、ホームへ戻る遷移に任せる
            if (movingToTrash || mutableUiState.value.movedToTrash) return
            mutableUiState.update { it.copy(loading = false, operationError = BookDetailOperationError.LOAD) }
            return
        }
        mutableUiState.update { it.withSummary(summary) }
    }

    fun onMoveToTrashRequested() {
        val state = mutableUiState.value
        if (state.loading || state.operationInProgress || state.operationError == BookDetailOperationError.LOAD) return
        mutableUiState.update {
            it.copy(
                moveToTrashConfirmation =
                    MoveToTrashConfirmationUiState(
                        title = it.title,
                        pageCount = it.pageCount,
                        storageBytes = it.storageBytes,
                    ),
            )
        }
    }

    fun onMoveToTrashDismissed() {
        mutableUiState.update { it.copy(moveToTrashConfirmation = null) }
    }

    fun onMoveToTrashConfirmed() {
        if (mutableUiState.value.moveToTrashConfirmation == null) return
        movingToTrash = true
        mutableUiState.update {
            it.copy(moveToTrashConfirmation = null, operationInProgress = true, operationError = null)
        }
        viewModelScope.launch {
            try {
                repository.moveToTrash(projectId)
                mutableUiState.update { state ->
                    state.copy(operationInProgress = false, movedToTrash = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                movingToTrash = false
                mutableUiState.update {
                    it.copy(operationInProgress = false, operationError = BookDetailOperationError.MOVE_TO_TRASH)
                }
            }
        }
    }

    fun onOcrBatchRequested() {
        if (mutableUiState.value.operationInProgress) return
        mutableUiState.update { it.copy(operationInProgress = true, operationError = null, queuedOcrCount = null) }
        viewModelScope.launch {
            try {
                val count = enqueueProjectOcr(projectId)
                mutableUiState.update { it.copy(operationInProgress = false, queuedOcrCount = count) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(operationInProgress = false, operationError = BookDetailOperationError.OCR_BATCH)
                }
            }
        }
    }

    fun onMessageDismissed() {
        mutableUiState.update { it.copy(operationError = null, queuedOcrCount = null) }
    }

    companion object {
        fun factory(
            projectId: UUID,
            repository: BookProjectRepository,
            enqueueProjectOcr: suspend (UUID) -> Int,
            findInterruptedExports: suspend (UUID) -> List<InterruptedExport>,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    BookDetailViewModel(projectId, repository, enqueueProjectOcr, findInterruptedExports)
                }
            }
    }
}

/** 統計だけを差し替え、進行中の操作・確認ダイアログ・メッセージはそのまま残す。 */
private fun BookDetailUiState.withSummary(summary: BookProjectSummary) =
    copy(
        loading = false,
        title = summary.project.title,
        author = summary.project.author,
        note = summary.project.note,
        pageCount = summary.pageCount,
        ocrCompletedCount = summary.ocrCompletedCount,
        awaitingOcrCount = summary.awaitingOcrCount,
        ocrErrorCount = summary.ocrErrorCount,
        storageBytes = summary.storageBytes,
        operationError = operationError.takeIf { it != BookDetailOperationError.LOAD },
    )
