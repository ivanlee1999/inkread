package me.ash.reader.performance

import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.ui.page.home.feeds.FolderPublication
import me.ash.reader.ui.page.home.feeds.FolderSection
import java.util.Date

/** Versioned, network-free content. Change this only when deliberately starting a new baseline. */
internal object PerformanceFixture {
    const val VERSION = "inkread-responsive-v1"
    data class Scale(val name: String, val articles: Int, val folders: Int, val feedsPerFolder: Int, val firstFolderFeeds: Int) {
        val publications: Int get() = (folders - 1) * feedsPerFolder + firstFolderFeeds
    }
    fun scale(name: String) = when (name) {
        "small" -> Scale(name, 200, 24, 4, 8) // 100 publications total.
        "medium" -> Scale(name, 500, 24, 4, 300)
        "stress" -> Scale(name, 1_000, 100, 20, 1_000)
        else -> error("Unknown fixture scale: $name")
    }
    const val SAMPLES = 8
    const val WARMUPS = 2
    val readerHtml = buildString {
        repeat(160) { index ->
            append("<h2>Section $index</h2><p>Readable independent reporting. 城市图书馆重新开放，阅读让生活更加丰富。 ")
            repeat(8) { append("A measured paragraph preserves the whole story and gives each page meaningful content. ") }
            append("</p>")
        }
    }
    fun articles(scale: Scale): List<ArticleFlowItem> {
        val feed = Feed("perf-feed", "Independent Journal", url = "https://example.invalid/feed", groupId = "perf-group", accountId = 1)
        return (0 until scale.articles).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(
                Article("perf-article-$index", Date(1_700_000_000_000L - index * 60_000L),
                    "Story $index: ${listOf("A measured view of the world", "城市生活与科学文化", "Longer independent reporting on communities and the natural world")[index % 3]}",
                    author = "Fixture Reporter", rawDescription = readerHtml,
                    shortDescription = "A deterministic report of culture, science and community. ".repeat(2 + index % 7),
                    link = "https://example.invalid/story/$index", feedId = feed.id, accountId = 1,
                ).also { it.dateString = "10:00 AM" }, feed,
            ))
        }
    }
    fun folders(scale: Scale) = (0 until scale.folders).map { folder ->
        FolderSection("perf-folder-$folder", "Folder $folder: independent sources and culture",
            (0 until if (folder == 0) scale.firstFolderFeeds else scale.feedsPerFolder).map { index ->
                FolderPublication("perf-folder-$folder-feed-$index", "Publication $index: science and society",
                    "example.invalid", count = 20 + index)
            })
    }
}
