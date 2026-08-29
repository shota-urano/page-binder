package com.pagebinder.app.ui.pageedit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.pagelist.PageThumbnailRequest
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorAccent
import com.pagebinder.app.ui.theme.ColorDivider
import com.pagebinder.app.ui.theme.ColorError
import com.pagebinder.app.ui.theme.ColorOverlayBg
import com.pagebinder.app.ui.theme.ColorOverlayText
import com.pagebinder.app.ui.theme.ColorPrimary
import com.pagebinder.app.ui.theme.ColorSuccess
import com.pagebinder.app.ui.theme.ColorText
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.DISABLED_ALPHA
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit
import kotlin.math.roundToInt

/** 回転・切り取り編集画面の操作。ViewModel を持ち込まずに画面をテストできるようにまとめてある */
@Stable
class PageEditScreenActions(
    /** アプリバーの ×。未保存の変更があるかどうかで、破棄確認か閉じるかを呼び出し側が決める */
    val onCloseRequested: () -> Unit,
    val onReload: () -> Unit,
    val onRotateClockwise: () -> Unit,
    val onUndoRequested: () -> Unit,
    val onCropHandleDragged: (PageCropHandle, Float, Float) -> Unit,
    val onCropDragFinished: () -> Unit,
    val onApplyToAllPagesChanged: (Boolean) -> Unit,
    val onSaveRequested: () -> Unit,
    val onBulkApplyConfirmed: () -> Unit,
    val onBulkApplyDismissed: () -> Unit,
    val onDiscardConfirmed: () -> Unit,
    val onDiscardDismissed: () -> Unit,
    val onMessageDismissed: () -> Unit,
)

/** 編集キャンバス。ページ画像と切り取り枠が出ていることを見る */
const val PAGE_EDIT_CANVAS_TEST_TAG = "pageEditCanvas"

/** 一括適用のチェックボックス */
const val PAGE_EDIT_APPLY_ALL_TEST_TAG = "pageEditApplyAll"

/** 切り取り枠のつまみ。どのつまみを掴んだかを分けて確認できるようにする */
fun pageEditCropHandleTestTag(handle: PageCropHandle): String = "pageEditCropHandle_${handle.name}"

/**
 * 回転・切り取り編集画面（docs/design/08-page-edit.md / docs/specs/08-page-editing.md §3.2）。
 *
 * 描くのはアプリバーから下のコンテンツ領域だけで、ステータスバー・ナビゲーションバーは OS が描く
 * （docs/design/system/03-principles.md「モック画像の読み方」）。表示値はすべて [uiState] から描き、
 * モックのサンプルデータ（ページ番号・紙面の内容）は持たない。
 *
 * 画面のどの操作も元画像には触れない。回転・切り取りは属性として保存するだけで、
 * その約束はキャンバス下端の「元の画像は変更されません」で利用者にも明示する（FR-IMG-007）。
 */
@Composable
fun PageEditScreen(
    uiState: PageEditUiState,
    imageLoader: PageThumbnailLoader,
    actions: PageEditScreenActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageEditTopBar(uiState = uiState, actions = actions)
            when {
                uiState.loadFailed ->
                    PageEditNotice(messageRes = R.string.page_edit_load_failed, onRetry = actions.onReload)
                uiState.loading -> Spacer(modifier = Modifier.weight(1f))
                else -> {
                    PageEditCanvas(
                        uiState = uiState,
                        imageLoader = imageLoader,
                        actions = actions,
                        modifier = Modifier.weight(1f),
                    )
                    uiState.message?.let { message ->
                        PageEditMessageRow(message = message, onDismiss = actions.onMessageDismissed)
                    }
                    PageEditControls(uiState = uiState, actions = actions)
                }
            }
        }
    }
    if (uiState.bulkConfirmationVisible) {
        PageEditBulkApplyDialog(
            pageCount = uiState.projectPageCount,
            onConfirm = actions.onBulkApplyConfirmed,
            onDismiss = actions.onBulkApplyDismissed,
        )
    }
    if (uiState.discardConfirmationVisible) {
        PageEditDiscardDialog(
            onConfirm = actions.onDiscardConfirmed,
            onDismiss = actions.onDiscardDismissed,
        )
    }
}

/**
 * アプリバー。左に ×、右にテキストボタン「保存」（docs/design/mockups/08-page-edit.png）。
 * タイトルのページ番号は UiState から描く（モックの「12」はサンプル）。
 */
@Composable
private fun PageEditTopBar(
    uiState: PageEditUiState,
    actions: PageEditScreenActions,
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
        IconButton(onClick = actions.onCloseRequested, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.page_edit_close),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text = uiState.pageSequence?.let { stringResource(R.string.page_edit_title, it) }.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = actions.onSaveRequested,
            enabled = uiState.canSave,
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) {
            Text(
                text = stringResource(R.string.page_edit_save),
                style = MaterialTheme.typography.labelLarge,
                color = if (uiState.canSave) ColorPrimary else ColorPrimary.copy(alpha = DISABLED_ALPHA),
            )
        }
    }
}

/**
 * 編集キャンバス（暗色地）。ページ画像・切り取り枠・下端のキャプションを載せる。
 *
 * 地色は `--color-overlay-bg`（docs/design/system/01-tokens.md）。ライトテーマの画面の中で
 * ここだけ暗いのは素材どおりで、紙面と切り取り枠の境目を見分けやすくするため。
 */
@Composable
private fun PageEditCanvas(
    uiState: PageEditUiState,
    imageLoader: PageThumbnailLoader,
    actions: PageEditScreenActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().background(ColorOverlayBg).testTag(PAGE_EDIT_CANVAS_TEST_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            PageEditImageArea(uiState = uiState, imageLoader = imageLoader, actions = actions)
        }
        Text(
            text = stringResource(R.string.page_edit_non_destructive),
            // キャプション = 12sp（docs/design/08-page-edit.md「キャンバス下端キャプション」・
            // docs/design/system/01-tokens.md「タイポグラフィ」）
            style = MaterialTheme.typography.labelSmall,
            color = ColorOverlayText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = ScreenHorizontalMargin, vertical = SpaceUnit * 2),
        )
    }
}

/**
 * ページ画像と切り取り枠。
 *
 * 画像は非破壊の rotation だけを適用して読む（crop は枠として重ねるので適用しない）。
 * 取得契約は一覧・OCR編集と同じ [PageThumbnailLoader] を使い回す。
 */
@Composable
private fun PageEditImageArea(
    uiState: PageEditUiState,
    imageLoader: PageThumbnailLoader,
    actions: PageEditScreenActions,
    modifier: Modifier = Modifier,
) {
    val pageId = uiState.pageId ?: return
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().padding(SpaceUnit),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val targetWidthPx = with(density) { maxWidth.roundToPx() }
        val request =
            remember(pageId, uiState.rotation, targetWidthPx) {
                PageThumbnailRequest(
                    pageId = pageId,
                    rotation = uiState.rotation,
                    // 切り取りは枠で見せるので、画像そのものは切り取らずに全体を出す
                    crop = PageCrop(),
                    targetWidthPx = targetWidthPx,
                )
            }
        var attempt by remember(request) { mutableIntStateOf(0) }
        var image by remember(request) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(request) { mutableStateOf(false) }
        LaunchedEffect(request, attempt) {
            failed = false
            val loaded = runCatching { imageLoader.load(request) }.getOrNull()
            image = loaded
            failed = loaded == null
        }

        val current = image
        when {
            current != null -> {
                // つまみ（48dp）が画像の縁に半分はみ出すので、その分だけ内側に画像を置く
                val availableWidth = maxWidth - MinTouchTarget
                val availableHeight = maxHeight - MinTouchTarget
                val aspect = current.width.toFloat() / current.height.coerceAtLeast(1)
                val displayWidth = minOf(availableWidth, availableHeight * aspect)
                val displayHeight = displayWidth / aspect
                Box(
                    modifier =
                        Modifier
                            .width(displayWidth + MinTouchTarget)
                            .height(displayHeight + MinTouchTarget),
                ) {
                    Image(
                        bitmap = current,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier =
                            Modifier
                                .padding(MinTouchTarget / 2)
                                .width(displayWidth)
                                .height(displayHeight),
                    )
                    PageCropFrame(
                        crop = uiState.crop,
                        enabled = uiState.canEdit,
                        displayWidth = displayWidth,
                        displayHeight = displayHeight,
                        onHandleDragged = actions.onCropHandleDragged,
                        onDragFinished = actions.onCropDragFinished,
                    )
                }
            }
            failed ->
                TextButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(
                        text = stringResource(R.string.page_edit_image_retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorAccent,
                    )
                }
            else -> Box(modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 切り取り枠（docs/design/08-page-edit.md）。accent の線・白丸のつまみ8個・枠外の減光。
 *
 * つまみのドラッグ量はここで正規化してから ViewModel へ渡す。保存される crop は 0〜1 の
 * 正規化座標なので、端末の解像度や表示倍率は保存値に影響しない（docs/specs/08-page-editing.md §3.2）。
 *
 * 座標は「画像の左上を原点にした表示ピクセル」でなく、つまみの当たり判定 48dp を確保するために
 * 画像の四方へ 24dp ずつ広げた箱の中で扱う。つまみの中心が画像の角に来ても当たり判定が箱から出ない。
 */
@Composable
private fun PageCropFrame(
    crop: PageCrop,
    enabled: Boolean,
    displayWidth: Dp,
    displayHeight: Dp,
    onHandleDragged: (PageCropHandle, Float, Float) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { displayWidth.toPx() }
    val heightPx = with(density) { displayHeight.toPx() }
    val insetPx = with(density) { (MinTouchTarget / 2).toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val left = insetPx + crop.left * widthPx
        val top = insetPx + crop.top * heightPx
        val right = insetPx + crop.right * widthPx
        val bottom = insetPx + crop.bottom * heightPx
        val scrim = ColorOverlayBg.copy(alpha = CROP_SCRIM_ALPHA)
        // 枠外の減光。画像の外へはみ出した分は描かない
        drawRect(scrim, Offset(insetPx, insetPx), Size(widthPx, top - insetPx))
        drawRect(scrim, Offset(insetPx, top), Size(left - insetPx, bottom - top))
        drawRect(scrim, Offset(right, top), Size(insetPx + widthPx - right, bottom - top))
        drawRect(scrim, Offset(insetPx, bottom), Size(widthPx, insetPx + heightPx - bottom))
        drawRect(
            color = ColorAccent,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = CROP_FRAME_STROKE.toPx()),
        )
    }
    PageCropHandle.entries.forEach { handle ->
        val label = stringResource(handle.labelRes())
        Box(
            modifier =
                Modifier
                    .offset {
                        IntOffset(
                            (handle.normalizedX(crop) * widthPx).roundToInt(),
                            (handle.normalizedY(crop) * heightPx).roundToInt(),
                        )
                    }.size(MinTouchTarget)
                    .testTag(pageEditCropHandleTestTag(handle))
                    .semantics { contentDescription = label }
                    .pointerInput(handle, widthPx, heightPx, enabled) {
                        if (!enabled) return@pointerInput
                        detectDragGestures(
                            onDragEnd = onDragFinished,
                            onDragCancel = onDragFinished,
                        ) { change, dragAmount ->
                            change.consume()
                            onHandleDragged(handle, dragAmount.x / widthPx, dragAmount.y / heightPx)
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(CROP_HANDLE_SIZE),
                shape = CircleShape,
                color = Color.White,
                border = BorderStroke(HAIRLINE_BORDER_WIDTH, ColorAccent),
            ) {}
        }
    }
}

/**
 * 操作エリア（明色地）。枠線ボタン2つの行と、一括適用のチェックボックス行
 * （docs/design/mockups/08-page-edit.png）。
 */
@Composable
private fun PageEditControls(
    uiState: PageEditUiState,
    actions: PageEditScreenActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(ScreenHorizontalMargin),
                horizontalArrangement = Arrangement.spacedBy(SpaceUnit * 2),
            ) {
                PageEditSecondaryButton(
                    iconRes = R.drawable.ic_rotate_90,
                    labelRes = R.string.page_edit_rotate,
                    enabled = uiState.canEdit,
                    onClick = actions.onRotateClockwise,
                    modifier = Modifier.weight(1f),
                )
                PageEditSecondaryButton(
                    iconRes = R.drawable.ic_undo,
                    labelRes = R.string.page_edit_undo,
                    enabled = uiState.canUndo,
                    onClick = actions.onUndoRequested,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = ColorDivider)
            PageEditApplyToAllRow(uiState = uiState, actions = actions)
        }
    }
}

/**
 * Secondary ボタン（docs/design/system/02-components.md「枠線 primary・文字 primary・透明地」）。
 * 高さは最小48dp（同「タップ領域最小48dp」）。
 */
@Composable
private fun PageEditSecondaryButton(
    iconRes: Int,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = MinTouchTarget),
        shape = RoundedCornerShape(ButtonCornerRadius),
        border = BorderStroke(HAIRLINE_BORDER_WIDTH, if (enabled) ColorPrimary else ColorDivider),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = ColorPrimary,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = ColorPrimary.copy(alpha = DISABLED_ALPHA),
            ),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(BUTTON_ICON_SIZE),
        )
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
    }
}

/** 「この書籍の全ページに同じ切り取りを適用」（FR-IMG-005/006）。行全体をタップ領域にする */
@Composable
private fun PageEditApplyToAllRow(
    uiState: PageEditUiState,
    actions: PageEditScreenActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget + SpaceUnit * 2)
                .clickable(enabled = uiState.canEdit) {
                    actions.onApplyToAllPagesChanged(!uiState.applyCropToAllPages)
                }.padding(horizontal = SpaceUnit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = uiState.applyCropToAllPages,
            onCheckedChange = actions.onApplyToAllPagesChanged,
            enabled = uiState.canEdit,
            colors =
                CheckboxDefaults.colors(
                    checkedColor = ColorPrimary,
                    uncheckedColor = ColorTextSecondary,
                    checkmarkColor = Color.White,
                ),
            modifier = Modifier.size(MinTouchTarget).testTag(PAGE_EDIT_APPLY_ALL_TEST_TAG),
        )
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text = stringResource(R.string.page_edit_apply_to_all),
            style = MaterialTheme.typography.bodyLarge,
            color = ColorText,
        )
    }
}

/** 保存・失敗の結果。次の操作か「閉じる」まで出したままにする */
@Composable
private fun PageEditMessageRow(
    message: PageEditMessage,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failed = message.isFailure
    val accent = if (failed) ColorError else ColorSuccess
    Row(
        modifier = modifier.fillMaxWidth().padding(start = ScreenHorizontalMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (failed) Icons.Filled.Warning else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(BUTTON_ICON_SIZE),
        )
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text =
                when (message) {
                    PageEditMessage.Saved -> stringResource(R.string.page_edit_message_saved)
                    is PageEditMessage.SavedToAllPages ->
                        stringResource(R.string.page_edit_message_saved_to_all, message.pageCount)
                    PageEditMessage.SaveFailed -> stringResource(R.string.page_edit_message_save_failed)
                    PageEditMessage.EditUndone -> stringResource(R.string.page_edit_message_undone)
                    PageEditMessage.UndoFailed -> stringResource(R.string.page_edit_message_undo_failed)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = accent,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.page_edit_message_dismiss),
                tint = ColorTextSecondary,
            )
        }
    }
}

/**
 * 一括適用つき保存の確認（docs/design/08-page-edit.md「未定事項」で要確認とされた確認）。
 *
 * 影響するページ数を本文に必ず出す（docs/design/system/02-components.md「対象情報を必ず本文に含める」）。
 * 実行ボタンは Destructive ではなく Primary。切り取りは非破壊で、元画像もOCR結果も消えず、
 * 変わるのは crop 属性と「再実行が必要」への状態変化だけだから（FR-IMG-007・同 §3.3）。
 * キャンセルは Secondary（同「キャンセルは Secondary」）。
 */
@Composable
private fun PageEditBulkApplyDialog(
    pageCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PageEditConfirmDialog(
        titleRes = R.string.page_edit_apply_dialog_title,
        message = stringResource(R.string.page_edit_apply_dialog_message, pageCount),
        confirmLabelRes = R.string.page_edit_apply_dialog_confirm,
        confirmColor = ColorPrimary,
        dismissLabelRes = R.string.page_edit_apply_dialog_cancel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * × で閉じるときの破棄確認（docs/design/08-page-edit.md「× は変更を破棄して閉じる（破棄確認の有無は未定）」）。
 *
 * 編集中の内容を捨てる操作なので確認を挟む。実行は Destructive、キャンセルは Secondary
 * （docs/design/system/02-components.md「ダイアログ」）。
 */
@Composable
private fun PageEditDiscardDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PageEditConfirmDialog(
        titleRes = R.string.page_edit_discard_dialog_title,
        message = stringResource(R.string.page_edit_discard_dialog_message),
        confirmLabelRes = R.string.page_edit_discard_dialog_confirm,
        confirmColor = ColorError,
        dismissLabelRes = R.string.page_edit_discard_dialog_cancel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** この画面の確認ダイアログの共通の形（surface・角丸12dp・実行=塗り・キャンセル=Secondary） */
@Composable
private fun PageEditConfirmDialog(
    titleRes: Int,
    message: String,
    confirmLabelRes: Int,
    confirmColor: Color,
    dismissLabelRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(CardCornerRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleLarge,
                color = ColorText,
            )
        },
        text = {
            Text(text = message, style = MaterialTheme.typography.bodyLarge, color = ColorText)
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = MinTouchTarget),
                shape = RoundedCornerShape(ButtonCornerRadius),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = confirmColor,
                        contentColor = Color.White,
                    ),
            ) {
                Text(text = stringResource(confirmLabelRes), style = MaterialTheme.typography.labelLarge)
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
                Text(text = stringResource(dismissLabelRes), style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

/** 読み込み失敗の案内 */
@Composable
private fun PageEditNotice(
    messageRes: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
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
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(
                    text = stringResource(R.string.page_edit_reload),
                    style = MaterialTheme.typography.labelLarge,
                    color = ColorPrimary,
                )
            }
        }
    }
}

/** つまみの中心の位置（正規化）。辺の中央のつまみは両端の中点に置く */
private fun PageCropHandle.normalizedX(crop: PageCrop): Float =
    when {
        movesLeft -> crop.left
        movesRight -> crop.right
        else -> (crop.left + crop.right) / 2f
    }

private fun PageCropHandle.normalizedY(crop: PageCrop): Float =
    when {
        movesTop -> crop.top
        movesBottom -> crop.bottom
        else -> (crop.top + crop.bottom) / 2f
    }

/** つまみの読み上げ名。色や位置だけに頼らず、どこを掴むのかを文字でも示す */
private fun PageCropHandle.labelRes(): Int =
    when (this) {
        PageCropHandle.TOP_LEFT -> R.string.page_edit_crop_handle_top_left
        PageCropHandle.TOP -> R.string.page_edit_crop_handle_top
        PageCropHandle.TOP_RIGHT -> R.string.page_edit_crop_handle_top_right
        PageCropHandle.LEFT -> R.string.page_edit_crop_handle_left
        PageCropHandle.RIGHT -> R.string.page_edit_crop_handle_right
        PageCropHandle.BOTTOM_LEFT -> R.string.page_edit_crop_handle_bottom_left
        PageCropHandle.BOTTOM -> R.string.page_edit_crop_handle_bottom
        PageCropHandle.BOTTOM_RIGHT -> R.string.page_edit_crop_handle_bottom_right
    }

/** 切り取り枠の外の減光の濃さ。紙面がまだ読める程度に留める（素材の枠外はグレーの減光） */
private const val CROP_SCRIM_ALPHA = 0.55f

/**
 * 寸法はすべて基本グリッド 8dp = [SpaceUnit] の倍数か、トークンの既定値で置く
 * （docs/design/system/01-tokens.md「余白・寸法」）。モック画像からの目測値は使わない。
 */
private val CROP_HANDLE_SIZE = SpaceUnit * 2
private val CROP_FRAME_STROKE = 2.dp
private val HAIRLINE_BORDER_WIDTH = 1.dp
private val BUTTON_ICON_SIZE = SpaceUnit * 3
