package com.outofthewhale.booklight.lp2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * The Light Phone look, rebuilt without the SDK.
 *
 * The LP3 tool gets `LightTheme` and its primitives from `com.thelightphone.*`,
 * none of which exists here. The palette keeps the same shape - a background, a
 * foreground and one muted tone - because the constraint that produced it still
 * holds: these screens are black and white, so meaning comes from weight, size
 * and placement rather than colour.
 */
data class Palette(
    val background: Color,
    val content: Color,
    val contentSecondary: Color,
) {
    companion object {
        val Dark = Palette(
            background = Color.Black,
            content = Color.White,
            contentSecondary = Color(0xFFBBBBBB),
        )
        val Light = Palette(
            background = Color.White,
            content = Color.Black,
            contentSecondary = Color(0xFF666666),
        )
    }
}

data class Typography(
    val heading: TextStyle,
    val subheading: TextStyle,
    /** The page itself: a serif, because a book wants a book face. */
    val reading: TextStyle,
    val detail: TextStyle,
    val fine: TextStyle,
) {
    companion object {
        val Default = Typography(
            heading = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal),
            subheading = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal),
            reading = TextStyle(
                fontSize = 15.sp,
                lineHeight = 24.sp,
                fontFamily = FontFamily.Serif,
            ),
            detail = TextStyle(fontSize = 13.sp),
            fine = TextStyle(fontSize = 11.sp),
        )
    }

    /** The same scale, every size multiplied by [factor]. */
    fun scaled(factor: Float): Typography = Typography(
        heading = heading.scale(factor),
        subheading = subheading.scale(factor),
        reading = reading.scale(factor),
        detail = detail.scale(factor),
        fine = fine.scale(factor),
    )
}

private fun TextStyle.scale(factor: Float) = copy(
    fontSize = fontSize * factor,
    // Only reading sets a line height; multiplying an unspecified one throws.
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
)

val LocalPalette = staticCompositionLocalOf { Palette.Dark }
val LocalTypography = staticCompositionLocalOf { Typography.Default }

/**
 * App-wide look state, so a change reaches every screen.
 *
 * Text size is a setting rather than a constant because this panel reports
 * itself as 160dpi while being physically about 270 - every size therefore
 * renders at roughly two fifths of its nominal height, and no single number
 * suits both reading a page and glancing at a list.
 */
object ThemeController {
    var palette by mutableStateOf(Palette.Dark)
        private set

    var textScale by mutableStateOf(DEFAULT_TEXT_SCALE)
        private set

    /** Where to write a change; set once, at startup. */
    private var persist: ((Boolean, Float) -> Unit)? = null

    val isDark: Boolean get() = palette == Palette.Dark

    /** Applies what was saved, and says where later changes should be kept. */
    fun restore(dark: Boolean, scale: Float, persist: (Boolean, Float) -> Unit) {
        palette = if (dark) Palette.Dark else Palette.Light
        textScale = scale.coerceIn(TEXT_SCALES.first(), TEXT_SCALES.last())
        this.persist = persist
    }

    fun toggle() {
        palette = if (isDark) Palette.Light else Palette.Dark
        persist?.invoke(isDark, textScale)
    }

    /** Steps to the next size, wrapping at the largest. */
    fun cycleTextScale() {
        val next = TEXT_SCALES.firstOrNull { it > textScale + 0.01f } ?: TEXT_SCALES.first()
        textScale = next
        persist?.invoke(isDark, next)
    }
}

val TEXT_SCALES = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/**
 * Bigger than it looks. The same finding as Word of Light: at 1.0 a line on
 * this display runs far longer than the Light Phone III's, so the default
 * starts well above nominal rather than at it.
 */
const val DEFAULT_TEXT_SCALE = 1.5f

@Composable
fun BookLightTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPalette provides ThemeController.palette,
        LocalTypography provides Typography.Default.scaled(ThemeController.textScale),
        content = content,
    )
}
