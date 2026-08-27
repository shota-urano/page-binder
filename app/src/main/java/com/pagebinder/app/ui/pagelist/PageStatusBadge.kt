package com.pagebinder.app.ui.pagelist

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.ui.theme.ColorAccentContainer
import com.pagebinder.app.ui.theme.ColorAccentContent
import com.pagebinder.app.ui.theme.ColorError
import com.pagebinder.app.ui.theme.ColorPrimary
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.ColorWarning

/**
 * ページに付く状態バッジ（docs/design/system/02-components.md「状態バッジ・アイコン」）。
 *
 * 色だけで区別せず、必ずアイコン+文字を併記する（requirements §16.4）。
 */
enum class PageStatusBadge(
    @StringRes val labelRes: Int,
) {
    OCR_PENDING(R.string.page_list_ocr_pending),
    OCR_RUNNING(R.string.page_list_ocr_running),
    OCR_SUCCEEDED(R.string.page_list_ocr_succeeded),
    OCR_FAILED(R.string.page_list_ocr_failed),
    OCR_STALE(R.string.page_list_ocr_stale),
    WARNING_DUPLICATE(R.string.page_list_warning_duplicate),
    WARNING_BLACK(R.string.page_list_warning_black),
    WARNING_IMAGE_ERROR(R.string.page_list_warning_image_error),
}

/** OCR状態のバッジ。docs/design/system/02-components.md の対応表そのまま */
val PageListItemUiState.ocrBadge: PageStatusBadge
    get() =
        when (ocrState) {
            PageOcrState.PENDING -> PageStatusBadge.OCR_PENDING
            PageOcrState.RUNNING -> PageStatusBadge.OCR_RUNNING
            PageOcrState.SUCCEEDED -> PageStatusBadge.OCR_SUCCEEDED
            PageOcrState.FAILED -> PageStatusBadge.OCR_FAILED
            PageOcrState.STALE -> PageStatusBadge.OCR_STALE
        }

/** 品質警告のバッジ。警告が無ければ null */
val PageListItemUiState.warningBadge: PageStatusBadge?
    get() =
        when (qualityState) {
            PageQualityState.NORMAL -> null
            PageQualityState.DUPLICATE -> PageStatusBadge.WARNING_DUPLICATE
            PageQualityState.BLACK -> PageStatusBadge.WARNING_BLACK
            PageQualityState.ERROR -> PageStatusBadge.WARNING_IMAGE_ERROR
        }

/** 一覧セルの pill バッジ1つ */
@Composable
fun PageStatusBadgeChip(
    badge: PageStatusBadge,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = badge.containerColor(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = BADGE_HORIZONTAL_PADDING, vertical = BADGE_VERTICAL_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgeIcon(badge = badge, tint = badge.contentColor())
            Spacer(modifier = Modifier.width(BADGE_ICON_GAP))
            Text(
                text = stringResource(badge.labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = badge.contentColor(),
            )
        }
    }
}

/**
 * 警告とOCR状態を横に並べる（リスト表示用）。
 * OCR状態は常に出し、警告があるページはその左に警告バッジを足す
 * （docs/specs/08-page-editing.md §3.1「各ページに OCR状態アイコン…と重複・黒画面警告を表示」）。
 */
@Composable
fun PageStatusBadgeRow(
    item: PageListItemUiState,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        item.warningBadge?.let { warning ->
            PageStatusBadgeChip(badge = warning)
            Spacer(modifier = Modifier.width(BADGE_ICON_GAP))
        }
        PageStatusBadgeChip(badge = item.ocrBadge)
    }
}

/**
 * 警告とOCR状態を縦に積む（グリッド表示用）。
 *
 * 3列のセル幅には pill を2つ横に並べられないので縦に積む。OCR状態はモックと同じ最下段・左寄せに置き、
 * 警告があるページだけその上に警告バッジが増える形にして、モック
 * （docs/design/mockups/07-page-list.png）からの見た目の差を最小にしている。
 * どちらの表示でもOCR状態は必ず出す（docs/specs/08-page-editing.md §3.1）。
 */
@Composable
fun PageStatusBadgeStack(
    item: PageListItemUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(BADGE_ICON_GAP),
    ) {
        item.warningBadge?.let { warning ->
            PageStatusBadgeChip(badge = warning)
        }
        PageStatusBadgeChip(badge = item.ocrBadge)
    }
}

@Composable
private fun BadgeIcon(
    badge: PageStatusBadge,
    tint: Color,
) {
    val vector: ImageVector? =
        when (badge) {
            PageStatusBadge.OCR_SUCCEEDED -> Icons.Filled.Check
            PageStatusBadge.OCR_FAILED -> Icons.Filled.Close
            PageStatusBadge.WARNING_DUPLICATE,
            PageStatusBadge.WARNING_BLACK,
            PageStatusBadge.WARNING_IMAGE_ERROR,
            -> Icons.Filled.Warning
            else -> null
        }
    if (vector != null) {
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(BADGE_ICON_SIZE),
        )
        return
    }
    val drawableRes =
        when (badge) {
            PageStatusBadge.OCR_PENDING -> R.drawable.ic_ocr_pending
            PageStatusBadge.OCR_RUNNING -> R.drawable.ic_ocr_running
            else -> R.drawable.ic_ocr_stale
        }
    Icon(
        painter = painterResource(drawableRes),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(BADGE_ICON_SIZE),
    )
}

/**
 * バッジの地色。
 * 「完了」だけが静かな表示（accent の淡地 + 濃い accent の文字）で、注意を引きたい状態は塗り+白文字にする
 * — モック（docs/design/mockups/07-page-list.png）の見え方に合わせた使い分け。
 */
private fun PageStatusBadge.containerColor(): Color =
    when (this) {
        PageStatusBadge.OCR_PENDING -> ColorTextSecondary
        PageStatusBadge.OCR_RUNNING -> ColorPrimary
        PageStatusBadge.OCR_SUCCEEDED -> ColorAccentContainer
        PageStatusBadge.OCR_FAILED -> ColorError
        PageStatusBadge.OCR_STALE -> ColorWarning
        PageStatusBadge.WARNING_DUPLICATE, PageStatusBadge.WARNING_BLACK -> ColorWarning
        PageStatusBadge.WARNING_IMAGE_ERROR -> ColorError
    }

private fun PageStatusBadge.contentColor(): Color =
    when (this) {
        // ミントの淡地の上は白文字だとコントラストが足りないので濃い accent（約7.5:1）
        PageStatusBadge.OCR_SUCCEEDED -> ColorAccentContent
        else -> Color.White
    }

private val BADGE_ICON_SIZE = 16.dp
private val BADGE_ICON_GAP = 4.dp
private val BADGE_HORIZONTAL_PADDING = 8.dp
private val BADGE_VERTICAL_PADDING = 4.dp
