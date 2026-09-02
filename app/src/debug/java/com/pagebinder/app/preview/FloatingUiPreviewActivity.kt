package com.pagebinder.app.preview

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pagebinder.app.R
import com.pagebinder.app.capture.CaptureStatusNotifier
import com.pagebinder.app.domain.CaptureOverlayState
import com.pagebinder.app.ui.overlay.CaptureOverlayContent

/**
 * debug ビルド専用の連続撮影フローティングUIプレビュー。
 *
 * production と同じ [CaptureOverlayContent] と [CaptureStatusNotifier] を、オーバーレイ権限・
 * MediaProjection の同意なしで実機に出すための入口（目視・スクリーンショット・実機テスト用）。
 * production の APK には含まれない。
 *
 * オーバーレイは他アプリの上に重なる部品なので、背面には撮影対象アプリを描かず
 * `--color-background` の無地だけを敷く（docs/design/00-design-overview.md 共通注意4）。
 *
 * 検証できること:
 * - 連続撮影中であることがオーバーレイと通知の両方に出る（FR-AUTO-005）。通知は Android 13 以降
 *   `POST_NOTIFICATIONS` の実行時許可が無いと一切出ないため、この画面でも許可を要求する
 *   （production の要求経路は `ui/captureprep/CapturePrepRoute.kt` の
 *   `onRequestNotificationPermission`）
 * - 保存枚数がサンプル値の固定表示ではないこと。0枚から始め、連続撮影中は最短保存間隔
 *   （2秒・docs/specs/06-auto-capture.md §4）ごとに1枚進み、一時停止中は止まる。
 *   枚数pillのタップでも1枚進む（このタップ操作はプレビュー側で足したもので、production の
 *   [CaptureOverlayContent] は枚数pillに操作を持たせていない）
 */
class FloatingUiPreviewActivity : ComponentActivity() {
    private val notifier by lazy { CaptureStatusNotifier(this) }
    private val ticker = Handler(Looper.getMainLooper())
    private lateinit var content: CaptureOverlayContent
    private var state = CaptureOverlayState.CONTINUOUS_ACTIVE
    private var savedCount = 0

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderBoth() }

    private val savedPageTick =
        object : Runnable {
            override fun run() {
                if (state == CaptureOverlayState.CONTINUOUS_ACTIVE) {
                    savedCount += 1
                    renderBoth()
                }
                ticker.postDelayed(this, MINIMUM_INTERVAL_MILLIS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedCount = intent.getIntExtra(EXTRA_SAVED_COUNT, 0).coerceAtLeast(0)
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
        // 枚数が固定値でないことを操作でも確かめられるようにする（プレビュー限定の操作）
        content.view.findViewById<View>(R.id.capture_overlay_count).setOnClickListener {
            savedCount += 1
            renderBoth()
        }
        notifier.createChannel()
        requestNotificationPermissionIfNeeded()
        renderBoth()
    }

    override fun onResume() {
        super.onResume()
        // 再開のたびに積み増さない（重ねると1回の間隔で複数枚進んでしまう）
        ticker.removeCallbacks(savedPageTick)
        ticker.postDelayed(savedPageTick, MINIMUM_INTERVAL_MILLIS)
    }

    override fun onPause() {
        ticker.removeCallbacks(savedPageTick)
        super.onPause()
    }

    override fun onDestroy() {
        getSystemService(NotificationManager::class.java).cancel(CaptureStatusNotifier.NOTIFICATION_ID)
        super.onDestroy()
    }

    /** オーバーレイと通知の両方に同じ状態を出す（production の CaptureStatusPresenter と同じ配り方） */
    private fun renderBoth() {
        content.render(state, savedCount)
        notifier.post(state, savedCount)
    }

    /**
     * Android 13 以降は実行時許可が無いと通知が出ない。production は撮影準備画面で要求し、
     * 未許可なら撮影を開始させない（`ui/captureprep/CapturePrepViewModel.kt` の canStart）。
     * このプレビューは撮影準備画面を通らないので、ここで同じ許可を要求する。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        /** 開始枚数。既定は0枚（モックの「24枚」はサンプル値なので初期値には使わない） */
        const val EXTRA_SAVED_COUNT = "saved_count"

        /** 最短保存間隔の初期値2秒（docs/specs/06-auto-capture.md §4） */
        const val MINIMUM_INTERVAL_MILLIS = 2000L

        /** docs/design/system/01-tokens.md: --color-background */
        const val BACKGROUND = 0xFFF8FAFC.toInt()
    }
}
