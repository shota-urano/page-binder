package com.pagebinder.app.ui.capture

import android.app.Notification
import android.app.NotificationManager
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.R
import com.pagebinder.app.capture.CaptureStatusNotifier
import com.pagebinder.app.domain.AutoCaptureStopReason
import com.pagebinder.app.domain.CaptureOverlayState
import com.pagebinder.app.preview.FloatingUiPreviewActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 連続撮影中のフローティングUIと通知の受け入れ基準（pagebinder-q6m.4）を、利用者操作の側から確認する
 * （docs/specs/06-auto-capture.md §3.4 / docs/design/06-floating-ui.md / FR-AUTO-005）。
 *
 * オーバーレイは production の [com.pagebinder.app.ui.overlay.CaptureOverlayContent] を、通知は
 * production の [CaptureStatusNotifier] をそのまま使う。オーバーレイ権限と MediaProjection の同意を
 * 実機テストで取れないため、この2つを載せた debug プレビュー画面から操作する。
 */
@RunWith(AndroidJUnit4::class)
class AutoCaptureFloatingUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Android 13 以降、`POST_NOTIFICATIONS` が未許可だと通知は一切出ない。
     * production は撮影準備画面で要求し（ui/captureprep/CapturePrepRoute.kt）、
     * プレビュー画面も同じ許可を要求するが、テストでは許可ダイアログを挟まずに済ませる。
     */
    @Before
    fun grantNotificationPermission() {
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            .close()
        waitForIdle()
    }

    @Test
    fun `連続撮影中と保存枚数がオーバーレイに出る`() {
        ActivityScenario.launch(FloatingUiPreviewActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.capture_overlay_status)
                assertEquals(string(R.string.capture_overlay_continuous_state), status.text.toString())
                assertTrue(status.isShown)
                assertTrue(status.width > 0)

                val count = activity.findViewById<TextView>(R.id.capture_overlay_count)
                assertTrue(count.isShown)
                assertEquals(string(R.string.capture_overlay_saved_count, savedCount(activity)), count.text.toString())

                // 連続モードでは撮影ボタンを出さず、一時停止・停止だけを出す（docs/design/06-floating-ui.md）
                assertTrue(activity.findViewById<View>(R.id.capture_overlay_pause_button).isShown)
                assertTrue(activity.findViewById<View>(R.id.capture_overlay_stop_button).isShown)
                assertTrue(activity.findViewById<View>(R.id.capture_overlay_state_icon).isShown)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.capture_overlay_capture_button).visibility)
            }
        }
    }

    @Test
    fun `一時停止ボタンで一時停止中になり再度押すと連続撮影中へ戻る`() {
        ActivityScenario.launch(FloatingUiPreviewActivity::class.java).use { scenario ->
            waitForIdle()
            clickPause(scenario)
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.capture_overlay_status)
                assertEquals(string(R.string.capture_overlay_paused_state), status.text.toString())
                val pause = activity.findViewById<View>(R.id.capture_overlay_pause_button)
                assertEquals(string(R.string.capture_overlay_resume), pause.contentDescription.toString())
                // 一時停止中も保存済み枚数は出したままにする
                assertTrue(activity.findViewById<View>(R.id.capture_overlay_count).isShown)
            }

            clickPause(scenario)
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.capture_overlay_status)
                assertEquals(string(R.string.capture_overlay_continuous_state), status.text.toString())
                val pause = activity.findViewById<View>(R.id.capture_overlay_pause_button)
                assertEquals(string(R.string.capture_overlay_pause), pause.contentDescription.toString())
            }
        }
    }

    @Test
    fun `停止ボタンで撮影セッションを終える`() {
        ActivityScenario.launch(FloatingUiPreviewActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.capture_overlay_stop_button).performClick()
                // 停止はセッションの終了そのもの。画面が閉じ始めたことをその場で見る
                assertTrue(activity.isFinishing)
            }
        }
    }

    @Test
    fun `保存枚数はサンプル値の固定表示ではなく操作で増える`() {
        ActivityScenario.launch(FloatingUiPreviewActivity::class.java).use { scenario ->
            waitForIdle()
            // 進み続けるカウンタを止めてから数える（一時停止中は自動で増えない）
            clickPause(scenario)

            var before = 0
            scenario.onActivity { activity -> before = savedCount(activity) }
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.capture_overlay_count).performClick()
            }
            waitForIdle()
            scenario.onActivity { activity ->
                val after = savedCount(activity)
                assertTrue("枚数が増えていない: before=$before after=$after", after > before)
                val count = activity.findViewById<TextView>(R.id.capture_overlay_count)
                assertEquals(string(R.string.capture_overlay_saved_count, after), count.text.toString())
            }
        }
    }

    @Test
    fun `連続撮影中は通知シェードに出る常駐通知が実際に立っている`() {
        ActivityScenario.launch(FloatingUiPreviewActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { }

            val manager = context.getSystemService(NotificationManager::class.java)
            val posted =
                manager.activeNotifications.firstOrNull { it.id == CaptureStatusNotifier.NOTIFICATION_ID }
            assertNotNull("撮影中の常駐通知が出ていない", posted)
            val text = posted!!.notification.text()
            assertTrue(text, text.contains(string(R.string.capture_notification_continuous)))
        }
    }

    @Test
    fun `連続撮影中と保存枚数が通知にも出る`() {
        val notifier = CaptureStatusNotifier(context)

        val active = notifier.build(CaptureOverlayState.CONTINUOUS_ACTIVE, savedCount = 24).text()
        assertTrue(active, active.contains(string(R.string.capture_notification_continuous)))
        assertTrue(active, active.contains("24"))

        val paused = notifier.build(CaptureOverlayState.CONTINUOUS_PAUSED, savedCount = 24).text()
        assertTrue(paused, paused.contains(string(R.string.capture_notification_paused)))

        // 停止操作は通知からもできる（docs/specs/06-auto-capture.md §3.4 / UC-03 手順7）
        val notification = notifier.build(CaptureOverlayState.CONTINUOUS_ACTIVE, savedCount = 0)
        val stopLabel = string(R.string.capture_notification_stop)
        assertTrue(notification.actions.orEmpty().any { it.title.toString() == stopLabel })
    }

    @Test
    fun `上限に達して自動停止したことを通知で明示する`() {
        val notifier = CaptureStatusNotifier(context)

        val pages = notifier.buildAutoStopped(AutoCaptureStopReason.MAXIMUM_PAGES)
        assertEquals(string(R.string.capture_notification_auto_stopped_pages), pages?.text())

        val duration = notifier.buildAutoStopped(AutoCaptureStopReason.MAXIMUM_DURATION)
        assertEquals(string(R.string.capture_notification_auto_stopped_duration), duration?.text())

        // 利用者が停止ボタンで止めたときは、自動停止の通知を出さない
        assertNull(notifier.buildAutoStopped(AutoCaptureStopReason.EXPLICIT))
    }

    /** 表示中の「%d枚」から数だけを取り出す */
    private fun savedCount(activity: FloatingUiPreviewActivity): Int {
        val shown = activity.findViewById<TextView>(R.id.capture_overlay_count).text.toString()
        return shown.filter(Char::isDigit).toInt()
    }

    private fun Notification.text(): String = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun clickPause(scenario: ActivityScenario<FloatingUiPreviewActivity>) {
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.capture_overlay_pause_button).performClick()
        }
        waitForIdle()
    }

    private fun waitForIdle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    private fun string(
        resId: Int,
        vararg formatArgs: Any,
    ): String = if (formatArgs.isEmpty()) context.getString(resId) else context.getString(resId, *formatArgs)
}
