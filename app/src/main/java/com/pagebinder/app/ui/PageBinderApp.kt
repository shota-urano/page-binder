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
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.bookdetail.BookDetailActions
import com.pagebinder.app.ui.bookdetail.BookDetailScreen
import com.pagebinder.app.ui.bookdetail.BookDetailViewModel
import com.pagebinder.app.ui.bookedit.BookEditScreen
import com.pagebinder.app.ui.bookedit.BookEditViewModel
import com.pagebinder.app.ui.consent.ConsentGate
import com.pagebinder.app.ui.consent.ConsentScreen
import com.pagebinder.app.ui.consent.ConsentUiState
import com.pagebinder.app.ui.home.HomeScreen
import com.pagebinder.app.ui.home.HomeViewModel
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
    enqueueProjectOcr: suspend (UUID) -> Int,
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
                    enqueueProjectOcr = enqueueProjectOcr,
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
    enqueueProjectOcr: suspend (UUID) -> Int,
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
                        ),
                )
            val detailState by detailViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(current) { detailViewModel.load() }
            LaunchedEffect(detailState.movedToTrash) {
                if (detailState.movedToTrash) destination = MainDestination.Home
            }
            BookDetailScreen(
                uiState = detailState,
                actions =
                    BookDetailActions(
                        onBack = { destination = MainDestination.Home },
                        onEdit = { destination = MainDestination.Edit(current.projectId) },
                        onManualCapture = {},
                        onContinuousCapture = {},
                        onPageList = { destination = MainDestination.PageList(current.projectId) },
                        onOcrBatch = detailViewModel::onOcrBatchRequested,
                        onExport = {},
                        onBookSettings = { destination = MainDestination.Edit(current.projectId) },
                        onMoveToTrashRequested = detailViewModel::onMoveToTrashRequested,
                        onMoveToTrashConfirmed = detailViewModel::onMoveToTrashConfirmed,
                        onMoveToTrashDismissed = detailViewModel::onMoveToTrashDismissed,
                        onReload = detailViewModel::load,
                        manualCaptureAvailable = false,
                        continuousCaptureAvailable = false,
                        exportAvailable = false,
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
                onPageOpened = {},
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

    data class PageList(val projectId: UUID) : MainDestination
}
