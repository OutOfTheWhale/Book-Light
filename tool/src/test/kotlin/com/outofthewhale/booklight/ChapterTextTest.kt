package com.outofthewhale.booklight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterTextTest {

    private fun chapter(vararg blocks: Block) = Chapter(blocks = blocks.toList())

    @Test
    fun `blocks are laid out with a blank line between them`() {
        val text = ChapterText.of(chapter(Block(s = "one"), Block(s = "two")))
        assertEquals("one\n\ntwo", text.text)
    }

    @Test
    fun `a stored offset maps past the separators to the same character`() {
        // The stored offset knows nothing about paragraph spacing, so the "t"
        // of "two" is character 3 stored and character 5 laid out.
        val text = ChapterText.of(chapter(Block(s = "one"), Block(s = "two")))
        assertEquals(5, text.toDisplayOffset(3))
        assertEquals('t', text.text[5])
    }

    @Test
    fun `layout offset and stored offset are inverses through every block`() {
        val text = ChapterText.of(
            chapter(Block(s = "alpha"), Block(s = "beta"), Block(s = "gamma"))
        )
        for (stored in 0 until chapter(
            Block(s = "alpha"), Block(s = "beta"), Block(s = "gamma")
        ).charCount) {
            assertEquals(stored, text.toChapterOffset(text.toDisplayOffset(stored)))
        }
    }

    @Test
    fun `an offset landing on a separator resolves to the block before it`() {
        // Nothing is stored inside the gap between paragraphs, so a layout
        // offset there has to fall somewhere sensible rather than drift.
        val text = ChapterText.of(chapter(Block(s = "one"), Block(s = "two")))
        assertEquals(3, text.toChapterOffset(3))   // just after "one"
        assertEquals(3, text.toChapterOffset(4))
    }

    @Test
    fun `emphasis becomes a run over the same characters`() {
        val text = ChapterText.of(
            chapter(
                Block(s = "Call me Ishmael", spans = listOf(Span(8, 7, SpanKind.ITALIC)))
            )
        )
        val run = text.runs.single { it.kind == RunKind.ITALIC }
        assertEquals("Ishmael", text.text.substring(run.start, run.end))
    }

    @Test
    fun `emphasis in a later block is offset by the separators before it`() {
        val text = ChapterText.of(
            chapter(
                Block(s = "one"),
                Block(s = "Call me Ishmael", spans = listOf(Span(8, 7, SpanKind.ITALIC))),
            )
        )
        val run = text.runs.single { it.kind == RunKind.ITALIC }
        assertEquals("Ishmael", text.text.substring(run.start, run.end))
    }

    @Test
    fun `a heading becomes a run over the whole block`() {
        val text = ChapterText.of(chapter(Block(t = BlockType.H1, s = "CHAPTER I")))
        val run = text.runs.single { it.kind == RunKind.H1 }
        assertEquals("CHAPTER I", text.text.substring(run.start, run.end))
    }

    @Test
    fun `a span running past the end of its block is clipped, not dropped`() {
        val text = ChapterText.of(chapter(Block(s = "short", spans = listOf(Span(2, 99, SpanKind.BOLD)))))
        val run = text.runs.single { it.kind == RunKind.BOLD }
        assertTrue(run.end <= text.text.length)
        assertEquals("ort", text.text.substring(run.start, run.end))
    }

    @Test
    fun `a break takes no room and holds no position`() {
        val text = ChapterText.of(
            chapter(Block(s = "one"), Block(t = BlockType.RULE), Block(s = "two"))
        )
        assertEquals("one\n\ntwo", text.text)
        assertEquals(3, text.toChapterOffset(text.toDisplayOffset(3)))
    }

    @Test
    fun `an empty chapter maps everything to zero rather than crashing`() {
        val text = ChapterText.of(Chapter())
        assertEquals("", text.text)
        assertEquals(0, text.toDisplayOffset(50))
        assertEquals(0, text.toChapterOffset(50))
    }

    @Test
    fun `an offset past the end of the chapter clamps to the last character`() {
        val text = ChapterText.of(chapter(Block(s = "one")))
        assertEquals(3, text.toDisplayOffset(999))
    }
}
