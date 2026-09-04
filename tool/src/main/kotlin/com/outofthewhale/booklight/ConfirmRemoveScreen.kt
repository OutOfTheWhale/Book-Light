package com.outofthewhale.booklight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * Asks before a book is removed.
 *
 * Deleting is the one thing here that cannot be undone from the phone - the
 * book has to be sent across again - so it gets a question of its own rather
 * than happening under a long press.
 *
 * Returns true only if REMOVE was chosen. Backing out returns nothing.
 */
class ConfirmRemoveScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {

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
                    text = "REMOVE",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 28.dp),
                )
                LightText(
                    text = title,
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                LightText(
                    text = "This deletes the book from the phone. Your place in it goes too.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 44.dp),
                )
                LightText(
                    text = "REMOVE",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { goBack(true) }
                        .padding(vertical = 14.dp),
                )
                LightText(
                    text = "KEEP",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { goBack() }
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}
