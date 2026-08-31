package com.pagebinder.app.ui.captureprep

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.R
import com.pagebinder.app.ui.theme.PageBinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapturePrepScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overlayNotGrantedShowsReasonAndDisablesStart() {
        composeRule.setContent {
            PageBinderTheme {
                CapturePrepScreen(
                    uiState =
                        CapturePrepUiState(
                            bookTitle = "状態から渡した書籍",
                            mode = CaptureMode.MANUAL,
                            overlayGranted = false,
                        ),
                    actions = actions(),
                )
            }
        }

        composeRule.onNodeWithText("状態から渡した書籍").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.capture_prep_overlay_required)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.capture_prep_start)).assertIsNotEnabled()
    }

    @Test
    fun continuousModeShowsAllCaptureSettingsAndPermissionExplanations() {
        composeRule.setContent {
            PageBinderTheme {
                CapturePrepScreen(
                    uiState =
                        CapturePrepUiState(
                            bookTitle = "実データ",
                            mode = CaptureMode.CONTINUOUS,
                            overlayGranted = true,
                        ),
                    actions = actions(),
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.capture_prep_minimum_interval)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.capture_prep_maximum_pages)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.capture_prep_maximum_time)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.capture_prep_projection_title)).assertIsDisplayed()
    }

    private fun actions() =
        CapturePrepActions(
            onBack = {},
            onModeSelected = {},
            onMinimumIntervalChanged = {},
            onMaximumPagesChanged = {},
            onMaximumMinutesChanged = {},
            onOpenOverlaySettings = {},
            onRequestNotificationPermission = {},
            onStart = {},
        )

    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
