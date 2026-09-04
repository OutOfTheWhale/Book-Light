package com.outofthewhale.booklight

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsTest {

    private fun book(vararg lengths: Int) = Book(
        title = "B",
        chapters = lengths.map { n -> Chapter(blocks = listOf(Block(s = "x".repeat(n)))) },
    )

    @Test
    fun `chapter length counts block strings and nothing between them`() {
        val chapter = Chapter(
            title = "One",
            blocks = listOf(
                Block(t = BlockType.H1, s = "One"),
                Block(s = "Call me Ishmael."),
            ),
        )
        assertEquals(3 + 16, chapter.charCount)
    }

    @Test
    fun `a break contributes nothing to the count`() {
        assertEquals(0, Chapter(blocks = listOf(Block(t = BlockType.RULE))).charCount)
    }

    @Test
    fun `charsBefore accumulates the chapters ahead of the index`() {
        val b = book(100, 250, 40)
        assertEquals(0, b.charsBefore(0))
        assertEquals(100, b.charsBefore(1))
        assertEquals(350, b.charsBefore(2))
        assertEquals(390, b.charCount)
    }

    @Test
    fun `charsBefore clamps rather than throwing on an out of range chapter`() {
        val b = book(10)
        assertEquals(0, b.charsBefore(-3))
        assertEquals(10, b.charsBefore(9))
    }

    @Test
    fun `an empty book is zero characters, not a crash`() {
        val b = Book(title = "Nothing")
        assertEquals(0, b.charCount)
        assertEquals(0, b.charsBefore(0))
        assertEquals(0, b.charsBefore(5))
    }
}
