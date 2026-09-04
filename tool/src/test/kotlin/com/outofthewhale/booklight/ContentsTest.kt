package com.outofthewhale.booklight

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
