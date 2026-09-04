package com.outofthewhale.booklight

import android.util.Base64
import kotlinx.serialization.json.Json
import java.io.File

/** A book in the library, as much as is known without opening it. */
data class BookEntry(
    /** The file name, which is also the key progress is stored under. */
    val id: String,
    val title: String,
    val author: String? = null,
    /** The cached cover image, or null for a book that has none. */
    val cover: File? = null,
)

/**
 * The books on the phone.
 *
 * One `.book` file per book, in one directory. Copy a file in, it appears;
 * delete it, it is gone. There is nothing else to keep in step.
 */
class BookStore(private val booksDir: File, private val coversDir: File? = null) {

    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<BookEntry> =
        booksDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXTENSION, ignoreCase = true) }
            ?.map { entry(it) }
            ?.sortedBy { it.title.lowercase() }
            .orEmpty()

    fun load(id: String): Book? {
        val file = File(booksDir, id)
        if (!file.isFile || file.parentFile != booksDir) return null
        return runCatching { json.decodeFromString<Book>(file.readText()) }.getOrNull()
    }

    /**
     * Read a book's name without parsing it.
     *
     * Listing the library must not cost a full parse - a novel is a megabyte or
     * more of JSON, and the list would stall on every open. The title and author
     * are the first two keys the converter writes, so a few kilobytes off the
     * front is enough, and a file that does not oblige falls back to its name.
     */
    private fun entry(file: File): BookEntry {
        val head = readHead(file, HEAD_BYTES)

        return BookEntry(
            id = file.name,
            title = jsonString(head, "title") ?: file.nameWithoutExtension,
            author = jsonString(head, "author"),
            cover = cachedCover(file),
        )
    }

    /**
     * The book's cover, decoded once and kept as an image file.
     *
     * The cover lives inside the .book, so finding it means reading the front
     * of the file - cheap once, wasteful on every listing. An empty cache file
     * records "this book has no cover", so a coverless book is not re-scanned
     * every time the library opens.
     */
    private fun cachedCover(file: File): File? {
        val covers = coversDir ?: return null
        val cache = File(covers, file.name + ".img")
        if (cache.isFile) return cache.takeIf { it.length() > 0 }

        covers.mkdirs()
        val encoded = jsonString(readHead(file, COVER_HEAD_BYTES), "cover")
        if (encoded == null) {
            runCatching { cache.writeBytes(ByteArray(0)) }
            return null
        }
        return runCatching {
            cache.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
            cache
        }.getOrNull()
    }

    private fun readHead(file: File, bytes: Int): String = runCatching {
        file.inputStream().use { stream ->
            val buffer = ByteArray(bytes)
            var filled = 0
            while (filled < bytes) {
                val read = stream.read(buffer, filled, bytes - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled <= 0) "" else String(buffer, 0, filled, Charsets.UTF_8)
        }
    }.getOrDefault("")

    companion object {
        const val EXTENSION = ".book"
        private const val HEAD_BYTES = 4096
        /** Enough to hold a cover of the size the converter allows. */
        private const val COVER_HEAD_BYTES = 512 * 1024

        /**
         * Pull one string value out of the front of a JSON document.
         *
         * Returns null for a key that is absent, is not a string (`"author":null`
         * is normal), or runs off the end of the buffer - all of which mean
         * "ask the file name instead", not "fail".
         */
        internal fun jsonString(source: String, key: String): String? {
            val marker = "\"$key\""
            var i = source.indexOf(marker)
            if (i < 0) return null
            i += marker.length
            while (i < source.length && source[i].isWhitespace()) i++
            if (i >= source.length || source[i] != ':') return null
            i++
            while (i < source.length && source[i].isWhitespace()) i++
            if (i >= source.length || source[i] != '"') return null
            i++

            val out = StringBuilder()
            while (i < source.length) {
                when (val c = source[i]) {
                    '\\' -> {
                        if (i + 1 >= source.length) return null
                        when (val escape = source[i + 1]) {
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'u' -> {
                                if (i + 5 >= source.length) return null
                                val code = source.substring(i + 2, i + 6).toIntOrNull(16)
                                    ?: return null
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> out.append(escape)
                        }
                        i += 2
                    }
                    '"' -> return out.toString()
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return null
        }
    }
}
