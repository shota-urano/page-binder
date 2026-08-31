package com.pagebinder.app.ui.captureprep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val DEFAULT_MINIMUM_INTERVAL_SECONDS = 2
private const val MIN_INTERVAL_SECONDS = 1
private const val MAX_INTERVAL_SECONDS = 30

enum class CaptureMode {
    MANUAL,
    CONTINUOUS,
}

data class CapturePrepUiState(
    val bookTitle: String,
    val mode: CaptureMode,
    val overlayGranted: Boolean = false,
    val notificationPermissionRequired: Boolean = false,
    val notificationGranted: Boolean = true,
    val minimumIntervalSeconds: Int = DEFAULT_MINIMUM_INTERVAL_SECONDS,
    val maximumPages: Int? = null,
    val maximumMinutes: Int? = null,
    val projectionConsentRequest: Long? = null,
    val projectionDenied: Boolean = false,
) {
    val canStart: Boolean
        get() = overlayGranted && (!notificationPermissionRequired || notificationGranted)

    val blockedByOverlay: Boolean
        get() = !overlayGranted

    val blockedByNotifications: Boolean
        get() = notificationPermissionRequired && !notificationGranted
}

class CapturePrepViewModel(
    bookTitle: String,
    initialMode: CaptureMode,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(CapturePrepUiState(bookTitle = bookTitle, mode = initialMode))
    val uiState: StateFlow<CapturePrepUiState> = mutableUiState.asStateFlow()

    fun refreshPermissions(
        overlayGranted: Boolean,
        notificationPermissionRequired: Boolean,
        notificationGranted: Boolean,
    ) {
        mutableUiState.update {
            it.copy(
                overlayGranted = overlayGranted,
                notificationPermissionRequired = notificationPermissionRequired,
                notificationGranted = notificationGranted,
            )
        }
    }

    fun onModeSelected(mode: CaptureMode) {
        mutableUiState.update { it.copy(mode = mode) }
    }

    fun onMinimumIntervalChanged(seconds: Int) {
        mutableUiState.update {
            it.copy(minimumIntervalSeconds = seconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS))
        }
    }

    fun onMaximumPagesChanged(value: Int?) {
        mutableUiState.update { it.copy(maximumPages = value?.takeIf { pages -> pages > 0 }) }
    }

    fun onMaximumMinutesChanged(value: Int?) {
        mutableUiState.update { it.copy(maximumMinutes = value?.takeIf { minutes -> minutes > 0 }) }
    }

    fun onStartRequested() {
        if (!mutableUiState.value.canStart) return
        mutableUiState.update {
            it.copy(
                projectionConsentRequest = (it.projectionConsentRequest ?: 0L) + 1L,
                projectionDenied = false,
            )
        }
    }

    fun onProjectionConsentLaunched() {
        mutableUiState.update { it.copy(projectionConsentRequest = null) }
    }

    fun onProjectionConsentResult(granted: Boolean) {
        mutableUiState.update { it.copy(projectionDenied = !granted) }
    }

    companion object {
        fun factory(
            bookTitle: String,
            initialMode: CaptureMode,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { CapturePrepViewModel(bookTitle, initialMode) }
            }
    }
}
