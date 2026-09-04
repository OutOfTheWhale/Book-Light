package com.outofthewhale.booklight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightFileShare
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** A book in the list, with how far through it the reader is. */
data class Shelved(val entry: BookEntry, val fraction: Float?)

class LibraryViewModel(
    private val booksDir: File,
    private val bookStore: BookStore,
    private val progressStore: ProgressStore,
    private val fileShare: LightFileShare?,
) : LightViewModel<Unit>() {

    val books = MutableStateFlow<List<Shelved>>(emptyList())
    val loaded = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        takeInInbox()

        val entries = bookStore.list()
        val progress = progressStore.read()

        // A book whose file has gone should not keep its mark: putting the same
        // file back later would resume somewhere the reader never left off.
        val ids = entries.map { it.id }
        val tidied = progress.retaining(ids)
        if (tidied !== progress) progressStore.update { it.retaining(ids) }

        val recent = tidied.recent().withIndex().associate { (order, id) -> id to order }
        books.value = entries
            .map { entry ->
                Shelved(
                    entry = entry,
                    fraction = tidied.of(entry.id)?.let { mark ->
                        bookStore.load(entry.id)?.fractionAt(mark.position)
                    },
                )
            }
            // Whatever was read last sits at the top; everything unread keeps
            // its alphabetical order below.
            .sortedWith(
                compareBy(
                    { recent[it.entry.id] ?: Int.MAX_VALUE },
                    { it.entry.title.lowercase() },
                )
            )
        loaded.value = true
    }

    private fun takeInInbox() {
        val share = fileShare ?: return
        val names = runCatching { share.list(INBOX) }.getOrDefault(emptyList())
        if (names.isEmpty()) return
        val taken = drainInbox(
            inboxNames = names,
            read = { name ->
                runCatching {
                    share.read("$INBOX/$name") { it.readText().toByteArray() }
                }.getOrNull()
            },
            booksDir = booksDir,
        )
        taken.forEach { runCatching { share.delete("$INBOX/$it") } }
    }
}

@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LibraryViewModel>(sealedActivity) {

    override val viewModelClass: Class<LibraryViewModel> get() = LibraryViewModel::class.java

    override fun createViewModel(): LibraryViewModel {
        val booksDir = booksDir(lightContext.filesDir)
        return LibraryViewModel(
            booksDir = booksDir,
            bookStore = BookStore(booksDir),
            progressStore = ProgressStore(lightContext.dataStore),
            fileShare = lightContext.fileShare,
        )
    }

    @Composable
    override fun Content() {
        val books by viewModel.books.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            val colors = LightThemeTokens.colors
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(horizontal = 32.dp, vertical = 28.dp)
            ) {
                LightText(
                    text = "BOOKS",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 20.dp),
                )

                if (loaded && books.isEmpty()) {
                    LightText(
                        text = "No books yet.\n\nConvert one on a computer with " +
                            "convert.py and copy the .book file across.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                } else {
                    LazyColumn {
                        items(books, key = { it.entry.id }) { shelved ->
                            Row(shelved) { open(shelved.entry.id) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Row(shelved: Shelved, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 14.dp)
        ) {
            LightText(text = shelved.entry.title, variant = LightTextVariant.Copy)
            val detail = listOfNotNull(
                shelved.entry.author,
                shelved.fraction?.let { "${(it * 100).toInt()}%" },
            ).joinToString("  ·  ")
            if (detail.isNotEmpty()) {
                LightText(
                    text = detail,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    private fun open(bookId: String) {
        navigateTo<Unit>({ sealed -> ReaderScreen(sealed, bookId) })
    }
}
