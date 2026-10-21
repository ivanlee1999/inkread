package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import me.ash.reader.domain.data.Diff
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import kotlin.math.roundToInt

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
    horizontalPadding: Dp = 16.dp,
    columnSpacing: Dp = 24.dp,
    restoreArticleId: String? = null,
    onArticlePositioned: (String, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
) {
    // peek builds structural rows without fetching pages. Only composed cells access get().
    val rows = rowPlan ?: newspaperRows(pagingItems.itemCount, columns, einkPageStart ?: 0,
        einkPageEnd ?: pagingItems.itemCount) { pagingItems.peek(it) is ArticleFlowItem.Date }
    val rowKey: (NewspaperRow) -> Any = { row ->
        val firstIndex = row.indices.first()
        pagingItems.peek(firstIndex)?.let { key(it) } ?: "placeholder-$firstIndex"
    }
    val renderRow: @Composable (Int, NewspaperRow) -> Unit = { rowIndex, row ->
        val firstIndex = row.indices.first()
        if (row.isDate) {
            val date = pagingItems[firstIndex] as? ArticleFlowItem.Date
            if (date != null) StickyHeader(date.date, isShowFeedIcon, articleListTonalElevation)
        } else {
            val ruleColor = MaterialTheme.colorScheme.onSurface
            val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
                val laneCount = columns.coerceIn(1, 4)
                val occupiedLanes = row.slots.sumOf { it.span }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val laneWidthDp = (maxWidth - columnSpacing * (laneCount - 1)) / laneCount
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("newspaper-row-$firstIndex").drawBehind {
                            val gap = columnSpacing.toPx()
                            val laneWidth = (size.width - gap * (laneCount - 1)) / laneCount
                            var precedingLanes = 0
                            row.slots.dropLast(1).forEach { slot ->
                                precedingLanes += slot.span
                                val boundary = precedingLanes * laneWidth + (precedingLanes - 0.5f) * gap
                                val x = if (isRtl) size.width - boundary else boundary
                                drawLine(
                                    color = ruleColor,
                                    start = Offset(x, 10.dp.toPx()),
                                    end = Offset(x, (size.height - 12.dp.toPx()).coerceAtLeast(10.dp.toPx())),
                                    strokeWidth = 0.5.dp.toPx(),
                                )
                            }
                        },
                        horizontalArrangement = Arrangement.spacedBy(columnSpacing),
                    ) {
                        row.slots.forEach { slot ->
                            Column(Modifier.width(laneWidthDp * slot.span + columnSpacing * (slot.span - 1))
                                .testTag("newspaper-slot-${slot.indices.first()}")) {
                                slot.indices.forEachIndexed { position, index ->
                                    if (position > 0) HorizontalDivider(color = ruleColor, thickness = 0.5.dp)
                                    val cellKey = (pagingItems.peek(index) as? ArticleFlowItem.Article)
                                        ?.articleWithFeed?.article?.id ?: "placeholder-$index"
                                    key(cellKey) {
                                        val item = pagingItems[index] as? ArticleFlowItem.Article
                                        if (item != null) {
                                            val article = item.articleWithFeed.article
                                            // This wrapper is a direct child of its slot, whose top
                                            // aligns with the lazy row. Lower briefs have nonzero y.
                                            Box(if (restoreArticleId == article.id) {
                                                Modifier.onGloballyPositioned { coordinates ->
                                                    onArticlePositioned(article.id, index, rowIndex,
                                                        columns, coordinates.positionInParent().y.roundToInt())
                                                }
                                            } else Modifier) {
                                                SwipeableArticleItem(
                                                    articleWithFeed = item.articleWithFeed,
                                                    isLead = row.isLead,
                                                    storyStyle = slot.style,
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
                            }
                        }
                        // An incomplete final row keeps the same printed-column width.
                        if (occupiedLanes < laneCount) {
                            val remaining = laneCount - occupiedLanes
                            Spacer(Modifier.width(laneWidthDp * remaining + columnSpacing * (remaining - 1)))
                        }
                    }
                }
                // A section rule closes the lead, not every row. Column rules and the
                // separators between stacked briefs already establish the reading order.
                if (row.isLead) HorizontalDivider(color = ruleColor, thickness = 0.5.dp)
            }
        }
    }
    if (!isShowStickyHeader) {
        items(
            count = rows.size,
            key = { rowKey(rows[it]) },
            contentType = { if (rows[it].isDate) CONTENT_TYPE_DATE_HEADER else CONTENT_TYPE_ARTICLE },
        ) { renderRow(it, rows[it]) }
    } else {
        // Sticky headers require separate lazy intervals; ordinary rows stay counted above.
        rows.forEachIndexed { rowIndex, row ->
            if (row.isDate) {
                stickyHeader(key = rowKey(row), contentType = CONTENT_TYPE_DATE_HEADER) {
                    renderRow(rowIndex, row)
                }
            } else {
                item(key = rowKey(row), contentType = CONTENT_TYPE_ARTICLE) { renderRow(rowIndex, row) }
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
