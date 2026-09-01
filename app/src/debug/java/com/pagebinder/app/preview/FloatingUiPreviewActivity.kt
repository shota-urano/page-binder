package com.pagebinder.app.preview

import android.app.NotificationManager
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.pagebinder.app.capture.CaptureStatusNotifier
import com.pagebinder.app.domain.CaptureOverlayState
import com.pagebinder.app.ui.overlay.CaptureOverlayContent

/**
 * debug ビルド専用の連続撮影フローティングUIプレビュー。
 *
 * production と同じ [CaptureOverlayContent] と [CaptureStatusNotifier] を、オーバーレイ権限・
 * MediaProjection の同意なしで実機に出すための入口（目視・スクリーンショット用）。
 * production の APK には含まれない。
 *
 * オーバーレイは他アプリの上に重なる部品なので、背面には撮影対象アプリを描かず
 * `--color-background` の無地だけを敷く（docs/design/00-design-overview.md 共通注意4）。
 */
class FloatingUiPreviewActivity : ComponentActivity() {
    private val notifier by lazy { CaptureStatusNotifier(this) }
    private lateinit var content: CaptureOverlayContent
    private var state = CaptureOverlayState.CONTINUOUS_ACTIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content =
            CaptureOverlayContent(
                context = this,
                onCapture = {},
                onPauseChanged = { paused ->
                    state =
                        if (paused) {
                            CaptureOverlayState.CONTINUOUS_PAUSED
                        } else {
                            CaptureOverlayState.CONTINUOUS_ACTIVE
                        }
                    renderBoth()
                },
                onStop = { finish() },
            )
        val root =
            FrameLayout(this).apply {
                setBackgroundColor(BACKGROUND)
                addView(
                    content.view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL },
                )
            }
        root.fitsSystemWindows = true
        setContentView(root)
        // 明るい地色の上に立つプレビューなので、OSのステータスバーアイコンを暗色にしてもらう
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = true
        notifier.createChannel()
        renderBoth()
    }

    override fun onDestroy() {
        getSystemService(NotificationManager::class.java).cancel(CaptureStatusNotifier.NOTIFICATION_ID)
        super.onDestroy()
    }

    /** オーバーレイと通知の両方に同じ状態を出す（production の CaptureStatusPresenter と同じ配り方） */
    private fun renderBoth() {
        content.render(state, PREVIEW_SAVED_COUNT)
        notifier.post(state, PREVIEW_SAVED_COUNT)
    }

    private companion object {
        /**
         * プレビュー用の保存枚数。production では連続撮影の状態機械が数えた枚数が入る
         * （モックの「24枚」はサンプル値なので、production 側にこの値は無い）。
         */
        const val PREVIEW_SAVED_COUNT = 24

        /** docs/design/system/01-tokens.md: --color-background */
        const val BACKGROUND = 0xFFF8FAFC.toInt()
    }
}
