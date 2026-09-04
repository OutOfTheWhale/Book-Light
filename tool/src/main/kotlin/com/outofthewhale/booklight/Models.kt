package com.outofthewhale.booklight

import kotlinx.serialization.Serializable

/**
 * One book, one file.
 *
 * A `.book` file is this class as JSON. The converter on the desktop turns an
 * EPUB, PDF or anything else into one of these; the phone only ever reads this.
 * That is the whole reason the reader needs no parsers - the sandbox forbids
 * every library that could parse a real book format, so none of that happens here.
 *
 * The book is held whole while it is open. A long novel is a few megabytes of
 * text, which is nothing on either phone.
 */
@Serializable
data class Book(
    val title: String,
    val author: String? = null,
    /** What it was converted from - "epub", "pdf", "txt". Shown nowhere, kept for support. */
    val source: String? = null,
    val chapters: List<Chapter> = emptyList(),
) {
    /** Characters in the whole book, for percent-through. */
    val charCount: Int get() = chapters.sumOf { it.charCount }

    /** Characters before chapter [index] begins. Clamps rather than throwing. */
    fun charsBefore(index: Int): Int {
        var total = 0
        for (i in 0 until index.coerceIn(0, chapters.size)) total += chapters[i].charCount
        return total
    }
}

@Serializable
data class Chapter(
    val title: String? = null,
    /**
     * True when this is the rest of the chapter above, split only so the phone
     * can lay it out. The contents leaves these out - they are not chapters.
     */
    val continues: Boolean = false,
    val blocks: List<Block> = emptyList(),
) {
    /**
     * The length of this chapter as reading position counts it: the block strings
     * concatenated, nothing between them.
     *
     * The paginator must count the same way. If the two ever disagree, a resumed
     * book opens at the wrong place and nothing looks broken - the page still
     * renders, just not where the reader left off.
     */
    val charCount: Int get() = blocks.sumOf { it.s.length }
}

/**
 * One paragraph, heading or break.
 *
 * Italics ride along as [spans] instead of splitting the text, so a paragraph
 * stays one string and lays out as a single `AnnotatedString`. Laying words out
 * separately would wreck prose wrapping.
 */
@Serializable
data class Block(
    val t: BlockType = BlockType.P,
    val s: String = "",
    val spans: List<Span> = emptyList(),
)

@Serializable
enum class BlockType { P, H1, H2, H3, QUOTE, LI, RULE }

/** [n] characters from index [i] are styled. Offsets are into [Block.s]. */
@Serializable
data class Span(val i: Int, val n: Int, val style: SpanKind)

@Serializable
enum class SpanKind { ITALIC, BOLD }

/**
 * Where the reader is in a book.
 *
 * **A character offset, never a page number.** Pages depend on font size, margins
 * and screen - they differ between the LP2 and the LP3, and change the moment the
 * type is adjusted. An offset into the text is a property of the book, so it
 * survives all of that.
 */
@Serializable
data class Position(
    val chapter: Int = 0,
    val charOffset: Int = 0,
)
