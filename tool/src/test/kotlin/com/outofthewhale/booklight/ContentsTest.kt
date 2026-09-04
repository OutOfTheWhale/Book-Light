package com.outofthewhale.booklight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentsTest {

    private fun chapter(title: String?, continues: Boolean = false) =
        Chapter(title = title, continues = continues, blocks = listOf(Block(s = "text")))

    @Test
    fun `a continuation is not listed as a chapter of its own`() {
        // Splitting a long chapter is a layout decision. Listing the pieces put
        // a bare "2" and "6" between the real entries in Moby Dick.
        val book = Book(
            title = "B",
            chapters = listOf(
                chapter("CHAPTER 1."),
                chapter(null, continues = true),
                chapter("CHAPTER 2."),
            ),
        )
        assertEquals(listOf("CHAPTER 1.", "CHAPTER 2."), book.contents().map { it.title })
    }

    @Test
    fun `an entry keeps the real chapter index, not its place in the list`() {
        // The index is what the reader jumps to, so dropping continuations must
        // not renumber what is left.
        val book = Book(
            title = "B",
            chapters = listOf(
                chapter("One"),
                chapter(null, continues = true),
                chapter("Two"),
            ),
        )
        assertEquals(listOf(0, 2), book.contents().map { it.chapter })
    }

    @Test
    fun `a chapter the book never named is still listed`() {
        // Unnamed is not the same as a continuation: this one needs a way in.
        val book = Book(title = "B", chapters = listOf(chapter(null)))
        assertEquals(listOf<String?>(null), book.contents().map { it.title })
        assertEquals(listOf(0), book.contents().map { it.chapter })
    }

    @Test
    fun `a book with no chapters lists nothing`() {
        assertEquals(emptyList(), Book(title = "B").contents())
    }

    @Test
    fun `the heading names the chapter being read`() {
        val book = Book(title = "B", chapters = listOf(chapter("One"), chapter("Two")))
        assertEquals("Two", book.chapterLabel(1))
    }

    @Test
    fun `a continuation borrows the name of the chapter it continues`() {
        // Otherwise the heading blanks out halfway through a long chapter,
        // which reads as a bug rather than as a page turn.
        val book = Book(
            title = "B",
            chapters = listOf(
                chapter("CHAPTER 1."),
                chapter(null, continues = true),
                chapter(null, continues = true),
            ),
        )
        assertEquals("CHAPTER 1.", book.chapterLabel(2))
    }

    @Test
    fun `a chapter the book never named has no heading rather than a wrong one`() {
        // Unnamed is not a continuation: it must not inherit from above.
        val book = Book(title = "B", chapters = listOf(chapter("One"), chapter(null)))
        assertNull(book.chapterLabel(1))
    }

    @Test
    fun `an out of range chapter clamps instead of throwing`() {
        val book = Book(title = "B", chapters = listOf(chapter("Only")))
        assertEquals("Only", book.chapterLabel(9))
        assertEquals("Only", book.chapterLabel(-4))
    }

    @Test
    fun `a book with no chapters has no heading`() {
        assertNull(Book(title = "B").chapterLabel(0))
    }
}
