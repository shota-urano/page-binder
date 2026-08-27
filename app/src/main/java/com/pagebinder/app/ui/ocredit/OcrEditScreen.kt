package com.pagebinder.app.ui.ocredit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagebinder.app.R
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.ui.pagelist.PageStatusBadge
import com.pagebinder.app.ui.pagelist.PageStatusBadgeChip
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.pagelist.PageThumbnailRequest
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorAccent
import com.pagebinder.app.ui.theme.ColorAccentContainer
import com.pagebinder.app.ui.theme.ColorAccentContent
import com.pagebinder.app.ui.theme.ColorDivider
import com.pagebinder.app.ui.theme.ColorError
import com.pagebinder.app.ui.theme.ColorPrimary
import com.pagebinder.app.ui.theme.ColorPrimaryDark
import com.pagebinder.app.ui.theme.ColorSuccess
import com.pagebinder.app.ui.theme.ColorText
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.DISABLED_ALPHA
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit

/** OCR編集画面の操作。ViewModel を持ち込まずに画面をテストできるようにまとめてある */
@Stable
class OcrEditScreenActions(
    val onBack: () -> Unit,
    val onReload: () -> Unit,
    val onSearchToggled: () -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onSearchNext: () -> Unit,
    val onSearchPrevious: () -> Unit,
    val onRerunRequested: () -> Unit,
    val onZoomIn: () -> Unit,
    val onZoomOut: () -> Unit,
    val onSplitRatioChange: (Float) -> Unit,
    val onTextChange: (String) -> Unit,
    val onSaveRequested: () -> Unit,
    val onRevertRequested: () -> Unit,
    val onRevertConfirmed: () -> Unit,
    val onRevertDismissed: () -> Unit,
)

/** 本文の編集領域。手動修正の受け入れ基準を利用者操作の側から確認するために付ける */
const val OCR_EDIT_TEXT_TEST_TAG = "ocrEditText"

/** 検索欄。展開したことと入力を、件数表示と切り離して確認できるようにする */
const val OCR_EDIT_SEARCH_FIELD_TEST_TAG = "ocrEditSearchField"

/** 上ペイン（ページ画像）。分割表示が保たれていることを見る */
const val OCR_EDIT_IMAGE_PANE_TEST_TAG = "ocrEditImagePane"

/**
 * OCR編集画面（docs/design/10-ocr-edit.md / docs/specs/09-ocr.md §3.5）。
 *
 * 描くのはアプリバーから下のコンテンツ領域だけで、ステータスバー・ナビゲーションバーは OS が描く
 * （docs/design/system/03-principles.md「モック画像の読み方」）。表示値はすべて [uiState] から描き、
 * モックのサンプルデータ（書籍名・ページ本文）は持たない。
 *
 * 素材は上下分割なので上下で実装する（左右分割は素材が無く未定 — docs/design/10-ocr-edit.md「未定事項」）。
 */
@Composable
fun OcrEditScreen(
    uiState: OcrEditUiState,
    imageLoader: PageThumbnailLoader,
    actions: OcrEditScreenActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OcrEditTopBar(uiState = uiState, actions = actions)
            if (uiState.search.visible) {
                OcrEditSearchBar(search = uiState.search, actions = actions)
            }
            when {
                uiState.loadFailed ->
                    OcrEditNotice(
                        messageRes = R.string.ocr_edit_load_failed,
                        onRetry = actions.onReload,
                    )
                uiState.loading -> Spacer(modifier = Modifier.weight(1f))
                else -> OcrEditPanes(uiState = uiState, imageLoader = imageLoader, actions = actions)
            }
        }
    }
    if (uiState.revertDialogVisible) {
        OcrEditRevertDialog(
            editedLength = uiState.draftText.length,
            onConfirm = actions.onRevertConfirmed,
            onDismiss = actions.onRevertDismissed,
        )
    }
}

/**
 * 上ペイン（ページ画像）・分割ハンドル・下ペイン（OCRテキスト）。
 * ハンドルのドラッグで比率が変わる（docs/design/10-ocr-edit.md「インタラクション」の推測どおり）。
 */
@Composable
private fun OcrEditPanes(
    uiState: OcrEditUiState,
    imageLoader: PageThumbnailLoader,
    actions: OcrEditScreenActions,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        Column(modifier = Modifier.fillMaxSize()) {
            OcrPageImagePane(
                uiState = uiState,
                imageLoader = imageLoader,
                actions = actions,
                modifier = Modifier.weight(uiState.splitRatio),
            )
            OcrEditSplitHandle(
                onDragDelta = { delta ->
                    actions.onSplitRatioChange(uiState.splitRatio + delta / totalHeightPx)
                },
            )
            OcrEditTextPane(
                uiState = uiState,
                actions = actions,
                modifier = Modifier.weight(1f - uiState.splitRatio),
            )
        }
    }
}

/**
 * アプリバー。左に戻る、右に検索・再実行（docs/design/mockups/10-ocr-edit.png）。
 * タイトルのページ番号は UiState から描く（モックの「12」はサンプル）。
 */
@Composable
private fun OcrEditTopBar(
    uiState: OcrEditUiState,
    actions: OcrEditScreenActions,
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
        IconButton(onClick = actions.onBack, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.ocr_edit_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text = uiState.pageSequence?.let { stringResource(R.string.ocr_edit_title, it) }.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = actions.onSearchToggled, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.ocr_edit_search),
                tint = if (uiState.search.visible) ColorPrimary else MaterialTheme.colorScheme.onBackground,
            )
        }
        IconButton(
            onClick = actions.onRerunRequested,
            enabled = uiState.canRerun,
            modifier = Modifier.size(MinTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.ocr_edit_rerun),
                tint =
                    if (uiState.canRerun) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = DISABLED_ALPHA)
                    },
            )
        }
    }
}

/**
 * ページ内検索（docs/specs/09-ocr.md §3.5）。展開後の見た目は素材が無い
 * （docs/design/10-ocr-edit.md「未定事項」）ため、入力欄・件数・前後移動だけの最小構成にしている。
 */
@Composable
private fun OcrEditSearchBar(
    search: OcrEditSearchUiState,
    actions: OcrEditScreenActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalMargin, vertical = SpaceUnit / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = search.query,
            onValueChange = actions.onSearchQueryChange,
            singleLine = true,
            label = { Text(text = stringResource(R.string.ocr_edit_search_hint)) },
            shape = RoundedCornerShape(ButtonCornerRadius),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ColorPrimary,
                    unfocusedBorderColor = ColorDivider,
                    focusedLabelColor = ColorPrimary,
                    unfocusedLabelColor = ColorTextSecondary,
                ),
            modifier = Modifier.weight(1f).testTag(OCR_EDIT_SEARCH_FIELD_TEST_TAG),
        )
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text =
                if (search.noMatch) {
                    stringResource(R.string.ocr_edit_search_no_match)
                } else {
                    stringResource(
                        R.string.ocr_edit_search_match_count,
                        search.currentMatchNumber,
                        search.matchCount,
                    )
                },
            style = MaterialTheme.typography.bodyMedium,
            color = if (search.noMatch) ColorError else ColorTextSecondary,
        )
        IconButton(
            onClick = actions.onSearchPrevious,
            enabled = search.canStep,
            modifier = Modifier.size(MinTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.ocr_edit_search_previous),
                tint = if (search.canStep) ColorPrimary else ColorTextSecondary.copy(alpha = DISABLED_ALPHA),
            )
        }
        IconButton(
            onClick = actions.onSearchNext,
            enabled = search.canStep,
            modifier = Modifier.size(MinTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.ocr_edit_search_next),
                tint = if (search.canStep) ColorPrimary else ColorTextSecondary.copy(alpha = DISABLED_ALPHA),
            )
        }
        IconButton(onClick = actions.onSearchToggled, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.ocr_edit_search_close),
                tint = ColorTextSecondary,
            )
        }
    }
}

/**
 * 上ペイン。非破壊の rotation / crop を適用した派生画像を表示する
 * （docs/specs/07-image-quality.md §3.4。元画像は読むだけで書き換えない）。
 *
 * 画像の取得契約は一覧と同じ [PageThumbnailLoader] を使い回す。
 * 「非破壊属性を適用した派生画像を目標幅で作る」という同じ要求なので、別の抽象を新設していない。
 */
@Composable
private fun OcrPageImagePane(
    uiState: OcrEditUiState,
    imageLoader: PageThumbnailLoader,
    actions: OcrEditScreenActions,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(ColorDivider)
                .clipToBounds()
                .testTag(OCR_EDIT_IMAGE_PANE_TEST_TAG),
    ) {
        val pageId = uiState.pageId
        if (pageId != null) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val paneWidth = maxWidth
                OcrPageImage(
                    request =
                        PageThumbnailRequest(
                            pageId = pageId,
                            rotation = uiState.rotation,
                            crop = uiState.crop,
                            targetWidthPx = with(LocalDensity.current) { (paneWidth * IMAGE_DECODE_SCALE).roundToPx() },
                        ),
                    loader = imageLoader,
                    displayWidth = paneWidth * uiState.zoomScale,
                )
            }
        }
        OcrEditZoomControl(
            uiState = uiState,
            actions = actions,
            modifier = Modifier.align(Alignment.BottomEnd).padding(ScreenHorizontalMargin),
        )
    }
}

@Composable
private fun OcrPageImage(
    request: PageThumbnailRequest,
    loader: PageThumbnailLoader,
    displayWidth: Dp,
    modifier: Modifier = Modifier,
) {
    var attempt by remember(request) { mutableIntStateOf(0) }
    var image by remember(request) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(request) { mutableStateOf(false) }

    LaunchedEffect(request, attempt) {
        failed = false
        val loaded = runCatching { loader.load(request) }.getOrNull()
        image = loaded
        failed = loaded == null
    }

    val current = image
    when {
        current != null ->
            Box(
                modifier =
                    modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
            ) {
                Image(
                    bitmap = current,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier =
                        Modifier
                            .width(displayWidth)
                            .height(displayWidth * current.height / current.width.coerceAtLeast(1)),
                )
            }
        failed ->
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(
                        text = stringResource(R.string.ocr_edit_image_retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorPrimary,
                    )
                }
            }
        else -> Box(modifier = modifier.fillMaxSize())
    }
}

/** ズームコントロール pill「− 100% ＋」（docs/design/mockups/10-ocr-edit.png 右下） */
@Composable
private fun OcrEditZoomControl(
    uiState: OcrEditUiState,
    actions: OcrEditScreenActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = actions.onZoomOut,
                enabled = uiState.canZoomOut,
                modifier = Modifier.size(MinTouchTarget),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_zoom_out),
                    contentDescription = stringResource(R.string.ocr_edit_zoom_out),
                    tint = if (uiState.canZoomOut) ColorText else ColorText.copy(alpha = DISABLED_ALPHA),
                )
            }
            Text(
                text = stringResource(R.string.ocr_edit_zoom_percent, uiState.zoomPercent),
                style = MaterialTheme.typography.bodyMedium,
                color = ColorText,
            )
            IconButton(
                onClick = actions.onZoomIn,
                enabled = uiState.canZoomIn,
                modifier = Modifier.size(MinTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.ocr_edit_zoom_in),
                    tint = if (uiState.canZoomIn) ColorText else ColorText.copy(alpha = DISABLED_ALPHA),
                )
            }
        }
    }
}

/** 分割ハンドル（モックの中央・短い横バー）。上下ドラッグで分割比率が変わる */
@Composable
private fun OcrEditSplitHandle(
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.ocr_edit_split_handle)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SPLIT_HANDLE_HEIGHT)
                .background(MaterialTheme.colorScheme.background)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState(onDelta = onDragDelta),
                ).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(SPLIT_HANDLE_BAR_WIDTH, SPLIT_HANDLE_BAR_HEIGHT),
            shape = CircleShape,
            color = ColorDivider,
        ) {}
    }
}

/**
 * 下ペイン。OCRテキストカード（ステータス行 + 編集領域）と、その下のフッター行
 * （「元のOCR結果へ戻す」+「保存」）。
 */
@Composable
private fun OcrEditTextPane(
    uiState: OcrEditUiState,
    actions: OcrEditScreenActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalMargin)
                .padding(bottom = SpaceUnit * 2),
        verticalArrangement = Arrangement.spacedBy(SpaceUnit),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(CardCornerRadius),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp,
        ) {
            Column(modifier = Modifier.padding(ScreenHorizontalMargin)) {
                OcrEditStatusRow(uiState = uiState)
                Spacer(modifier = Modifier.height(SpaceUnit))
                if (uiState.resultAvailable) {
                    OcrEditTextField(
                        uiState = uiState,
                        onTextChange = actions.onTextChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.ocr_edit_result_missing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ColorTextSecondary,
                    )
                }
            }
        }
        uiState.message?.let { OcrEditMessageRow(message = it) }
        OcrEditFooter(uiState = uiState, actions = actions)
    }
}

/**
 * ステータス行。修正済みのときはモックどおり「修正済み」pill +「元のOCR結果は保持されます」。
 * 未修正時の表示は素材が無い（docs/design/10-ocr-edit.md「未定事項」）ので、
 * ページ一覧と同じOCR状態バッジを出して、実行中・失敗のページを開いたときも状態が分かるようにしている。
 * どちらも色だけで区別せずアイコン+文字を併記する（docs/design/system/03-principles.md）。
 */
@Composable
private fun OcrEditStatusRow(
    uiState: OcrEditUiState,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (uiState.edited) {
            OcrEditedBadge()
        } else {
            PageStatusBadgeChip(badge = uiState.ocrState.toBadge())
        }
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text =
                stringResource(
                    if (uiState.edited) R.string.ocr_edit_original_preserved else R.string.ocr_edit_original_shown,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = ColorTextSecondary,
        )
    }
}

/** 「修正済み」pill。地は accent の淡地・文字は濃い accent（バッジと同じ配色規則） */
@Composable
private fun OcrEditedBadge(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = CircleShape, color = ColorAccentContainer) {
        Row(
            modifier = Modifier.padding(horizontal = SpaceUnit, vertical = SpaceUnit / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                tint = ColorAccentContent,
                modifier = Modifier.size(BADGE_ICON_SIZE),
            )
            Spacer(modifier = Modifier.width(SpaceUnit / 2))
            Text(
                text = stringResource(R.string.ocr_edit_badge_edited),
                style = MaterialTheme.typography.labelSmall,
                color = ColorAccentContent,
            )
        }
    }
}

/**
 * 本文の編集領域。編集結果は [OcrEditUiState.draftText] にだけ入り、
 * 元のOCR結果（fullText）は画面のどの操作でも書き換わらない（docs/specs/09-ocr.md §3.5）。
 *
 * 検索の一致は背景色で示し、いま選ばれている一致へは選択位置を動かして送る
 * （長文でも一致行が画面内へ来るようにするため）。
 */
@Composable
private fun OcrEditTextField(
    uiState: OcrEditUiState,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(uiState.draftText)) }
    LaunchedEffect(uiState.draftText) {
        // 「元へ戻す」など画面外の要因で本文が入れ替わったときだけ差し替える（入力中は上書きしない）
        if (fieldValue.text != uiState.draftText) {
            fieldValue = TextFieldValue(uiState.draftText, TextRange(uiState.draftText.length))
        }
    }
    val currentMatch = uiState.search.currentMatch
    LaunchedEffect(currentMatch) {
        if (currentMatch != null && currentMatch.last < fieldValue.text.length) {
            fieldValue = fieldValue.copy(selection = TextRange(currentMatch.first, currentMatch.last + 1))
        }
    }
    val transformation =
        remember(uiState.search.matches, uiState.search.currentIndex) {
            OcrSearchHighlightTransformation(uiState.search.matches, uiState.search.currentIndex)
        }
    BasicTextField(
        value = fieldValue,
        onValueChange = { updated ->
            fieldValue = updated
            if (updated.text != uiState.draftText) onTextChange(updated.text)
        },
        enabled = !uiState.saving,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = ColorText, lineHeight = BODY_LINE_HEIGHT),
        cursorBrush = SolidColor(ColorPrimary),
        visualTransformation = transformation,
        modifier = modifier.testTag(OCR_EDIT_TEXT_TEST_TAG),
    )
}

/** 検索の一致に背景色を付ける。文字数を変えないので索引の対応はそのまま */
private class OcrSearchHighlightTransformation(
    private val matches: List<IntRange>,
    private val currentIndex: Int,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (matches.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val highlighted =
            buildAnnotatedString {
                append(text)
                matches.forEachIndexed { index, range ->
                    val end = (range.last + 1).coerceAtMost(text.length)
                    if (range.first < end) {
                        addStyle(
                            SpanStyle(
                                background =
                                    if (index == currentIndex) {
                                        ColorPrimary.copy(alpha = CURRENT_MATCH_ALPHA)
                                    } else {
                                        ColorAccent.copy(alpha = MATCH_ALPHA)
                                    },
                            ),
                            range.first,
                            end,
                        )
                    }
                }
            }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

/** 保存・破棄・再実行の結果。次の操作まで出したままにする */
@Composable
private fun OcrEditMessageRow(
    message: OcrEditMessage,
    modifier: Modifier = Modifier,
) {
    val succeeded = message in SUCCESS_MESSAGES
    val accent = if (succeeded) ColorSuccess else ColorError
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (succeeded) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(BADGE_ICON_SIZE),
        )
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text = stringResource(message.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = accent,
        )
    }
}

/** フッター行: テキストボタン「元のOCR結果へ戻す」（左）+ Primaryボタン「保存」（右） */
@Composable
private fun OcrEditFooter(
    uiState: OcrEditUiState,
    actions: OcrEditScreenActions,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = actions.onRevertRequested,
            enabled = uiState.canRevert,
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) {
            Text(
                text = stringResource(R.string.ocr_edit_revert),
                style = MaterialTheme.typography.labelLarge,
                color = if (uiState.canRevert) ColorPrimary else ColorPrimary.copy(alpha = DISABLED_ALPHA),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        Button(
            onClick = actions.onSaveRequested,
            enabled = uiState.canSave,
            shape = RoundedCornerShape(ButtonCornerRadius),
            interactionSource = interactionSource,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = if (pressed) ColorPrimaryDark else ColorPrimary,
                    contentColor = Color.White,
                    disabledContainerColor = ColorPrimary.copy(alpha = DISABLED_ALPHA),
                    disabledContentColor = Color.White.copy(alpha = DISABLED_ALPHA),
                ),
            modifier = Modifier.width(SAVE_BUTTON_WIDTH).heightIn(min = MinTouchTarget),
        ) {
            Text(
                text = stringResource(R.string.ocr_edit_save),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * 「元のOCR結果へ戻す」の確認（docs/design/10-ocr-edit.md の推測どおり確認を挟む）。
 * 破壊操作なので、捨てる対象の分量を本文に含める（docs/design/system/02-components.md「ダイアログ」）。
 */
@Composable
private fun OcrEditRevertDialog(
    editedLength: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(CardCornerRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.ocr_edit_revert_dialog_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.ocr_edit_revert_dialog_message, editedLength),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.ocr_edit_revert_dialog_confirm),
                    style = MaterialTheme.typography.labelLarge,
                    color = ColorError,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.ocr_edit_revert_dialog_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = ColorTextSecondary,
                )
            }
        },
    )
}

/** 読み込み失敗の案内 */
@Composable
private fun OcrEditNotice(
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
                    text = stringResource(R.string.ocr_edit_reload),
                    style = MaterialTheme.typography.labelLarge,
                    color = ColorPrimary,
                )
            }
        }
    }
}

/** OCR状態 → 一覧と同じ状態バッジ（docs/design/system/02-components.md の対応表） */
private fun PageOcrState.toBadge(): PageStatusBadge =
    when (this) {
        PageOcrState.PENDING -> PageStatusBadge.OCR_PENDING
        PageOcrState.RUNNING -> PageStatusBadge.OCR_RUNNING
        PageOcrState.SUCCEEDED -> PageStatusBadge.OCR_SUCCEEDED
        PageOcrState.FAILED -> PageStatusBadge.OCR_FAILED
        PageOcrState.STALE -> PageStatusBadge.OCR_STALE
    }

private fun OcrEditMessage.labelRes(): Int =
    when (this) {
        OcrEditMessage.SAVED -> R.string.ocr_edit_message_saved
        OcrEditMessage.SAVE_FAILED -> R.string.ocr_edit_message_save_failed
        OcrEditMessage.REVERTED -> R.string.ocr_edit_message_reverted
        OcrEditMessage.REVERT_FAILED -> R.string.ocr_edit_message_revert_failed
        OcrEditMessage.RERUN_QUEUED -> R.string.ocr_edit_message_rerun_queued
        OcrEditMessage.RERUN_FAILED -> R.string.ocr_edit_message_rerun_failed
    }

private val SUCCESS_MESSAGES =
    setOf(OcrEditMessage.SAVED, OcrEditMessage.REVERTED, OcrEditMessage.RERUN_QUEUED)

/**
 * 復号目標幅をペイン幅より少し大きく取る。拡大しても粗くなりすぎないための余裕で、
 * 最大倍率いっぱい（300%）までは取らない（メモリを使い切らないため）。
 */
private const val IMAGE_DECODE_SCALE = 1.5f

private const val CURRENT_MATCH_ALPHA = 0.30f
private const val MATCH_ALPHA = 0.35f

private val BADGE_ICON_SIZE = 16.dp
private val SPLIT_HANDLE_HEIGHT = 24.dp
private val SPLIT_HANDLE_BAR_WIDTH = 40.dp
private val SPLIT_HANDLE_BAR_HEIGHT = 4.dp
private val SAVE_BUTTON_WIDTH = 140.dp

/** 本文は行間広め（docs/design/10-ocr-edit.md「本文16sp・行間広め」） */
private val BODY_LINE_HEIGHT = 28.sp
