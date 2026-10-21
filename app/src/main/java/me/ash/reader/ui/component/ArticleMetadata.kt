package me.ash.reader.ui.component

private val bylineWhitespace = Regex("[\\s\\p{Z}\u0085]+")
private val bylineBidiFormatting = Regex("[\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]")

/** Source and actual author, without blank labels or repeated attribution. */
fun articleByline(source: String, author: String?, showSource: Boolean = true): String {
    fun String.normalized() = replace(bylineWhitespace, " ").trim()
    // Formatting marks affect display direction, not whether two labels name the same source.
    fun String.comparisonKey() = replace(bylineBidiFormatting, "").normalized()

    val publication = source.normalized()
    val writer = author.orEmpty().normalized()
    val publicationKey = publication.comparisonKey()
    val writerKey = writer.comparisonKey()
    return listOfNotNull(
        publication.takeIf { showSource && publicationKey.isNotEmpty() },
        writer.takeIf { writerKey.isNotEmpty() && !writerKey.equals(publicationKey, ignoreCase = true) },
    ).joinToString(" · ")
}
