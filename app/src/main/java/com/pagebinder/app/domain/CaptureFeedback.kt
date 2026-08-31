package com.pagebinder.app.domain

/** User-selectable feedback.  Sound is opt-in; haptic and visual success feedback remain on. */
data class CaptureFeedbackSettings(
    val captureSoundEnabled: Boolean = false,
)

interface CaptureFeedbackSettingsRepository {
    suspend fun read(): CaptureFeedbackSettings

    suspend fun save(settings: CaptureFeedbackSettings)
}

interface CaptureFeedbackGateway {
    fun saved(
        pageNumber: Int,
        playSound: Boolean,
    )

    fun failed(reason: CapturePageFailure)
}

/** Keeps user feedback separate from the storage transaction and makes its outcome testable. */
class CaptureFeedbackController(
    private val settingsRepository: CaptureFeedbackSettingsRepository,
    private val gateway: CaptureFeedbackGateway,
) {
    suspend fun present(result: CapturePageResult) {
        when (result) {
            is CapturePageResult.Saved ->
                gateway.saved(
                    result.page.sequence,
                    settingsRepository.read().captureSoundEnabled,
                )
            is CapturePageResult.Isolated -> gateway.failed(CapturePageFailure.BLACK_SCREEN)
            is CapturePageResult.Failed -> gateway.failed(result.reason)
            CapturePageResult.IgnoredAlreadyCapturing -> Unit
        }
    }
}
