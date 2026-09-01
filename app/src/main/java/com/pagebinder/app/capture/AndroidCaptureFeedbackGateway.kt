package com.pagebinder.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import com.pagebinder.app.domain.CaptureFeedbackGateway
import com.pagebinder.app.domain.CapturePageFailure

/** Android-only haptic, optional sound, and concise visual feedback for one capture result. */
class AndroidCaptureFeedbackGateway(
    context: Context,
) : CaptureFeedbackGateway {
    private val appContext = context.applicationContext
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun saved(
        pageNumber: Int,
        playSound: Boolean,
    ) {
        mainHandler.post {
            vibrate()
            if (playSound) {
                val tone =
                    ToneGenerator(
                        AudioManager.STREAM_NOTIFICATION,
                        TONE_VOLUME,
                    )
                tone.startTone(ToneGenerator.TONE_PROP_ACK)
                mainHandler.postDelayed(tone::release, TONE_RELEASE_DELAY_MILLIS)
            }
            Toast.makeText(appContext, "ページ $pageNumber を保存しました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun failed(reason: CapturePageFailure) {
        val message =
            when (reason) {
                CapturePageFailure.NO_FRAME -> "画面を取得できませんでした。もう一度お試しください。"
                CapturePageFailure.SAVE_FAILED -> "ページを保存できませんでした。空き容量を確認してもう一度お試しください。"
                CapturePageFailure.BLACK_SCREEN -> "黒い画面を検出したため、通常ページには保存しませんでした。"
                CapturePageFailure.ROLLBACK_FAILED -> "保存の取り消しを完了できませんでした。アプリを再起動してページ一覧を確認してください。"
            }
        mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
    }

    /** VIBRATE is declared in the app manifest; this helper does not need a runtime permission. */
    @SuppressLint("MissingPermission")
    private fun vibrate() {
        val vibrator = appContext.getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VIBRATION_MILLIS)
        }
    }

    private companion object {
        const val VIBRATION_MILLIS = 35L
        const val TONE_VOLUME = 70
        const val TONE_RELEASE_DELAY_MILLIS = 200L
    }
}
