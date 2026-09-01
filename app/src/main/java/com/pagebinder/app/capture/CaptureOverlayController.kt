package com.pagebinder.app.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.pagebinder.app.domain.CaptureOverlayGateway
import com.pagebinder.app.domain.CaptureOverlayState
import kotlin.math.roundToInt

internal interface OverlayWindow {
    fun attach(
        state: CaptureOverlayState,
        savedCount: Int,
    )

    fun update(
        state: CaptureOverlayState,
        savedCount: Int,
    )

    fun setVisible(visible: Boolean)

    fun detach()
}

class CaptureOverlayController internal constructor(
    private val window: OverlayWindow,
) : CaptureOverlayGateway {
    constructor(
        context: Context,
        onCapture: () -> Unit,
        onPauseChanged: (Boolean) -> Unit,
        onStop: () -> Unit,
    ) : this(AndroidOverlayWindow(context, onCapture, onPauseChanged, onStop))

    private var attached = false
    private var hiddenForCapture = false

    override fun show(
        state: CaptureOverlayState,
        savedCount: Int,
    ) {
        hiddenForCapture = false
        if (attached) {
            window.update(state, savedCount)
            window.setVisible(true)
        } else {
            window.attach(state, savedCount)
            attached = true
        }
    }

    override fun update(
        state: CaptureOverlayState,
        savedCount: Int,
    ) {
        if (attached) window.update(state, savedCount)
    }

    override fun hideForCapture() {
        if (!attached || hiddenForCapture) return
        hiddenForCapture = true
        window.setVisible(false)
    }

    override fun restoreAfterCapture() {
        if (!attached || !hiddenForCapture) return
        hiddenForCapture = false
        window.setVisible(true)
    }

    override fun remove() {
        if (!attached) return
        window.detach()
        attached = false
        hiddenForCapture = false
    }
}

internal object OverlaySnapCalculator {
    fun snapX(
        currentX: Int,
        overlayWidth: Int,
        screenWidth: Int,
    ): Int {
        val rightEdge = (screenWidth - overlayWidth).coerceAtLeast(0)
        return if (currentX + overlayWidth / 2 < screenWidth / 2) 0 else rightEdge
    }
}

private class AndroidOverlayWindow(
    context: Context,
    private val onCapture: () -> Unit,
    private val onPauseChanged: (Boolean) -> Unit,
    private val onStop: () -> Unit,
) : OverlayWindow {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var content: CaptureOverlayContent? = null
    private var params: WindowManager.LayoutParams? = null

    @Volatile
    private var desiredVisible = true

    override fun attach(
        state: CaptureOverlayState,
        savedCount: Int,
    ) {
        desiredVisible = true
        mainHandler.post {
            if (content != null || !Settings.canDrawOverlays(appContext)) return@post
            val overlayContent = CaptureOverlayContent(appContext, onCapture, onPauseChanged, onStop)
            val overlay = overlayContent.view
            overlay.setOnTouchListener(DragTouchListener())
            overlayContent.render(state, savedCount)
            val layoutParams = newLayoutParams()
            content = overlayContent
            params = layoutParams
            overlay.visibility = if (desiredVisible) View.VISIBLE else View.INVISIBLE
            windowManager.addView(overlay, layoutParams)
            overlay.post {
                layoutParams.x =
                    OverlaySnapCalculator.snapX(
                        currentX = layoutParams.x,
                        overlayWidth = overlay.width,
                        screenWidth = screenDimensions().first,
                    )
                windowManager.updateViewLayout(overlay, layoutParams)
            }
        }
    }

    override fun update(
        state: CaptureOverlayState,
        savedCount: Int,
    ) {
        mainHandler.post { content?.render(state, savedCount) }
    }

    override fun setVisible(visible: Boolean) {
        desiredVisible = visible
        mainHandler.post { content?.view?.visibility = if (visible) View.VISIBLE else View.INVISIBLE }
    }

    override fun detach() {
        desiredVisible = false
        mainHandler.post {
            content?.let { runCatching { windowManager.removeView(it.view) } }
            content = null
            params = null
        }
    }

    private fun newLayoutParams(): WindowManager.LayoutParams {
        val (screenWidth, screenHeight) = screenDimensions()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - dp(OVERLAY_MARGIN_DP)).coerceAtLeast(0)
            y = screenHeight / VERTICAL_START_FRACTION
        }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(
            touched: View,
            event: MotionEvent,
        ): Boolean {
            val layoutParams = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = startX + (event.rawX - startRawX).roundToInt()
                    layoutParams.y = startY + (event.rawY - startRawY).roundToInt()
                    windowManager.updateViewLayout(touched, layoutParams)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    layoutParams.x =
                        OverlaySnapCalculator.snapX(
                            currentX = layoutParams.x,
                            overlayWidth = touched.width,
                            screenWidth = screenDimensions().first,
                        )
                    windowManager.updateViewLayout(touched, layoutParams)
                    touched.performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    layoutParams.x =
                        OverlaySnapCalculator.snapX(
                            currentX = layoutParams.x,
                            overlayWidth = touched.width,
                            screenWidth = screenDimensions().first,
                        )
                    windowManager.updateViewLayout(touched, layoutParams)
                    return true
                }
            }
            return false
        }
    }

    private fun screenDimensions(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
            metrics.widthPixels to metrics.heightPixels
        }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).roundToInt()

    private companion object {
        /** 初期位置は画面右端。オーバーレイ幅ぶんだけ内側から始め、addView 後の実測で端へ吸着させる */
        const val OVERLAY_MARGIN_DP = 80
        const val VERTICAL_START_FRACTION = 3
    }
}
