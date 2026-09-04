package com.outofthewhale.booklight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow

class LibraryViewModel : LightViewModel<Unit>() {
    val books = MutableStateFlow<List<BookManifest>>(emptyList())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Phase 1 fills this from filesDir/books.
    }
}

@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LibraryViewModel>(sealedActivity) {

    override val viewModelClass: Class<LibraryViewModel> get() = LibraryViewModel::class.java

    override fun createViewModel() = LibraryViewModel()

    @Composable
    override fun Content() {
        val books by viewModel.books.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(32.dp)
            ) {
                LightText(
                    text = "BOOK LIGHT",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
                if (books.isEmpty()) {
                    LightText(
                        text = "No books yet.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                }
            }
        }
    }
}
