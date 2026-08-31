package com.pagebinder.app.ui.captureprep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePrepViewModelTest {
    @Test
    fun `overlay denial disables capture start and does not request projection consent`() {
        val viewModel = CapturePrepViewModel("実データ", CaptureMode.MANUAL)

        viewModel.refreshPermissions(
            overlayGranted = false,
            notificationPermissionRequired = false,
            notificationGranted = true,
        )
        viewModel.onStartRequested()

        assertFalse(viewModel.uiState.value.canStart)
        assertTrue(viewModel.uiState.value.blockedByOverlay)
        assertNull(viewModel.uiState.value.projectionConsentRequest)
    }

    @Test
    fun `required notification denial also blocks start`() {
        val viewModel = CapturePrepViewModel("実データ", CaptureMode.MANUAL)

        viewModel.refreshPermissions(
            overlayGranted = true,
            notificationPermissionRequired = true,
            notificationGranted = false,
        )

        assertFalse(viewModel.uiState.value.canStart)
        assertTrue(viewModel.uiState.value.blockedByNotifications)
    }

    @Test
    fun `granted permissions create a single consumable projection request`() {
        val viewModel = CapturePrepViewModel("実データ", CaptureMode.CONTINUOUS)
        viewModel.refreshPermissions(
            overlayGranted = true,
            notificationPermissionRequired = true,
            notificationGranted = true,
        )

        viewModel.onStartRequested()
        assertEquals(1L, viewModel.uiState.value.projectionConsentRequest)

        viewModel.onProjectionConsentLaunched()
        assertNull(viewModel.uiState.value.projectionConsentRequest)
        assertTrue(viewModel.uiState.value.canStart)
    }

    @Test
    fun `continuous interval is constrained to the specified range`() {
        val viewModel = CapturePrepViewModel("実データ", CaptureMode.CONTINUOUS)

        viewModel.onMinimumIntervalChanged(0)
        assertEquals(1, viewModel.uiState.value.minimumIntervalSeconds)

        viewModel.onMinimumIntervalChanged(31)
        assertEquals(30, viewModel.uiState.value.minimumIntervalSeconds)
    }
}
