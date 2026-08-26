package com.pagebinder.app.domain

import java.time.Instant

data class ConsentRecord(
    val consentedAt: Instant,
    val wordingVersion: String,
)

interface ConsentRepository {
    /** Returns null when no valid consent record exists or the record cannot be read. */
    suspend fun getConsent(): ConsentRecord?

    suspend fun saveConsent(record: ConsentRecord)
}
