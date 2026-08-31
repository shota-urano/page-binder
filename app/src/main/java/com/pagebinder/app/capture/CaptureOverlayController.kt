package com.pagebinder.app.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.pagebinder.app.R
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
    private var view: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var state: CaptureOverlayState = CaptureOverlayState.STOPPED
    private var savedCount: Int = 0

    @Volatile
    private var desiredVisible = true

    override fun attach(
        state: CaptureOverlayState,
        savedCount: Int,
    ) {
        desiredVisible = true
        mainHandler.post {
            if (view != null || !Settings.canDrawOverlays(appContext)) return@post
            this.state = state
            this.savedCount = savedCount
            val overlay = buildView()
            val layoutParams = newLayoutParams()
            view = overlay
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
        mainHandler.post {
            this.state = state
            this.savedCount = savedCount
            view?.let(::render)
        }
    }

    override fun setVisible(visible: Boolean) {
        desiredVisible = visible
        mainHandler.post { view?.visibility = if (visible) View.VISIBLE else View.INVISIBLE }
    }

    override fun detach() {
        desiredVisible = false
        mainHandler.post {
            view?.let { runCatching { windowManager.removeView(it) } }
            view = null
            params = null
        }
    }

    private fun buildView(): LinearLayout =
        LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(32).toFloat()
                    setColor(OVERLAY_BACKGROUND)
                }
            addView(actionButton("📷", appContext.getString(R.string.capture_overlay_capture), onCapture))
            addView(statusView())
            addView(countView())
            addView(
                actionButton("Ⅱ", appContext.getString(R.string.capture_overlay_pause)) {
                    val paused = state != CaptureOverlayState.CONTINUOUS_PAUSED
                    onPauseChanged(paused)
                },
            )
            addView(actionButton("■", appContext.getString(R.string.capture_overlay_stop), onStop))
            setOnTouchListener(DragTouchListener())
            render(this)
        }

    private fun render(container: LinearLayout) {
        val captureButton = container.getChildAt(CAPTURE_INDEX)
        val status = container.getChildAt(STATUS_INDEX) as TextView
        val count = container.getChildAt(COUNT_INDEX) as TextView
        val pause = container.getChildAt(PAUSE_INDEX) as Button
        status.text =
            appContext.getString(
                when (state) {
                    CaptureOverlayState.MANUAL_ACTIVE -> R.string.capture_overlay_manual_state
                    CaptureOverlayState.CONTINUOUS_ACTIVE -> R.string.capture_overlay_continuous_state
                    CaptureOverlayState.CONTINUOUS_PAUSED -> R.string.capture_overlay_paused_state
                    CaptureOverlayState.STOPPED -> R.string.capture_overlay_stopped_state
                },
            )
        val continuous =
            state == CaptureOverlayState.CONTINUOUS_ACTIVE ||
                state == CaptureOverlayState.CONTINUOUS_PAUSED
        captureButton.visibility = if (state == CaptureOverlayState.MANUAL_ACTIVE) View.VISIBLE else View.GONE
        count.visibility = if (continuous) View.VISIBLE else View.GONE
        count.text = appContext.getString(R.string.capture_overlay_saved_count, savedCount)
        pause.visibility = if (continuous) View.VISIBLE else View.GONE
        pause.text = if (state == CaptureOverlayState.CONTINUOUS_PAUSED) "▶" else "Ⅱ"
        pause.contentDescription =
            appContext.getString(
                if (state == CaptureOverlayState.CONTINUOUS_PAUSED) {
                    R.string.capture_overlay_resume
                } else {
                    R.string.capture_overlay_pause
                },
            )
    }

    private fun statusView() =
        TextView(appContext).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

    private fun countView() =
        TextView(appContext).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

    private fun actionButton(
        glyph: String,
        description: String,
        action: () -> Unit,
    ) = Button(appContext).apply {
        text = glyph
        contentDescription = description
        setTextColor(Color.WHITE)
        textSize = 20f
        minWidth = dp(48)
        minHeight = dp(48)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
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
            x = (screenWidth - dp(80)).coerceAtLeast(0)
            y = screenHeight / 3
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
        const val CAPTURE_INDEX = 0
        const val STATUS_INDEX = 1
        const val COUNT_INDEX = 2
        const val PAUSE_INDEX = 3
        const val OVERLAY_BACKGROUND = 0xD91A2233.toInt()
    }
}
