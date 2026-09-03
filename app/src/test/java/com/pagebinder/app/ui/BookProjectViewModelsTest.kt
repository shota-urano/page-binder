package com.pagebinder.app.ui

import com.pagebinder.app.domain.BookProject
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.BookProjectSort
import com.pagebinder.app.domain.BookProjectSummary
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.domain.InterruptedExport
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.ui.bookdetail.BookDetailOperationError
import com.pagebinder.app.ui.bookdetail.BookDetailViewModel
import com.pagebinder.app.ui.bookedit.BookEditFieldError
import com.pagebinder.app.ui.bookedit.BookEditOperationError
import com.pagebinder.app.ui.bookedit.BookEditViewModel
import com.pagebinder.app.ui.home.HomeViewModel
import com.pagebinder.app.ui.trash.TrashOperationError
import com.pagebinder.app.ui.trash.TrashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
abstract class BookProjectViewModelTestBase {
    @Before
    fun setUpDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetDispatcher() {
        Dispatchers.resetMain()
    }
}

class HomeViewModelTest : BookProjectViewModelTestBase() {
    @Test
    fun `empty data and normalized search produce immutable UI transitions`() =
        runTest {
            val repository = FakeBookProjectRepository()
            val viewModel = HomeViewModel(repository)
            assertTrue(viewModel.uiState.value.empty)

            repository.active =
                mutableListOf(
                    summary(title = "ＡＢＣ入門", author = "著者 One", updatedAt = Instant.parse("2026-08-30T01:00:00Z")),
                    summary(title = "別の本", author = "Someone", updatedAt = Instant.parse("2026-08-30T02:00:00Z")),
                )
            val firstBook = repository.active.first()
            val firstPage = page(firstBook.project.id)
            val viewModelWithThumbnails =
                HomeViewModel(repository) { projectId -> firstPage.takeIf { it.projectId == projectId } }
            assertEquals(listOf("別の本", "ＡＢＣ入門"), viewModelWithThumbnails.uiState.value.books.map { it.title })
            assertEquals(
                firstPage.id,
                viewModelWithThumbnails.uiState.value.books.single { it.title == "ＡＢＣ入門" }.firstPageId,
            )

            viewModelWithThumbnails.onQueryChange("abc")
            assertEquals(listOf("ＡＢＣ入門"), viewModelWithThumbnails.uiState.value.books.map { it.title })
            assertEquals("abc", viewModelWithThumbnails.uiState.value.query)
        }

    @Test
    fun `default sort is updated descending and every selectable sort reaches repository`() =
        runTest {
            val repository = FakeBookProjectRepository()
            val viewModel = HomeViewModel(repository)
            assertEquals(BookProjectSort.UPDATED_AT, viewModel.uiState.value.sort)

            viewModel.onSortChange(BookProjectSort.CREATED_AT)
            viewModel.onSortChange(BookProjectSort.TITLE)

            assertEquals(
                listOf(BookProjectSort.UPDATED_AT, BookProjectSort.CREATED_AT, BookProjectSort.TITLE),
                repository.listSortCalls,
            )
        }
}

class BookEditViewModelTest : BookProjectViewModelTestBase() {
    @Test
    fun `blank and every over-limit field block save`() =
        runTest {
            val repository = FakeBookProjectRepository()
            val viewModel = BookEditViewModel(null, repository)
            viewModel.save()
            assertEquals(BookEditFieldError.REQUIRED, viewModel.uiState.value.titleError)

            viewModel.onTitleChange("x".repeat(201))
            assertEquals(BookEditFieldError.TOO_LONG, viewModel.uiState.value.titleError)
            viewModel.onTitleChange("Valid")
            viewModel.onAuthorChange("x".repeat(201))
            assertEquals(BookEditFieldError.TOO_LONG, viewModel.uiState.value.authorError)
            viewModel.onAuthorChange("")
            viewModel.onNoteChange("x".repeat(2_001))
            assertEquals(BookEditFieldError.TOO_LONG, viewModel.uiState.value.noteError)
            assertFalse(viewModel.uiState.value.canSave)
            assertTrue(repository.createCalls.isEmpty())
        }

    @Test
    fun `create and update failures remain represented in UI state`() =
        runTest {
            val createRepository = FakeBookProjectRepository(failCreate = true)
            val createViewModel = BookEditViewModel(null, createRepository)
            createViewModel.onTitleChange("New")
            createViewModel.save()
            assertEquals(BookEditOperationError.CREATE, createViewModel.uiState.value.operationError)

            val existing = summary(title = "Old")
            val updateRepository =
                FakeBookProjectRepository(active = mutableListOf(existing), failUpdate = true)
            val updateViewModel = BookEditViewModel(existing.project.id, updateRepository)
            updateViewModel.onTitleChange("Changed")
            updateViewModel.save()
            assertEquals(BookEditOperationError.UPDATE, updateViewModel.uiState.value.operationError)
        }

    @Test
    fun `failed edit load cannot be converted into an update attempt`() =
        runTest {
            val projectId = UUID.fromString("10000000-0000-0000-0000-000000000099")
            val repository = FakeBookProjectRepository(failFind = true)
            val viewModel = BookEditViewModel(projectId, repository)

            assertEquals(BookEditOperationError.LOAD, viewModel.uiState.value.operationError)
            viewModel.onTitleChange("Changed")
            assertFalse(viewModel.uiState.value.canSave)
            viewModel.save()
            assertTrue(repository.updateCalls.isEmpty())
        }
}

class BookDetailViewModelTest : BookProjectViewModelTestBase() {
    @Test
    fun `statistics and deletion target data come from repository summary`() =
        runTest {
            val summary =
                summary(
                    title = "Detail",
                    pageCount = 12,
                    storageBytes = 28L * 1_048_576,
                    ocrCompleted = 9,
                    ocrErrors = 2,
                )
            val viewModel =
                BookDetailViewModel(
                    projectId = summary.project.id,
                    repository = FakeBookProjectRepository(active = mutableListOf(summary)),
                    enqueueProjectOcr = { 0 },
                    findInterruptedExports = { emptyList() },
                )

            val state = viewModel.uiState.value
            assertEquals(12, state.pageCount)
            assertEquals(9, state.ocrCompletedCount)
            assertEquals(2, state.ocrErrorCount)
            assertEquals(28L * 1_048_576, state.storageBytes)

            viewModel.onMoveToTrashRequested()
            assertEquals("Detail", viewModel.uiState.value.moveToTrashConfirmation?.title)
            assertEquals(12, viewModel.uiState.value.moveToTrashConfirmation?.pageCount)
            assertEquals(28L * 1_048_576, viewModel.uiState.value.moveToTrashConfirmation?.storageBytes)
        }

    @Test
    fun `capture and deletion update statistics without leaving the screen`() =
        runTest {
            val empty = summary(title = "Detail", pageCount = 0, storageBytes = 0)
            val repository = FakeBookProjectRepository(active = mutableListOf(empty))
            val viewModel = BookDetailViewModel(empty.project.id, repository, { 0 }) { emptyList() }

            // 撮影前: 書き出しは無効（1ページも無い書籍には成果物が無い）
            assertEquals(0, viewModel.uiState.value.pageCount)
            assertEquals(0L, viewModel.uiState.value.storageBytes)
            assertFalse(viewModel.uiState.value.exportAvailable)

            // 撮影オーバーレイで2ページ増えた（書籍詳細は前面に出たまま・load を呼び直さない）
            repository.publish(empty.copy(pageCount = 2, storageBytes = 634_061, ocrCompletedCount = 1))

            val captured = viewModel.uiState.value
            assertEquals(2, captured.pageCount)
            assertEquals(634_061L, captured.storageBytes)
            assertEquals(1, captured.ocrCompletedCount)
            assertTrue(captured.exportAvailable)

            // ページ削除でも同じ購読で戻る
            repository.publish(empty.copy(pageCount = 0, storageBytes = 0, ocrCompletedCount = 0))

            assertEquals(0, viewModel.uiState.value.pageCount)
            assertFalse(viewModel.uiState.value.exportAvailable)
        }

    @Test
    fun `statistics keep updating while a confirmation dialog is open`() =
        runTest {
            val initial = summary(title = "Detail", pageCount = 1, storageBytes = 1_000)
            val repository = FakeBookProjectRepository(active = mutableListOf(initial))
            val viewModel = BookDetailViewModel(initial.project.id, repository, { 0 }) { emptyList() }

            viewModel.onMoveToTrashRequested()
            repository.publish(initial.copy(pageCount = 3, storageBytes = 3_000))

            assertEquals(3, viewModel.uiState.value.pageCount)
            // 確認ダイアログは購読の再発行で閉じない（表示値は要求時点のスナップショット）
            assertEquals(1, viewModel.uiState.value.moveToTrashConfirmation?.pageCount)
        }

    @Test
    fun `OCR failure remains represented`() =
        runTest {
            val summary = summary(title = "Detail")
            val viewModel =
                BookDetailViewModel(
                    projectId = summary.project.id,
                    repository = FakeBookProjectRepository(active = mutableListOf(summary)),
                    enqueueProjectOcr = { throw IOException() },
                    findInterruptedExports = { emptyList() },
                )
            viewModel.onOcrBatchRequested()
            assertEquals(BookDetailOperationError.OCR_BATCH, viewModel.uiState.value.operationError)
            assertFalse(viewModel.uiState.value.operationInProgress)
        }

    /**
     * 未完了の提示はレコードの実状態に従う（docs/specs/11-export.md §3.2 末尾）。
     * 「再試行を押した」ことでは消えない — 書き出し画面から戻る・SAF を閉じるだけなら
     * レコードは queued / running のまま残っており、再試行の導線を失わせてはならない。
     */
    @Test
    fun `未完了の提示は再試行では消えずレコードが残る限り出続ける`() =
        runTest {
            val summary = summary(title = "Detail", pageCount = 3)
            val markdown = InterruptedExport(UUID.randomUUID(), ExportType.MARKDOWN)
            val imageZip = InterruptedExport(UUID.randomUUID(), ExportType.IMAGE_ZIP)
            val incomplete = mutableListOf(markdown, imageZip)
            var detectCalls = 0
            val viewModel =
                BookDetailViewModel(
                    projectId = summary.project.id,
                    repository = FakeBookProjectRepository(active = mutableListOf(summary)),
                    enqueueProjectOcr = { 0 },
                    findInterruptedExports = {
                        detectCalls++
                        incomplete.toList()
                    },
                )

            // 書籍詳細を開いた時点で検出が走り、最も古い未完了が再試行対象になる
            assertEquals(1, detectCalls)
            assertEquals(markdown.recordId, viewModel.uiState.value.interruptedExport?.recordId)
            assertEquals(ExportType.MARKDOWN, viewModel.uiState.value.interruptedExport?.format)
            assertEquals(2, viewModel.uiState.value.interruptedExport?.count)

            // 統計の更新では提示が消えない
            viewModel.onOcrBatchRequested()
            assertNotNull(viewModel.uiState.value.interruptedExport)

            // 再試行 → 書き出し画面から戻る（レコードは未完了のまま）。提示は同じ対象で出続ける
            viewModel.load()
            assertEquals(2, detectCalls)
            assertEquals(markdown.recordId, viewModel.uiState.value.interruptedExport?.recordId)
            assertEquals(2, viewModel.uiState.value.interruptedExport?.count)
        }

    /** 複数件は古い順に1件ずつ。1件解消しても残りの提示と再試行は失われない */
    @Test
    fun `未完了が複数あるとき1件解消すると残りが提示される`() =
        runTest {
            val summary = summary(title = "Detail", pageCount = 3)
            val markdown = InterruptedExport(UUID.randomUUID(), ExportType.MARKDOWN)
            val imageZip = InterruptedExport(UUID.randomUUID(), ExportType.IMAGE_ZIP)
            val incomplete = mutableListOf(markdown, imageZip)
            val viewModel =
                BookDetailViewModel(
                    projectId = summary.project.id,
                    repository = FakeBookProjectRepository(active = mutableListOf(summary)),
                    enqueueProjectOcr = { 0 },
                    findInterruptedExports = { incomplete.toList() },
                )

            assertEquals(2, viewModel.uiState.value.interruptedExport?.count)

            // 再試行が成功して最も古いレコードが終端した（検出から外れる）
            incomplete.remove(markdown)
            viewModel.load()

            assertEquals(imageZip.recordId, viewModel.uiState.value.interruptedExport?.recordId)
            assertEquals(ExportType.IMAGE_ZIP, viewModel.uiState.value.interruptedExport?.format)
            assertEquals(1, viewModel.uiState.value.interruptedExport?.count)

            // 残り1件も解消したら提示が消える
            incomplete.remove(imageZip)
            viewModel.load()

            assertNull(viewModel.uiState.value.interruptedExport)
        }

    @Test
    fun `detection failure leaves the screen without an interrupted export`() =
        runTest {
            val summary = summary(title = "Detail")
            val viewModel =
                BookDetailViewModel(
                    projectId = summary.project.id,
                    repository = FakeBookProjectRepository(active = mutableListOf(summary)),
                    enqueueProjectOcr = { 0 },
                    findInterruptedExports = { throw IOException() },
                )

            assertNull(viewModel.uiState.value.interruptedExport)
            assertNull(viewModel.uiState.value.operationError)
        }
}

class TrashViewModelTest : BookProjectViewModelTestBase() {
    @Test
    fun `restore and permanent delete call repository after confirmation`() =
        runTest {
            val trashed = summary(title = "Trash", deletedAt = Instant.parse("2026-08-01T00:00:00Z"))
            val repository = FakeBookProjectRepository(trash = mutableListOf(trashed))
            val viewModel = TrashViewModel(repository) { Instant.parse("2026-08-02T00:00:00Z") }

            assertEquals(1, repository.purgeCalls)
            viewModel.restore(trashed.project.id)
            assertEquals(listOf(trashed.project.id), repository.restoreCalls)

            repository.trash = mutableListOf(trashed)
            viewModel.load()
            viewModel.requestPermanentDelete(trashed.project.id)
            assertNotNull(viewModel.uiState.value.deleteConfirmation)
            assertTrue(repository.deleteCalls.isEmpty())
            viewModel.confirmPermanentDelete()
            assertEquals(listOf(trashed.project.id), repository.deleteCalls)
            assertNull(viewModel.uiState.value.deleteConfirmation)
        }

    @Test
    fun `operation errors remain represented`() =
        runTest {
            val trashed = summary(title = "Trash", deletedAt = Instant.parse("2026-08-01T00:00:00Z"))
            val repository =
                FakeBookProjectRepository(trash = mutableListOf(trashed), failRestore = true)
            val viewModel = TrashViewModel(repository)
            viewModel.restore(trashed.project.id)
            assertEquals(TrashOperationError.RESTORE, viewModel.uiState.value.operationError)
        }
}

private class FakeBookProjectRepository(
    var active: MutableList<BookProjectSummary> = mutableListOf(),
    var trash: MutableList<BookProjectSummary> = mutableListOf(),
    private val failCreate: Boolean = false,
    private val failUpdate: Boolean = false,
    private val failRestore: Boolean = false,
    private val failFind: Boolean = false,
) : BookProjectRepository {
    /** Room の再クエリに相当する再発行トリガー（ページ追加・削除で進む）。 */
    private val revision = MutableStateFlow(0)
    val listSortCalls = mutableListOf<BookProjectSort>()
    val createCalls = mutableListOf<String>()
    val restoreCalls = mutableListOf<UUID>()
    val deleteCalls = mutableListOf<UUID>()
    val updateCalls = mutableListOf<UUID>()
    var purgeCalls = 0

    override suspend fun create(
        title: String,
        author: String?,
        note: String?,
    ): BookProject {
        createCalls += title
        if (failCreate) throw IOException()
        return summary(title = title, author = author, note = note).project
    }

    override suspend fun findById(id: UUID): BookProject? {
        if (failFind) throw IOException()
        return (active + trash).firstOrNull { it.project.id == id }?.project
    }

    override suspend fun findSummaryById(id: UUID): BookProjectSummary? =
        (active + trash).firstOrNull { it.project.id == id }

    override fun observeSummaryById(id: UUID): Flow<BookProjectSummary?> =
        if (failFind) {
            flow { throw IOException() }
        } else {
            revision.map { (active + trash).firstOrNull { summary -> summary.project.id == id } }
        }

    /** 撮影・ページ削除でページ数と使用容量が変わったことを購読者へ流す。 */
    fun publish(updated: BookProjectSummary) {
        active.removeAll { it.project.id == updated.project.id }
        active += updated
        revision.value += 1
    }

    override suspend fun update(
        id: UUID,
        title: String,
        author: String?,
        note: String?,
    ): BookProject {
        updateCalls += id
        if (failUpdate) throw IOException()
        return requireNotNull(findById(id)).copy(title = title, author = author, note = note)
    }

    override suspend fun listActive(sort: BookProjectSort): List<BookProjectSummary> {
        listSortCalls += sort
        return active.sorted(sort)
    }

    override suspend fun searchActive(
        query: String,
        sort: BookProjectSort,
    ): List<BookProjectSummary> {
        val normalized = query.normalized()
        return active
            .filter {
                it.project.title.normalized().contains(normalized) ||
                    it.project.author?.normalized()?.contains(normalized) == true
            }.sorted(sort)
    }

    override suspend fun listTrash(): List<BookProjectSummary> = trash.toList()

    override suspend fun moveToTrash(id: UUID): BookProject =
        requireNotNull(
            findById(id),
        ).copy(deletedAt = Instant.now())

    override suspend fun restore(id: UUID): BookProject {
        restoreCalls += id
        if (failRestore) throw IOException()
        val project = requireNotNull(findById(id))
        trash.removeAll { it.project.id == id }
        return project.copy(deletedAt = null)
    }

    override suspend fun deletePermanently(id: UUID) {
        deleteCalls += id
        trash.removeAll { it.project.id == id }
    }

    override suspend fun purgeExpiredTrash(): Int {
        purgeCalls++
        return 0
    }
}

private fun List<BookProjectSummary>.sorted(sort: BookProjectSort): List<BookProjectSummary> =
    when (sort) {
        BookProjectSort.UPDATED_AT -> sortedByDescending { it.project.updatedAt }
        BookProjectSort.CREATED_AT -> sortedByDescending { it.project.createdAt }
        BookProjectSort.TITLE -> sortedBy { it.project.title.normalized() }
    }

private fun String.normalized(): String =
    Normalizer
        .normalize(this, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)

private fun summary(
    title: String,
    author: String? = null,
    note: String? = null,
    updatedAt: Instant = Instant.parse("2026-08-30T00:00:00Z"),
    deletedAt: Instant? = null,
    pageCount: Int = 0,
    storageBytes: Long = 0,
    ocrCompleted: Int = 0,
    ocrErrors: Int = 0,
): BookProjectSummary {
    val id = UUID.nameUUIDFromBytes("$title-$updatedAt-$deletedAt".toByteArray())
    return BookProjectSummary(
        project =
            BookProject(
                id = id,
                title = title,
                author = author,
                note = note,
                createdAt = updatedAt.minusSeconds(60),
                updatedAt = updatedAt,
                deletedAt = deletedAt,
            ),
        pageCount = pageCount,
        storageBytes = storageBytes,
        ocrCompletedCount = ocrCompleted,
        ocrErrorCount = ocrErrors,
    )
}

private fun page(projectId: UUID): Page =
    Page(
        id = UUID.fromString("20000000-0000-0000-0000-000000000001"),
        projectId = projectId,
        sequence = 1,
        originalImagePath = "projects/$projectId/images/page-1.png",
        width = 100,
        height = 200,
        rotation = 90,
        crop = PageCrop(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f),
        capturedAt = Instant.parse("2026-08-30T00:00:00Z"),
        contentHash = "content",
        perceptualHash = "perceptual",
        qualityState = PageQualityState.NORMAL,
        ocrState = PageOcrState.SUCCEEDED,
    )
