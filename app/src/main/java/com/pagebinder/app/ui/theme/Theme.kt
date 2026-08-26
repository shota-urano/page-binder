package com.pagebinder.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// docs/design/system/01-tokens.md「色」（値の正本はトークン。ここでは名前を写すだけ）
val ColorPrimary = Color(0xFF1E3FAE)
val ColorPrimaryDark = Color(0xFF16308C)
val ColorAccent = Color(0xFF2DD4A8)
val ColorBackground = Color(0xFFF8FAFC)
val ColorSurface = Color(0xFFFFFFFF)
val ColorText = Color(0xFF1A2233)
val ColorTextSecondary = Color(0xFF5B6472)
val ColorDivider = Color(0xFFE2E8F0)
val ColorError = Color(0xFFDC2626)
val ColorWarning = Color(0xFFD97706)
val ColorSuccess = Color(0xFF16A34A)

// docs/design/system/01-tokens.md「余白・寸法」「角丸」
val SpaceUnit = 8.dp
val ScreenHorizontalMargin = 16.dp
val MinTouchTarget = 48.dp
val CardCornerRadius = 12.dp
val ButtonCornerRadius = 8.dp

private val PageBinderColorScheme =
    lightColorScheme(
        primary = ColorPrimary,
        onPrimary = Color.White,
        primaryContainer = ColorPrimary,
        onPrimaryContainer = Color.White,
        secondary = ColorAccent,
        onSecondary = ColorText,
        background = ColorBackground,
        onBackground = ColorText,
        surface = ColorSurface,
        onSurface = ColorText,
        surfaceVariant = ColorDivider,
        onSurfaceVariant = ColorTextSecondary,
        error = ColorError,
        onError = Color.White,
        outline = ColorDivider,
        outlineVariant = ColorDivider,
    )

/**
 * docs/design/system/01-tokens.md「タイポグラフィ」
 * 見出し22sp / 画面タイトル18sp / 本文16sp / 補助14sp / キャプション12sp。
 * 太さは 見出し・ボタン Medium(500)、本文 Regular(400)。
 */
private val PageBinderTypography =
    Typography(
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
        titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    )

@Composable
fun PageBinderTheme(content: @Composable () -> Unit) {
    // MVP はライトのみ（docs/design/system/01-tokens.md「テーマ」）
    MaterialTheme(
        colorScheme = PageBinderColorScheme,
        typography = PageBinderTypography,
        content = content,
    )
}
