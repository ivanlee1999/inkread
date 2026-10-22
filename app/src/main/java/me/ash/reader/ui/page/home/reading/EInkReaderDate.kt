package me.ash.reader.ui.page.home.reading

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** RSS keeps its publication-date presentation; Saved explicitly identifies capture time. */
internal fun einkReaderDate(date: Date, label: String? = null): String {
    val formatted = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(date)
    return label?.takeIf { it.isNotBlank() }?.let { "$it $formatted" } ?: formatted
}
