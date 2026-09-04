package com.outofthewhale.booklight

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsTest {

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
    fun `a rule contributes nothing to the count`() {
        val chapter = Chapter(blocks = listOf(Block(t = BlockType.RULE)))
        assertEquals(0, chapter.charCount)
    }

    @Test
    fun `charsBefore accumulates the chapters ahead of the index`() {
        val book = BookManifest(
            id = "b",
            title = "B",
            chapters = listOf(
                ChapterInfo(file = "ch/0001.json", charCount = 100),
                ChapterInfo(file = "ch/0002.json", charCount = 250),
                ChapterInfo(file = "ch/0003.json", charCount = 40),
            ),
        )
        assertEquals(0, book.charsBefore(0))
        assertEquals(100, book.charsBefore(1))
        assertEquals(350, book.charsBefore(2))
        assertEquals(390, book.charCount)
    }

    @Test
    fun `charsBefore clamps rather than throwing on an out of range chapter`() {
        val book = BookManifest(
            id = "b",
            title = "B",
            chapters = listOf(ChapterInfo(file = "ch/0001.json", charCount = 10)),
        )
        assertEquals(0, book.charsBefore(-3))
        assertEquals(10, book.charsBefore(9))
    }
}
