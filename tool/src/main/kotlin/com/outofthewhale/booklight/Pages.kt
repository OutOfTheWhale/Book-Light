package com.outofthewhale.booklight

/**
 * Where the pages of a chapter begin and end.
 *
 * Offsets are into the laid-out text (see [ChapterText]), because that is what
 * the text measurer speaks. They are converted back to stored offsets before
 * anything is saved.
 */
data class Pages(val breaks: List<Int>) {

    val count: Int get() = (breaks.size - 1).coerceAtLeast(0)

    fun start(page: Int): Int = breaks.getOrElse(page.coerceIn(0, count)) { 0 }

    fun end(page: Int): Int = breaks.getOrElse(page.coerceIn(0, count) + 1) { breaks.lastOrNull() ?: 0 }

    /**
     * The page holding [offset].
     *
     * This is how a saved position becomes a page again after the type size or
     * the screen has changed - the offset is the durable thing, the page is
     * recomputed around it every time.
     */
    fun pageAt(offset: Int): Int {
        for (page in 0 until count) {
            if (offset < breaks[page + 1]) return page
        }
        return (count - 1).coerceAtLeast(0)
    }

    companion object {
        /**
         * Break a chapter into pages.
         *
         * [lineCount] and [lineTop] describe the chapter laid out at full
         * height, exactly as a `TextLayoutResult` reports it; [lineStart] gives
         * the character each line begins at. A page takes as many whole lines as
         * fit in [viewportHeight], and never fewer than one - a line taller than
         * the viewport would otherwise loop for ever.
         */
        fun of(
            lineCount: Int,
            viewportHeight: Float,
            lineTop: (Int) -> Float,
            lineBottom: (Int) -> Float,
            lineStart: (Int) -> Int,
            textLength: Int,
        ): Pages {
            if (lineCount <= 0 || viewportHeight <= 0f) return Pages(listOf(0, textLength))

            val breaks = mutableListOf(0)
            var first = 0
            while (first < lineCount) {
                val top = lineTop(first)
                var last = first
                while (last + 1 < lineCount && lineBottom(last + 1) - top <= viewportHeight) {
                    last++
                }
                first = last + 1
                breaks.add(if (first < lineCount) lineStart(first) else textLength)
            }
            return Pages(breaks)
        }
    }
}
