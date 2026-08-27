package com.pagebinder.app.ocr

internal data class OcrReadingBlock(
    val sourceIndex: Int,
    val rect: OcrPixelRect,
    val lineRects: List<OcrPixelRect>,
)

/** Applies conservative coordinate-based corrections while retaining ML Kit order when ambiguous. */
internal object OcrReadingOrderCorrector {
    fun order(blocks: List<OcrReadingBlock>): List<Int> {
        if (blocks.size < 2 || blocks.any { !it.rect.hasArea }) return blocks.map { it.sourceIndex }

        return if (isVertical(blocks)) {
            orderVertical(blocks)
        } else {
            orderHorizontal(blocks)
        }
    }

    private fun isVertical(blocks: List<OcrReadingBlock>): Boolean {
        val lines = blocks.flatMap { it.lineRects }.filter { it.hasArea }
        if (lines.isEmpty()) return false

        val vertical = lines.count { it.height > it.width * ORIENTATION_RATIO }
        val horizontal = lines.count { it.width > it.height * ORIENTATION_RATIO }
        return vertical > horizontal
    }

    private fun orderVertical(blocks: List<OcrReadingBlock>): List<Int> =
        overlappingGroups(blocks) { first, second -> first.rect.horizontalOverlapRatio(second.rect) }
            .sortedByDescending { group -> group.maxOf { it.rect.right } }
            .flatMap { group -> group.sortedWith(compareBy({ it.rect.top }, { -it.rect.right }, { it.sourceIndex })) }
            .map { it.sourceIndex }

    private fun orderHorizontal(blocks: List<OcrReadingBlock>): List<Int> {
        val columns =
            overlappingGroups(blocks) { first, second -> first.rect.horizontalOverlapRatio(second.rect) }
                .sortedBy { group -> group.minOf { it.rect.left } }
        if (columns.size < 2 || !columnsAreConcurrent(columns)) return blocks.map { it.sourceIndex }

        return columns
            .flatMap { column -> column.sortedWith(compareBy({ it.rect.top }, { it.rect.left }, { it.sourceIndex })) }
            .map { it.sourceIndex }
    }

    private fun columnsAreConcurrent(columns: List<List<OcrReadingBlock>>): Boolean {
        val spans =
            columns.map { column ->
                OcrPixelRect(
                    left = column.minOf { it.rect.left },
                    top = column.minOf { it.rect.top },
                    right = column.maxOf { it.rect.right },
                    bottom = column.maxOf { it.rect.bottom },
                )
            }
        return spans.zipWithNext().all { (first, second) ->
            first.verticalOverlapRatio(second) >= COLUMN_VERTICAL_OVERLAP_RATIO
        }
    }

    private fun overlappingGroups(
        blocks: List<OcrReadingBlock>,
        overlap: (OcrReadingBlock, OcrReadingBlock) -> Float,
    ): List<List<OcrReadingBlock>> {
        val unvisited = blocks.toMutableSet()
        val result = mutableListOf<List<OcrReadingBlock>>()
        while (unvisited.isNotEmpty()) {
            val group = mutableListOf<OcrReadingBlock>()
            val pending = ArrayDeque<OcrReadingBlock>().apply { add(unvisited.first()) }
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!unvisited.remove(current)) continue
                group += current
                unvisited
                    .filter { candidate -> overlap(current, candidate) >= SAME_COLUMN_OVERLAP_RATIO }
                    .forEach(pending::add)
            }
            result += group
        }
        return result
    }

    private val OcrPixelRect.width: Int get() = right - left

    private val OcrPixelRect.height: Int get() = bottom - top

    private val OcrPixelRect.hasArea: Boolean get() = width > 0 && height > 0

    private fun OcrPixelRect.horizontalOverlapRatio(other: OcrPixelRect): Float {
        val overlap = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
        return overlap.toFloat() / minOf(width, other.width)
    }

    private fun OcrPixelRect.verticalOverlapRatio(other: OcrPixelRect): Float {
        val overlap = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
        return overlap.toFloat() / minOf(height, other.height)
    }

    private const val ORIENTATION_RATIO = 1.2f
    private const val SAME_COLUMN_OVERLAP_RATIO = 0.5f
    private const val COLUMN_VERTICAL_OVERLAP_RATIO = 0.5f
}
