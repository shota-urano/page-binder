package com.pagebinder.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.R
import com.pagebinder.app.data.RoomBookProjectRepository
import com.pagebinder.app.data.RoomPageRepository
import com.pagebinder.app.data.TestBookProjectDatabase
import com.pagebinder.app.domain.AutoCaptureSettings
import com.pagebinder.app.domain.AutoCaptureSettingsRepository
import com.pagebinder.app.domain.CaptureFeedbackSettings
import com.pagebinder.app.domain.CaptureFeedbackSettingsRepository
import com.pagebinder.app.domain.ExportStarter
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.storage.FileProjectFileStore
import com.pagebinder.app.ui.bookdetail.BOOK_DETAIL_OCR_COMPLETED_TEST_TAG
import com.pagebinder.app.ui.bookdetail.BOOK_DETAIL_OCR_ERROR_TEST_TAG
import com.pagebinder.app.ui.bookdetail.BOOK_DETAIL_PAGE_COUNT_TEST_TAG
import com.pagebinder.app.ui.bookdetail.BOOK_DETAIL_STORAGE_TEST_TAG
import com.pagebinder.app.ui.consent.ConsentGate
import com.pagebinder.app.ui.consent.ConsentUiState
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.theme.PageBinderTheme
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * pagebinder-fu6 の受け入れ基準を実機（エミュレータ）で確かめる。
 *
 * 「撮影でページが増えたとき、書籍詳細を離れずに統計が更新され書き出しが有効化される」ことが
 * 主眼。撮影そのもの（MediaProjection）は OS の許可ダイアログを伴い自動検証できないので、
 * ここでは撮影の成果——docs/specs/05-manual-capture.md §3.1 手順6-7 の「画像を保存してから
 * Page を登録する」——を書籍詳細を前面に置いたまま実行し、画面が追従することを見る。
 * DB・ファイル領域は本番と同じ Room / [FileProjectFileStore] を使う。
 */
@RunWith(AndroidJUnit4::class)
class BookDetailStatisticsUpdateTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val projectId = UUID.fromString("60000000-0000-0000-0000-000000000001")
    private val firstPageId = UUID.fromString("60000000-0000-0000-0000-000000000101")
    private val secondPageId = UUID.fromString("60000000-0000-0000-0000-000000000102")

    private lateinit var filesDirectory: File
    private lateinit var database: TestBookProjectDatabase
    private lateinit var fileStore: FileProjectFileStore
    private lateinit var bookProjectRepository: RoomBookProjectRepository
    private lateinit var pageRepository: RoomPageRepository

    @Before
    fun setUp() {
        filesDirectory = File(context.cacheDir, "book-detail-statistics-${UUID.randomUUID()}")
        check(filesDirectory.mkdirs()) { "テスト用のファイル領域を作れませんでした" }
        database =
            Room
                .inMemoryDatabaseBuilder(context, TestBookProjectDatabase::class.java)
                .build()
        fileStore = FileProjectFileStore(filesDirectory)
        bookProjectRepository =
            RoomBookProjectRepository(
                dao = database.bookProjectDao(),
                fileStore = fileStore,
                now = { CREATED_AT },
                newId = { projectId },
            )
        pageRepository = RoomPageRepository(database.pageDao())
        runBlocking { bookProjectRepository.create(BOOK_TITLE, null, null) }
    }

    @After
    fun tearDown() {
        database.close()
        filesDirectory.deleteRecursively()
    }

    @Test
    fun `撮影でページが増えると書籍詳細を離れずに統計が更新され書き出しが有効になる`() {
        showApp()
        openBookDetail()

        assertStatistics(pageCount = "0", storage = "0 B")
        composeTestRule
            .onNodeWithText(string(R.string.book_detail_export))
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule
            .onNodeWithText(string(R.string.book_detail_export_unavailable))
            .performScrollTo()
            .assertIsDisplayed()

        capturePage(firstPageId, sequence = 1)

        awaitStatistic(BOOK_DETAIL_PAGE_COUNT_TEST_TAG, "1")
        awaitStatistic(BOOK_DETAIL_STORAGE_TEST_TAG, formatStorageBytes(IMAGE_BYTES.toLong()))
        // 書籍詳細のままで更新された（撮影導線がそのまま出ている＝画面を離れていない）
        composeTestRule.onNodeWithText(string(R.string.book_detail_manual_capture)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.book_detail_export))
            .performScrollTo()
            .assertIsEnabled()
        composeTestRule.onNodeWithText(string(R.string.book_detail_export_unavailable)).assertDoesNotExist()

        capturePage(secondPageId, sequence = 2)

        awaitStatistic(BOOK_DETAIL_PAGE_COUNT_TEST_TAG, "2")
        awaitStatistic(BOOK_DETAIL_STORAGE_TEST_TAG, formatStorageBytes(2L * IMAGE_BYTES))
        composeTestRule.onNodeWithText(string(R.string.book_detail_manual_capture)).assertIsDisplayed()
    }

    @Test
    fun `ページを削除すると書籍詳細を離れずに統計が戻り書き出しが無効になる`() {
        showApp()
        openBookDetail()

        capturePage(firstPageId, sequence = 1)
        awaitStatistic(BOOK_DETAIL_PAGE_COUNT_TEST_TAG, "1")

        deletePage(firstPageId)

        awaitStatistic(BOOK_DETAIL_PAGE_COUNT_TEST_TAG, "0")
        awaitStatistic(BOOK_DETAIL_STORAGE_TEST_TAG, "0 B")
        composeTestRule.onNodeWithText(string(R.string.book_detail_manual_capture)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.book_detail_export))
            .performScrollTo()
            .assertIsNotEnabled()
    }

    /**
     * 1ページ分の撮影結果を書き込む（docs/specs/05-manual-capture.md §3.1 手順6→7 の順）。
     * 書籍詳細を前面に置いたまま呼ぶことが、この検証の要点。
     */
    private fun capturePage(
        pageId: UUID,
        sequence: Int,
    ) {
        val image = File(filesDirectory, "projects/$projectId/images/$pageId.webp")
        checkNotNull(image.parentFile) { "画像の保存先が決まりません" }.mkdirs()
        image.writeBytes(ByteArray(IMAGE_BYTES))
        runBlocking { pageRepository.insert(page(pageId, sequence)) }
    }

    private fun deletePage(pageId: UUID) {
        File(filesDirectory, "projects/$projectId/images/$pageId.webp").delete()
        runBlocking { pageRepository.delete(projectId, setOf(pageId)) }
    }

    private fun assertStatistics(
        pageCount: String,
        storage: String,
    ) {
        awaitStatistic(BOOK_DETAIL_PAGE_COUNT_TEST_TAG, pageCount)
        awaitStatistic(BOOK_DETAIL_STORAGE_TEST_TAG, storage)
        awaitStatistic(BOOK_DETAIL_OCR_COMPLETED_TEST_TAG, "0")
        awaitStatistic(BOOK_DETAIL_OCR_ERROR_TEST_TAG, "0")
    }

    private fun awaitStatistic(
        testTag: String,
        value: String,
    ) {
        composeTestRule.waitUntil("統計「$testTag」が $value にならない", TIMEOUT_MILLIS) {
            runCatching {
                composeTestRule.onNodeWithTag(testTag, useUnmergedTree = true).assertTextEquals(value)
            }.isSuccess
        }
    }

    private fun openBookDetail() {
        awaitText(R.string.home_search_hint)
        composeTestRule.waitUntil("ホームに書籍が出ない", TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(BOOK_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(BOOK_TITLE).performClick()
        awaitText(R.string.book_detail_manual_capture)
    }

    private fun showApp() {
        composeTestRule.setContent {
            PageBinderTheme {
                PageBinderApp(
                    uiState = ConsentUiState(gate = ConsentGate.Unlocked),
                    onAgree = {},
                    onDecline = {},
                    bookProjectRepository = bookProjectRepository,
                    pageRepository = pageRepository,
                    pageThumbnailLoader = PageThumbnailLoader { ImageBitmap(THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX) },
                    exportStarter = ExportStarter { emptyFlow() },
                    enqueueProjectOcr = { 0 },
                    // 未完了の書き出しの提示はこのテストの対象外（PageBinderAppNavigationTest で見る）
                    findInterruptedExports = { emptyList() },
                    autoCaptureSettingsRepository = FakeAutoCaptureSettingsRepository(),
                    captureFeedbackSettingsRepository = FakeCaptureFeedbackSettingsRepository(),
                    // 撮影の開始（MediaProjection）は OS の許可が要るのでここでは行わない
                    startCapture = { _, _ -> },
                )
            }
        }
    }

    private fun page(
        id: UUID,
        sequence: Int,
    ) = Page(
        id = id,
        projectId = projectId,
        sequence = sequence,
        originalImagePath = "projects/$projectId/images/$id.webp",
        width = 1_080,
        height = 2_400,
        rotation = 0,
        crop = PageCrop(),
        capturedAt = CREATED_AT,
        contentHash = "content-$sequence",
        perceptualHash = "perceptual-$sequence",
        qualityState = PageQualityState.NORMAL,
        ocrState = PageOcrState.PENDING,
    )

    private fun string(resId: Int): String = context.getString(resId)

    private fun awaitText(resId: Int) {
        val text = string(resId)
        composeTestRule.waitUntil("画面に「$text」が出ない", TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class FakeAutoCaptureSettingsRepository : AutoCaptureSettingsRepository {
        override suspend fun read(): AutoCaptureSettings = AutoCaptureSettings()

        override suspend fun save(settings: AutoCaptureSettings) = Unit
    }

    private class FakeCaptureFeedbackSettingsRepository : CaptureFeedbackSettingsRepository {
        override suspend fun read(): CaptureFeedbackSettings = CaptureFeedbackSettings()

        override suspend fun save(settings: CaptureFeedbackSettings) = Unit
    }

    private companion object {
        const val BOOK_TITLE = "統計自動更新の検証"
        const val TIMEOUT_MILLIS = 10_000L
        const val THUMBNAIL_SIZE_PX = 8
        const val IMAGE_BYTES = 4_096
        val CREATED_AT: Instant = Instant.parse("2026-09-03T00:00:00Z")
    }
}
