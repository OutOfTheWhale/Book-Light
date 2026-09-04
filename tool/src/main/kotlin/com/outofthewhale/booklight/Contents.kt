package com.outofthewhale.booklight

/** A chapter the contents will list, and where jumping to it lands. */
data class ContentsEntry(val chapter: Int, val title: String?)

/**
 * Everything a book's contents should list.
 *
 * Chapters split only so the phone could lay them out are left out: they are
 * the same chapter, and listing them put a bare "2" and "6" between the real
 * entries. Reading runs into them on its own at a page turn.
 */
fun Book.contents(): List<ContentsEntry> =
    chapters.mapIndexedNotNull { index, chapter ->
        if (chapter.continues) null else ContentsEntry(index, chapter.title)
    }

/**
 * What to call the chapter at [index] at the top of the page.
 *
 * A continuation has no name of its own, so it borrows the one it continues -
 * otherwise the heading would blank out halfway through a long chapter, which
 * reads as a bug rather than as a page turn.
 */
fun Book.chapterLabel(index: Int): String? {
    if (chapters.isEmpty()) return null
    var at = index.coerceIn(0, chapters.lastIndex)
    while (at > 0 && chapters[at].continues) at--
    return chapters[at].title
}
