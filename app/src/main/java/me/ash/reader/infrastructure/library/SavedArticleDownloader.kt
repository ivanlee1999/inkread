package me.ash.reader.infrastructure.library

internal data class SavedExtractedArticle(
    val html: String,
    val title: String,
    val author: String?,
    val sourceName: String?,
    val excerpt: String?,
)
internal interface SavedArticleDownloader {
    suspend fun extract(url: String, titleHint: String?): SavedExtractedArticle
    suspend fun fetchImage(url: String): ByteArray
}
