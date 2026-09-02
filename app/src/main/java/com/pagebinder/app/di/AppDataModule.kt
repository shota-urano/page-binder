package com.pagebinder.app.di

import android.content.Context
import androidx.room.Room
import com.pagebinder.app.capture.AndroidCaptureGateway
import com.pagebinder.app.data.BookProjectDao
import com.pagebinder.app.data.ExportRecordDao
import com.pagebinder.app.data.OcrJobDao
import com.pagebinder.app.data.OcrResultDao
import com.pagebinder.app.data.PageBinderDatabase
import com.pagebinder.app.data.PageDao
import com.pagebinder.app.data.RoomBookProjectRepository
import com.pagebinder.app.data.RoomExportRecordRepository
import com.pagebinder.app.data.RoomOcrJobRepository
import com.pagebinder.app.data.RoomOcrResultRepository
import com.pagebinder.app.data.RoomPageRepository
import com.pagebinder.app.data.createAutoCaptureSettingsRepository
import com.pagebinder.app.data.createConsentRepository
import com.pagebinder.app.domain.AutoCaptureSettingsRepository
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.CaptureGateway
import com.pagebinder.app.domain.ConsentRepository
import com.pagebinder.app.domain.ExportRecordRepository
import com.pagebinder.app.domain.ExportStorageGateway
import com.pagebinder.app.domain.ImageStore
import com.pagebinder.app.domain.OcrGateway
import com.pagebinder.app.domain.OcrJobRepository
import com.pagebinder.app.domain.OcrResultRepository
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.domain.PdfGateway
import com.pagebinder.app.export.PdfBoxPdfGateway
import com.pagebinder.app.ocr.MlKitOcrGateway
import com.pagebinder.app.storage.FileImageStore
import com.pagebinder.app.storage.FileProjectFileStore
import com.pagebinder.app.storage.ProjectFileStore
import com.pagebinder.app.storage.SafExportStorageGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Process-wide bindings for the domain repository and gateway ports in
 * docs/specs/01-architecture.md section 3.3.
 *
 * Application-level composition still owns capture-session orchestration. New consumers should
 * receive these ports through Hilt instead of creating framework adapters directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppDataModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): PageBinderDatabase =
        Room
            .databaseBuilder(context, PageBinderDatabase::class.java, DATABASE_NAME)
            .addMigrations(PageBinderDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideBookProjectDao(database: PageBinderDatabase): BookProjectDao = database.bookProjectDao()

    @Provides
    fun providePageDao(database: PageBinderDatabase): PageDao = database.pageDao()

    @Provides
    fun provideOcrJobDao(database: PageBinderDatabase): OcrJobDao = database.ocrJobDao()

    @Provides
    fun provideOcrResultDao(database: PageBinderDatabase): OcrResultDao = database.ocrResultDao()

    @Provides
    fun provideExportRecordDao(database: PageBinderDatabase): ExportRecordDao = database.exportRecordDao()

    @Provides
    @Singleton
    fun provideProjectFileStore(
        @ApplicationContext context: Context,
    ): ProjectFileStore = FileProjectFileStore(context.filesDir)

    @Provides
    @Singleton
    fun provideBookProjectRepository(
        dao: BookProjectDao,
        fileStore: ProjectFileStore,
    ): BookProjectRepository = RoomBookProjectRepository(dao, fileStore)

    @Provides
    @Singleton
    fun providePageRepository(dao: PageDao): PageRepository = RoomPageRepository(dao)

    @Provides
    @Singleton
    fun provideOcrJobRepository(dao: OcrJobDao): OcrJobRepository = RoomOcrJobRepository(dao)

    @Provides
    @Singleton
    fun provideOcrResultRepository(dao: OcrResultDao): OcrResultRepository = RoomOcrResultRepository(dao)

    @Provides
    @Singleton
    fun provideExportRecordRepository(dao: ExportRecordDao): ExportRecordRepository = RoomExportRecordRepository(dao)

    @Provides
    @Singleton
    fun provideImageStore(
        @ApplicationContext context: Context,
    ): FileImageStore = FileImageStore(context.filesDir)

    @Provides
    fun bindImageStore(imageStore: FileImageStore): ImageStore = imageStore

    @Provides
    @Singleton
    fun provideCaptureGateway(
        @ApplicationContext context: Context,
    ): CaptureGateway = AndroidCaptureGateway(context)

    @Provides
    @Singleton
    fun provideOcrGateway(): OcrGateway = MlKitOcrGateway()

    @Provides
    @Singleton
    fun providePdfGateway(
        @ApplicationContext context: Context,
    ): PdfGateway = PdfBoxPdfGateway(context)

    @Provides
    @Singleton
    fun provideExportStorageGateway(
        @ApplicationContext context: Context,
    ): ExportStorageGateway = SafExportStorageGateway(context.contentResolver)

    @Provides
    @Singleton
    fun provideAutoCaptureSettingsRepository(
        @ApplicationContext context: Context,
    ): AutoCaptureSettingsRepository = createAutoCaptureSettingsRepository(context)

    @Provides
    @Singleton
    fun provideConsentRepository(
        @ApplicationContext context: Context,
    ): ConsentRepository = createConsentRepository(context)

    private const val DATABASE_NAME = "pagebinder.db"
}
