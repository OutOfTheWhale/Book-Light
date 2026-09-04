package com.outofthewhale.booklight

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * A chapter as styled text.
 *
 * Shared with the Light Phone 2 build: it is Compose, but not the SDK, so both
 * phones draw a chapter from exactly the same description.
 */
fun ChapterText.annotated(base: TextStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    for (run in runs) {
        val style = when (run.kind) {
            RunKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            RunKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            RunKind.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic)
            RunKind.H1 -> SpanStyle(fontSize = base.fontSize * 1.25f)
            RunKind.H2 -> SpanStyle(fontSize = base.fontSize * 1.15f)
            RunKind.H3 -> SpanStyle(fontSize = base.fontSize * 1.05f)
        }
        addStyle(style, run.start, run.end)
    }
}
