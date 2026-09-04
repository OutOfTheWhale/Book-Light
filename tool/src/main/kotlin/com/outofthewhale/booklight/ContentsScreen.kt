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
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * The contents, reached by tapping the middle of a page.
 *
 * Returns the chapter to jump to, or nothing at all if the reader backs out.
 */
class ContentsScreen(
    sealedActivity: SealedLightActivity,
    private val entries: List<ContentsEntry>,
) : SimpleLightScreen<Int>(sealedActivity) {

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
                LightText(
                    text = "CONTENTS",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
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
                                .lightClickable { goBack(entry.chapter) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
