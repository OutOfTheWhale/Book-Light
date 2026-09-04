package com.outofthewhale.booklight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * What the reader asked for on the way out of the contents.
 *
 * Two different destinations, so a chapter number alone cannot carry the
 * answer: [Library] means leave the book altogether.
 */
sealed interface ContentsChoice {
    data class Chapter(val index: Int) : ContentsChoice
    data object Library : ContentsChoice
}

/**
 * The contents, reached from the book and chapter at the top of a page.
 *
 * BACK returns to the page being read, LIBRARY leaves the book behind. The
 * back gesture does the same as BACK, delivering no result at all.
 */
class ContentsScreen(
    sealedActivity: SealedLightActivity,
    private val entries: List<ContentsEntry>,
) : SimpleLightScreen<ContentsChoice>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 32.dp, vertical = 28.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .lightClickable { goBack() }
                            .padding(vertical = 4.dp),
                    ) {
                        LightIcon(icon = LightIcons.BACK)
                        LightText(
                            text = "BACK",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    LightText(
                        text = "LIBRARY",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier
                            .lightClickable { goBack(ContentsChoice.Library) }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                    )
                }

                LazyColumn {
                    items(entries, key = { it.chapter }) { entry ->
                        LightText(
                            // A chapter the book never named still has to be
                            // reachable, so it is listed by its number.
                            text = entry.title ?: "${entry.chapter + 1}",
                            variant = LightTextVariant.Copy,
                            lighten = entry.title == null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    goBack(ContentsChoice.Chapter(entry.chapter))
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
