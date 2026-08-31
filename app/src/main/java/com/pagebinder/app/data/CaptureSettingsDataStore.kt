package com.pagebinder.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pagebinder.app.domain.AutoCaptureSensitivity
import com.pagebinder.app.domain.AutoCaptureSettings
import com.pagebinder.app.domain.AutoCaptureSettingsRepository
import com.pagebinder.app.domain.CaptureFeedbackSettings
import com.pagebinder.app.domain.CaptureFeedbackSettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Duration

private val Context.captureSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "capture_settings")

fun createAutoCaptureSettingsRepository(context: Context): AutoCaptureSettingsRepository =
    DataStoreAutoCaptureSettingsRepository(context.applicationContext.captureSettingsDataStore)

fun createCaptureFeedbackSettingsRepository(context: Context): CaptureFeedbackDataStoreRepository =
    CaptureFeedbackDataStoreRepository(context.applicationContext.captureSettingsDataStore)

class DataStoreAutoCaptureSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : AutoCaptureSettingsRepository {
    override suspend fun read(): AutoCaptureSettings {
        val preferences = dataStore.data.first()
        val interval = preferences[MINIMUM_INTERVAL_SECONDS] ?: DEFAULT_INTERVAL_SECONDS
        val pages = preferences[MAXIMUM_PAGES]?.takeIf { it > 0 }
        val duration =
            preferences[MAXIMUM_DURATION_SECONDS]
                ?.takeIf { it > 0 }
                ?.let { seconds -> Duration.ofSeconds(seconds.toLong()) }
        val sensitivity =
            preferences[SENSITIVITY]?.let { saved -> AutoCaptureSensitivity.entries.firstOrNull { it.name == saved } }
                ?: AutoCaptureSensitivity.MEDIUM
        return AutoCaptureSettings(Duration.ofSeconds(interval.toLong()), pages, duration, sensitivity)
    }

    override suspend fun save(settings: AutoCaptureSettings) {
        dataStore.edit { preferences ->
            preferences[MINIMUM_INTERVAL_SECONDS] = settings.minimumInterval.seconds.toInt()
            settings.maximumPages?.let { preferences[MAXIMUM_PAGES] = it } ?: preferences.remove(MAXIMUM_PAGES)
            settings.maximumDuration?.let { preferences[MAXIMUM_DURATION_SECONDS] = it.seconds.toInt() }
                ?: preferences.remove(MAXIMUM_DURATION_SECONDS)
            preferences[SENSITIVITY] = settings.sensitivity.name
        }
    }

    private companion object {
        const val DEFAULT_INTERVAL_SECONDS = 2
        val MINIMUM_INTERVAL_SECONDS = intPreferencesKey("auto_minimum_interval_seconds")
        val MAXIMUM_PAGES = intPreferencesKey("auto_maximum_pages")
        val MAXIMUM_DURATION_SECONDS = intPreferencesKey("auto_maximum_duration_seconds")
        val SENSITIVITY = stringPreferencesKey("auto_sensitivity")
    }
}

class CaptureFeedbackDataStoreRepository(
    private val dataStore: DataStore<Preferences>,
) : CaptureFeedbackSettingsRepository {
    override suspend fun read(): CaptureFeedbackSettings =
        CaptureFeedbackSettings(captureSoundEnabled = dataStore.data.first()[CAPTURE_SOUND_ENABLED] ?: false)

    override suspend fun save(settings: CaptureFeedbackSettings) {
        dataStore.edit { it[CAPTURE_SOUND_ENABLED] = settings.captureSoundEnabled }
    }

    private companion object {
        val CAPTURE_SOUND_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("capture_sound_enabled")
    }
}
