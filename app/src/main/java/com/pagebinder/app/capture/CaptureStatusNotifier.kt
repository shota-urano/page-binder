package com.pagebinder.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.pagebinder.app.R
import com.pagebinder.app.domain.AutoCaptureStopReason
import com.pagebinder.app.domain.CaptureMode
import com.pagebinder.app.domain.CaptureOverlayGateway
import com.pagebinder.app.domain.CaptureOverlayState

/**
 * 撮影中の常駐通知（docs/specs/06-auto-capture.md §3.4 / FR-AUTO-005）。
 *
 * オーバーレイと同じ撮影状態・保存枚数を通知にも出し、通知からも停止できるようにする。
 * 通知は [CaptureForegroundService] の前景通知そのもの（同じ ID）で、更新も同じ ID へ流す。
 * 書籍タイトル・保存先URI・OCR文面は通知に載せない（AGENTS.md / requirements §16.3）。
 */
class CaptureStatusNotifier(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun build(
        state: CaptureOverlayState,
        savedCount: Int,
    ): Notification =
        Notification
            .Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(appContext.getString(R.string.capture_notification_title))
            .setContentText(contentText(state, savedCount))
            .setOngoing(state != CaptureOverlayState.STOPPED)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        appContext.getString(R.string.capture_notification_stop),
                        stopPendingIntent(),
                    ).build(),
            ).build()

    /**
     * 撮影中の状態を通知へ反映する。停止した状態は前景通知の取り下げ側（サービス）が扱うので、
     * ここからは出し直さない（取り下げ後に出し直すと常駐通知が残る）。
     */
    fun post(
        state: CaptureOverlayState,
        savedCount: Int,
    ) {
        if (state == CaptureOverlayState.STOPPED) return
        appContext
            .getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(state, savedCount))
    }

    /**
     * 上限（最大ページ数・最大時間）に達して連続撮影を自動停止したことを知らせる
     * （docs/specs/06-auto-capture.md §6「最大ページ数/時間到達: 自動停止し、通知で明示」）。
     * 利用者が自分で止めたとき（EXPLICIT）は知らせるものが無いので null を返す。
     */
    fun buildAutoStopped(reason: AutoCaptureStopReason): Notification? {
        val textRes =
            when (reason) {
                AutoCaptureStopReason.MAXIMUM_PAGES -> R.string.capture_notification_auto_stopped_pages
                AutoCaptureStopReason.MAXIMUM_DURATION -> R.string.capture_notification_auto_stopped_duration
                AutoCaptureStopReason.EXPLICIT -> return null
            }
        return Notification
            .Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(appContext.getString(R.string.capture_notification_stopped_title))
            .setContentText(appContext.getString(textRes))
            .setAutoCancel(true)
            .build()
    }

    fun postAutoStopped(reason: AutoCaptureStopReason) {
        val notification = buildAutoStopped(reason) ?: return
        appContext
            .getSystemService(NotificationManager::class.java)
            .notify(AUTO_STOPPED_NOTIFICATION_ID, notification)
    }

    /** 予期しない停止（OS側の画面共有停止・画面ロック等）を後追いで知らせる単発通知 */
    fun postUnexpectedStop() {
        val notification =
            Notification
                .Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setContentTitle(appContext.getString(R.string.capture_notification_stopped_title))
                .setContentText(appContext.getString(R.string.capture_notification_stopped_message))
                .setAutoCancel(true)
                .build()
        appContext
            .getSystemService(NotificationManager::class.java)
            .notify(STOPPED_NOTIFICATION_ID, notification)
    }

    fun createChannel() {
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                appContext.getString(R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appContext.getString(R.string.capture_notification_channel_description)
                setShowBadge(false)
            }
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun contentText(
        state: CaptureOverlayState,
        savedCount: Int,
    ): String {
        val stateText = appContext.getString(stateTextRes(state))
        val showsCount =
            savedCount > 0 &&
                (state == CaptureOverlayState.CONTINUOUS_ACTIVE || state == CaptureOverlayState.CONTINUOUS_PAUSED)
        return if (showsCount) {
            appContext.getString(R.string.capture_notification_saved_count, stateText, savedCount)
        } else {
            stateText
        }
    }

    private fun stateTextRes(state: CaptureOverlayState): Int =
        when (state) {
            CaptureOverlayState.MANUAL_ACTIVE -> R.string.capture_notification_manual
            CaptureOverlayState.CONTINUOUS_ACTIVE -> R.string.capture_notification_continuous
            CaptureOverlayState.CONTINUOUS_PAUSED -> R.string.capture_notification_paused
            CaptureOverlayState.STOPPED -> R.string.capture_notification_stopped_title
        }

    private fun stopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            appContext,
            STOP_PENDING_INTENT_REQUEST_CODE,
            Intent(appContext, CaptureForegroundService::class.java)
                .setAction(CaptureForegroundService.ACTION_STOP_CAPTURE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val NOTIFICATION_ID = 2501
        private const val NOTIFICATION_CHANNEL_ID = "capture_session"
        private const val STOPPED_NOTIFICATION_ID = 2503
        private const val AUTO_STOPPED_NOTIFICATION_ID = 2504
        private const val STOP_PENDING_INTENT_REQUEST_CODE = 2502

        /** セッション開始直後（保存0枚）の状態。連続撮影は開始時点から「連続撮影中」と出す */
        fun initialState(mode: CaptureMode): CaptureOverlayState =
            if (mode == CaptureMode.CONTINUOUS) {
                CaptureOverlayState.CONTINUOUS_ACTIVE
            } else {
                CaptureOverlayState.MANUAL_ACTIVE
            }
    }
}

/**
 * 撮影状態をオーバーレイと常駐通知の両方へ配る（FR-AUTO-005: 連続撮影中であることを両方に明示する）。
 *
 * 状態の作り手（CaptureSessionCoordinator / CapturePageController）からは出力先が1つに見えるので、
 * 通知を出すためのぶら下がりが domain 側へ増えない。
 */
class CaptureStatusPresenter(
    private val overlay: CaptureOverlayGateway,
    private val postStatusNotification: (CaptureOverlayState, Int) -> Unit,
) : CaptureOverlayGateway {
    override fun show(
        state: CaptureOverlayState,
        savedCount: Int,
    ) {
        overlay.show(state, savedCount)
        postStatusNotification(state, savedCount)
    }

    override fun update(
        state: CaptureOverlayState,
        savedCount: Int,
    ) {
        overlay.update(state, savedCount)
        postStatusNotification(state, savedCount)
    }

    /** 撮影の一瞬だけ隠すのはオーバーレイの話（FR-CAP-002）。通知は出したままにする */
    override fun hideForCapture() {
        overlay.hideForCapture()
    }

    override fun restoreAfterCapture() {
        overlay.restoreAfterCapture()
    }

    override fun remove() {
        overlay.remove()
    }
}
