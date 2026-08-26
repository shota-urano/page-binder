package com.pagebinder.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.pagebinder.app.domain.ConsentRepository

private val Context.consentDataStore: DataStore<Preferences> by preferencesDataStore(name = "consent")

/** 同意履歴の保存先（端末内 DataStore — docs/specs/12-legal-guardrails.md §4）を組み立てる。 */
fun createConsentRepository(context: Context): ConsentRepository =
    DataStoreConsentRepository(context.applicationContext.consentDataStore)
