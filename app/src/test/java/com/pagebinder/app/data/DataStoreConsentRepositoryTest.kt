package com.pagebinder.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.pagebinder.app.domain.ConsentRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.time.Instant

class DataStoreConsentRepositoryTest {
    @Test
    fun `returns not consented when no consent has been saved`() =
        runBlocking {
            val repository = DataStoreConsentRepository(InMemoryPreferencesDataStore())

            assertNull(repository.getConsent())
        }

    @Test
    fun `returns saved consent timestamp and wording version`() =
        runBlocking {
            val repository = DataStoreConsentRepository(InMemoryPreferencesDataStore())
            val consent =
                ConsentRecord(
                    consentedAt = Instant.parse("2026-08-26T01:23:45Z"),
                    wordingVersion = "legal-consent-v1",
                )

            repository.saveConsent(consent)

            assertEquals(consent, repository.getConsent())
        }

    @Test
    fun `returns not consented when reading preferences fails`() =
        runBlocking {
            val failingDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> = flow { throw IOException("read failed") }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                        error("updateData must not be called")
                }
            val repository = DataStoreConsentRepository(failingDataStore)

            assertNull(repository.getConsent())
        }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
