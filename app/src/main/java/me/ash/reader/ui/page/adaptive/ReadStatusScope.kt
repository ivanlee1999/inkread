package me.ash.reader.ui.page.adaptive

/** Mirrors AbstractRssRepository.markAsRead's precedence and ArticleDao's strict date bound. */
internal data class ReadStatusScope(
    val groupId: String?,
    val feedId: String?,
    val articleId: String?,
    val beforeMillis: Long?,
) {
    fun matches(id: String, feed: String, group: String, publishedAt: Long): Boolean {
        val before = beforeMillis ?: Long.MAX_VALUE
        return when {
            groupId != null -> group == groupId && publishedAt < before
            feedId != null -> feed == feedId && publishedAt < before
            articleId != null -> id == articleId // Single-article updates ignore the date bound.
            else -> publishedAt < before
        }
    }
}
