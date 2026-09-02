package com.pagebinder.app.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pagebinder.app.R
import com.pagebinder.app.domain.CaptureOverlayState
import kotlin.math.roundToInt

/**
 * フローティングUIの見た目そのもの（docs/design/06-floating-ui.md / docs/specs/04-capture-session.md §3.4）。
 *
 * WindowManager への出し入れ・ドラッグ吸着は [CaptureOverlayController] 側の責務で、ここは
 * 「撮影状態と保存枚数を渡すと描き替わるビュー」だけを持つ。切り離してあるので、オーバーレイ権限が
 * 無い実機テストや debug プレビューからも同じ部品をそのまま組み立てられる。
 *
 * 色・寸法は docs/design/system/01-tokens.md のトークンに従う（モック画像からの目測はしない）。
 */
class CaptureOverlayContent(
    context: Context,
    private val onCapture: () -> Unit,
    private val onPauseChanged: (Boolean) -> Unit,
    private val onStop: () -> Unit,
) {
    private val appContext = context.applicationContext
    private var state: CaptureOverlayState = CaptureOverlayState.STOPPED
    private val captureButton = circleButton(R.id.capture_overlay_capture_button, R.drawable.ic_capture_camera, ACCENT)
    private val stateIcon = stateIconView()
    private val statusPill = pillView(R.id.capture_overlay_status)
    private val countPill = pillView(R.id.capture_overlay_count)
    private val pauseButton = circleButton(R.id.capture_overlay_pause_button, R.drawable.ic_capture_pause, OUTLINE)
    private val stopButton = circleButton(R.id.capture_overlay_stop_button, R.drawable.ic_capture_stop, OUTLINE)

    /** 縦長 pill のオーバーレイ本体。地色は `--color-overlay-bg`（#1A2233 85%） */
    val view: LinearLayout =
        LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(CONTAINER_CORNER_RADIUS_DP).toFloat()
                    setColor(OVERLAY_BACKGROUND)
                }
            addView(captureButton)
            addView(stateIcon)
            addView(statusPill)
            addView(countPill)
            addView(pauseButton)
            addView(stopButton)
        }

    init {
        captureButton.setOnClickListener { onCapture() }
        pauseButton.setOnClickListener { onPauseChanged(state != CaptureOverlayState.CONTINUOUS_PAUSED) }
        stopButton.setOnClickListener { onStop() }
        render(CaptureOverlayState.STOPPED, savedCount = 0)
    }

    /** 撮影状態と保存枚数を、状態pill・枚数pill・各ボタンの出し入れへ反映する */
    fun render(
        state: CaptureOverlayState,
        savedCount: Int,
    ) {
        this.state = state
        val continuous =
            state == CaptureOverlayState.CONTINUOUS_ACTIVE || state == CaptureOverlayState.CONTINUOUS_PAUSED
        statusPill.text = appContext.getString(statusTextRes(state))
        captureButton.visibility = if (state == CaptureOverlayState.MANUAL_ACTIVE) View.VISIBLE else View.GONE
        stateIcon.visibility = if (continuous) View.VISIBLE else View.GONE
        countPill.visibility = if (continuous) View.VISIBLE else View.GONE
        countPill.text = appContext.getString(R.string.capture_overlay_saved_count, savedCount)
        pauseButton.visibility = if (continuous) View.VISIBLE else View.GONE
        val paused = state == CaptureOverlayState.CONTINUOUS_PAUSED
        pauseButton.setImageResource(
            if (paused) R.drawable.ic_capture_resume else R.drawable.ic_capture_pause,
        )
        pauseButton.contentDescription =
            appContext.getString(
                if (paused) R.string.capture_overlay_resume else R.string.capture_overlay_pause,
            )
    }

    private fun statusTextRes(state: CaptureOverlayState): Int =
        when (state) {
            CaptureOverlayState.MANUAL_ACTIVE -> R.string.capture_overlay_manual_state
            CaptureOverlayState.CONTINUOUS_ACTIVE -> R.string.capture_overlay_continuous_state
            CaptureOverlayState.CONTINUOUS_PAUSED -> R.string.capture_overlay_paused_state
            CaptureOverlayState.STOPPED -> R.string.capture_overlay_stopped_state
        }

    /** 連続撮影中を示す円形アイコン。状態pillと重ねて読み上げないよう、読み上げ対象からは外す */
    private fun stateIconView() =
        ImageView(appContext).apply {
            id = R.id.capture_overlay_state_icon
            setImageResource(R.drawable.ic_capture_continuous)
            setPadding(dp(ICON_PADDING_DP), dp(ICON_PADDING_DP), dp(ICON_PADDING_DP), dp(ICON_PADDING_DP))
            background = circleBackground(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = circleLayoutParams()
        }

    private fun pillView(viewId: Int) =
        TextView(appContext).apply {
            id = viewId
            setTextColor(OVERLAY_TEXT)
            textSize = CAPTION_TEXT_SIZE_SP
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(PILL_CORNER_RADIUS_DP).toFloat()
                    setColor(PILL_FILL)
                    setStroke(dp(1), OUTLINE)
                }
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(GAP_DP) }
        }

    private fun circleButton(
        viewId: Int,
        iconRes: Int,
        ringColor: Int,
    ) = ImageButton(appContext).apply {
        id = viewId
        setImageResource(iconRes)
        setPadding(dp(ICON_PADDING_DP), dp(ICON_PADDING_DP), dp(ICON_PADDING_DP), dp(ICON_PADDING_DP))
        background = circleBackground(ringColor)
        contentDescription = appContext.getString(defaultDescriptionRes(viewId))
        layoutParams = circleLayoutParams()
    }

    private fun defaultDescriptionRes(viewId: Int): Int =
        when (viewId) {
            R.id.capture_overlay_capture_button -> R.string.capture_overlay_capture
            R.id.capture_overlay_pause_button -> R.string.capture_overlay_pause
            else -> R.string.capture_overlay_stop
        }

    private fun circleBackground(ringColor: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(RING_WIDTH_DP), ringColor)
        }

    private fun circleLayoutParams() =
        LinearLayout.LayoutParams(dp(CIRCLE_SIZE_DP), dp(CIRCLE_SIZE_DP)).apply { topMargin = dp(GAP_DP) }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).roundToInt()

    private companion object {
        /** docs/design/system/01-tokens.md: --color-overlay-bg（#1A2233 @85% = alpha 0xD9） */
        const val OVERLAY_BACKGROUND = 0xD91A2233.toInt()

        /** --color-overlay-text */
        const val OVERLAY_TEXT = 0xFFFFFFFF.toInt()

        /** --color-accent（撮影系の強調。撮影ボタン・連続状態アイコンのリング） */
        const val ACCENT = 0xFF2DD4A8.toInt()

        /** 一時停止・停止ボタンと pill の細い縁（overlay-text を薄く敷いたもの） */
        const val OUTLINE = 0x3DFFFFFF
        const val PILL_FILL = 0x24FFFFFF

        /** 円形ボタンは 56dp（docs/design/system/02-components.md「撮影ボタンは56dp円形」） */
        const val CIRCLE_SIZE_DP = 56
        const val ICON_PADDING_DP = 16
        const val RING_WIDTH_DP = 2

        /** 基本グリッド 8dp（docs/design/system/01-tokens.md） */
        const val GAP_DP = 8
        const val CONTAINER_CORNER_RADIUS_DP = 32
        const val PILL_CORNER_RADIUS_DP = 12

        /** キャプション 12sp */
        const val CAPTION_TEXT_SIZE_SP = 12f
    }
}
