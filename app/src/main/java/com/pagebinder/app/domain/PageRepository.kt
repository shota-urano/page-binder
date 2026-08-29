package com.pagebinder.app.domain

import java.time.Instant
import java.util.UUID

enum class PageQualityState(val serializedName: String) {
    NORMAL("normal"),
    DUPLICATE("duplicate"),
    BLACK("black"),
    ERROR("error"),
}

enum class PageOcrState(val serializedName: String) {
    PENDING("pending"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    STALE("stale"),
}

/** Crop bounds normalized against the image after clockwise rotation. */
data class PageCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "Page crop coordinates must be finite"
        }
        require(listOf(left, top, right, bottom).all { it in 0f..1f }) {
            "Page crop coordinates must be normalized"
        }
        require(left < right && top < bottom) { "Page crop must have a positive area" }
    }
}

data class Page(
    val id: UUID,
    val projectId: UUID,
    val sequence: Int,
    val originalImagePath: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val crop: PageCrop,
    val capturedAt: Instant,
    val contentHash: String,
    val perceptualHash: String,
    val qualityState: PageQualityState,
    val ocrState: PageOcrState,
) {
    init {
        require(sequence >= 1) { "Page sequence must start at 1" }
        require(width > 0 && height > 0) { "Page dimensions must be positive" }
        require(rotation in VALID_PAGE_ROTATIONS) { "Page rotation must be 0, 90, 180, or 270 degrees" }
        require(originalImagePath.isNotBlank() && !originalImagePath.startsWith('/')) {
            "Page image path must be relative"
        }
    }
}

val VALID_PAGE_ROTATIONS = setOf(0, 90, 180, 270)

/** Pages a crop edit is applied to (FR-IMG-005/006: per page, or the whole book at once). */
enum class PageCropScope {
    PAGE,
    PROJECT,
}

/** Persistence boundary for page data and atomic page-editing operations. */
interface PageRepository {
    suspend fun insert(page: Page)

    suspend fun findById(id: UUID): Page?

    suspend fun findByProject(projectId: UUID): List<Page>

    /** Replaces the complete page order for [projectId] and assigns contiguous sequences from 1. */
    suspend fun reorder(
        projectId: UUID,
        orderedPageIds: List<UUID>,
    )

    /** Deletes all selected records and compacts the remaining project sequences from 1. */
    suspend fun delete(
        projectId: UUID,
        pageIds: Set<UUID>,
    )

    /** Updates non-destructive rotation metadata and marks prior OCR stale when it changes. */
    suspend fun updateRotation(
        pageId: UUID,
        rotation: Int,
    )

    /** Updates non-destructive crop metadata and marks prior OCR stale when it changes. */
    suspend fun updateCrop(
        pageId: UUID,
        crop: PageCrop,
    )

    /**
     * Stores the rotation of [pageId] together with [crop] for every page selected by [cropScope]
     * as a single atomic edit: either every affected row changes or none does, and one undo entry
     * covers the whole operation.
     *
     * @return the number of pages the crop was applied to.
     */
    suspend fun updatePageEdit(
        pageId: UUID,
        rotation: Int,
        crop: PageCrop,
        cropScope: PageCropScope = PageCropScope.PAGE,
    ): Int

    /** Restores the state before the most recent successful page-editing operation. */
    suspend fun undoLastEdit(): Boolean
}

sealed class PageRepositoryException(message: String) : Exception(message) {
    class PageNotFound(id: UUID) : PageRepositoryException("Page not found: $id")

    class InvalidProjectOrder :
        PageRepositoryException("The supplied order must contain every project page exactly once")

    class PagesNotInProject : PageRepositoryException("Every deleted page must belong to the selected project")
}
