package com.outofthewhale.booklight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PagesTest {

    /** Ten lines, ten units tall each, ten characters each. */
    private fun pages(lines: Int, viewport: Float, height: Float = 10f, chars: Int = 10) =
        Pages.of(
            lineCount = lines,
            viewportHeight = viewport,
            lineTop = { it * height },
            lineBottom = { (it + 1) * height },
            lineStart = { it * chars },
            textLength = lines * chars,
        )

    @Test
    fun `a viewport holding three lines gives pages of three lines`() {
        val p = pages(lines = 9, viewport = 30f)
        assertEquals(3, p.count)
        assertEquals(listOf(0, 30, 60, 90), p.breaks)
    }

    @Test
    fun `a last page shorter than the viewport is still a page`() {
        val p = pages(lines = 7, viewport = 30f)
        assertEquals(3, p.count)
        assertEquals(70, p.end(2))
    }

    @Test
    fun `pages cover the text with no gap and no overlap`() {
        val p = pages(lines = 11, viewport = 40f)
        assertEquals(0, p.start(0))
        for (page in 0 until p.count - 1) {
            assertEquals(p.end(page), p.start(page + 1))
        }
        assertEquals(110, p.end(p.count - 1))
    }

    @Test
    fun `a line taller than the viewport still takes a page rather than looping`() {
        // Never fewer than one line per page: a heading larger than the screen
        // would otherwise never advance and the reader would hang.
        val p = pages(lines = 3, viewport = 5f, height = 10f)
        assertEquals(3, p.count)
    }

    @Test
    fun `an offset finds the page holding it`() {
        val p = pages(lines = 9, viewport = 30f)
        assertEquals(0, p.pageAt(0))
        assertEquals(0, p.pageAt(29))
        assertEquals(1, p.pageAt(30))
        assertEquals(2, p.pageAt(89))
    }

    @Test
    fun `an offset past the end lands on the last page, not off the end`() {
        // This is the path a saved position takes when the type has been made
        // larger since - it must land somewhere real.
        val p = pages(lines = 9, viewport = 30f)
        assertEquals(2, p.pageAt(9999))
    }

    @Test
    fun `an empty chapter is one empty page`() {
        val p = Pages.of(0, 100f, { 0f }, { 0f }, { 0 }, 0)
        assertTrue(p.count <= 1)
        assertEquals(0, p.start(0))
    }

    @Test
    fun `a viewport of no height does not divide the text into nothing`() {
        val p = pages(lines = 5, viewport = 0f)
        assertEquals(1, p.count)
        assertEquals(50, p.end(0))
    }
}
