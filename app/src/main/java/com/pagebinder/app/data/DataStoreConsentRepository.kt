package com.pagebinder.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pagebinder.app.domain.ConsentRecord
import com.pagebinder.app.domain.ConsentRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.concurrent.CancellationException

class DataStoreConsentRepository(
    private val dataStore: DataStore<Preferences>,
) : ConsentRepository {
    override suspend fun getConsent(): ConsentRecord? =
        try {
            val preferences = dataStore.data.first()
            val consentedAtEpochMillis = preferences[CONSENTED_AT_EPOCH_MILLIS] ?: return null
            val wordingVersion = preferences[WORDING_VERSION] ?: return null

            ConsentRecord(
                consentedAt = Instant.ofEpochMilli(consentedAtEpochMillis),
                wordingVersion = wordingVersion,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    override suspend fun saveConsent(record: ConsentRecord) {
        dataStore.edit { preferences ->
            preferences[CONSENTED_AT_EPOCH_MILLIS] = record.consentedAt.toEpochMilli()
            preferences[WORDING_VERSION] = record.wordingVersion
        }
    }

    private companion object {
        val CONSENTED_AT_EPOCH_MILLIS = longPreferencesKey("consented_at_epoch_millis")
        val WORDING_VERSION = stringPreferencesKey("wording_version")
    }
}
