package com.outofthewhale.booklight

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Which chapter and page are showing, and where the reader left off.
 *
 * Page and chapter live here rather than in the composition so that a volume
 * key and a chapter chosen from the contents can move the reader too - both
 * arrive from outside the composition and would have nowhere to write.
 */
class ReaderViewModel(
    private val bookStore: BookStore,
    private val progressStore: ProgressStore,
    val bookId: String,
) : LightViewModel<Unit>() {

    val book = MutableStateFlow<Book?>(null)
    val loading = MutableStateFlow(true)

    /** Where the book was left. Only used until the first page is settled. */
    val resume = MutableStateFlow(Position())

    val chapter = MutableStateFlow(0)
    val page = MutableStateFlow(UNRESOLVED)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!loading.value) return
        viewModelScope.launch {
            val mark = progressStore.read().of(bookId)?.position ?: Position()
            val loaded = bookStore.load(bookId)
            resume.value = mark
            chapter.value = mark.chapter.coerceAtLeast(0)
            book.value = loaded
            loading.value = false
        }
    }

    /**
     * Move by [direction] pages, running on into the next or previous chapter
     * at the ends. [pageCount] is only known once the text has been measured,
     * so it arrives with the call rather than being held here.
     */
    fun turn(direction: Int, pageCount: Int, lastChapter: Int) {
        val here = page.value
        if (here < 0 || here == LAST_PAGE) return
        val next = here + direction
        when {
            next in 0 until pageCount -> page.value = next
            next < 0 && chapter.value > 0 -> {
                chapter.value -= 1
                page.value = LAST_PAGE
            }
            next >= pageCount && chapter.value < lastChapter -> {
                chapter.value += 1
                page.value = 0
            }
        }
    }

    /** Record the page the composition actually settled on. */
    fun settle(resolved: Int) {
        if (page.value != resolved) page.value = resolved
    }

    fun jumpTo(chapterIndex: Int) {
        chapter.value = chapterIndex
        page.value = 0
    }

    fun save(position: Position) {
        viewModelScope.launch {
            progressStore.save(bookId, position, System.currentTimeMillis())
        }
    }

    companion object {
        /** No page chosen yet - the saved offset decides once the text is measured. */
        const val UNRESOLVED = -1
        /** The end of a chapter whose length is not known yet. */
        const val LAST_PAGE = Int.MAX_VALUE
    }
}

/**
 * The reading screen.
 *
 * One page at a time, centred, the clock above and two chevrons below. Turning
 * a page is a tap on the left or right third of the screen, a chevron, or a
 * volume key; a tap in the middle opens the contents.
 *
 * Pages are worked out by measuring the whole chapter and cutting it into
 * screenfuls. Nothing durable depends on that measurement: what gets saved is a
 * character offset, so a different type size or a different phone re-finds the
 * same words on whatever page they now fall.
 */
class ReaderScreen(
    sealedActivity: SealedLightActivity,
    private val bookId: String,
) : LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    override val viewModelClass: Class<ReaderViewModel> get() = ReaderViewModel::class.java

    override fun createViewModel() = ReaderViewModel(
        bookStore = BookStore(booksDir(lightContext.filesDir)),
        progressStore = ProgressStore(lightContext.dataStore),
        bookId = bookId,
    )

    /** Set by the composition once it knows how many pages the chapter has. */
    private var turnPage: ((Int) -> Unit)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> { turnPage?.invoke(1); true }
        KeyEvent.KEYCODE_VOLUME_UP -> { turnPage?.invoke(-1); true }
        else -> super.onKeyDown(keyCode, event)
    }

    @Composable
    override fun Content() {
        val book by viewModel.book.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
                contentAlignment = Alignment.Center,
            ) {
                val loaded = book
                when {
                    loading -> Unit
                    loaded == null -> LightText(
                        text = "Could not open this book.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                    else -> Reading(loaded)
                }
            }
        }
    }

    @Composable
    private fun Reading(book: Book) {
        val colors = LightThemeTokens.colors
        val chapterIndex by viewModel.chapter.collectAsState()
        val storedPage by viewModel.page.collectAsState()
        val resume by viewModel.resume.collectAsState()
        val lastChapter = (book.chapters.size - 1).coerceAtLeast(0)

        val chapterText = remember(book, chapterIndex) {
            ChapterText.of(book.chapters.getOrElse(chapterIndex) { Chapter() })
        }
        val bodyStyle = remember(colors.content) {
            TextStyle(
                // A serif, as the reading screen has. The Light face stays on
                // the chrome; a book wants a book face.
                fontFamily = FontFamily.Serif,
                fontSize = BODY_SIZE,
                lineHeight = BODY_LINE_HEIGHT,
                color = colors.content,
                textAlign = TextAlign.Center,
                hyphens = Hyphens.Auto,
            )
        }
        val annotated = remember(chapterText, bodyStyle) { chapterText.annotated(bodyStyle) }
        val measurer = rememberTextMeasurer()

        Column(modifier = Modifier.fillMaxSize()) {
            Clock(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 12.dp)
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = SIDE_MARGIN),
            ) {
                val widthPx = constraints.maxWidth
                val heightPx = constraints.maxHeight.toFloat()

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
                    storedPage == ReaderViewModel.LAST_PAGE -> (pages.count - 1).coerceAtLeast(0)
                    storedPage == ReaderViewModel.UNRESOLVED && chapterIndex == resume.chapter ->
                        pages.pageAt(chapterText.toDisplayOffset(resume.charOffset))
                    storedPage == ReaderViewModel.UNRESOLVED -> 0
                    else -> storedPage.coerceIn(0, (pages.count - 1).coerceAtLeast(0))
                }

                LaunchedEffect(pages, current, chapterIndex) {
                    viewModel.settle(current)
                    viewModel.save(
                        Position(chapterIndex, chapterText.toChapterOffset(pages.start(current)))
                    )
                }

                val turn: (Int) -> Unit = { direction ->
                    viewModel.turn(direction, pages.count, lastChapter)
                }
                turnPage = turn

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(pages, chapterIndex) {
                            detectTapGestures { offset ->
                                when {
                                    offset.x < size.width / 3f -> turn(-1)
                                    offset.x > size.width * 2f / 3f -> turn(1)
                                    else -> openContents(book)
                                }
                            }
                        },
                ) {
                    BasicText(
                        text = annotated.subSequence(pages.start(current), pages.end(current)),
                        style = bodyStyle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Chevrons(
                colour = colors.contentSecondary,
                onBack = { turnPage?.invoke(-1) },
                onForward = { turnPage?.invoke(1) },
            )
        }
    }

    private fun openContents(book: Book) {
        // The callback runs only when a chapter was actually chosen - backing
        // out of the contents delivers no result at all.
        navigateTo({ sealed -> ContentsScreen(sealed, book.chapters.map { it.title }) }) { chosen ->
            viewModel.jumpTo(chosen)
        }
    }

    private companion object {
        val BODY_SIZE = 30.sp
        val BODY_LINE_HEIGHT = 48.sp
        val SIDE_MARGIN = 34.dp
    }
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = LocalTime.now()
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        LightText(
            text = now.format(CLOCK_FORMAT),
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun Chevrons(colour: Color, onBack: () -> Unit, onForward: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(colour, pointsLeft = true, onClick = onBack)
        Box(modifier = Modifier.size(width = 30.dp, height = 1.dp))
        Chevron(colour, pointsLeft = false, onClick = onForward)
    }
}

@Composable
private fun Chevron(colour: Color, pointsLeft: Boolean, onClick: () -> Unit) {
    Canvas(
        modifier = Modifier
            .size(44.dp)
            .pointerInput(Unit) { detectTapGestures { onClick() } },
    ) {
        val tipX = if (pointsLeft) size.width * 0.38f else size.width * 0.62f
        val backX = if (pointsLeft) size.width * 0.60f else size.width * 0.40f
        val top = size.height * 0.30f
        val middle = size.height * 0.50f
        val bottom = size.height * 0.70f
        drawLine(colour, Offset(backX, top), Offset(tipX, middle), strokeWidth = 3f)
        drawLine(colour, Offset(tipX, middle), Offset(backX, bottom), strokeWidth = 3f)
    }
}
