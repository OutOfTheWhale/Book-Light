package com.outofthewhale.booklight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProgressTest {

    @Test
    fun `a saved position comes back`() {
        val progress = Progress().with("a.book", Position(3, 120), at = 10)
        assertEquals(Position(3, 120), progress.of("a.book")?.position)
    }

    @Test
    fun `saving again replaces rather than accumulates`() {
        val progress = Progress()
            .with("a.book", Position(1, 10), at = 1)
            .with("a.book", Position(4, 40), at = 2)
        assertEquals(1, progress.marks.size)
        assertEquals(Position(4, 40), progress.of("a.book")?.position)
    }

    @Test
    fun `an unread book has no mark`() {
        assertNull(Progress().of("never opened.book"))
    }

    @Test
    fun `recent orders by when each book was last opened`() {
        val progress = Progress()
            .with("old.book", Position(), at = 100)
            .with("newest.book", Position(), at = 300)
            .with("middle.book", Position(), at = 200)
        assertEquals(listOf("newest.book", "middle.book", "old.book"), progress.recent())
    }

    @Test
    fun `marks for deleted books are dropped`() {
        // A mark left behind would silently resume a book the reader had
        // removed and put back, at a place they never left off.
        val progress = Progress()
            .with("kept.book", Position(1, 1), at = 1)
            .with("gone.book", Position(2, 2), at = 2)
            .retaining(listOf("kept.book"))
        assertEquals(setOf("kept.book"), progress.marks.keys)
    }

    @Test
    fun `retaining everything changes nothing at all`() {
        val progress = Progress().with("a.book", Position(1, 1), at = 1)
        assertSame(progress, progress.retaining(listOf("a.book")))
    }

    @Test
    fun `fraction through the book counts the chapters before it`() {
        val book = Book(
            title = "B",
            chapters = listOf(
                Chapter(blocks = listOf(Block(s = "x".repeat(100)))),
                Chapter(blocks = listOf(Block(s = "x".repeat(100)))),
            ),
        )
        assertEquals(0f, book.fractionAt(Position(0, 0)))
        assertEquals(0.5f, book.fractionAt(Position(1, 0)))
        assertEquals(1f, book.fractionAt(Position(1, 100)))
    }

    @Test
    fun `fraction stays in range for a position past the end of the book`() {
        val book = Book(title = "B", chapters = listOf(Chapter(blocks = listOf(Block(s = "xxx")))))
        assertEquals(1f, book.fractionAt(Position(9, 9999)))
        assertEquals(0f, book.fractionAt(Position(-1, -1)))
    }

    @Test
    fun `an empty book is zero rather than a divide by zero`() {
        assertEquals(0f, Book(title = "Nothing").fractionAt(Position(0, 0)))
    }
}
