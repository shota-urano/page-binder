package com.pagebinder.app.capture

import com.pagebinder.app.domain.CaptureStopReason

/** Selects the concise, actionable notice for an unexpected capture-session stop. */
enum class CaptureStopNotice {
    GENERIC,
    SELECT_ENTIRE_SCREEN,
}

fun CaptureStopReason.stopNotice(): CaptureStopNotice =
    when (this) {
        CaptureStopReason.SHARED_CONTENT_NOT_VISIBLE -> CaptureStopNotice.SELECT_ENTIRE_SCREEN
        else -> CaptureStopNotice.GENERIC
    }
