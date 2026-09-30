package com.outofthewhale.booklight.lp2

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.outofthewhale.booklight.BookStore
import com.outofthewhale.booklight.ProgressStore
import com.outofthewhale.booklight.booksDir
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "booklight")

private val DARK = booleanPreferencesKey("ui.dark")
private val TEXT_SCALE = floatPreferencesKey("ui.textScale")
private val FLASH = booleanPreferencesKey("ui.flashOnChange")

/**
 * The Light Phone 2 build.
 *
 * Everything below the screen layer is the same code the LP3 tool runs - the
 * book format, the chapter flattening, the pagination and the saved reading
 * position are shared by source path, not copied. Only the drawing differs,
 * because `LightScreen` and the `Light*` primitives exist solely inside the SDK.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The app's own external directory, which needs no permission and is
        // where `tools/push.py` puts an LP2 book - a plain `adb push`, with
        // none of the run-as staging the sandboxed LP3 tool requires. Falls
        // back to internal storage if external is unavailable.
        val root = getExternalFilesDir(null) ?: filesDir
        val books = booksDir(root)

        // Read before the first frame rather than after it: restoring the look
        // asynchronously would draw the wrong theme and size for a moment, and
        // this is two values out of a store the process has open anyway.
        val saved = runBlocking { dataStore.data.first() }
        ThemeController.restore(
            dark = saved[DARK] ?: true,
            scale = saved[TEXT_SCALE] ?: DEFAULT_TEXT_SCALE,
            flash = saved[FLASH] ?: true,
        ) { dark, scale, flash ->
            lifecycleScope.launch {
                dataStore.edit {
                    it[DARK] = dark
                    it[TEXT_SCALE] = scale
                    it[FLASH] = flash
                }
            }
        }

        setContent {
            BookLightTheme {
                BookLightApp(
                    bookStore = BookStore(books),
                    progressStore = ProgressStore(dataStore),
                )
            }
        }
    }
}
