package com.pagebinder.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagebinder.app.domain.AutoCaptureSettingsRepository
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.CaptureFeedbackSettingsRepository
import com.pagebinder.app.domain.ExportProjectSummary
import com.pagebinder.app.domain.ExportStarter
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.domain.InterruptedExport
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.bookdetail.BookDetailActions
import com.pagebinder.app.ui.bookdetail.BookDetailScreen
import com.pagebinder.app.ui.bookdetail.BookDetailViewModel
import com.pagebinder.app.ui.bookedit.BookEditScreen
import com.pagebinder.app.ui.bookedit.BookEditViewModel
import com.pagebinder.app.ui.captureprep.AuthorizedCaptureRequest
import com.pagebinder.app.ui.captureprep.CaptureMode
import com.pagebinder.app.ui.captureprep.CapturePrepRoute
import com.pagebinder.app.ui.captureprep.CapturePrepViewModel
import com.pagebinder.app.ui.consent.ConsentGate
import com.pagebinder.app.ui.consent.ConsentScreen
import com.pagebinder.app.ui.consent.ConsentUiState
import com.pagebinder.app.ui.export.ExportRoute
import com.pagebinder.app.ui.export.ExportViewModel
import com.pagebinder.app.ui.home.HomeScreen
import com.pagebinder.app.ui.home.HomeViewModel
import com.pagebinder.app.ui.pageedit.PageEditRoute
import com.pagebinder.app.ui.pageedit.PageEditViewModel
import com.pagebinder.app.ui.pagelist.PageListRoute
import com.pagebinder.app.ui.pagelist.PageListViewModel
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.trash.TrashScreen
import com.pagebinder.app.ui.trash.TrashViewModel
import java.util.UUID

/**
 * 同意ゲート付きのアプリ本体。
 * [ConsentUiState.canEnterMainFeatures] が true になるまで主要機能（ホーム以降）を構成しない
 * — これが docs/specs/12-legal-guardrails.md §3.1 のナビゲーションガード。
 *
 * OS が描くステータスバー・ナビゲーションバーは自前で描かず、safeDrawing のインセットだけを避ける。
 */
@Composable
fun PageBinderApp(
    uiState: ConsentUiState,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    bookProjectRepository: BookProjectRepository,
    pageRepository: PageRepository,
    pageThumbnailLoader: PageThumbnailLoader,
    exportStarter: ExportStarter,
    enqueueProjectOcr: suspend (UUID) -> Int,
    /**
     * 前回のプロセスが残した未完了の書き出しを、書籍プロジェクト単位で古い順に返す
     * （docs/specs/11-export.md §3.2 末尾。実装は `export/` の InterruptedExportDetector）。
     */
    findInterruptedExports: suspend (UUID) -> List<InterruptedExport>,
    /** 再試行の書き出しが成功したときに、取り残されていたレコードを終端させる（同 §3.2 手順6） */
    resolveInterruptedExport: suspend (UUID) -> Unit,
    autoCaptureSettingsRepository: AutoCaptureSettingsRepository,
    captureFeedbackSettingsRepository: CaptureFeedbackSettingsRepository,
    startCapture: (UUID, AuthorizedCaptureRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        when (uiState.gate) {
            ConsentGate.Checking -> ConsentGateLoading()
            ConsentGate.ConsentRequired ->
                ConsentScreen(
                    uiState = uiState,
                    onAgree = onAgree,
                    onDecline = onDecline,
                )
            ConsentGate.Unlocked ->
                PageBinderMain(
                    repository = bookProjectRepository,
                    pageRepository = pageRepository,
                    pageThumbnailLoader = pageThumbnailLoader,
                    exportStarter = exportStarter,
                    enqueueProjectOcr = enqueueProjectOcr,
                    findInterruptedExports = findInterruptedExports,
                    resolveInterruptedExport = resolveInterruptedExport,
                    autoCaptureSettingsRepository = autoCaptureSettingsRepository,
                    captureFeedbackSettingsRepository = captureFeedbackSettingsRepository,
                    startCapture = startCapture,
                )
        }
    }
}

/** 同意履歴の読み込み中。判定が出るまで何も見せない（未同意側にも同意側にも倒さない） */
@Composable
private fun ConsentGateLoading(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
}

@Composable
private fun PageBinderMain(
    repository: BookProjectRepository,
    pageRepository: PageRepository,
    pageThumbnailLoader: PageThumbnailLoader,
    exportStarter: ExportStarter,
    enqueueProjectOcr: suspend (UUID) -> Int,
    findInterruptedExports: suspend (UUID) -> List<InterruptedExport>,
    resolveInterruptedExport: suspend (UUID) -> Unit,
    autoCaptureSettingsRepository: AutoCaptureSettingsRepository,
    captureFeedbackSettingsRepository: CaptureFeedbackSettingsRepository,
    startCapture: (UUID, AuthorizedCaptureRequest) -> Unit,
) {
    var destination by remember { mutableStateOf<MainDestination>(MainDestination.Home) }
    BackHandler(enabled = destination != MainDestination.Home) {
        destination =
            when (val current = destination) {
                MainDestination.Home -> MainDestination.Home
                is MainDestination.Edit ->
                    current.projectId?.let(MainDestination::Detail) ?: MainDestination.Home
                is MainDestination.Detail -> MainDestination.Home
                MainDestination.Trash -> MainDestination.Home
                is MainDestination.PageList -> MainDestination.Detail(current.projectId)
                is MainDestination.CapturePrep -> MainDestination.Detail(current.projectId)
                is MainDestination.Export -> MainDestination.Detail(current.project.projectId)
                is MainDestination.PageEdit -> MainDestination.PageList(current.projectId)
            }
    }
    when (val current = destination) {
        MainDestination.Home -> {
            val homeViewModel: HomeViewModel =
                viewModel(
                    key = "home",
                    factory =
                        HomeViewModel.factory(repository) { projectId ->
                            pageRepository.findByProject(projectId).firstOrNull()
                        },
                )
            val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(current) { homeViewModel.load() }
            HomeScreen(
                uiState = homeState,
                onQueryChange = homeViewModel::onQueryChange,
                onSortChange = homeViewModel::onSortChange,
                onBookClick = { destination = MainDestination.Detail(it.id) },
                onNewBook = { destination = MainDestination.Edit(null) },
                onTrash = { destination = MainDestination.Trash },
                onReload = homeViewModel::load,
                thumbnailLoader = pageThumbnailLoader,
            )
        }
        is MainDestination.Edit -> {
            val editViewModel: BookEditViewModel =
                viewModel(
                    key = "book-edit-${current.instanceId}",
                    factory = BookEditViewModel.factory(current.projectId, repository),
                )
            val editState by editViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(editState.savedProjectId) {
                editState.savedProjectId?.let {
                    editViewModel.onNavigationHandled()
                    destination = MainDestination.Detail(it)
                }
            }
            BookEditScreen(
                uiState = editState,
                onTitleChange = editViewModel::onTitleChange,
                onAuthorChange = editViewModel::onAuthorChange,
                onNoteChange = editViewModel::onNoteChange,
                onSave = editViewModel::save,
                onBack = {
                    destination =
                        current.projectId?.let(MainDestination::Detail) ?: MainDestination.Home
                },
            )
        }
        is MainDestination.Detail -> {
            val detailViewModel: BookDetailViewModel =
                viewModel(
                    key = "book-detail-${current.projectId}",
                    factory =
                        BookDetailViewModel.factory(
                            current.projectId,
                            repository,
                            enqueueProjectOcr,
                            findInterruptedExports,
                        ),
                )
            val detailState by detailViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(current) { detailViewModel.load() }
            LaunchedEffect(detailState.movedToTrash) {
                if (detailState.movedToTrash) destination = MainDestination.Home
            }
            // 書き出し画面へ渡す要約。通常の書き出しと未完了書き出しの再試行で同じものを使う
            val exportSummary = {
                ExportProjectSummary(
                    projectId = current.projectId,
                    title = detailState.title,
                    pageCount = detailState.pageCount,
                    // OCR完了以外（未処理・実行中・失敗・stale）が FR-EXP-009 の警告件数
                    ocrIncompletePageCount =
                        (detailState.pageCount - detailState.ocrCompletedCount)
                            .coerceIn(0, detailState.pageCount),
                )
            }
            BookDetailScreen(
                uiState = detailState,
                actions =
                    BookDetailActions(
                        onBack = { destination = MainDestination.Home },
                        onEdit = { destination = MainDestination.Edit(current.projectId) },
                        onManualCapture = {
                            destination =
                                MainDestination.CapturePrep(
                                    projectId = current.projectId,
                                    bookTitle = detailState.title,
                                    mode = CaptureMode.MANUAL,
                                )
                        },
                        onContinuousCapture = {
                            destination =
                                MainDestination.CapturePrep(
                                    projectId = current.projectId,
                                    bookTitle = detailState.title,
                                    mode = CaptureMode.CONTINUOUS,
                                )
                        },
                        onPageList = { destination = MainDestination.PageList(current.projectId) },
                        onOcrBatch = detailViewModel::onOcrBatchRequested,
                        onExport = { destination = MainDestination.Export(exportSummary()) },
                        onBookSettings = { destination = MainDestination.Edit(current.projectId) },
                        onMoveToTrashRequested = detailViewModel::onMoveToTrashRequested,
                        onMoveToTrashConfirmed = detailViewModel::onMoveToTrashConfirmed,
                        onMoveToTrashDismissed = detailViewModel::onMoveToTrashDismissed,
                        onReload = detailViewModel::load,
                        // 未完了の書き出しの再試行。保存先は選び直す必要があるので
                        // （SAF の書き込み先URIは持ち越さない — docs/specs/11-export.md §3.2 手順4）、
                        // 前回と同じ出力形式を選んだ状態で書き出し画面へ入り直す。
                        // 提示はここでは消さない。戻る・SAF を閉じるだけならレコードは未完了のまま
                        // 残っており、書籍詳細へ戻った時点の再検出でまた提示される
                        onRetryInterruptedExport = {
                            detailState.interruptedExport?.let { interrupted ->
                                destination =
                                    MainDestination.Export(
                                        project = exportSummary(),
                                        initialFormat = interrupted.format,
                                        interruptedRecordId = interrupted.recordId,
                                    )
                            }
                        },
                        manualCaptureAvailable = !detailState.loading,
                        continuousCaptureAvailable = !detailState.loading,
                        exportAvailable = detailState.exportAvailable,
                    ),
            )
        }
        MainDestination.Trash -> {
            val trashViewModel: TrashViewModel =
                viewModel(key = "trash", factory = TrashViewModel.factory(repository))
            val trashState by trashViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(current) { trashViewModel.load() }
            TrashScreen(
                uiState = trashState,
                onBack = { destination = MainDestination.Home },
                onRestore = trashViewModel::restore,
                onDeleteRequested = trashViewModel::requestPermanentDelete,
                onDeleteConfirmed = trashViewModel::confirmPermanentDelete,
                onDeleteDismissed = trashViewModel::dismissPermanentDelete,
                onReload = trashViewModel::load,
            )
        }
        is MainDestination.PageList -> {
            val pageListViewModel: PageListViewModel =
                viewModel(
                    key = "page-list-${current.projectId}",
                    factory = PageListViewModel.factory(current.projectId, pageRepository),
                )
            PageListRoute(
                viewModel = pageListViewModel,
                thumbnailLoader = pageThumbnailLoader,
                onBack = { destination = MainDestination.Detail(current.projectId) },
                onPageOpened = { pageId ->
                    destination = MainDestination.PageEdit(current.projectId, pageId)
                },
            )
        }
        is MainDestination.CapturePrep -> {
            val capturePrepViewModel: CapturePrepViewModel =
                viewModel(
                    key = "capture-prep-${current.projectId}-${current.mode}",
                    factory =
                        CapturePrepViewModel.factory(
                            current.bookTitle,
                            current.mode,
                            autoCaptureSettingsRepository,
                            captureFeedbackSettingsRepository,
                        ),
                )
            CapturePrepRoute(
                viewModel = capturePrepViewModel,
                onBack = { destination = MainDestination.Detail(current.projectId) },
                onCaptureAuthorized = {
                    startCapture(current.projectId, it)
                    destination = MainDestination.Detail(current.projectId)
                },
                onCaptureDenied = { destination = MainDestination.Detail(current.projectId) },
            )
        }
        is MainDestination.Export -> {
            val exportViewModel: ExportViewModel =
                viewModel(
                    // 書き出しごとに作り直す。権限確認・OCR未完了の続行は前回の選択を持ち越さない
                    // （docs/specs/12-legal-guardrails.md §3.2 / FR-EXP-009）
                    key = "export-${current.instanceId}",
                    factory =
                        ExportViewModel.factory(
                            project = current.project,
                            exportStarter = exportStarter,
                            initialFormat = current.initialFormat,
                            // 再試行として開かれたときだけ、成功後に取り残されたレコードを閉じる
                            resolveInterruptedExport =
                                current.interruptedRecordId?.let { recordId ->
                                    { resolveInterruptedExport(recordId) }
                                },
                        ),
                )
            ExportRoute(
                viewModel = exportViewModel,
                onBack = { destination = MainDestination.Detail(current.project.projectId) },
            )
        }
        is MainDestination.PageEdit -> {
            val pageEditViewModel: PageEditViewModel =
                viewModel(
                    key = "page-edit-${current.instanceId}",
                    factory = PageEditViewModel.factory(current.pageId, pageRepository),
                )
            val pageEditState by pageEditViewModel.uiState.collectAsStateWithLifecycle()
            val closePageEdit = { destination = MainDestination.PageList(current.projectId) }
            // 外側の BackHandler より後に登録されるのでこちらが先に呼ばれる。
            // 未保存の変更があるときは破棄確認を挟む（PageEditRoute の閉じる操作と同じ判断）
            BackHandler {
                if (pageEditState.unsavedChanges) pageEditViewModel.onDiscardRequested() else closePageEdit()
            }
            PageEditRoute(
                viewModel = pageEditViewModel,
                imageLoader = pageThumbnailLoader,
                onClose = closePageEdit,
            )
        }
    }
}

private sealed interface MainDestination {
    data object Home : MainDestination

    data class Edit(
        val projectId: UUID?,
        val instanceId: UUID = UUID.randomUUID(),
    ) : MainDestination

    data class Detail(val projectId: UUID) : MainDestination

    data object Trash : MainDestination

    /** 一覧は [com.pagebinder.app.domain.PageRepository] を購読するので、読み直しの合図を持たない */
    data class PageList(
        val projectId: UUID,
    ) : MainDestination

    data class CapturePrep(
        val projectId: UUID,
        val bookTitle: String,
        val mode: CaptureMode,
    ) : MainDestination

    /** 書き出し画面（docs/specs/11-export.md §3.2 手順1）。要約は書籍詳細が組み立てて渡す */
    data class Export(
        val project: ExportProjectSummary,
        /** 未完了の書き出しの再試行では前回の出力形式を選んだ状態で開く（同 §3.2 末尾） */
        val initialFormat: ExportType = ExportType.SEARCHABLE_PDF,
        /**
         * 再試行の対象になっている未完了レコード（通常の書き出しでは null）。
         * 書き出しが成功したときだけ、このレコードを終端させて提示を解消する。
         */
        val interruptedRecordId: UUID? = null,
        val instanceId: UUID = UUID.randomUUID(),
    ) : MainDestination

    /** 回転・切り取り編集画面（docs/specs/08-page-editing.md §3.2） */
    data class PageEdit(
        val projectId: UUID,
        val pageId: UUID,
        val instanceId: UUID = UUID.randomUUID(),
    ) : MainDestination
}
