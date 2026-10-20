package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import me.ash.reader.domain.data.Diff
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed

@Suppress("FunctionName")
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.ArticleList(
    pagingItems: LazyPagingItems<ArticleFlowItem>,
    diffMap: Map<String, Diff>,
    isShowFeedIcon: Boolean,
    isShowStickyHeader: Boolean,
    articleListTonalElevation: Int,
    isSwipeEnabled: () -> Boolean = { true },
    isMenuEnabled: Boolean = true,
    onClick: (ArticleWithFeed, Int) -> Unit = { _, _ -> },
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
    einkPageStart: Int? = null,
    einkPageEnd: Int? = null,
    feedUnreadCounts: Map<String, Int> = emptyMap(),
    columns: Int = 1,
    rowPlan: List<NewspaperRow>? = null,
) {
    // peek builds structural rows without fetching pages. Only composed cells access get().
    val rows = rowPlan ?: newspaperRows(pagingItems.itemCount, columns, einkPageStart ?: 0,
        einkPageEnd ?: pagingItems.itemCount) { pagingItems.peek(it) is ArticleFlowItem.Date }
    val rowKey: (NewspaperRow) -> Any = { row ->
        val firstIndex = row.indices.first()
        pagingItems.peek(firstIndex)?.let { key(it) } ?: "placeholder-$firstIndex"
    }
    val renderRow: @Composable (NewspaperRow) -> Unit = { row ->
        val firstIndex = row.indices.first()
        if (row.isDate) {
            val date = pagingItems[firstIndex] as? ArticleFlowItem.Date
            if (date != null) StickyHeader(date.date, isShowFeedIcon, articleListTonalElevation)
        } else {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                row.indices.forEach { index ->
                    val cellKey = (pagingItems.peek(index) as? ArticleFlowItem.Article)
                        ?.articleWithFeed?.article?.id ?: "placeholder-$index"
                    key(cellKey) {
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                            val item = pagingItems[index] as? ArticleFlowItem.Article
                            if (item != null) {
                                val article = item.articleWithFeed.article
                                SwipeableArticleItem(
                                    articleWithFeed = item.articleWithFeed,
                                    isLead = row.isLead,
                                    isUnread = diffMap[article.id]?.isUnread ?: article.isUnread,
                                    feedUnreadCount = feedUnreadCounts[item.articleWithFeed.feed.id] ?: 0,
                                    articleListTonalElevation = articleListTonalElevation,
                                    onClick = { onClick(it, index) },
                                    isSwipeEnabled = isSwipeEnabled,
                                    isMenuEnabled = isMenuEnabled,
                                    onToggleStarred = onToggleStarred,
                                    onToggleRead = onToggleRead,
                                    onMarkAboveAsRead = if (index <= 1) null else onMarkAboveAsRead,
                                    onMarkBelowAsRead = if (index == pagingItems.itemCount - 1) null else onMarkBelowAsRead,
                                    onShare = onShare,
                                )
                            }
                        }
                    }
                }
                // Keep an unpaired supporting story at the same column width.
                if (!row.isLead && row.indices.size < columns) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    if (!isShowStickyHeader) {
        items(
            count = rows.size,
            key = { rowKey(rows[it]) },
            contentType = { if (rows[it].isDate) CONTENT_TYPE_DATE_HEADER else CONTENT_TYPE_ARTICLE },
        ) { renderRow(rows[it]) }
    } else {
        // Sticky headers require separate lazy intervals; ordinary rows stay counted above.
        rows.forEach { row ->
            if (row.isDate) {
                stickyHeader(key = rowKey(row), contentType = CONTENT_TYPE_DATE_HEADER) {
                    renderRow(row)
                }
            } else {
                item(key = rowKey(row), contentType = CONTENT_TYPE_ARTICLE) { renderRow(row) }
            }
        }
    }
}


private fun key(item: ArticleFlowItem): String = when (item) {
    is ArticleFlowItem.Article -> "article-${item.articleWithFeed.article.id}"
    is ArticleFlowItem.Date -> "date-${item.date}"
}

const val CONTENT_TYPE_ARTICLE = 1
const val CONTENT_TYPE_DATE_HEADER = 2
