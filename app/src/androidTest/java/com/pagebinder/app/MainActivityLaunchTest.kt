package com.pagebinder.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.data.createConsentRepository
import com.pagebinder.app.domain.ConsentRecord
import com.pagebinder.app.legal.CURRENT_CONSENT_WORDING
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * ランチャーの入口（[MainActivity]）が本番の依存を組み立てて起動できることを見る。
 *
 * 起動後に出る画面は端末に残った同意履歴で変わる（未同意なら同意画面、同意済みなら書籍一覧）ので、
 * このテストは前提となる同意履歴を自分で用意してから起動する。端末に前回の実行が残した状態が
 * あってもなくても同じ経路を通す（pagebinder-ons: 端末状態依存でフルスイート時のみ落ちた）。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    /** Activity は [ActivityScenario] 側で起動するので、Compose 階層だけを見るルールを使う */
    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun agreeToConsentBeforeLaunch() =
        runBlocking {
            createConsentRepository(context).saveConsent(
                ConsentRecord(
                    consentedAt = Instant.now(),
                    wordingVersion = CURRENT_CONSENT_WORDING.version,
                ),
            )
        }

    @Test
    fun launches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertNotNull(activity) }
            // 同意ゲートを抜けた本体（書籍一覧）まで描けていること。書籍DBも前回の実行を引き継がず
            // 空から始まるので、一覧は必ず空表示になる。
            // 同意履歴・書籍DBの読み出しは Compose の待機対象外なので明示的に待つ。
            awaitText(R.string.home_search_hint)
            awaitText(R.string.home_empty)
        }
    }

    private fun awaitText(resId: Int) {
        val text = context.getString(resId)
        composeTestRule.waitUntil("画面に「$text」が出ない", LAUNCH_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        /** 起動直後・高負荷なエミュレータでも待ち切れる幅を取る（描けた時点で先へ進むので実行時間は延びない） */
        const val LAUNCH_TIMEOUT_MILLIS = 30_000L
    }
}
