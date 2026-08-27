package com.pagebinder.app.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.pagebinder.app.domain.OcrGateway
import com.pagebinder.app.domain.OcrInput
import com.pagebinder.app.domain.OcrOutput
import com.pagebinder.app.domain.OcrRecognitionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bundled Japanese ML Kit implementation. ML Kit types are confined to the ocr package. */
class MlKitOcrGateway(
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
    private val preprocessingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : OcrGateway,
    Closeable {
    override suspend fun recognize(input: OcrInput): OcrOutput {
        val prepared = withContext(preprocessingDispatcher) { OcrImagePreprocessor.prepare(input) }
        try {
            try {
                val sourceImageHash = withContext(preprocessingDispatcher) { prepared.bitmap.sha256() }
                val text = recognizer.process(InputImage.fromBitmap(prepared.bitmap, 0)).awaitResult()
                return OcrOutput(
                    fullText = text.text,
                    blocksJson = OcrBlocksJsonEncoder.encode(text, prepared.coordinateMapper),
                    engineVersion = ENGINE_VERSION,
                    sourceImageHash = sourceImageHash,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw OcrRecognitionException("OCR recognition failed", error)
            }
        } finally {
            prepared.bitmap.recycle()
        }
    }

    override fun close() = recognizer.close()

    private suspend fun Task<Text>.awaitResult(): Text =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener { continuation.cancel() }
        }

    private fun Bitmap.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES * 2).putInt(width).putInt(height).array())
        val pixels = IntArray(width)
        val bytes = ByteBuffer.allocate(width * Int.SIZE_BYTES)
        for (row in 0 until height) {
            getPixels(pixels, 0, width, 0, row, width, 1)
            bytes.clear()
            pixels.forEach(bytes::putInt)
            digest.update(bytes.array())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val ENGINE_VERSION = "mlkit-text-recognition-v2-japanese:16.0.1"
    }
}
