package com.pagebinder.app.data

import androidx.room.TypeConverter
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.domain.OcrState
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import java.time.Instant
import java.util.UUID

/** Persists domain value types in the version 2 database's existing TEXT representation. */
class PageBinderTypeConverters {
    @TypeConverter fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter fun instantToString(value: Instant?): String? = value?.toString()

    @TypeConverter fun stringToInstant(value: String?): Instant? = value?.let(Instant::parse)

    @TypeConverter fun pageQualityStateToString(value: PageQualityState?): String? = value?.serializedName

    @TypeConverter
    fun stringToPageQualityState(value: String?): PageQualityState? =
        value?.let { serializedName -> PageQualityState.entries.single { it.serializedName == serializedName } }

    @TypeConverter fun pageOcrStateToString(value: PageOcrState?): String? = value?.serializedName

    @TypeConverter
    fun stringToPageOcrState(value: String?): PageOcrState? =
        value?.let { serializedName -> PageOcrState.entries.single { it.serializedName == serializedName } }

    @TypeConverter fun ocrStateToString(value: OcrState?): String? = value?.serializedName

    @TypeConverter
    fun stringToOcrState(value: String?): OcrState? =
        value?.let { serializedName -> OcrState.entries.single { it.serializedName == serializedName } }

    @TypeConverter fun exportTypeToString(value: ExportType?): String? = value?.serializedName

    @TypeConverter
    fun stringToExportType(value: String?): ExportType? =
        value?.let { serializedName -> ExportType.entries.single { it.serializedName == serializedName } }

    @TypeConverter fun exportStateToString(value: ExportState?): String? = value?.serializedName

    @TypeConverter
    fun stringToExportState(value: String?): ExportState? =
        value?.let { serializedName -> ExportState.entries.single { it.serializedName == serializedName } }
}
