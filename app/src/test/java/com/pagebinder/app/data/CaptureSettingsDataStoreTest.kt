package com.pagebinder.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.pagebinder.app.domain.AutoCaptureSettings
import com.pagebinder.app.domain.CaptureFeedbackSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Duration

class CaptureSettingsDataStoreTest {
    @Test
    fun `auto capture defaults to two seconds`() =
        runBlocking {
            assertEquals(
                Duration.ofSeconds(2),
                DataStoreAutoCaptureSettingsRepository(InMemoryStore()).read().minimumInterval,
            )
        }

    @Test
    fun `auto capture settings reject intervals outside one to thirty seconds`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AutoCaptureSettings(minimumInterval = Duration.ofSeconds(0))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AutoCaptureSettings(minimumInterval = Duration.ofSeconds(31))
        }
    }

    @Test
    fun `capture sound defaults to disabled`() =
        runBlocking {
            val repository = CaptureFeedbackDataStoreRepository(InMemoryStore())
            assertFalse(repository.read().captureSoundEnabled)
            repository.save(CaptureFeedbackSettings(captureSoundEnabled = true))
            assertEquals(CaptureFeedbackSettings(true), repository.read())
        }

    private class InMemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
