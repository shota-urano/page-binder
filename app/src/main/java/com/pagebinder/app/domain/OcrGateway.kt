package com.pagebinder.app.domain

import java.io.InputStream

fun interface OcrImageSource {
    fun openInputStream(): InputStream
}

/** Crop bounds normalized against the image after clockwise rotation. */
data class OcrCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "OCR crop coordinates must be finite"
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "OCR crop coordinates must be normalized"
        }
        require(left < right && top < bottom) { "OCR crop must have a positive area" }
    }
}

data class OcrInput(
    val image: OcrImageSource,
    val rotationDegrees: Int = 0,
    val crop: OcrCrop = OcrCrop(),
) {
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "OCR rotation must be 0, 90, 180, or 270 degrees"
        }
    }
}

data class OcrOutput(
    val fullText: String,
    val blocksJson: String,
    val engineVersion: String,
    val sourceImageHash: String,
)

class OcrInputException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class OcrRecognitionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Recognizes one image without exposing ML Kit types to callers. */
fun interface OcrGateway {
    @Throws(OcrInputException::class, OcrRecognitionException::class)
    suspend fun recognize(input: OcrInput): OcrOutput
}
