package com.pagebinder.app.capture

import android.graphics.Bitmap
import com.pagebinder.app.domain.AutoCaptureFrame
import com.pagebinder.app.domain.CapturedFrame
import com.pagebinder.app.image.BitmapFrameDifference
import com.pagebinder.app.image.BitmapGrayscale
import com.pagebinder.app.image.BitmapPerceptualHash
import com.pagebinder.app.image.LowResolutionGrayscaleImage

/** Converts a captured ARGB frame into the low-cost observations consumed by AutoCaptureMachine. */
class ContinuousFrameAnalyzer {
    private var previous: LowResolutionGrayscaleImage? = null

    fun analyze(frame: CapturedFrame): AutoCaptureFrame {
        val bitmap = Bitmap.createBitmap(frame.argbPixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            val grayscale = BitmapGrayscale.createLowResolution(bitmap)
            val difference = previous?.let { BitmapFrameDifference.distance(it, grayscale) } ?: 0.0
            previous = grayscale
            return AutoCaptureFrame(BitmapPerceptualHash.calculate(bitmap), difference)
        } finally {
            bitmap.recycle()
        }
    }
}
