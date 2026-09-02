package com.pagebinder.app.domain

/**
 * Persistence boundary for application settings used by continuous capture.
 *
 * The implementation owns the DataStore dependency; callers only work with domain settings.
 */
interface SettingsRepository {
    suspend fun read(): AutoCaptureSettings

    suspend fun save(settings: AutoCaptureSettings)
}

/**
 * Compatibility name for existing capture consumers.
 *
 * `SettingsRepository` is the architecture port defined in docs/specs/01-architecture.md §3.3.
 */
typealias AutoCaptureSettingsRepository = SettingsRepository
