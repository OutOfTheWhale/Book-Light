package com.outofthewhale.booklight

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookStoreTest {

    private fun read(source: String, key: String = "title") =
        BookStore.jsonString(source, key)

    @Test
    fun `a title is read off the front of the document`() {
        assertEquals("Moby Dick", read("""{"title":"Moby Dick","author":"Melville"}"""))
    }

    @Test
    fun `an author is read too`() {
        assertEquals("Melville", read("""{"title":"Moby Dick","author":"Melville"}""", "author"))
    }

    @Test
    fun `a null author reads as no author rather than the word null`() {
        assertNull(read("""{"title":"B","author":null}""", "author"))
    }

    @Test
    fun `an escaped quote inside the title survives`() {
        assertEquals("He said \"hi\"", read("""{"title":"He said \"hi\""}"""))
    }

    @Test
    fun `a unicode escape is decoded`() {
        // Written as a normal string so the backslash is unmistakably a
        // backslash: this is the six characters a JSON file actually holds.
        val json = "{\"title\":\"caf\u00e9\"}"
        assertEquals("caf\u00e9", read(json))
    }

    @Test
    fun `a non-ascii character passes through unharmed`() {
        assertEquals("caf\u00e9", read("""{"title":"caf\u00e9"}"""))
    }

    @Test
    fun `a title cut off by the end of the buffer reads as absent`() {
        // The head is only the first few kilobytes. A truncated string means
        // "fall back to the file name", not "show half a title".
        assertNull(read("""{"title":"A very long tit"""))
    }

    @Test
    fun `a missing key reads as absent`() {
        assertNull(read("""{"author":"Melville"}"""))
    }

    @Test
    fun `whitespace after the colon is allowed`() {
        assertEquals("B", read("""{"title" : "B"}"""))
    }

    @Test
    fun `an empty head reads as absent rather than throwing`() {
        assertNull(read(""))
    }

    // -- removing ---------------------------------------------------------

    private fun shelf(): Pair<File, BookStore> {
        val dir = Files.createTempDirectory("booklight").toFile()
        dir.deleteOnExit()
        return dir to BookStore(dir)
    }

    @Test
    fun `a book is removed from the shelf`() {
        val (dir, store) = shelf()
        val book = File(dir, "A Book.book").apply { writeText("""{"title":"A Book"}""") }

        assertTrue(store.delete("A Book.book"))
        assertFalse(book.exists())
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `removing a book that is not there is false, not a crash`() {
        val (_, store) = shelf()
        assertFalse(store.delete("Never Existed.book"))
    }

    @Test
    fun `a name that climbs out of the books folder is refused`() {
        // The id comes from a file name, but nothing downstream should have to
        // trust that - deleting is the one operation here with no undo.
        val (dir, store) = shelf()
        val outside = File(dir.parentFile, "keep-me.book").apply { writeText("x") }
        outside.deleteOnExit()

        assertFalse(store.delete("../" + outside.name))
        assertTrue(outside.exists())
    }

    @Test
    fun `removing one book leaves the others alone`() {
        val (dir, store) = shelf()
        File(dir, "One.book").writeText("""{"title":"One"}""")
        File(dir, "Two.book").writeText("""{"title":"Two"}""")

        store.delete("One.book")
        assertEquals(listOf("Two"), store.list().map { it.title })
    }
}
