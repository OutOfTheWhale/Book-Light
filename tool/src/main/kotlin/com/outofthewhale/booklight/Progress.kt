package com.outofthewhale.booklight

import kotlinx.serialization.Serializable

/**
 * Where the reader stopped in every book.
 *
 * Held as one document rather than a record per book, so it saves in one write
 * and the "recently read" list needs no scan. Transformations are pure, which
 * is what makes them testable without a phone.
 */
@Serializable
data class Progress(
    val marks: Map<String, Mark> = emptyMap(),
) {
    fun of(bookId: String): Mark? = marks[bookId]

    fun with(bookId: String, position: Position, at: Long): Progress =
        copy(marks = marks + (bookId to Mark(position.chapter, position.charOffset, at)))

    /** Drop a book's mark - used when its file is gone. */
    fun without(bookId: String): Progress =
        if (bookId in marks) copy(marks = marks - bookId) else this

    /**
     * Forget books whose files are no longer there.
     *
     * Without this a deleted book keeps its mark for ever, and putting the same
     * file back would silently resume somewhere the reader does not remember
     * leaving off.
     */
    fun retaining(bookIds: Collection<String>): Progress {
        val keep = marks.filterKeys { it in bookIds }
        return if (keep.size == marks.size) this else copy(marks = keep)
    }

    /** Book ids, most recently opened first. */
    fun recent(): List<String> =
        marks.entries.sortedByDescending { it.value.at }.map { it.key }
}

@Serializable
data class Mark(
    val chapter: Int = 0,
    val charOffset: Int = 0,
    /** Epoch millis, for ordering the library by what was read last. */
    val at: Long = 0,
) {
    val position: Position get() = Position(chapter, charOffset)
}

/**
 * How far through a book a mark sits, as a fraction from 0 to 1.
 *
 * Derived rather than stored: it depends on the book, and storing it would let
 * it disagree with the position it came from.
 */
fun Book.fractionAt(position: Position): Float {
    val total = charCount
    if (total <= 0) return 0f
    val chapter = position.chapter.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
    val within = if (chapters.isEmpty()) 0
    else position.charOffset.coerceIn(0, chapters[chapter].charCount)
    return ((charsBefore(chapter) + within).toFloat() / total).coerceIn(0f, 1f)
}
