package me.ash.reader.ui.page.home.flow

import androidx.compose.runtime.compositionLocalOf
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.GroupWithFeed

internal data class ArticleFeedIdentity(val accountId: Int, val feedId: String)

/** Current network preferences are separate from the retained edition's content and geometry. */
internal data class ArticleFeedPolicy(
    val allowRemoteMedia: Boolean,
    val openInBrowser: Boolean,
    val iconUrl: String?,
)

// Null is only the standalone preview/test fallback. FlowPage always supplies a map, including
// an empty map while preferences load, so unknown or removed sources cannot start requests.
internal val LocalArticleFeedPolicies = compositionLocalOf<Map<ArticleFeedIdentity, ArticleFeedPolicy>?> { null }

internal fun articleFeedPolicies(groups: List<GroupWithFeed>): Map<ArticleFeedIdentity, ArticleFeedPolicy> =
    groups.flatMap { it.feeds }.associate { feed ->
        ArticleFeedIdentity(feed.accountId, feed.id) to feed.networkPolicy()
    }

internal fun articleFeedPolicy(
    feed: Feed,
    current: Map<ArticleFeedIdentity, ArticleFeedPolicy>?,
): ArticleFeedPolicy = if (current == null) feed.networkPolicy()
else current[ArticleFeedIdentity(feed.accountId, feed.id)] ?: ArticleFeedPolicy(false, false, null)

private fun Feed.networkPolicy() = ArticleFeedPolicy(
    allowRemoteMedia = !isNewsletter,
    openInBrowser = isBrowser && !isNewsletter,
    iconUrl = icon.takeUnless { isNewsletter },
)
