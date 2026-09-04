package com.outofthewhale.booklight

/**
 * A chapter flattened into one string, ready to lay out.
 *
 * The reader measures and paginates a whole chapter at once, which means it
 * needs the chapter as a single run of text. But reading position is stored as
 * an offset into the chapter's blocks concatenated with nothing between them
 * (see [Chapter.charCount]) - and the laid-out string has blank lines between
 * paragraphs. The two offsets differ, and this class is what converts between
 * them.
 *
 * Keeping the stored offset free of layout is the point. Change the paragraph
 * spacing here and every saved position still lands where the reader left off.
 */
class ChapterText private constructor(
    val text: String,
    val runs: List<StyleRun>,
    private val displayStarts: IntArray,
    private val chapterStarts: IntArray,
    private val lengths: IntArray,
) {

    /** Layout offset -> stored offset. */
    fun toChapterOffset(displayOffset: Int): Int {
        if (displayStarts.isEmpty()) return 0
        for (i in displayStarts.indices.reversed()) {
            if (displayOffset >= displayStarts[i]) {
                val within = (displayOffset - displayStarts[i]).coerceIn(0, lengths[i])
                return chapterStarts[i] + within
            }
        }
        return 0
    }

    /** Stored offset -> layout offset. */
    fun toDisplayOffset(chapterOffset: Int): Int {
        if (displayStarts.isEmpty()) return 0
        for (i in chapterStarts.indices.reversed()) {
            if (chapterOffset >= chapterStarts[i]) {
                val within = (chapterOffset - chapterStarts[i]).coerceIn(0, lengths[i])
                return displayStarts[i] + within
            }
        }
        return 0
    }

    companion object {
        /** A blank line between blocks, as the page in the photograph has. */
        const val SEPARATOR = "\n\n"

        fun of(chapter: Chapter): ChapterText {
            val builder = StringBuilder()
            val runs = mutableListOf<StyleRun>()
            val displayStarts = mutableListOf<Int>()
            val chapterStarts = mutableListOf<Int>()
            val lengths = mutableListOf<Int>()
            var chapterOffset = 0

            for (block in chapter.blocks) {
                // A break carries no text, so it cannot hold a reading position
                // and needs no entry in the maps.
                if (block.t == BlockType.RULE || block.s.isEmpty()) {
                    chapterOffset += block.s.length
                    continue
                }
                if (builder.isNotEmpty()) builder.append(SEPARATOR)

                val start = builder.length
                builder.append(block.s)
                displayStarts.add(start)
                chapterStarts.add(chapterOffset)
                lengths.add(block.s.length)
                chapterOffset += block.s.length

                blockRun(block.t)?.let { runs.add(StyleRun(start, start + block.s.length, it)) }
                for (span in block.spans) {
                    val from = start + span.i
                    val to = (from + span.n).coerceAtMost(start + block.s.length)
                    if (to > from) {
                        runs.add(
                            StyleRun(
                                from,
                                to,
                                if (span.style == SpanKind.BOLD) RunKind.BOLD else RunKind.ITALIC,
                            )
                        )
                    }
                }
            }

            return ChapterText(
                text = builder.toString(),
                runs = runs,
                displayStarts = displayStarts.toIntArray(),
                chapterStarts = chapterStarts.toIntArray(),
                lengths = lengths.toIntArray(),
            )
        }

        private fun blockRun(type: BlockType): RunKind? = when (type) {
            BlockType.H1 -> RunKind.H1
            BlockType.H2 -> RunKind.H2
            BlockType.H3 -> RunKind.H3
            BlockType.QUOTE -> RunKind.QUOTE
            else -> null
        }
    }
}

data class StyleRun(val start: Int, val end: Int, val kind: RunKind)

enum class RunKind { ITALIC, BOLD, H1, H2, H3, QUOTE }
