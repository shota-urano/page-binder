package com.pagebinder.app.spike.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.IBinder
import android.os.Parcelable
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only one-frame spike. Event recording makes the required runtime ordering reviewable.
 */
class MediaProjectionSpikeService : Service() {
    private val completed = AtomicBoolean(false)
    private lateinit var record: MediaProjectionSpikeRecord
    private lateinit var callbackThread: HandlerThread
    private lateinit var callbackHandler: Handler
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                record.append("MEDIA_PROJECTION_CALLBACK_STOPPED")
                finishResources(stopProjection = false)
            }
        }

    override fun onCreate() {
        super.onCreate()
        record = MediaProjectionSpikeRecord(this)
        callbackThread = HandlerThread("phase0-media-projection").apply { start() }
        callbackHandler = Handler(callbackThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startAsMediaProjectionForegroundService()
        record.append("FOREGROUND_SERVICE_STARTED")

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.parcelableIntentExtra(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            fail("MISSING_ONE_SHOT_PERMISSION_RESULT")
            return START_NOT_STICKY
        }

        runCatching {
            startProjection(resultCode, resultData)
        }.onFailure { error ->
            fail("${error.javaClass.simpleName}:${error.message.orEmpty().replace('\n', ' ')}")
        }
        intent.removeExtra(EXTRA_RESULT_DATA)
        return START_NOT_STICKY
    }

    private fun startProjection(
        resultCode: Int,
        resultData: Intent,
    ) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        record.append("MEDIA_PROJECTION_ACQUIRED")

        val (width, height) = captureSize()
        val density = resources.displayMetrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener(::saveFirstFrame, callbackHandler)

        mediaProjection?.registerCallback(projectionCallback, callbackHandler)
        record.append("MEDIA_PROJECTION_CALLBACK_REGISTERED")

        virtualDisplay =
            mediaProjection?.createVirtualDisplay(
                "PageBinderPhase0",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                callbackHandler,
            )
        checkNotNull(virtualDisplay) { "VirtualDisplay was not created" }
        record.append("VIRTUAL_DISPLAY_CREATED")
    }

    private fun saveFirstFrame(reader: ImageReader) {
        if (completed.get()) return
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes.single()
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            val padded =
                Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888).apply {
                    copyPixelsFromBuffer(plane.buffer)
                }
            val frame = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            check(
                record.imageFile().outputStream().use {
                    frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                },
            ) { "Bitmap compression failed" }
            padded.recycle()
            frame.recycle()
            record.append("FRAME_BITMAP_SAVED=${image.width}x${image.height}")
            record.append("RESULT=PASS")
            finishResources(stopProjection = true)
        } catch (error: Exception) {
            fail("${error.javaClass.simpleName}:${error.message.orEmpty().replace('\n', ' ')}")
        } finally {
            image.close()
        }
    }

    private fun captureSize(): Pair<Int, Int> {
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            @Suppress("DEPRECATION")
            android.util.DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics).let {
                it.widthPixels to it.heightPixels
            }
        }
    }

    private fun startAsMediaProjectionForegroundService() {
        val notification =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("PageBinder Phase 0")
                .setContentText("One-frame MediaProjection verification")
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Phase 0 capture verification",
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun fail(reason: String) {
        record.finishWithFailure(reason)
        finishResources(stopProjection = true)
    }

    private fun finishResources(stopProjection: Boolean) {
        if (!completed.compareAndSet(false, true)) return
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        if (stopProjection) mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finishResources(stopProjection = true)
        callbackThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun Intent.parcelableIntentExtra(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            getParcelableExtra<Parcelable>(key) as? Intent
        }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "phase0_capture"
        private const val NOTIFICATION_ID = 2401
    }
}
