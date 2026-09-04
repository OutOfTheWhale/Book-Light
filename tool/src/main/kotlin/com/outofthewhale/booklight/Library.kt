package com.outofthewhale.booklight

import java.io.File

/**
 * Where books live: `books/` inside the tool's own directory.
 *
 * The tool cannot browse the phone's storage - the sandbox forbids the Context
 * and the content resolver that would take - so every book has to arrive here.
 */
fun booksDir(filesDir: File): File = File(filesDir, "books").also { it.mkdirs() }

/**
 * The inbox: anything dropped into the tool's shared folder.
 *
 * `LightFileShare` writes into a directory LightOS can reach, so a book that
 * arrives by any route lands here and is moved into the library on the next
 * look. It means the tool never has to know how a file got onto the phone.
 */
const val INBOX = "inbox"

/**
 * Move whatever turned up in the inbox into the library.
 *
 * Returns the names taken in. A file that cannot be moved is left where it is
 * rather than lost - it will be tried again next time.
 */
fun drainInbox(inboxNames: List<String>, read: (String) -> ByteArray?, booksDir: File): List<String> {
    val taken = mutableListOf<String>()
    for (name in inboxNames) {
        if (!name.endsWith(BookStore.EXTENSION, ignoreCase = true)) continue
        val bytes = read(name) ?: continue
        val destination = File(booksDir, File(name).name)
        val wrote = runCatching { destination.writeBytes(bytes) }.isSuccess
        if (wrote) taken.add(name)
    }
    return taken
}
