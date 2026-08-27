package com.pagebinder.app.ui.pagelist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.pagebinder.app.R
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorDivider
import com.pagebinder.app.ui.theme.ColorError
import com.pagebinder.app.ui.theme.ColorPrimary
import com.pagebinder.app.ui.theme.ColorSurface
import com.pagebinder.app.ui.theme.ColorText
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit
import java.util.UUID
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed

/** ページ一覧画面の操作。ViewModel を持ち込まずに画面をテストできるようにまとめてある */
@Stable
class PageListScreenActions(
    val onBack: () -> Unit,
    val onViewModeChange: (PageListViewMode) -> Unit,
    val onFilterChange: (PageListFilter) -> Unit,
    /** 通常時のセルタップ。回転・切り取り編集画面（docs/design/08-page-edit.md）へ進む */
    val onPageOpened: (UUID) -> Unit,
    val onPageLongPressed: (UUID) -> Unit,
    val onSelectionToggled: (UUID) -> Unit,
    val onSelectionCleared: () -> Unit,
    /** 選択モードバーのごみ箱。件数を出す確認ダイアログを開くだけで、まだ削除しない（同 §6） */
    val onDeleteSelectedRequested: () -> Unit,
    /** 確認ダイアログの「削除」 */
    val onDeleteConfirmed: () -> Unit,
    /** 確認ダイアログの「キャンセル」・スクリムのタップ */
    val onDeleteDismissed: () -> Unit,
    /** ドラッグ中の入れ替え（表示上の並びだけを動かす） */
    val onPageMoved: (fromIndex: Int, toIndex: Int) -> Unit,
    /** 指を離したときの並べ替え確定 */
    val onReorderFinished: () -> Unit,
    /** 直前1操作の取り消し（docs/specs/08-page-editing.md §3.4） */
    val onUndoRequested: () -> Unit,
    /** 取り消し案内・失敗表示を閉じる */
    val onMessageDismissed: () -> Unit,
    val onReload: () -> Unit,
)

/**
 * テスト用タグ。表示切替が実際に入れ替わったこと・500ページでもスクロールできることを見る
 * （docs/specs/08-page-editing.md §3.1）。
 */
const val PAGE_LIST_GRID_TEST_TAG = "pageListGrid"
const val PAGE_LIST_ROWS_TEST_TAG = "pageListRows"

/** 取り消し案内・操作失敗のバー */
const val PAGE_LIST_MESSAGE_BAR_TEST_TAG = "pageListMessageBar"

/**
 * グリッドのセル1件に付くタグ。§3.1 の「各ページに OCR状態…と重複・黒画面警告を表示」を
 * ページ単位で確認できるようにする（画面全体から文言を探すと、どのページに何が付いたか分からない）。
 */
fun pageListCellTestTag(sequence: Int): String = "pageListCell_$sequence"

/** リスト表示の行1件に付くタグ。用途は [pageListCellTestTag] と同じ */
fun pageListRowTestTag(sequence: Int): String = "pageListRow_$sequence"

/**
 * 並べ替えつまみに付くタグ。セル/行は clickable で子孫の semantics を統合するので、
 * テストからは `useUnmergedTree = true` で参照する。
 */
fun pageReorderHandleTestTag(sequence: Int): String = "pageReorderHandle_$sequence"

/**
 * ページ一覧画面（docs/design/07-page-list.md / docs/specs/08-page-editing.md §3.1）。
 *
 * 描くのはアプリバーから下のコンテンツ領域だけで、ステータスバー・ナビゲーションバーは OS が描く
 * （docs/design/system/03-principles.md「モック画像の読み方」）。表示値はすべて [uiState] から描き、
 * モックのサンプルデータ（書籍名・本文）は持たない。
 */
@Composable
fun PageListScreen(
    uiState: PageListUiState,
    thumbnailLoader: PageThumbnailLoader,
    actions: PageListScreenActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (uiState.selectionMode) {
                    PageListSelectionTopBar(
                        selectedCount = uiState.selectedCount,
                        onDeleteSelectedRequested = actions.onDeleteSelectedRequested,
                        onSelectionCleared = actions.onSelectionCleared,
                    )
                } else {
                    PageListTopBar(onBack = actions.onBack)
                }
                PageListToolbar(
                    viewMode = uiState.viewMode,
                    filter = uiState.filter,
                    onViewModeChange = actions.onViewModeChange,
                    onFilterChange = actions.onFilterChange,
                )
                when {
                    uiState.loadFailed ->
                        PageListNotice(
                            messageRes = R.string.page_list_load_failed,
                            onRetry = actions.onReload,
                        )
                    uiState.emptyProject -> PageListNotice(messageRes = R.string.page_list_empty)
                    uiState.emptyByFilter -> PageListNotice(messageRes = R.string.page_list_filter_empty)
                    uiState.viewMode == PageListViewMode.GRID ->
                        PageGrid(uiState = uiState, thumbnailLoader = thumbnailLoader, actions = actions)
                    else ->
                        PageRows(uiState = uiState, thumbnailLoader = thumbnailLoader, actions = actions)
                }
            }
            PageListMessageBar(
                uiState = uiState,
                onUndoRequested = actions.onUndoRequested,
                onDismiss = actions.onMessageDismissed,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(ScreenHorizontalMargin),
            )
        }
    }
    uiState.deleteConfirmation?.let { confirmation ->
        PageDeleteConfirmDialog(
            pageCount = confirmation.pageCount,
            onConfirm = actions.onDeleteConfirmed,
            onDismiss = actions.onDeleteDismissed,
        )
    }
}

/**
 * 削除確認ダイアログ（docs/specs/08-page-editing.md §6「削除確認で件数を必ず表示」）。
 *
 * この画面のダイアログ素材は無い（docs/design/07-page-list.md「未定事項」）ので、
 * 素材のあるごみ箱画面（docs/design/mockups/04-trash.png）の様式に合わせる:
 * 本文に対象件数、実行は Destructive（`--color-error` 塗り・白文字）、
 * キャンセルは Secondary（primary 枠線・primary 文字・透明地。docs/design/system/02-components.md）。
 * ごみ箱の完全削除と違いページ削除は取り消せる（同 §3.4）ので、本文もそのとおりに書く。
 */
@Composable
private fun PageDeleteConfirmDialog(
    pageCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(CardCornerRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.page_list_delete_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                color = ColorText,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.page_list_delete_dialog_message, pageCount),
                style = MaterialTheme.typography.bodyLarge,
                color = ColorText,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(ButtonCornerRadius),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = ColorError,
                        contentColor = Color.White,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.page_list_delete_dialog_confirm),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(ButtonCornerRadius),
                border = BorderStroke(HAIRLINE_BORDER_WIDTH, ColorPrimary),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = ColorPrimary,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.page_list_delete_dialog_cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/**
 * 取り消し案内と操作失敗の表示（docs/specs/08-page-editing.md §3.4・§6）。
 *
 * 素材が無いので新しい色は作らず、カードの様式（surface 白・角丸12dp・elevation 1・divider 枠）で出す
 * （docs/design/system/01-tokens.md・02-components.md）。自動では消さず、
 * 「元に戻す」を押すか閉じるまで残す（取り消しが直前1操作しか無く、押し逃すと戻せないため）。
 */
@Composable
private fun PageListMessageBar(
    uiState: PageListUiState,
    onUndoRequested: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorRes = uiState.operationError?.messageRes()
    val undoableEdit = uiState.undoableEdit
    if (errorRes == null && undoableEdit == null) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag(PAGE_LIST_MESSAGE_BAR_TEST_TAG),
        shape = RoundedCornerShape(CardCornerRadius),
        color = ColorSurface,
        border = BorderStroke(HAIRLINE_BORDER_WIDTH, ColorDivider),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = SpaceUnit * 2, end = SpaceUnit / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    when {
                        errorRes != null -> stringResource(errorRes)
                        undoableEdit is PageListUndoableEdit.Delete ->
                            stringResource(R.string.page_list_undo_delete, undoableEdit.pageCount)
                        else -> stringResource(R.string.page_list_undo_reorder)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorRes != null) ColorError else ColorText,
                modifier = Modifier.weight(1f).padding(vertical = SpaceUnit),
            )
            if (undoableEdit != null) {
                TextButton(onClick = onUndoRequested, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(
                        text = stringResource(R.string.page_list_undo_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorPrimary,
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.page_list_message_dismiss),
                    tint = ColorTextSecondary,
                )
            }
        }
    }
}

private fun PageListOperationError.messageRes(): Int =
    when (this) {
        PageListOperationError.DELETE -> R.string.page_list_delete_failed
        PageListOperationError.REORDER -> R.string.page_list_reorder_failed
        PageListOperationError.UNDO -> R.string.page_list_undo_failed
    }

/**
 * 通常時のアプリバー。素材が無い状態（docs/design/07-page-list.md「未定事項」）なので、
 * 他画面と同じ「background 地に黒文字・左に戻る」だけに留める（docs/design/00-design-overview.md）。
 */
@Composable
private fun PageListTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget + SpaceUnit * 2)
                .padding(horizontal = SpaceUnit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.page_list_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text = stringResource(R.string.page_list_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * 選択モードのアプリバー（docs/design/mockups/07-page-list.png）。
 * 全画面で唯一 `--color-primary` 塗り+白文字になるバー（docs/design/00-design-overview.md）。
 */
@Composable
private fun PageListSelectionTopBar(
    selectedCount: Int,
    onDeleteSelectedRequested: () -> Unit,
    onSelectionCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = ColorPrimary) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = MinTouchTarget + SpaceUnit * 2)
                    .padding(horizontal = SpaceUnit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.page_list_selection_count, selectedCount),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = SpaceUnit).weight(1f),
            )
            IconButton(onClick = onDeleteSelectedRequested, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.page_list_selection_delete),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onSelectionCleared, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.page_list_selection_clear),
                    tint = Color.White,
                )
            }
        }
    }
}

/** 表示切替トグル（左）と絞り込み（右）の行 */
@Composable
private fun PageListToolbar(
    viewMode: PageListViewMode,
    filter: PageListFilter,
    onViewModeChange: (PageListViewMode) -> Unit,
    onFilterChange: (PageListFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalMargin, vertical = SpaceUnit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewModeToggle(viewMode = viewMode, onViewModeChange = onViewModeChange)
        Spacer(modifier = Modifier.weight(1f))
        PageListFilterMenu(filter = filter, onFilterChange = onFilterChange)
    }
}

@Composable
private fun ViewModeToggle(
    viewMode: PageListViewMode,
    onViewModeChange: (PageListViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .height(MinTouchTarget)
                .clip(RoundedCornerShape(ButtonCornerRadius))
                .border(HAIRLINE_BORDER_WIDTH, ColorDivider, RoundedCornerShape(ButtonCornerRadius)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val gridLabel = stringResource(R.string.page_list_view_mode_grid)
        val listLabel = stringResource(R.string.page_list_view_mode_list)
        ViewModeButton(
            selected = viewMode == PageListViewMode.GRID,
            onClick = { onViewModeChange(PageListViewMode.GRID) },
        ) { tint ->
            Icon(
                painter = painterResource(R.drawable.ic_view_grid),
                contentDescription = gridLabel,
                tint = tint,
            )
        }
        VerticalDivider(color = ColorDivider)
        ViewModeButton(
            selected = viewMode == PageListViewMode.LIST,
            onClick = { onViewModeChange(PageListViewMode.LIST) },
        ) { tint ->
            Icon(
                imageVector = Icons.AutoMirrored.Filled.List,
                contentDescription = listLabel,
                tint = tint,
            )
        }
    }
}

/**
 * 選択側に primary の枠を付ける（docs/design/07-page-list.md「選択側に枠」）。
 * 地は塗らない — モックの選択側も地色は背景のままで、
 * 様式は Secondary ボタン（枠線 primary・文字 primary・透明地。docs/design/system/02-components.md）に合わせる。
 */
@Composable
private fun ViewModeButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color) -> Unit,
) {
    val selectionShape = RoundedCornerShape(ButtonCornerRadius)
    Box(
        modifier =
            modifier
                .size(TOGGLE_BUTTON_WIDTH, MinTouchTarget)
                .then(
                    if (selected) {
                        Modifier.border(SELECTED_BORDER_WIDTH, ColorPrimary, selectionShape)
                    } else {
                        Modifier
                    },
                ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon(if (selected) ColorPrimary else ColorTextSecondary)
    }
}

/**
 * 絞り込みメニュー。モックの「ページ順 ▼」と同じ位置・様式で、
 * 中身は警告フィルタ（[PageListFilter] の KDoc に理由）。
 */
@Composable
private fun PageListFilterMenu(
    filter: PageListFilter,
    onFilterChange: (PageListFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = MinTouchTarget)
                    .clip(RoundedCornerShape(ButtonCornerRadius))
                    .clickable { expanded = true }
                    .padding(horizontal = SpaceUnit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(filter.labelRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = ColorText,
            )
            Spacer(modifier = Modifier.width(SpaceUnit / 2))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = ColorTextSecondary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PageListFilter.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(candidate.labelRes())) },
                    onClick = {
                        onFilterChange(candidate)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** グリッド表示。3列・8dp間隔（docs/design/system/01-tokens.md「サムネイルグリッド」） */
@Composable
private fun PageGrid(
    uiState: PageListUiState,
    thumbnailLoader: PageThumbnailLoader,
    actions: PageListScreenActions,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val reorderLayout = remember(gridState) { gridState.asPageReorderLayout() }
    val reorderState =
        rememberPageReorderState(
            layout = reorderLayout,
            onMove = actions.onPageMoved,
            onFinished = actions.onReorderFinished,
        )
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = gridState,
        modifier = modifier.fillMaxSize().testTag(PAGE_LIST_GRID_TEST_TAG),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalMargin, vertical = SpaceUnit),
        horizontalArrangement = Arrangement.spacedBy(SpaceUnit),
        verticalArrangement = Arrangement.spacedBy(SpaceUnit),
    ) {
        gridItemsIndexed(items = uiState.visiblePages, key = { _, item -> item.pageId }) { index, item ->
            PageGridCell(
                item = item,
                index = index,
                selected = uiState.isSelected(item.pageId),
                selectionMode = uiState.selectionMode,
                reorderEnabled = uiState.reorderEnabled,
                reorderState = reorderState,
                thumbnailLoader = thumbnailLoader,
                actions = actions,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageGridCell(
    item: PageListItemUiState,
    index: Int,
    selected: Boolean,
    selectionMode: Boolean,
    reorderEnabled: Boolean,
    reorderState: PageReorderState,
    thumbnailLoader: PageThumbnailLoader,
    actions: PageListScreenActions,
    modifier: Modifier = Modifier,
) {
    val cellShape = RoundedCornerShape(CardCornerRadius)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .reorderPlacement(reorderState = reorderState, index = index, shape = cellShape)
                .testTag(pageListCellTestTag(item.sequence))
                .then(
                    if (selected) {
                        Modifier.border(SELECTED_BORDER_WIDTH, ColorPrimary, cellShape)
                    } else {
                        Modifier
                    },
                ).clip(cellShape)
                .combinedClickable(
                    onClick = {
                        if (selectionMode) {
                            actions.onSelectionToggled(
                                item.pageId,
                            )
                        } else {
                            actions.onPageOpened(item.pageId)
                        }
                    },
                    onLongClick = { actions.onPageLongPressed(item.pageId) },
                ).padding(CELL_INNER_PADDING),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(SEQUENCE_ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = ColorPrimary,
                    modifier = Modifier.size(SELECTION_CHECK_SIZE),
                )
                Spacer(modifier = Modifier.width(SpaceUnit / 2))
            }
            Text(
                text = stringResource(R.string.page_list_page_number, item.sequence),
                style = MaterialTheme.typography.bodyLarge,
                color = ColorText,
            )
        }
        Spacer(modifier = Modifier.height(SpaceUnit / 2))
        PageThumbnailCard(
            item = item,
            index = index,
            reorderEnabled = reorderEnabled,
            reorderState = reorderState,
            thumbnailLoader = thumbnailLoader,
        )
    }
}

/** サムネイルカードとその上に載る状態バッジ・並べ替えつまみ */
@Composable
private fun PageThumbnailCard(
    item: PageListItemUiState,
    index: Int,
    reorderEnabled: Boolean,
    reorderState: PageReorderState,
    thumbnailLoader: PageThumbnailLoader,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(CardCornerRadius)
    Surface(
        modifier = modifier.fillMaxWidth().aspectRatio(THUMBNAIL_ASPECT_RATIO),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(HAIRLINE_BORDER_WIDTH, ColorDivider),
        shadowElevation = 1.dp,
    ) {
        Box {
            PageThumbnail(
                item = item,
                loader = thumbnailLoader,
                targetWidth = GRID_THUMBNAIL_TARGET_WIDTH,
                modifier = Modifier.fillMaxSize(),
            )
            // モックはカード左下・左右とも8dpのインセット（docs/design/mockups/07-page-list.png）。
            // 長文バッジ（「再実行が必要」）でも端の余白を食い潰さないよう end 側も同じだけ空ける
            PageStatusBadgeStack(
                item = item,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = SpaceUnit, end = SpaceUnit, bottom = SpaceUnit),
            )
            if (reorderEnabled) {
                PageReorderHandle(
                    sequence = item.sequence,
                    index = index,
                    reorderState = reorderState,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

/**
 * 並べ替えつまみ（docs/specs/08-page-editing.md §3.2 FR-EDT-002）。
 *
 * 長押しは既に「選択モードを開始する」に割り当たっている（docs/design/07-page-list.md「インタラクション」）ので、
 * 同じジェスチャに並べ替えを重ねず専用のつまみを置く。ドラッグ中の見た目は素材が無い（同「未定事項」）ため、
 * トークン内（surface 白・divider 枠・pill）で作る。タップ領域は 48dp（docs/design/system/03-principles.md）。
 */
@Composable
private fun PageReorderHandle(
    sequence: Int,
    index: Int,
    reorderState: PageReorderState,
    modifier: Modifier = Modifier,
) {
    // ドラッグ中に並びが入れ替わると index が変わる。pointerInput の key にすると
    // ジェスチャ検出ごと作り直されてドラッグが切れるので、最新値だけを読む
    val currentIndex by rememberUpdatedState(index)
    Box(
        modifier =
            modifier
                .size(MinTouchTarget)
                .testTag(pageReorderHandleTestTag(sequence))
                .pointerInput(reorderState) {
                    detectDragGestures(
                        onDragStart = { reorderState.onDragStart(currentIndex) },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            reorderState.onDrag(dragAmount)
                        },
                        onDragEnd = { reorderState.onDragEnd() },
                        onDragCancel = { reorderState.onDragEnd() },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(REORDER_HANDLE_SIZE),
            shape = CircleShape,
            color = ColorSurface,
            border = BorderStroke(HAIRLINE_BORDER_WIDTH, ColorDivider),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = stringResource(R.string.page_list_reorder_handle, sequence),
                    tint = ColorTextSecondary,
                    modifier = Modifier.size(REORDER_HANDLE_ICON_SIZE),
                )
            }
        }
    }
}

/**
 * ドラッグ中のセル/行を指に追従させ、他のセルより前面へ出す。
 * 掴んでいる間だけ surface 地と控えめな影を敷いて、下の行と混ざらないようにする。
 */
private fun Modifier.reorderPlacement(
    reorderState: PageReorderState,
    index: Int,
    shape: RoundedCornerShape,
): Modifier =
    zIndex(if (reorderState.isDragging(index)) 1f else 0f)
        .graphicsLayer {
            val offset = reorderState.offsetFor(index)
            translationX = offset.x
            translationY = offset.y
        }.then(
            if (reorderState.isDragging(index)) {
                Modifier.shadow(DRAGGING_ELEVATION, shape).background(ColorSurface, shape)
            } else {
                Modifier
            },
        )

/**
 * リスト表示。素材が無い状態（docs/design/07-page-list.md「未定事項」）なので、
 * トークンの「リスト行高: 72dp（サムネイル付き）」「左サムネイル56dp角丸8dp」に従う
 * （docs/design/system/01-tokens.md / 02-components.md）。
 */
@Composable
private fun PageRows(
    uiState: PageListUiState,
    thumbnailLoader: PageThumbnailLoader,
    actions: PageListScreenActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val reorderLayout = remember(listState) { listState.asPageReorderLayout() }
    val reorderState =
        rememberPageReorderState(
            layout = reorderLayout,
            onMove = actions.onPageMoved,
            onFinished = actions.onReorderFinished,
        )
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag(PAGE_LIST_ROWS_TEST_TAG),
    ) {
        itemsIndexed(items = uiState.visiblePages, key = { _, item -> item.pageId }) { index, item ->
            // 行と区切り線でひと組。掴んだときはこの組ごと動かす
            Column(
                modifier =
                    Modifier.reorderPlacement(
                        reorderState = reorderState,
                        index = index,
                        shape = RoundedCornerShape(ButtonCornerRadius),
                    ),
            ) {
                PageListRow(
                    item = item,
                    index = index,
                    selected = uiState.isSelected(item.pageId),
                    selectionMode = uiState.selectionMode,
                    reorderEnabled = uiState.reorderEnabled,
                    reorderState = reorderState,
                    thumbnailLoader = thumbnailLoader,
                    actions = actions,
                )
                HorizontalDivider(color = ColorDivider)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageListRow(
    item: PageListItemUiState,
    index: Int,
    selected: Boolean,
    selectionMode: Boolean,
    reorderEnabled: Boolean,
    reorderState: PageReorderState,
    thumbnailLoader: PageThumbnailLoader,
    actions: PageListScreenActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(LIST_ROW_HEIGHT)
                .testTag(pageListRowTestTag(item.sequence))
                .background(
                    if (selected) ColorPrimary.copy(alpha = SELECTED_SURFACE_ALPHA) else Color.Transparent,
                ).combinedClickable(
                    onClick = {
                        if (selectionMode) {
                            actions.onSelectionToggled(
                                item.pageId,
                            )
                        } else {
                            actions.onPageOpened(item.pageId)
                        }
                    },
                    onLongClick = { actions.onPageLongPressed(item.pageId) },
                ).padding(horizontal = ScreenHorizontalMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.page_list_page_number, item.sequence),
            style = MaterialTheme.typography.bodyLarge,
            color = ColorText,
            textAlign = TextAlign.End,
            modifier = Modifier.width(SEQUENCE_COLUMN_WIDTH),
        )
        Spacer(modifier = Modifier.width(SpaceUnit * 1.5f))
        PageThumbnail(
            item = item,
            loader = thumbnailLoader,
            targetWidth = LIST_THUMBNAIL_SIZE,
            modifier =
                Modifier
                    .size(LIST_THUMBNAIL_SIZE)
                    .clip(RoundedCornerShape(ButtonCornerRadius))
                    .border(HAIRLINE_BORDER_WIDTH, ColorDivider, RoundedCornerShape(ButtonCornerRadius)),
        )
        Spacer(modifier = Modifier.width(SpaceUnit * 1.5f))
        PageStatusBadgeRow(item = item, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = ColorPrimary,
                modifier = Modifier.size(SELECTION_CHECK_SIZE),
            )
        }
        if (reorderEnabled) {
            PageReorderHandle(sequence = item.sequence, index = index, reorderState = reorderState)
        }
    }
}

/** 0件・読み込み失敗の案内。失敗時だけ再試行を出す */
@Composable
private fun PageListNotice(
    messageRes: Int,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = ScreenHorizontalMargin),
        ) {
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyLarge,
                color = ColorTextSecondary,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(
                        text = stringResource(R.string.page_list_reload),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorPrimary,
                    )
                }
            }
        }
    }
}

private fun PageListFilter.labelRes(): Int =
    when (this) {
        PageListFilter.ALL -> R.string.page_list_filter_all
        PageListFilter.DUPLICATE -> R.string.page_list_filter_duplicate
        PageListFilter.BLACK -> R.string.page_list_filter_black
        PageListFilter.OCR_INCOMPLETE -> R.string.page_list_filter_ocr_incomplete
    }

private const val GRID_COLUMNS = 3

/**
 * サムネイルの縦横比。撮影対象は端末画面の縦キャプチャなので 9:16 を基準にする
 * （モックのセルもこの比率で描かれている）。
 */
private const val THUMBNAIL_ASPECT_RATIO = 9f / 16f

/** 選択枠・選択地色（primary の薄地）。トークンに container 系が無いため不透明度で作る */
private const val SELECTED_SURFACE_ALPHA = 0.08f
private val SELECTED_BORDER_WIDTH = 2.dp
private val HAIRLINE_BORDER_WIDTH = 1.dp
private val TOGGLE_BUTTON_WIDTH = 56.dp
private val CELL_INNER_PADDING = 4.dp
private val SEQUENCE_ROW_HEIGHT = 24.dp
private val SEQUENCE_COLUMN_WIDTH = 32.dp
private val SELECTION_CHECK_SIZE = 20.dp

/** つまみの見た目。タップ領域は 48dp のまま（原則: 最小48dp） */
private val REORDER_HANDLE_SIZE = 32.dp
private val REORDER_HANDLE_ICON_SIZE = 20.dp

/** 掴んでいるあいだの影。トークンの「控えめ（elevation 1〜2 相当）」の上限に置く */
private val DRAGGING_ELEVATION = 2.dp

/** リスト行高 72dp・サムネイル56dp（docs/design/system/01-tokens.md / 02-components.md） */
private val LIST_ROW_HEIGHT = 72.dp
private val LIST_THUMBNAIL_SIZE = 56.dp

/** グリッドのサムネイル復号目標幅。3列の実効セル幅（約110dp）を少し上回る値に置く */
private val GRID_THUMBNAIL_TARGET_WIDTH = 160.dp
