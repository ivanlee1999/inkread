package me.ash.reader.ui.page.home.flow

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import org.junit.Assert.*
import org.junit.Test

class ArticleFeedPolicyTest {
    private val ordinary = Feed("feed", "Publication", icon = "https://example.test/icon",
        url = "https://example.test/feed", groupId = "group", accountId = 1, isBrowser = true)
    private fun policies(vararg feeds: Feed) = articleFeedPolicies(listOf(
        GroupWithFeed(Group("group", "Group", 1), feeds.toMutableList()),
    ))

    @Test fun markingRetainedEditionNewsletterImmediatelyDisablesPublisherImagesAndIcon() {
        val item = ArticleFlowItem.Article(ArticleWithFeed(Article(
            id = "story", date = Date(1), title = "Retained headline", rawDescription = "Original body",
            shortDescription = "Original preview", link = "https://example.test/article",
            feedId = ordinary.id, accountId = 1,
        ), ordinary))
        val state = NewspaperViewportState()
        val pager = Any()
        state.acceptSnapshot(pager, listOf(item))
        val rows = state.rows(1)
        val pages = listOf(NewspaperViewportPage(listOf(MeasuredNewspaperRow(rows.single(), 100))))
        state.pageCache.retainPartition(rows, "same-geometry", emptySet(), pages)
        val frozen = (state.items.single() as ArticleFlowItem.Article).articleWithFeed
        assertTrue(articleFeedPolicy(frozen.feed, policies(ordinary)).allowRemoteMedia)
        assertTrue(articleFeedPolicy(frozen.feed, policies(ordinary)).openInBrowser)

        val newsletter = ordinary.copy(isNewsletter = true)
        state.acceptSnapshot(pager, listOf(ArticleFlowItem.Article(item.articleWithFeed.copy(feed = newsletter))))
        val current = articleFeedPolicy(frozen.feed, policies(newsletter))

        assertFalse(current.allowRemoteMedia)
        assertFalse(current.openInBrowser)
        assertNull(current.iconUrl)
        assertSame(frozen, (state.items.single() as ArticleFlowItem.Article).articleWithFeed)
        assertEquals("Retained headline", frozen.article.title)
        assertEquals("Original body", frozen.article.rawDescription)
        assertSame(pages, state.pageCache.partition(state.rows(1), "same-geometry", emptySet()))
    }

    @Test fun missingOrOtherAccountPolicyDefersAllAutomaticRequests() {
        for (current in listOf(emptyMap(), policies(ordinary.copy(accountId = 2)))) {
            val policy = articleFeedPolicy(ordinary, current)
            assertFalse(policy.allowRemoteMedia)
            assertFalse(policy.openInBrowser)
            assertNull(policy.iconUrl)
        }
    }

    @Test fun unfilteredFeedPolicyWorksWhenFeedHasNoUnreadItems() {
        val policy = articleFeedPolicy(ordinary, policies(ordinary.copy(important = 0)))
        assertTrue(policy.allowRemoteMedia)
        assertTrue(policy.openInBrowser)
        assertEquals(ordinary.icon, policy.iconUrl)
    }

    @Test fun standalonePreviewRespectsNewsletterMarker() {
        val policy = articleFeedPolicy(ordinary.copy(isNewsletter = true), null)
        assertFalse(policy.allowRemoteMedia)
        assertFalse(policy.openInBrowser)
        assertNull(policy.iconUrl)
    }
}
