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

    private fun orderVertical(blocks: List<OcrReadingBlock>): List<Int> {
        val layout = columnLayout(blocks)
        val columns = layout.columns.sortedByDescending { group -> group.maxOf { it.rect.right } }
        return orderColumnSections(
            columns = columns,
            spanningBlocks = layout.spanningBlocks,
            blockComparator = compareBy({ it.rect.top }, { -it.rect.right }, { it.sourceIndex }),
        )
    }

    private fun orderHorizontal(blocks: List<OcrReadingBlock>): List<Int> {
        val layout = columnLayout(blocks)
        val columns = layout.columns.sortedBy { group -> group.minOf { it.rect.left } }
        if (columns.size < 2 || !columnsAreConcurrent(columns)) return blocks.map { it.sourceIndex }

        return orderColumnSections(
            columns = columns,
            spanningBlocks = layout.spanningBlocks,
            blockComparator = compareBy({ it.rect.top }, { it.rect.left }, { it.sourceIndex }),
        )
    }

    /**
     * Builds columns from normal-width blocks first. A wide block that overlaps more than one
     * established column is kept out of the column graph so it cannot transitively merge them.
     */
    private fun columnLayout(blocks: List<OcrReadingBlock>): ColumnLayout {
        val sortedWidths = blocks.map { it.rect.width }.sorted()
        val typicalWidth = sortedWidths[(sortedWidths.size - 1) / 2]
        val (wideCandidates, columnBlocks) =
            blocks.partition { it.rect.width >= typicalWidth * SPANNING_WIDTH_RATIO }
        val columns = overlappingGroups(columnBlocks).map { it.toMutableList() }.toMutableList()
        val spanningBlocks = mutableListOf<OcrReadingBlock>()

        wideCandidates.sortedWith(compareBy({ it.rect.width }, { it.sourceIndex })).forEach { block ->
            val matchingColumns =
                columns.filter { column ->
                    column.any { member ->
                        block.rect.horizontalOverlapRatio(member.rect) >= SAME_COLUMN_OVERLAP_RATIO
                    }
                }
            when (matchingColumns.size) {
                0 -> columns += mutableListOf(block)
                1 -> matchingColumns.single() += block
                else -> spanningBlocks += block
            }
        }
        return ColumnLayout(columns = columns, spanningBlocks = spanningBlocks)
    }

    private fun orderColumnSections(
        columns: List<List<OcrReadingBlock>>,
        spanningBlocks: List<OcrReadingBlock>,
        blockComparator: Comparator<OcrReadingBlock>,
    ): List<Int> {
        val remainingColumns = columns.map { it.sortedWith(blockComparator).toMutableList() }
        val result = mutableListOf<OcrReadingBlock>()
        spanningBlocks.sortedWith(blockComparator).forEach { spanningBlock ->
            remainingColumns.forEach { column ->
                val beforeSpanning = column.takeWhile { it.rect.top < spanningBlock.rect.top }
                result += beforeSpanning
                repeat(beforeSpanning.size) { column.removeAt(0) }
            }
            result += spanningBlock
        }
        remainingColumns.forEach { result += it }
        return result.map { it.sourceIndex }
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

    private fun overlappingGroups(blocks: List<OcrReadingBlock>): List<List<OcrReadingBlock>> {
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
                    .filter { candidate ->
                        current.rect.horizontalOverlapRatio(candidate.rect) >= SAME_COLUMN_OVERLAP_RATIO
                    }
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
    private const val SPANNING_WIDTH_RATIO = 1.5f

    private data class ColumnLayout(
        val columns: List<List<OcrReadingBlock>>,
        val spanningBlocks: List<OcrReadingBlock>,
    )
}
