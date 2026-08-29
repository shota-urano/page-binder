package com.pagebinder.app.ui.duplicatereview

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.ui.pagelist.PageListItemUiState
import com.pagebinder.app.ui.pagelist.PageStatusBadge
import com.pagebinder.app.ui.pagelist.PageStatusBadgeChip
import com.pagebinder.app.ui.pagelist.PageThumbnail
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorBackground
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

/** 確認画面の操作。ViewModel を持ち込まずに画面をテストできるようにまとめてある */
@Stable
class DuplicateReviewScreenActions(
    val onBack: () -> Unit,
    /** 「このページを残す」。同じ組の他方は削除候補になる（排他選択） */
    val onKeepPageSelected: (groupId: UUID, pageId: UUID) -> Unit,
    /** 黒画面候補の「削除」。件数を出す確認を開くだけで、まだ削除しない */
    val onBlackPageDeleteRequested: (UUID) -> Unit,
    /** 黒画面候補の「残す」。ページはそのままで確認一覧から外す */
    val onBlackPageKept: (UUID) -> Unit,
    /** 重複の削除候補をまとめて削除する要求。こちらも確認を開くだけ */
    val onDuplicateDeleteRequested: () -> Unit,
    val onDeleteConfirmed: () -> Unit,
    val onDeleteDismissed: () -> Unit,
    /** 直前1操作の取り消し（docs/specs/08-page-editing.md §3.4） */
    val onUndoRequested: () -> Unit,
    val onMessageDismissed: () -> Unit,
    val onReload: () -> Unit,
)

/** 候補一覧そのもの。0件・読み込み失敗と出し分かることを見る */
const val DUPLICATE_REVIEW_LIST_TEST_TAG = "duplicateReviewList"

/** 取り消し案内・操作失敗のバー */
const val DUPLICATE_REVIEW_MESSAGE_BAR_TEST_TAG = "duplicateReviewMessageBar"

/** 削除候補をまとめて削除するバー。削除候補が1件も無ければ出ない */
const val DUPLICATE_REVIEW_DELETE_BAR_TEST_TAG = "duplicateReviewDeleteBar"

/**
 * 確認ダイアログの実行ボタン。「削除」の文言は黒画面候補の行にも並ぶので、
 * ダイアログ側はタグで区別する
 */
const val DUPLICATE_REVIEW_DELETE_CONFIRM_TEST_TAG = "duplicateReviewDeleteConfirm"

/** 比較ペアの片側。ページ単位で「残す」の状態を確認できるようにする */
fun duplicateReviewCandidateTestTag(sequence: Int): String = "duplicateReviewCandidate_$sequence"

/** 「このページを残す」ボタン。同じ文言が組の数だけ並ぶのでページ単位で参照する */
fun duplicateReviewKeepTestTag(sequence: Int): String = "duplicateReviewKeep_$sequence"

fun duplicateReviewBlackRowTestTag(sequence: Int): String = "duplicateReviewBlackRow_$sequence"

fun duplicateReviewBlackDeleteTestTag(sequence: Int): String = "duplicateReviewBlackDelete_$sequence"

fun duplicateReviewBlackKeepTestTag(sequence: Int): String = "duplicateReviewBlackKeep_$sequence"

/**
 * 重複候補比較・黒画面候補一覧（docs/design/09-duplicate-review.md /
 * docs/specs/08-page-editing.md §3.2 FR-EDT-006・FR-EDT-007）。
 *
 * 描くのはアプリバーから下のコンテンツ領域だけで、ステータスバー・ナビゲーションバーは OS が描く
 * （docs/design/system/03-principles.md「モック画像の読み方」）。表示値はすべて [uiState] から描き、
 * モックのサンプルデータ（ページ番号・本文）は持たない。
 */
@Composable
fun DuplicateReviewScreen(
    uiState: DuplicateReviewUiState,
    thumbnailLoader: PageThumbnailLoader,
    actions: DuplicateReviewScreenActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            DuplicateReviewTopBar(onBack = actions.onBack)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    uiState.loadFailed ->
                        DuplicateReviewNotice(
                            messageRes = R.string.duplicate_review_load_failed,
                            onRetry = actions.onReload,
                        )
                    uiState.empty -> DuplicateReviewNotice(messageRes = R.string.duplicate_review_empty)
                    else ->
                        DuplicateReviewList(
                            uiState = uiState,
                            thumbnailLoader = thumbnailLoader,
                            actions = actions,
                        )
                }
            }
            DuplicateReviewMessageBar(
                uiState = uiState,
                onUndoRequested = actions.onUndoRequested,
                onDismiss = actions.onMessageDismissed,
            )
            DuplicateDeleteBar(
                candidateCount = uiState.duplicateDeleteCandidatePageIds.size,
                enabled = !uiState.deleting,
                onDelete = actions.onDuplicateDeleteRequested,
            )
        }
    }
    uiState.deleteConfirmation?.let { confirmation ->
        DuplicateReviewDeleteDialog(
            pageCount = confirmation.pageCount,
            onConfirm = actions.onDeleteConfirmed,
            onDismiss = actions.onDeleteDismissed,
        )
    }
}

/**
 * 候補一覧。重複・黒画面の2枚のカードを縦に積む（docs/design/09-duplicate-review.md「レイアウト構造」）。
 *
 * カードは見出し・候補・注記の断片に分けて [LazyColumn] へ流し込む。同じ地色（`--color-surface`）の
 * 断片を隙間なく並べるので見た目は1枚のカードのままで、候補が増えても見えている分しか組み立てない。
 */
@Composable
private fun DuplicateReviewList(
    uiState: DuplicateReviewUiState,
    thumbnailLoader: PageThumbnailLoader,
    actions: DuplicateReviewScreenActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(DUPLICATE_REVIEW_LIST_TEST_TAG),
        contentPadding = PaddingValues(vertical = SpaceUnit),
    ) {
        duplicateSection(uiState = uiState, thumbnailLoader = thumbnailLoader, actions = actions)
        if (uiState.duplicateGroups.isNotEmpty() && uiState.blackCandidates.isNotEmpty()) {
            item { Spacer(modifier = Modifier.height(SpaceUnit * 2)) }
        }
        blackSection(uiState = uiState, thumbnailLoader = thumbnailLoader, actions = actions)
    }
}

private fun LazyListScope.duplicateSection(
    uiState: DuplicateReviewUiState,
    thumbnailLoader: PageThumbnailLoader,
    actions: DuplicateReviewScreenActions,
) {
    if (uiState.duplicateGroups.isEmpty()) return
    item(key = "duplicateHeader") {
        ReviewCardPiece(piece = CardPiece.TOP) {
            SectionHeader(
                titleRes = R.string.duplicate_review_duplicate_heading,
                count = uiState.duplicateGroupCount,
                badge = PageStatusBadge.WARNING_DUPLICATE,
            )
        }
    }
    items(items = uiState.duplicateGroups, key = { it.groupId }) { group ->
        ReviewCardPiece(piece = CardPiece.MIDDLE) {
            DuplicateGroupComparison(
                group = group,
                thumbnailLoader = thumbnailLoader,
                onKeepPageSelected = actions.onKeepPageSelected,
            )
        }
    }
    item(key = "duplicateNote") {
        ReviewCardPiece(piece = CardPiece.BOTTOM) {
            Text(
                text = stringResource(R.string.duplicate_review_note),
                style = MaterialTheme.typography.labelSmall,
                color = ColorTextSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(bottom = SpaceUnit * 2),
            )
        }
    }
}

private fun LazyListScope.blackSection(
    uiState: DuplicateReviewUiState,
    thumbnailLoader: PageThumbnailLoader,
    actions: DuplicateReviewScreenActions,
) {
    if (uiState.blackCandidates.isEmpty()) return
    item(key = "blackHeader") {
        ReviewCardPiece(piece = CardPiece.TOP) {
            SectionHeader(
                titleRes = R.string.duplicate_review_black_heading,
                count = uiState.blackCandidateCount,
                badge = PageStatusBadge.WARNING_BLACK,
            )
        }
    }
    val lastPageId = uiState.blackCandidates.last().pageId
    items(items = uiState.blackCandidates, key = { it.pageId }) { candidate ->
        ReviewCardPiece(
            piece = if (candidate.pageId == lastPageId) CardPiece.BOTTOM else CardPiece.MIDDLE,
        ) {
            BlackCandidateRow(
                page = candidate,
                thumbnailLoader = thumbnailLoader,
                onDelete = { actions.onBlackPageDeleteRequested(candidate.pageId) },
                onKeep = { actions.onBlackPageKept(candidate.pageId) },
                modifier = Modifier.padding(bottom = SpaceUnit),
            )
        }
    }
}

/** カード断片の位置。上端・下端だけ角を丸める */
private enum class CardPiece {
    TOP,
    MIDDLE,
    BOTTOM,
}

/**
 * カード（`--color-surface` 白・角丸12dp。docs/design/system/01-tokens.md）の断片。
 * 左右のマージン16dp とカード内の左右余白16dp はどの断片でも同じにして、継ぎ目を見せない。
 */
@Composable
private fun ReviewCardPiece(
    piece: CardPiece,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalMargin),
        shape =
            RoundedCornerShape(
                topStart = if (piece == CardPiece.TOP) CardCornerRadius else 0.dp,
                topEnd = if (piece == CardPiece.TOP) CardCornerRadius else 0.dp,
                bottomStart = if (piece == CardPiece.BOTTOM) CardCornerRadius else 0.dp,
                bottomEnd = if (piece == CardPiece.BOTTOM) CardCornerRadius else 0.dp,
            ),
        color = ColorSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = ScreenHorizontalMargin),
            content = content,
        )
    }
}

/**
 * カードの見出し行（docs/design/09-duplicate-review.md「見出し行」）。
 *
 * 右端の警告 pill は一覧と同じ [PageStatusBadgeChip] を使う。`--color-warning` の pill という指定
 * （docs/design/system/02-components.md）を満たしつつ、アイコン+文字の併記（requirements §16.4）も
 * 一覧と同じ見え方で揃う。カード見出しは「見出し 22sp」= headlineSmall（docs/design/system/01-tokens.md）。
 */
@Composable
private fun SectionHeader(
    @StringRes titleRes: Int,
    count: Int,
    badge: PageStatusBadge,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = SpaceUnit * 2, bottom = SpaceUnit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes, count),
            style = MaterialTheme.typography.headlineSmall,
            color = ColorText,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(SpaceUnit))
        PageStatusBadgeChip(badge = badge)
    }
}

/** 比較ペア（横並び）。3枚以上の組でも同じ並びで比較できるように等分に置く */
@Composable
private fun DuplicateGroupComparison(
    group: DuplicateGroupUiState,
    thumbnailLoader: PageThumbnailLoader,
    onKeepPageSelected: (groupId: UUID, pageId: UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = SpaceUnit),
        horizontalArrangement = Arrangement.spacedBy(SpaceUnit),
    ) {
        group.pages.forEach { page ->
            DuplicateCandidate(
                page = page,
                kept = group.isKept(page.pageId),
                thumbnailLoader = thumbnailLoader,
                onKeep = { onKeepPageSelected(group.groupId, page.pageId) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 比較ペアの片側: プレビュー・ページ番号・「このページを残す」 */
@Composable
private fun DuplicateCandidate(
    page: PageListItemUiState,
    kept: Boolean,
    thumbnailLoader: PageThumbnailLoader,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnailShape = RoundedCornerShape(ButtonCornerRadius)
    Surface(
        modifier = modifier.testTag(duplicateReviewCandidateTestTag(page.sequence)),
        shape = RoundedCornerShape(CardCornerRadius),
        color = ColorSurface,
        border = BorderStroke(HAIRLINE_BORDER_WIDTH, ColorDivider),
    ) {
        Column(
            modifier = Modifier.padding(SpaceUnit),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PageThumbnail(
                item = page,
                loader = thumbnailLoader,
                targetWidth = COMPARE_THUMBNAIL_TARGET_WIDTH,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(THUMBNAIL_ASPECT_RATIO)
                        .clip(thumbnailShape)
                        .border(HAIRLINE_BORDER_WIDTH, ColorDivider, thumbnailShape),
            )
            Spacer(modifier = Modifier.height(SpaceUnit))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 選択側のチェック（docs/design/09-duplicate-review.md「選択側: チェック付き」）。
                // ボタンの中には置けない —「このページを残す」は全角8文字=16sp で 128dp あり、
                // 比較ペアの1列（実機 411dp で約 170dp）からカード・ボタンの余白を引いた
                // 約 138dp にチェック(20dp)+間隔まで収める余地が無く、必ず2行に折り返してしまう。
                // ページ番号の隣なら収まり、一覧の選択表示（PageListScreen）とも同じ形になる
                if (kept) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = ColorPrimary,
                        modifier = Modifier.size(KEEP_CHECK_SIZE),
                    )
                    Spacer(modifier = Modifier.width(SpaceUnit / 2))
                }
                Text(
                    text = stringResource(R.string.duplicate_review_page_label, page.sequence),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ColorText,
                )
            }
            Spacer(modifier = Modifier.height(SpaceUnit))
            KeepPageButton(page = page, kept = kept, onKeep = onKeep)
        }
    }
}

/**
 * 「このページを残す」（排他選択）。
 *
 * 選択側は primary の枠を強め、非選択側は divider の枠のままにする
 * （docs/design/09-duplicate-review.md「選択側: primary 枠強調 / 非選択側: 通常枠線」。
 * チェックは幅の都合でページ番号の隣に置く — [DuplicateCandidate] のコメント）。
 * 様式は Secondary ボタン（枠線・透明地。docs/design/system/02-components.md）。
 *
 * 文言はモックどおり1行で出す。左右の余白を詰めて全角8文字（128dp）を収めており、
 * 実機幅（411dp）では約 146dp 取れる。maxLines は指定しない — さらに狭い端末
 * （360dp 級）では切り詰めるより折り返す方が読めるし、左右とも同じ高さで折り返す。
 */
@Composable
private fun KeepPageButton(
    page: PageListItemUiState,
    kept: Boolean,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onKeep,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .testTag(duplicateReviewKeepTestTag(page.sequence))
                .semantics { selected = kept },
        shape = RoundedCornerShape(ButtonCornerRadius),
        border =
            BorderStroke(
                width = if (kept) SELECTED_BORDER_WIDTH else HAIRLINE_BORDER_WIDTH,
                color = if (kept) ColorPrimary else ColorDivider,
            ),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = if (kept) ColorPrimary else ColorText,
            ),
        contentPadding = PaddingValues(horizontal = SpaceUnit / 2, vertical = SpaceUnit),
    ) {
        Text(
            text = stringResource(R.string.duplicate_review_keep),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 黒画面候補の1行（docs/design/09-duplicate-review.md「候補行」）。
 *
 * 説明文は「保護された画面の可能性があります」まで。回避の案内は書かない
 * （AGENTS.md ルール2・docs/specs/12-legal-guardrails.md）。
 *
 * 説明文（全角16文字・14sp = 224dp）はモックどおり1行で出す。削除／残すと横に並べると
 * 実機幅（411dp）で約 166dp しか残らず必ず折り返すので、操作はページ番号と同じ段に寄せ、
 * 説明文だけ段を分けて行いっぱいに使う（約 263dp）。
 */
@Composable
private fun BlackCandidateRow(
    page: PageListItemUiState,
    thumbnailLoader: PageThumbnailLoader,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnailShape = RoundedCornerShape(ButtonCornerRadius)
    Surface(
        modifier = modifier.fillMaxWidth().testTag(duplicateReviewBlackRowTestTag(page.sequence)),
        shape = RoundedCornerShape(ButtonCornerRadius),
        color = ColorBackground,
    ) {
        Row(
            modifier = Modifier.heightIn(min = LIST_ROW_HEIGHT).padding(horizontal = SpaceUnit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageThumbnail(
                item = page,
                loader = thumbnailLoader,
                targetWidth = LIST_THUMBNAIL_SIZE,
                modifier =
                    Modifier
                        .size(LIST_THUMBNAIL_SIZE)
                        .clip(thumbnailShape)
                        .border(HAIRLINE_BORDER_WIDTH, ColorDivider, thumbnailShape),
            )
            Spacer(modifier = Modifier.width(SpaceUnit * 1.5f))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.duplicate_review_page_label, page.sequence),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ColorText,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onDelete,
                        modifier =
                            Modifier
                                .heightIn(min = MinTouchTarget)
                                .testTag(duplicateReviewBlackDeleteTestTag(page.sequence)),
                        contentPadding = PaddingValues(horizontal = SpaceUnit),
                    ) {
                        Text(
                            text = stringResource(R.string.duplicate_review_black_delete),
                            style = MaterialTheme.typography.labelLarge,
                            color = ColorError,
                        )
                    }
                    VerticalDivider(modifier = Modifier.height(ROW_DIVIDER_HEIGHT), color = ColorDivider)
                    TextButton(
                        onClick = onKeep,
                        modifier =
                            Modifier
                                .heightIn(min = MinTouchTarget)
                                .testTag(duplicateReviewBlackKeepTestTag(page.sequence)),
                        contentPadding = PaddingValues(horizontal = SpaceUnit),
                    ) {
                        Text(
                            text = stringResource(R.string.duplicate_review_black_keep),
                            style = MaterialTheme.typography.labelLarge,
                            color = ColorPrimary,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.duplicate_review_black_reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorTextSecondary,
                )
            }
        }
    }
}

/**
 * 削除候補をまとめて削除するバー。
 *
 * 「削除候補」が確定削除になるタイミングは素材に無い（docs/design/09-duplicate-review.md「未定事項」）ので、
 * 同ファイルの推測どおり明示的な実行操作を置き、押すと件数付きの確認へ進む。
 * 削除候補が1件も無いあいだは出さない。
 */
@Composable
private fun DuplicateDeleteBar(
    candidateCount: Int,
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidateCount == 0) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag(DUPLICATE_REVIEW_DELETE_BAR_TEST_TAG),
        color = ColorSurface,
        shadowElevation = 1.dp,
    ) {
        Button(
            onClick = onDelete,
            enabled = enabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(ScreenHorizontalMargin)
                    .heightIn(min = MinTouchTarget),
            shape = RoundedCornerShape(ButtonCornerRadius),
            colors = ButtonDefaults.buttonColors(containerColor = ColorError, contentColor = Color.White),
        ) {
            Text(
                text = stringResource(R.string.duplicate_review_delete_candidates, candidateCount),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * 削除確認ダイアログ（docs/specs/08-page-editing.md §6「削除確認で件数を必ず表示」）。
 *
 * この画面のダイアログ素材は無い（docs/design/09-duplicate-review.md「未定事項」）ので、
 * 素材のあるごみ箱画面（docs/design/mockups/04-trash.png）と同じ様式にする:
 * 本文に対象件数、実行は Destructive（`--color-error` 塗り・白文字）、
 * キャンセルは Secondary（primary 枠線・primary 文字・透明地。docs/design/system/02-components.md）。
 */
@Composable
private fun DuplicateReviewDeleteDialog(
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
                text = stringResource(R.string.duplicate_review_delete_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                color = ColorText,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.duplicate_review_delete_dialog_message, pageCount),
                style = MaterialTheme.typography.bodyLarge,
                color = ColorText,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier =
                    Modifier
                        .heightIn(min = MinTouchTarget)
                        .testTag(DUPLICATE_REVIEW_DELETE_CONFIRM_TEST_TAG),
                shape = RoundedCornerShape(ButtonCornerRadius),
                colors = ButtonDefaults.buttonColors(containerColor = ColorError, contentColor = Color.White),
            ) {
                Text(
                    text = stringResource(R.string.duplicate_review_delete_dialog_confirm),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = MinTouchTarget),
                shape = RoundedCornerShape(ButtonCornerRadius),
                border = BorderStroke(HAIRLINE_BORDER_WIDTH, ColorPrimary),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = ColorPrimary,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.duplicate_review_delete_dialog_cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/**
 * 取り消し案内と操作失敗の表示（docs/specs/08-page-editing.md §3.4・§6）。
 *
 * 一覧画面と同じく、自動では消さず「元に戻す」を押すか閉じるまで残す
 * （取り消しは直前1操作しか無く、押し逃すと戻せないため）。
 */
@Composable
private fun DuplicateReviewMessageBar(
    uiState: DuplicateReviewUiState,
    onUndoRequested: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorRes = uiState.operationError?.messageRes()
    val undoableDelete = uiState.undoableDelete
    if (errorRes == null && undoableDelete == null) return
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalMargin, vertical = SpaceUnit)
                .testTag(DUPLICATE_REVIEW_MESSAGE_BAR_TEST_TAG),
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
                    if (errorRes != null) {
                        stringResource(errorRes)
                    } else {
                        stringResource(R.string.duplicate_review_undo_delete, undoableDelete?.pageCount ?: 0)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorRes != null) ColorError else ColorText,
                modifier = Modifier.weight(1f).padding(vertical = SpaceUnit),
            )
            if (undoableDelete != null) {
                TextButton(onClick = onUndoRequested, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(
                        text = stringResource(R.string.duplicate_review_undo_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorPrimary,
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.duplicate_review_message_dismiss),
                    tint = ColorTextSecondary,
                )
            }
        }
    }
}

private fun DuplicateReviewOperationError.messageRes(): Int =
    when (this) {
        DuplicateReviewOperationError.DELETE -> R.string.duplicate_review_delete_failed
        DuplicateReviewOperationError.UNDO -> R.string.duplicate_review_undo_failed
    }

/**
 * アプリバー。他画面と同じ「background 地に黒文字・左に戻る」
 * （docs/design/00-design-overview.md）。
 */
@Composable
private fun DuplicateReviewTopBar(
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
                contentDescription = stringResource(R.string.duplicate_review_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text = stringResource(R.string.duplicate_review_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** 0件・読み込み失敗の案内。失敗時だけ再試行を出す */
@Composable
private fun DuplicateReviewNotice(
    @StringRes messageRes: Int,
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
                        text = stringResource(R.string.duplicate_review_reload),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorPrimary,
                    )
                }
            }
        }
    }
}

/** 撮影対象は端末画面の縦キャプチャなので 9:16（一覧のサムネイルと同じ比率） */
private const val THUMBNAIL_ASPECT_RATIO = 9f / 16f

private val SELECTED_BORDER_WIDTH = 2.dp
private val HAIRLINE_BORDER_WIDTH = 1.dp
private val KEEP_CHECK_SIZE = 20.dp

/** リスト行高 72dp・サムネイル56dp（docs/design/system/01-tokens.md / 02-components.md） */
private val LIST_ROW_HEIGHT = 72.dp
private val LIST_THUMBNAIL_SIZE = 56.dp
private val ROW_DIVIDER_HEIGHT = 24.dp

/** 比較プレビューの復号目標幅。2列に置いたときの実効幅（約150dp）を少し上回る値 */
private val COMPARE_THUMBNAIL_TARGET_WIDTH = 200.dp
