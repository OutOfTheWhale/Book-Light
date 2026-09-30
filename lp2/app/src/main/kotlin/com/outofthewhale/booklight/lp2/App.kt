package com.outofthewhale.booklight.lp2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.outofthewhale.booklight.Book
import com.outofthewhale.booklight.BookEntry
import com.outofthewhale.booklight.BookStore
import com.outofthewhale.booklight.Chapter
import com.outofthewhale.booklight.ChapterText
import com.outofthewhale.booklight.ContentsEntry
import com.outofthewhale.booklight.Pages
import com.outofthewhale.booklight.Position
import com.outofthewhale.booklight.ProgressStore
import com.outofthewhale.booklight.annotated
import com.outofthewhale.booklight.chapterLabel
import com.outofthewhale.booklight.contents
import com.outofthewhale.booklight.fractionAt

internal sealed interface Route {
    data object Library : Route
    data object Reading : Route
    data object Contents : Route
    data object Settings : Route
    data class Remove(val entry: BookEntry) : Route
}

/** A book in the list, with how far through it the reader is. */
private data class Shelved(val entry: BookEntry, val fraction: Float?)

/** No page chosen yet - the saved offset decides once the text is measured. */
private const val UNRESOLVED = -1

/** The end of a chapter whose length is not known yet. */
private const val LAST_PAGE = Int.MAX_VALUE

/**
 * Clears the ghosting an e-ink panel leaves behind.
 *
 * There is nothing on this device an app can ask for a full refresh - no e-ink
 * system property, and nothing in /sys/class/graphics/fb0 but the standard
 * display-processor nodes. So the refresh is provoked: every pixel is driven
 * to black and then to white, which is what the controller does during a full
 * update anyway, and the residue of the previous page goes with it.
 *
 * Both halves have to be held long enough for the panel to physically settle.
 * A full update on e-ink runs to several hundred milliseconds, so a frame or
 * two is not enough: if black has not finished drawing before white is asked
 * for, the two cancel and nothing visible happens at all.
 */
private const val FLASH_PHASE_MS = 260L

/** How much room the page turners take at the foot of the page. */
private val CHEVRON_BAND = 34.dp

@Composable
private fun chevronBandPx(): Int =
    with(LocalDensity.current) { CHEVRON_BAND.roundToPx() }

@Composable
fun BookLightApp(bookStore: BookStore, progressStore: ProgressStore) {
    var route by remember { mutableStateOf<Route>(Route.Library) }
    var shelf by remember { mutableStateOf<List<Shelved>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    var openId by remember { mutableStateOf<String?>(null) }
    var book by remember { mutableStateOf<Book?>(null) }
    var resume by remember { mutableStateOf(Position()) }

    // Hoisted out of the reader so the contents can move it too.
    var chapterIndex by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(UNRESOLVED) }

    // Bumped whenever the shelf changes underneath, to re-read it.
    var revision by remember { mutableIntStateOf(0) }

    var flashTrigger by remember { mutableIntStateOf(0) }
    var lastDrawn by remember { mutableStateOf<String?>(null) }

    // Everything that alters what is on the panel, in one string. A full
    // refresh is not a tidy-up on this hardware - it is how a new screen gets
    // shown at all - so any change to this has to provoke one.
    val drawn = buildString {
        append(
            when (route) {
                // Page and chapter matter while reading; elsewhere the route
                // alone says what is on screen.
                is Route.Reading -> "reading|$chapterIndex|$page"
                else -> route.toString()
            }
        )
        append('|').append(ThemeController.textScale)
        append('|').append(ThemeController.isDark)
        append('|').append(ThemeController.flashOnChange)
        append('|').append(revision)
    }

    LaunchedEffect(drawn) {
        val previous = lastDrawn
        lastDrawn = drawn
        // Not on the very first frame, and not on the half-drawn moment
        // between opening a book and its saved page being worked out.
        val settling = route is Route.Reading && page == UNRESOLVED
        if (previous != null && !settling && ThemeController.flashOnChange) {
            flashTrigger += 1
        }
    }

    LaunchedEffect(route, revision) {
        if (route !is Route.Library) return@LaunchedEffect
        val entries = bookStore.list()

        // A book whose file has gone should not keep its mark: putting the same
        // file back later would resume somewhere the reader never left off.
        val ids = entries.map { it.id }
        progressStore.update { it.retaining(ids) }
        val progress = progressStore.read()

        val recent = progress.recent().withIndex().associate { (order, id) -> id to order }
        shelf = entries
            .map { entry ->
                Shelved(
                    entry = entry,
                    fraction = progress.of(entry.id)?.let { mark ->
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
        loaded = true
    }

    // Loading is keyed on the id, not the route, so stepping out to the
    // contents and back does not throw the open book away and re-read it.
    LaunchedEffect(openId) {
        val id = openId ?: return@LaunchedEffect
        val mark = progressStore.read().of(id)?.position ?: Position()
        resume = mark
        chapterIndex = mark.chapter.coerceAtLeast(0)
        page = UNRESOLVED
        book = bookStore.load(id)
    }

    EInkFlash(flashTrigger) {
    when (val here = route) {
        is Route.Library -> LibraryView(
            shelf = shelf,
            loaded = loaded,
            onOpen = { id ->
                book = null
                openId = id
                route = Route.Reading
            },
            onHold = { route = Route.Remove(it) },
            onSettings = { route = Route.Settings },
        )

        is Route.Reading -> {
            val open = book
            if (open == null) {
                Centred("Opening...")
            } else {
                ReaderView(
                    book = open,
                    chapterIndex = chapterIndex,
                    storedPage = page,
                    resume = resume,
                    onChapter = { chapterIndex = it },
                    onPage = { page = it },
                    onContents = { route = Route.Contents },
                    onSave = { position ->
                        openId?.let { progressStore.save(it, position, System.currentTimeMillis()) }
                    },
                )
            }
        }

        is Route.Contents -> ContentsView(
            entries = book?.contents().orEmpty(),
            onPick = { index ->
                chapterIndex = index
                page = 0
                route = Route.Reading
            },
            onBack = { route = Route.Reading },
            onLibrary = {
                book = null
                openId = null
                revision++
                route = Route.Library
            },
        )

        is Route.Settings -> SettingsView(onBack = { route = Route.Library })

        is Route.Remove -> RemoveView(
            title = here.entry.title,
            onRemove = {
                bookStore.delete(here.entry.id)
                revision++
                route = Route.Library
            },
            onKeep = { route = Route.Library },
        )
    }
    }
}

// -------------------------------------------------------------------------
// Library
// -------------------------------------------------------------------------

@Composable
private fun LibraryView(
    shelf: List<Shelved>,
    loaded: Boolean,
    onOpen: (String) -> Unit,
    onHold: (BookEntry) -> Unit,
    onSettings: () -> Unit,
) {
    val palette = LocalPalette.current
    val type = LocalTypography.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(text = "BOOKS", style = type.fine.copy(color = palette.contentSecondary))
            BasicText(
                text = "SETTINGS",
                style = type.fine.copy(color = palette.contentSecondary),
                modifier = Modifier
                    .clickable(onClick = onSettings)
                    .padding(4.dp),
            )
        }

        if (loaded && shelf.isEmpty()) {
            BasicText(
                text = "No books yet. Convert one on a computer with the Book Light " +
                    "window, then send it across.",
                style = type.detail.copy(color = palette.contentSecondary),
            )
        } else {
            LazyColumn {
                items(shelf, key = { it.entry.id }) { shelved ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Not clickable(): that has no long press, and
                            // holding a book is how you remove it.
                            .pointerInput(shelved.entry.id) {
                                detectTapGestures(
                                    onTap = { onOpen(shelved.entry.id) },
                                    onLongPress = { onHold(shelved.entry) },
                                )
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        BasicText(
                            text = shelved.entry.title,
                            style = type.subheading.copy(color = palette.content),
                        )
                        val detail = listOfNotNull(
                            shelved.entry.author,
                            shelved.fraction?.let { "${(it * 100).toInt()}%" },
                        ).joinToString("  -  ")
                        if (detail.isNotEmpty()) {
                            BasicText(
                                text = detail,
                                style = type.fine.copy(color = palette.contentSecondary),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Reading
// -------------------------------------------------------------------------

@Composable
private fun ReaderView(
    book: Book,
    chapterIndex: Int,
    storedPage: Int,
    resume: Position,
    onChapter: (Int) -> Unit,
    onPage: (Int) -> Unit,
    onContents: () -> Unit,
    onSave: suspend (Position) -> Unit,
) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    val lastChapter = (book.chapters.size - 1).coerceAtLeast(0)

    val chapterText = remember(book, chapterIndex) {
        ChapterText.of(book.chapters.getOrElse(chapterIndex) { Chapter() })
    }
    val bodyStyle = remember(type.reading, palette.content) {
        type.reading.copy(
            color = palette.content,
            textAlign = TextAlign.Center,
            hyphens = Hyphens.Auto,
        )
    }
    val annotated = remember(chapterText, bodyStyle) { chapterText.annotated(bodyStyle) }
    val measurer = rememberTextMeasurer()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Header(
            title = book.title,
            chapter = book.chapterLabel(chapterIndex),
            onClick = onContents,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 6.dp, start = 14.dp, end = 14.dp),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            val widthPx = constraints.maxWidth
            // The chevrons sit inside this box, so the text must stop above
            // them rather than run underneath.
            val heightPx = (constraints.maxHeight - chevronBandPx()).toFloat()

            val layout = remember(annotated, widthPx) {
                measurer.measure(
                    text = annotated,
                    style = bodyStyle,
                    constraints = Constraints(maxWidth = widthPx),
                )
            }
            val pages = remember(layout, heightPx) {
                Pages.of(
                    lineCount = layout.lineCount,
                    viewportHeight = heightPx,
                    lineTop = { layout.getLineTop(it) },
                    lineBottom = { layout.getLineBottom(it) },
                    lineStart = { layout.getLineStart(it) },
                    textLength = annotated.length,
                )
            }

            // The saved offset only becomes a page here, after measuring -
            // which is exactly why an offset is what gets stored.
            val current = when {
                storedPage == LAST_PAGE -> (pages.count - 1).coerceAtLeast(0)
                storedPage == UNRESOLVED && chapterIndex == resume.chapter ->
                    pages.pageAt(chapterText.toDisplayOffset(resume.charOffset))
                storedPage == UNRESOLVED -> 0
                else -> storedPage.coerceIn(0, (pages.count - 1).coerceAtLeast(0))
            }

            LaunchedEffect(pages, current, chapterIndex) {
                if (storedPage != current) onPage(current)
                onSave(Position(chapterIndex, chapterText.toChapterOffset(pages.start(current))))
            }

            val turn: (Int) -> Unit = { direction ->
                val next = current + direction
                when {
                    next in 0 until pages.count -> onPage(next)
                    next < 0 && chapterIndex > 0 -> {
                        onChapter(chapterIndex - 1)
                        onPage(LAST_PAGE)
                    }
                    next >= pages.count && chapterIndex < lastChapter -> {
                        onChapter(chapterIndex + 1)
                        onPage(0)
                    }
                }
            }

            // The gesture detector is started once and kept. Keying it on the
            // page instead would rebuild it on every turn, and keying it on
            // anything that does not change per page - as it first did - leaves
            // it holding the turn from the page before, so forward moves to the
            // page already showing and reading stops dead after one tap.
            val latestTurn by rememberUpdatedState(turn)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            // The page does nothing but turn. The contents live
                            // behind the header, so a tap while reading can
                            // never land somewhere unexpected.
                            if (offset.x < size.width / 3f) latestTurn(-1) else latestTurn(1)
                        }
                    },
            ) {
                BasicText(
                    text = annotated.subSequence(pages.start(current), pages.end(current)),
                    style = bodyStyle,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Chevrons(
                colour = palette.contentSecondary,
                onBack = { turn(-1) },
                onForward = { turn(1) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * What is being read, above the page.
 *
 * Both lines are dim and small, so the eye passes over them on the way to the
 * text. The chapter line is left out entirely when the book never named one.
 */
@Composable
private fun Header(
    title: String,
    chapter: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = title,
            style = type.fine.copy(color = palette.contentSecondary, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!chapter.isNullOrBlank()) {
            BasicText(
                text = chapter,
                style = type.fine.copy(
                    color = palette.contentSecondary,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun Chevrons(
    colour: Color,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CHEVRON_BAND),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(colour, pointsLeft = true, onClick = onBack)
        Box(modifier = Modifier.size(width = 24.dp, height = 1.dp))
        Chevron(colour, pointsLeft = false, onClick = onForward)
    }
}

@Composable
private fun Chevron(colour: Color, pointsLeft: Boolean, onClick: () -> Unit) {
    Canvas(
        // clickable rather than a raw gesture detector: it follows the current
        // lambda, where a remembered detector would keep the first one.
        modifier = Modifier
            .size(30.dp)
            .clickable(onClick = onClick),
    ) {
        val tipX = if (pointsLeft) size.width * 0.38f else size.width * 0.62f
        val backX = if (pointsLeft) size.width * 0.60f else size.width * 0.40f
        drawLine(
            colour,
            Offset(backX, size.height * 0.30f),
            Offset(tipX, size.height * 0.5f),
            strokeWidth = 2f,
        )
        drawLine(
            colour,
            Offset(tipX, size.height * 0.5f),
            Offset(backX, size.height * 0.70f),
            strokeWidth = 2f,
        )
    }
}

// -------------------------------------------------------------------------
// Contents, settings, removal
// -------------------------------------------------------------------------

@Composable
private fun ContentsView(
    entries: List<ContentsEntry>,
    onPick: (Int) -> Unit,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
) {
    val palette = LocalPalette.current
    val type = LocalTypography.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "BACK",
                style = type.fine.copy(color = palette.contentSecondary),
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(4.dp),
            )
            BasicText(
                text = "LIBRARY",
                style = type.fine.copy(color = palette.contentSecondary),
                modifier = Modifier
                    .clickable(onClick = onLibrary)
                    .padding(4.dp),
            )
        }
        LazyColumn {
            items(entries, key = { it.chapter }) { entry ->
                BasicText(
                    // A chapter the book never named still has to be reachable,
                    // so it is listed by its number.
                    text = entry.title ?: "${entry.chapter + 1}",
                    style = type.detail.copy(
                        color = if (entry.title == null) {
                            palette.contentSecondary
                        } else {
                            palette.content
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(entry.chapter) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsView(onBack: () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        BasicText(
            text = "SETTINGS",
            style = type.heading.copy(color = palette.content),
            modifier = Modifier.padding(bottom = 10.dp),
        )

        BasicText(
            text = "TEXT SIZE",
            style = type.fine.copy(color = palette.contentSecondary),
            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
        )
        BasicText(
            text = "${(ThemeController.textScale * 100).toInt()}%",
            style = type.subheading.copy(color = palette.content),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { ThemeController.cycleTextScale() }
                .padding(vertical = 4.dp),
        )

        BasicText(
            text = "THEME",
            style = type.fine.copy(color = palette.contentSecondary),
            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
        )
        BasicText(
            text = if (ThemeController.isDark) "Dark" else "Light",
            style = type.subheading.copy(color = palette.content),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { ThemeController.toggle() }
                .padding(vertical = 4.dp),
        )

        BasicText(
            text = "SCREEN REFRESH",
            style = type.fine.copy(color = palette.contentSecondary),
            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
        )
        BasicText(
            text = if (ThemeController.flashOnChange) "On" else "Off",
            style = type.subheading.copy(color = palette.content),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { ThemeController.toggleFlash() }
                .padding(vertical = 4.dp),
        )
        BasicText(
            text = "Flashes on every change, which is what makes the new screen appear.",
            style = type.fine.copy(color = palette.contentSecondary),
        )

        Box(modifier = Modifier.height(24.dp))
        BasicText(
            text = "BACK",
            style = type.fine.copy(color = palette.content),
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun RemoveView(title: String, onRemove: () -> Unit, onKeep: () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        BasicText(
            text = "REMOVE",
            style = type.fine.copy(color = palette.contentSecondary),
            modifier = Modifier.padding(bottom = 16.dp),
        )
        BasicText(text = title, style = type.subheading.copy(color = palette.content))
        BasicText(
            text = "This deletes the book from the phone. Your place in it goes too.",
            style = type.fine.copy(color = palette.contentSecondary),
            modifier = Modifier.padding(top = 6.dp, bottom = 28.dp),
        )
        BasicText(
            text = "REMOVE",
            style = type.subheading.copy(color = palette.content),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRemove)
                .padding(vertical = 10.dp),
        )
        BasicText(
            text = "KEEP",
            style = type.subheading.copy(color = palette.contentSecondary),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onKeep)
                .padding(vertical = 10.dp),
        )
    }
}

/**
 * Wraps the app, painting over it when [trigger] changes.
 *
 * Counting turns and deciding when to flash is the caller's business; this
 * only performs one when asked. Trigger 0 is the initial composition and must
 * not flash, or every launch would start with a black screen.
 */
@Composable
private fun EInkFlash(trigger: Int, content: @Composable () -> Unit) {
    var phase by remember { mutableIntStateOf(0) }

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        phase = 1
        delay(FLASH_PHASE_MS)
        phase = 2
        delay(FLASH_PHASE_MS)
        phase = 0
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (phase != 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (phase == 1) Color.Black else Color.White)
                    // Swallow taps for the third of a second this lasts, so a
                    // turn during the flash cannot land on the page beneath.
                    .pointerInput(Unit) { detectTapGestures { } }
            )
        }
    }
}

@Composable
private fun Centred(message: String) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = message, style = type.detail.copy(color = palette.contentSecondary))
    }
}
