package com.pagebinder.app.ui.pagelist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorDivider
import com.pagebinder.app.ui.theme.ColorPrimary
import com.pagebinder.app.ui.theme.ColorText
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit
import java.util.UUID
import androidx.compose.foundation.lazy.grid.items as gridItems

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
    /**
     * 選択モードバーのごみ箱。件数を出す削除確認ダイアログは次の実装単位の担当
     * （docs/specs/08-page-editing.md §9「[Frontend] ドラッグ並べ替えと削除確認（件数表示）UI」）なので、
     * この画面は要求を上へ渡すだけで削除はしない（確認なしの複数削除をしない — 同 §6）。
     */
    val onDeleteSelectedRequested: () -> Unit,
    val onReload: () -> Unit,
)

/** 500ページでもスクロールできることを見るためのテスト用タグ（docs/specs/08-page-editing.md §3.1） */
const val PAGE_LIST_CONTENT_TEST_TAG = "pageListContent"

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
    }
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

/** 選択側に primary の枠を付ける（docs/design/07-page-list.md「選択側に枠」） */
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
                        Modifier
                            .background(ColorPrimary.copy(alpha = SELECTED_SURFACE_ALPHA), selectionShape)
                            .border(SELECTED_BORDER_WIDTH, ColorPrimary, selectionShape)
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
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = modifier.fillMaxSize().testTag(PAGE_LIST_CONTENT_TEST_TAG),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalMargin, vertical = SpaceUnit),
        horizontalArrangement = Arrangement.spacedBy(SpaceUnit),
        verticalArrangement = Arrangement.spacedBy(SpaceUnit),
    ) {
        gridItems(items = uiState.visiblePages, key = { it.pageId }) { item ->
            PageGridCell(
                item = item,
                selected = uiState.isSelected(item.pageId),
                selectionMode = uiState.selectionMode,
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
    selected: Boolean,
    selectionMode: Boolean,
    thumbnailLoader: PageThumbnailLoader,
    actions: PageListScreenActions,
    modifier: Modifier = Modifier,
) {
    val cellShape = RoundedCornerShape(CardCornerRadius)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
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
        PageThumbnailCard(item = item, thumbnailLoader = thumbnailLoader)
    }
}

/** サムネイルカードとその上に載る状態バッジ */
@Composable
private fun PageThumbnailCard(
    item: PageListItemUiState,
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
            PageStatusBadgeChip(
                badge = item.gridBadge,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = SpaceUnit),
            )
        }
    }
}

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
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(PAGE_LIST_CONTENT_TEST_TAG),
    ) {
        items(items = uiState.visiblePages, key = { it.pageId }) { item ->
            PageListRow(
                item = item,
                selected = uiState.isSelected(item.pageId),
                selectionMode = uiState.selectionMode,
                thumbnailLoader = thumbnailLoader,
                actions = actions,
            )
            HorizontalDivider(color = ColorDivider)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageListRow(
    item: PageListItemUiState,
    selected: Boolean,
    selectionMode: Boolean,
    thumbnailLoader: PageThumbnailLoader,
    actions: PageListScreenActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(LIST_ROW_HEIGHT)
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

/** リスト行高 72dp・サムネイル56dp（docs/design/system/01-tokens.md / 02-components.md） */
private val LIST_ROW_HEIGHT = 72.dp
private val LIST_THUMBNAIL_SIZE = 56.dp

/** グリッドのサムネイル復号目標幅。3列の実効セル幅（約110dp）を少し上回る値に置く */
private val GRID_THUMBNAIL_TARGET_WIDTH = 160.dp
