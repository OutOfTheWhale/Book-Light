package com.outofthewhale.booklight

import kotlinx.serialization.Serializable

/**
 * The normalised book format.
 *
 * Every book the reader opens has this shape, whatever it arrived as. EPUB, plain
 * text and HTML are parsed on the phone; PDF, MOBI and the rest are converted on a
 * desktop. Both routes produce the same files, so nothing above this layer ever has
 * to ask where a book came from.
 *
 * On disk, under `filesDir/books/<id>/`:
 *
 *     book.json        this manifest
 *     ch/0001.json     one [Chapter] per file
 *     cover.png        optional
 *
 * Chapters are separate files on purpose: a long novel is tens of megabytes of text
 * and must never be held whole.
 */
@Serializable
data class BookManifest(
    val id: String,
    val title: String,
    val author: String? = null,
    val source: SourceFormat = SourceFormat.UNKNOWN,
    val chapters: List<ChapterInfo> = emptyList(),
) {
    /** Characters in the whole book, for percent-through. */
    val charCount: Int get() = chapters.sumOf { it.charCount }

    /**
     * Characters before chapter [index] starts. Reading position is stored per
     * chapter, so turning it into a percentage needs this running total.
     */
    fun charsBefore(index: Int): Int {
        var total = 0
        for (i in 0 until index.coerceIn(0, chapters.size)) total += chapters[i].charCount
        return total
    }
}

@Serializable
data class ChapterInfo(
    /** Shown in the table of contents. Null for a chapter the source never named. */
    val title: String? = null,
    /** Path relative to the book directory, e.g. `ch/0001.json`. */
    val file: String,
    /** Must equal [Chapter.charCount] of the file it points at. */
    val charCount: Int = 0,
)

@Serializable
enum class SourceFormat { EPUB, TXT, MARKDOWN, HTML, FB2, CBZ, PDF, MOBI, AZW3, DOCX, UNKNOWN }

@Serializable
data class Chapter(
    val title: String? = null,
    val blocks: List<Block> = emptyList(),
) {
    /**
     * The length of this chapter as reading position counts it: the block strings
     * concatenated, nothing between them.
     *
     * Paginator and progress store must both count this way. If they disagree, a
     * saved position lands somewhere other than where the reader left off, and the
     * error is silent — the page still renders, just at the wrong place.
     */
    val charCount: Int get() = blocks.sumOf { it.s.length }
}

/**
 * One paragraph, heading or rule.
 *
 * Inline emphasis rides along as [spans] rather than splitting the text, so the
 * paragraph stays a single string and can be laid out as one `AnnotatedString`.
 * Laying words out separately would wreck prose wrapping.
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
 * **A character offset, never a page number.** Pages are a function of font size,
 * margins and screen; they differ between the LP2 and the LP3 and change the moment
 * the reader adjusts the type. An offset into the text is a property of the book,
 * so it survives all of that.
 */
@Serializable
data class Position(
    val chapter: Int = 0,
    val charOffset: Int = 0,
)
