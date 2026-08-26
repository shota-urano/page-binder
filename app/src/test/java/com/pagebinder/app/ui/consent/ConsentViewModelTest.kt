package com.pagebinder.app.ui.consent

import com.pagebinder.app.domain.ConsentRecord
import com.pagebinder.app.domain.ConsentRepository
import com.pagebinder.app.legal.CURRENT_CONSENT_WORDING
import com.pagebinder.app.legal.ConsentTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentViewModelTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-26T04:05:06Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `未同意なら同意画面のままで主要機能へ進めない`() =
        runTest {
            val viewModel = createViewModel(FakeConsentRepository())

            val uiState = viewModel.uiState.value
            assertEquals(ConsentGate.ConsentRequired, uiState.gate)
            assertFalse(uiState.canEnterMainFeatures)
        }

    @Test
    fun `同意履歴が読めない場合も未同意扱いで主要機能へ進めない`() =
        runTest {
            // getConsent() は読み込み失敗時に null を返す契約（specs 12 §6 の安全側フォールバック）
            val viewModel = createViewModel(FakeConsentRepository(stored = null))

            assertEquals(ConsentGate.ConsentRequired, viewModel.uiState.value.gate)
            assertFalse(viewModel.uiState.value.canEnterMainFeatures)
        }

    @Test
    fun `同意済みで起動すると主要機能へ進める`() =
        runTest {
            val repository =
                FakeConsentRepository(
                    stored =
                        ConsentRecord(
                            consentedAt = Instant.parse("2026-01-01T00:00:00Z"),
                            wordingVersion = CURRENT_CONSENT_WORDING.version,
                        ),
                )

            val viewModel = createViewModel(repository)

            assertEquals(ConsentGate.Unlocked, viewModel.uiState.value.gate)
            assertTrue(viewModel.uiState.value.canEnterMainFeatures)
        }

    @Test
    fun `同意すると同意日時と文言バージョンを保存して主要機能を解放する`() =
        runTest {
            val repository = FakeConsentRepository()
            val viewModel = createViewModel(repository)

            viewModel.onAgree()

            assertEquals(
                ConsentRecord(
                    consentedAt = Instant.parse("2026-08-26T04:05:06Z"),
                    wordingVersion = CURRENT_CONSENT_WORDING.version,
                ),
                repository.stored,
            )
            assertEquals(ConsentGate.Unlocked, viewModel.uiState.value.gate)
            assertFalse(viewModel.uiState.value.saving)
        }

    @Test
    fun `同意しないを選んでも主要機能へ進めない`() =
        runTest {
            val repository = FakeConsentRepository()
            val viewModel = createViewModel(repository)

            viewModel.onDecline()

            assertEquals(ConsentGate.ConsentRequired, viewModel.uiState.value.gate)
            assertFalse(viewModel.uiState.value.canEnterMainFeatures)
            assertTrue(viewModel.uiState.value.declineNoticeVisible)
            assertNull(repository.stored)
        }

    @Test
    fun `同意の保存に失敗したら主要機能を解放しない`() =
        runTest {
            val repository = FakeConsentRepository(failOnSave = true)
            val viewModel = createViewModel(repository)

            viewModel.onAgree()

            assertEquals(ConsentGate.ConsentRequired, viewModel.uiState.value.gate)
            assertFalse(viewModel.uiState.value.canEnterMainFeatures)
            assertTrue(viewModel.uiState.value.saveFailed)
            assertFalse(viewModel.uiState.value.saving)
        }

    @Test
    fun `同意画面のUiStateは仕様の4点をすべて含む`() =
        runTest {
            val viewModel = createViewModel(FakeConsentRepository())

            assertEquals(
                ConsentTerm.entries.toList(),
                viewModel.uiState.value.wording.terms,
            )
        }

    private fun createViewModel(repository: ConsentRepository) =
        ConsentViewModel(
            consentRepository = repository,
            wording = CURRENT_CONSENT_WORDING,
            clock = fixedClock,
        )

    private class FakeConsentRepository(
        var stored: ConsentRecord? = null,
        private val failOnSave: Boolean = false,
    ) : ConsentRepository {
        override suspend fun getConsent(): ConsentRecord? = stored

        override suspend fun saveConsent(record: ConsentRecord) {
            if (failOnSave) throw IOException("write failed")
            stored = record
        }
    }
}
