package com.pagebinder.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.pagebinder.app.domain.CaptureGateway
import com.pagebinder.app.domain.CaptureGatewayEvent
import com.pagebinder.app.domain.CaptureGatewayStartResult
import com.pagebinder.app.domain.CapturePermissionToken
import com.pagebinder.app.domain.CaptureSize
import com.pagebinder.app.domain.CaptureStartFailure
import com.pagebinder.app.domain.CaptureStartRejection
import com.pagebinder.app.domain.CaptureStopReason
import com.pagebinder.app.domain.CapturedFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * A single-use holder for a platform permission result. It intentionally has no read accessor:
 * the contained value is irrevocably removed by [take].
 */
internal class OneShotCapturePermission<T>(value: T) {
    private val value = AtomicReference<T?>(value)

    fun take(): T? = value.getAndSet(null)
}

/** One MediaProjection consent result; [consume] removes it permanently. */
class AndroidCapturePermissionToken private constructor(
    private val permission: OneShotCapturePermission<AndroidCapturePermissionResult>,
) : CapturePermissionToken {
    fun consume(): AndroidCapturePermissionResult? = permission.take()

    companion object {
        fun fromPermissionResult(
            resultCode: Int,
            resultData: Intent,
        ): CapturePermissionToken =
            AndroidCapturePermissionToken(
                OneShotCapturePermission(AndroidCapturePermissionResult(resultCode, resultData)),
            )
    }
}

data class AndroidCapturePermissionResult(
    val resultCode: Int,
    val resultData: Intent,
)

/**
 * Owns every Android MediaProjection object. Each start consumes one permission result, registers
 * the callback before creating exactly one VirtualDisplay, and clears all references on stop.
 */
class AndroidCaptureGateway(
    context: Context,
) : CaptureGateway {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val callbackThread = HandlerThread("pagebinder-capture").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val eventChannel = Channel<CaptureGatewayEvent>(Channel.UNLIMITED)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    override val events: Flow<CaptureGatewayEvent> = eventChannel.receiveAsFlow()

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var currentSize: CaptureSize? = null
    private var displayListenerRegistered = false

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                synchronized(lock) {
                    if (mediaProjection != null) resizeCaptureSurface(captureSize())
                }
            }
        }

    override fun start(permissionToken: CapturePermissionToken): CaptureGatewayStartResult =
        synchronized(lock) {
            if (mediaProjection != null) {
                return CaptureGatewayStartResult.Rejected(CaptureStartRejection.GATEWAY_ALREADY_ACTIVE)
            }
            val authorization =
                permissionToken as? AndroidCapturePermissionToken
                    ?: return CaptureGatewayStartResult.Rejected(
                        CaptureStartRejection.INVALID_PERMISSION_TOKEN,
                    )
            val permission =
                authorization.consume()
                    ?: return CaptureGatewayStartResult.Rejected(
                        CaptureStartRejection.PERMISSION_ALREADY_CONSUMED,
                    )

            startWithFreshPermission(permission)
        }

    override fun stop() {
        synchronized(lock) {
            releaseResources(stopProjection = true)
        }
    }

    override fun latestFrame(): CapturedFrame? =
        synchronized(lock) {
            val image = imageReader?.acquireLatestImage() ?: return null
            try {
                val plane = image.planes.single()
                val rowPadding = plane.rowStride - plane.pixelStride * image.width
                val paddedWidth = image.width + rowPadding / plane.pixelStride
                val bitmap =
                    Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888).apply {
                        copyPixelsFromBuffer(plane.buffer)
                    }
                val pixels = IntArray(image.width * image.height)
                bitmap.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                bitmap.recycle()
                CapturedFrame(image.width, image.height, pixels)
            } finally {
                image.close()
            }
        }

    private fun startWithFreshPermission(permission: AndroidCapturePermissionResult): CaptureGatewayStartResult {
        val projectionManager = appContext.getSystemService(MediaProjectionManager::class.java)
        val projection =
            projectionManager.getMediaProjection(permission.resultCode, permission.resultData)
                ?: return CaptureGatewayStartResult.Failed(CaptureStartFailure.MEDIA_PROJECTION_UNAVAILABLE)

        val size = captureSize()
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, IMAGE_BUFFER_COUNT)
        val callback = projectionCallback()
        return try {
            // §11.1: FGS was started by the service; acquire projection, then register Callback.
            projection.registerCallback(callback, callbackHandler)
            val display =
                projection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    size.width,
                    size.height,
                    appContext.resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    callbackHandler,
                ) ?: throw IllegalStateException("VirtualDisplay was not created")

            mediaProjection = projection
            projectionCallback = callback
            imageReader = reader
            virtualDisplay = display
            currentSize = size
            displayManager.registerDisplayListener(displayListener, callbackHandler)
            displayListenerRegistered = true
            CaptureGatewayStartResult.Started(size)
        } catch (_: RuntimeException) {
            reader.close()
            projection.unregisterCallback(callback)
            projection.stop()
            CaptureGatewayStartResult.Failed(CaptureStartFailure.VIRTUAL_DISPLAY_UNAVAILABLE)
        }
    }

    private fun projectionCallback(): MediaProjection.Callback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                synchronized(lock) {
                    releaseResources(stopProjection = false)
                }
                eventChannel.trySend(CaptureGatewayEvent.ProjectionStopped(CaptureStopReason.OS_STOPPED))
            }

            override fun onCapturedContentResize(
                width: Int,
                height: Int,
            ) {
                if (width <= 0 || height <= 0) return
                synchronized(lock) {
                    resizeCaptureSurface(CaptureSize(width, height))
                }
            }
        }

    /** Keeps the sole VirtualDisplay and swaps its ImageReader surface for subsequent frames. */
    private fun resizeCaptureSurface(size: CaptureSize) {
        if (currentSize == size) return
        val display = virtualDisplay ?: return
        val replacement = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, IMAGE_BUFFER_COUNT)
        try {
            display.resize(size.width, size.height, appContext.resources.displayMetrics.densityDpi)
            display.surface = replacement.surface
            imageReader?.close()
            imageReader = replacement
            currentSize = size
            eventChannel.trySend(CaptureGatewayEvent.ContentResized(size))
        } catch (_: RuntimeException) {
            replacement.close()
            eventChannel.trySend(CaptureGatewayEvent.ProjectionStopped(CaptureStopReason.ERROR))
        }
    }

    private fun releaseResources(stopProjection: Boolean) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        currentSize = null
        if (displayListenerRegistered) {
            displayManager.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        val projection = mediaProjection
        val callback = projectionCallback
        mediaProjection = null
        projectionCallback = null
        if (projection != null && callback != null) projection.unregisterCallback(callback)
        if (stopProjection) projection?.stop()
    }

    private fun captureSize(): CaptureSize {
        val windowManager = appContext.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            CaptureSize(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
            CaptureSize(metrics.widthPixels, metrics.heightPixels)
        }
    }

    companion object {
        private const val IMAGE_BUFFER_COUNT = 2
        private const val VIRTUAL_DISPLAY_NAME = "PageBinderCapture"
    }
}
