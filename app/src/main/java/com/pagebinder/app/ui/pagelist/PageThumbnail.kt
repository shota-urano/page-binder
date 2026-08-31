package com.pagebinder.app.ui.pagelist

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.ui.theme.ColorDivider
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.MinTouchTarget
import java.util.UUID

/**
 * サムネイル1枚の生成要求。
 *
 * 非破壊の rotation / crop を適用した派生画像を都度生成する（docs/specs/07-image-quality.md §3.4）ため、
 * 属性が変わればこの要求も変わり、サムネイルは作り直される。
 */
data class PageThumbnailRequest(
    val pageId: UUID,
    val rotation: Int,
    val crop: PageCrop,
    /** 復号時のサンプリング目標幅（px）。セルの表示幅から決める */
    val targetWidthPx: Int,
)

/**
 * 一覧サムネイルの取得契約。実装は `image/` の派生画像生成に閉じる（AGENTS.md ルール4）。
 *
 * 生成に失敗した場合（メモリ不足等）は null を返す。画面はプレースホルダを出して
 * 再試行できるようにする（docs/specs/08-page-editing.md §6）。
 */
fun interface PageThumbnailLoader {
    suspend fun load(request: PageThumbnailRequest): ImageBitmap?
}

private sealed interface ThumbnailState {
    data object Loading : ThumbnailState

    data class Loaded(val image: ImageBitmap) : ThumbnailState

    data object Failed : ThumbnailState
}

/**
 * ページのサムネイル。読み込み中は無地、失敗時はプレースホルダ＋再試行ボタンを出す
 * （docs/specs/08-page-editing.md §6「サムネイルをプレースホルダ表示し再試行可能にする」）。
 */
@Composable
fun PageThumbnail(
    item: PageListItemUiState,
    loader: PageThumbnailLoader,
    targetWidth: Dp,
    modifier: Modifier = Modifier,
) {
    PageThumbnail(
        pageId = item.pageId,
        rotation = item.rotation,
        crop = item.crop,
        loader = loader,
        targetWidth = targetWidth,
        modifier = modifier,
    )
}

/**
 * 書籍一覧など、ページ一覧の表示モデルを持たない画面から同じ非破壊サムネイルを使う入口。
 */
@Composable
fun PageThumbnail(
    pageId: UUID,
    rotation: Int,
    crop: PageCrop,
    loader: PageThumbnailLoader,
    targetWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val targetWidthPx = with(LocalDensity.current) { targetWidth.roundToPx() }
    val request =
        remember(pageId, rotation, crop, targetWidthPx) {
            PageThumbnailRequest(
                pageId = pageId,
                rotation = rotation,
                crop = crop,
                targetWidthPx = targetWidthPx,
            )
        }
    var attempt by remember(request) { mutableIntStateOf(0) }
    var state by remember(request) { mutableStateOf<ThumbnailState>(ThumbnailState.Loading) }

    LaunchedEffect(request, attempt) {
        state = ThumbnailState.Loading
        val image = runCatching { loader.load(request) }.getOrNull()
        state = if (image == null) ThumbnailState.Failed else ThumbnailState.Loaded(image)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val current = state) {
            ThumbnailState.Loading ->
                Surface(modifier = Modifier.fillMaxSize(), color = ColorDivider) {}
            is ThumbnailState.Loaded ->
                Image(
                    bitmap = current.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            ThumbnailState.Failed ->
                ThumbnailPlaceholder(onRetry = { attempt++ }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 派生画像を作れなかったときの表示。タップで作り直す */
@Composable
private fun ThumbnailPlaceholder(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = ColorDivider) {
        Box(contentAlignment = Alignment.Center) {
            IconButton(onClick = onRetry, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.page_list_thumbnail_retry),
                    tint = ColorTextSecondary,
                    modifier = Modifier.size(THUMBNAIL_RETRY_ICON_SIZE),
                )
            }
        }
    }
}

/** プレースホルダに置く再試行アイコンの大きさ。タップ領域は 48dp のまま（原則: 最小48dp） */
private val THUMBNAIL_RETRY_ICON_SIZE = 24.dp
