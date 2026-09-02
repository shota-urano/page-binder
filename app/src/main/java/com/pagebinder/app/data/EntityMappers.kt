package com.pagebinder.app.data

import com.pagebinder.app.domain.BookProject
import com.pagebinder.app.domain.ExportRecord
import com.pagebinder.app.domain.OcrCrop
import com.pagebinder.app.domain.OcrPage
import com.pagebinder.app.domain.OcrState
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.StoredOcrResult

internal fun BookProject.toEntity() =
    BookProjectEntity(
        id = id,
        title = title,
        author = author,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

internal fun BookProjectEntity.toDomain() =
    BookProject(
        id = id,
        title = title,
        author = author,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

internal fun Page.toEntity() =
    PageEntity(
        id = id,
        projectId = projectId,
        sequence = sequence,
        originalImagePath = originalImagePath,
        width = width,
        height = height,
        rotation = rotation,
        cropLeft = crop.left,
        cropTop = crop.top,
        cropRight = crop.right,
        cropBottom = crop.bottom,
        capturedAt = capturedAt,
        contentHash = contentHash,
        perceptualHash = perceptualHash,
        qualityState = qualityState,
        ocrState = ocrState,
    )

internal fun PageEntity.toDomain() =
    Page(
        id = id,
        projectId = projectId,
        sequence = sequence,
        originalImagePath = originalImagePath,
        width = width,
        height = height,
        rotation = rotation,
        crop = PageCrop(cropLeft, cropTop, cropRight, cropBottom),
        capturedAt = capturedAt,
        contentHash = contentHash,
        perceptualHash = perceptualHash,
        qualityState = qualityState,
        ocrState = ocrState,
    )

internal fun PageEntity.toOcrPage() =
    OcrPage(
        id = id,
        projectId = projectId,
        sequence = sequence,
        originalImagePath = originalImagePath,
        rotation = rotation,
        crop = OcrCrop(cropLeft, cropTop, cropRight, cropBottom),
        capturedAt = capturedAt,
        ocrState =
            OcrState.entries.single {
                it.serializedName == ocrState.serializedName
            },
    )

internal fun StoredOcrResult.toEntity() =
    OcrResultEntity(
        pageId = pageId,
        fullText = fullText,
        blocksJson = blocksJson,
        editedText = editedText,
        engineVersion = engineVersion,
        sourceImageHash = sourceImageHash,
        processedAt = processedAt,
    )

internal fun OcrResultEntity.toDomain() =
    StoredOcrResult(
        pageId = pageId,
        fullText = fullText,
        blocksJson = blocksJson,
        editedText = editedText,
        engineVersion = engineVersion,
        sourceImageHash = sourceImageHash,
        processedAt = processedAt,
    )

internal fun ExportRecord.toEntity() =
    ExportRecordEntity(
        id = id,
        projectId = projectId,
        type = type,
        targetUri = targetUri,
        state = state,
        createdAt = createdAt,
        completedAt = completedAt,
        errorCode = errorCode,
    )

internal fun ExportRecordEntity.toDomain() =
    ExportRecord(
        id = id,
        projectId = projectId,
        type = type,
        targetUri = targetUri,
        state = state,
        createdAt = createdAt,
        completedAt = completedAt,
        errorCode = errorCode,
    )
