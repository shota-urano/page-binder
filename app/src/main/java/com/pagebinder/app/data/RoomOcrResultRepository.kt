package com.pagebinder.app.data

import androidx.room.Dao
import androidx.room.Query
import com.pagebinder.app.domain.OcrResultRepository
import com.pagebinder.app.domain.StoredOcrResult
import java.time.Instant
import java.util.UUID

/**
 * OCR編集画面が使う読み書き。
 *
 * 更新できるのは `edited_text` 列だけで、`full_text` / `blocks_json` を書き換える SQL は置かない
 * （docs/specs/09-ocr.md §3.5「元のOCR結果 fullText は保持」を SQL の側で担保する）。
 */
@Dao
interface OcrResultDao {
    @Query("SELECT * FROM ocr_results WHERE page_id = :pageId")
    suspend fun findByPageId(pageId: String): OcrResultEntity?

    /** editedText が null なら手動修正の破棄（「元のOCR結果へ戻す」） */
    @Query("UPDATE ocr_results SET edited_text = :editedText WHERE page_id = :pageId")
    suspend fun updateEditedText(
        pageId: String,
        editedText: String?,
    ): Int

    @Query("DELETE FROM ocr_results WHERE page_id = :pageId")
    suspend fun deleteByPageId(pageId: String): Int
}

class RoomOcrResultRepository(
    private val dao: OcrResultDao,
) : OcrResultRepository {
    override suspend fun findByPageId(pageId: UUID): StoredOcrResult? = dao.findByPageId(pageId.toString())?.toDomain()

    override suspend fun saveEditedText(
        pageId: UUID,
        editedText: String,
    ): Boolean = dao.updateEditedText(pageId.toString(), editedText) == 1

    override suspend fun clearEditedText(pageId: UUID): Boolean = dao.updateEditedText(pageId.toString(), null) == 1
}

private fun OcrResultEntity.toDomain() =
    StoredOcrResult(
        pageId = UUID.fromString(pageId),
        fullText = fullText,
        blocksJson = blocksJson,
        editedText = editedText,
        engineVersion = engineVersion,
        sourceImageHash = sourceImageHash,
        processedAt = Instant.parse(processedAt),
    )
