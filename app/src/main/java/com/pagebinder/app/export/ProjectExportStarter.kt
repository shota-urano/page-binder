package com.pagebinder.app.export

import com.pagebinder.app.domain.BookProject
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.ExportFailureCode
import com.pagebinder.app.domain.ExportOptions
import com.pagebinder.app.domain.ExportPageRange
import com.pagebinder.app.domain.ExportProgressEvent
import com.pagebinder.app.domain.ExportProgressPhase
import com.pagebinder.app.domain.ExportStarter
import com.pagebinder.app.domain.ExportStorageGateway
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.domain.OcrResultRepository
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.domain.PdfGateway
import com.pagebinder.app.domain.PdfImageSource
import com.pagebinder.app.domain.PdfInput
import com.pagebinder.app.domain.PdfPage
import com.pagebinder.app.domain.PdfPageTransform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 書き出し画面（`ui/export/`）の [ExportStarter] の本番実装。
 *
 * 画面から来るのは「何を・どの範囲で・どこへ」だけなので、ここが BookProject / Page / OcrResult を
 * リポジトリから読んで [ExportArtifact] へ組み立て、[ExportEngine] に渡す
 * （docs/specs/11-export.md §2 入力・§3.1 出力形式）。
 * 一時出力は書籍ごとの `exports-cache/` を使う（docs/specs/02-data-model.md §3.2）。
 *
 * PDFBox・Room・ML Kit の型はここから外へ出ない（AGENTS.md ルール4）。保存URIはログへ出さない（同ルール6）。
 */
class ProjectExportStarter(
    private val bookProjectRepository: BookProjectRepository,
    private val pageRepository: PageRepository,
    private val ocrResultRepository: OcrResultRepository,
    private val pageImageSource: ExportPageImageSource,
    private val exportsCacheDirectory: (UUID) -> File,
    private val recordCoordinator: ExportRecordCoordinator,
    private val storageGateway: ExportStorageGateway,
    private val pdfGateway: PdfGateway,
    private val appVersion: String,
    private val clock: Clock = Clock.systemUTC(),
) : ExportStarter {
    /**
     * 組み立てに失敗した（書籍が消えている・範囲にページが無い等）場合は
     * `generation_failed` を1件流して終わる。ExportRecord を残すのは Export Engine が
     * 走り出してからで、走り出す前の失敗で queued のレコードを積み残さない。
     */
    override fun startExport(options: ExportOptions): Flow<ExportProgressEvent> =
        flow {
            val request =
                try {
                    buildRequest(options)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 例外の内容はログへ出さない（書籍タイトル・保存URIが混ざりうる）
                    emit(ExportProgressEvent.Failed(ExportFailureCode.GENERATION_FAILED))
                    return@flow
                }
            val engine =
                ExportEngine(
                    exportsCacheDirectory = exportsCacheDirectory(options.projectId),
                    recordCoordinator = recordCoordinator,
                    storageGateway = storageGateway,
                    pdfGateway = pdfGateway,
                )
            emitAll(engine.export(request).map(ExportEvent::toProgressEvent))
        }

    /** PDF画質は [ExportRequest] が [ExportOptions] から引き継ぐ（docs/design/11-export.md「PDF画質」） */
    private suspend fun buildRequest(options: ExportOptions): ExportRequest {
        val project =
            requireNotNull(bookProjectRepository.findById(options.projectId)) {
                "Export target project no longer exists"
            }
        val pages =
            pageRepository
                .findByProject(options.projectId)
                .sortedBy(Page::sequence)
                .filter { options.pageRange.includes(it.sequence) }
        require(pages.isNotEmpty()) { "Export needs at least one page in the selected range" }

        val artifact =
            when (options.type) {
                ExportType.SEARCHABLE_PDF -> ExportArtifact.SearchablePdf(pdfInput(pages, withTextLayer = true))
                ExportType.IMAGE_PDF -> ExportArtifact.ImagePdf(pdfInput(pages, withTextLayer = false))
                ExportType.MARKDOWN -> ExportArtifact.Markdown(textPages(pages))
                ExportType.TEXT_ZIP -> ExportArtifact.TextZip(textPages(pages))
                ExportType.IMAGE_ZIP -> imageZip(project, pages)
            }
        return ExportRequest(options, artifact)
    }

    /**
     * 画像PDFはテキスト層に依存しないので OCR を読まない（docs/specs/11-export.md §3.1）。
     *
     * blocksJson の座標は元画像基準（docs/specs/02-data-model.md §3.4）。元画像寸法と回転・切り取りも
     * [PdfPageTransform] で渡し、PDF側で派生画像と同じ変換行列を適用する（docs/specs/10-searchable-pdf.md §3.2）。
     */
    private suspend fun pdfInput(
        pages: List<Page>,
        withTextLayer: Boolean,
    ): PdfInput =
        PdfInput(
            pages.map { page ->
                val ocr = if (withTextLayer) ocrResultRepository.findByPageId(page.id) else null
                PdfPage(
                    sequence = page.sequence,
                    image = PdfImageSource { pageImageSource.openEdited(page) },
                    transform =
                        PdfPageTransform(
                            sourceWidth = page.width,
                            sourceHeight = page.height,
                            rotationDegrees = page.rotation,
                            crop = page.crop,
                        ),
                    ocrBlocksJson = ocr?.blocksJson,
                    fullText = ocr?.fullText,
                    editedText = ocr?.editedText,
                )
            },
        )

    /** OCR結果が無いページも境界とページ番号は出す（テキストは空。docs/specs/11-export.md §3.1・FR-EXP-009） */
    private suspend fun textPages(pages: List<Page>): List<TextExportPage> =
        pages.map { page ->
            val ocr = ocrResultRepository.findByPageId(page.id)
            TextExportPage(
                sequence = page.sequence,
                fullText = ocr?.fullText.orEmpty(),
                editedText = ocr?.editedText,
            )
        }

    private suspend fun imageZip(
        project: BookProject,
        pages: List<Page>,
    ): ExportArtifact.ImageZip {
        val ocrResults = pages.associate { page -> page.id to ocrResultRepository.findByPageId(page.id) }
        return ExportArtifact.ImageZip(
            images =
                pages.map { page ->
                    ExportImage(
                        sequence = page.sequence,
                        content = ExportContentSource { pageImageSource.openOriginal(page) },
                    )
                },
            manifestInput =
                ManifestInput(
                    appVersion = appVersion,
                    project =
                        ManifestProject(
                            title = project.title,
                            author = project.author,
                            note = project.note,
                            createdAt = project.createdAt,
                        ),
                    exportedAt = Instant.now(clock),
                    ocrEngineVersion =
                        pages
                            .firstNotNullOfOrNull { page -> ocrResults[page.id]?.engineVersion }
                            .orEmpty(),
                    pages =
                        pages.map { page ->
                            ManifestPage(
                                sequence = page.sequence,
                                capturedAt = page.capturedAt,
                                ocrState = page.ocrState.toManifestOcrState(),
                                contentHash = page.contentHash,
                                edited = page.transformed || ocrResults[page.id]?.editedText != null,
                            )
                        },
                ),
        )
    }
}

/** 非破壊編集（回転・切り取り）が入っているか。元画像そのままなら false */
private val Page.transformed: Boolean
    get() = rotation != 0 || crop != PageCrop()

private fun ExportPageRange.includes(sequence: Int): Boolean =
    when (this) {
        ExportPageRange.All -> true
        is ExportPageRange.Bounded -> sequence in firstPage..lastPage
    }

private fun PageOcrState.toManifestOcrState(): ManifestOcrState =
    when (this) {
        PageOcrState.PENDING -> ManifestOcrState.PENDING
        PageOcrState.RUNNING -> ManifestOcrState.RUNNING
        PageOcrState.SUCCEEDED -> ManifestOcrState.SUCCEEDED
        PageOcrState.FAILED -> ManifestOcrState.FAILED
        PageOcrState.STALE -> ManifestOcrState.STALE
    }

private fun ExportEvent.toProgressEvent(): ExportProgressEvent =
    when (this) {
        is ExportEvent.Progress ->
            ExportProgressEvent.Progress(
                phase = phase.toProgressPhase(),
                completedUnits = completedUnits,
                totalUnits = totalUnits,
            )
        is ExportEvent.Succeeded -> ExportProgressEvent.Succeeded
        is ExportEvent.Failed -> ExportProgressEvent.Failed(errorCode)
    }

private fun ExportPhase.toProgressPhase(): ExportProgressPhase =
    when (this) {
        ExportPhase.QUEUED -> ExportProgressPhase.QUEUED
        ExportPhase.GENERATING -> ExportProgressPhase.GENERATING
        ExportPhase.WRITING -> ExportProgressPhase.WRITING
    }
