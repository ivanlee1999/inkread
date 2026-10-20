package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.round
import coil.size.Precision
import coil.size.Scale
import me.ash.reader.R
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.infrastructure.preference.FlowArticleListDescPreference
import me.ash.reader.infrastructure.preference.FlowArticleReadIndicatorPreference
import me.ash.reader.infrastructure.preference.LocalArticleListSwipeEndAction
import me.ash.reader.infrastructure.preference.LocalArticleListSwipeStartAction
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedName
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListReadIndicator
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime
import me.ash.reader.infrastructure.preference.SwipeEndActionPreference
import me.ash.reader.infrastructure.preference.SwipeStartActionPreference
import me.ash.reader.infrastructure.rss.articleThumbnail
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.base.RYAsyncImage
import me.ash.reader.ui.component.menu.AnimatedDropdownMenu
import me.ash.reader.ui.ext.requiresBidi
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.layout.resolveArticleImageHeightDp
import me.ash.reader.ui.page.settings.color.flow.generateArticleWithFeedPreview
import me.ash.reader.ui.theme.applyTextDirection
import me.ash.reader.ui.theme.palette.onDark

@Composable
fun ArticleItem(
    modifier: Modifier = Modifier,
    articleWithFeed: ArticleWithFeed,
    isUnread: Boolean = articleWithFeed.article.isUnread,
    feedUnreadCount: Int = 0,
    isLead: Boolean = false,
    onClick: (ArticleWithFeed) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val feed = articleWithFeed.feed
    val article = articleWithFeed.article
    // Reuse an article's saved content, without downloading anything to build the list.
    // Disabling images also skips HTML inspection, not just the visible image slot.
    val image = if (LocalFlowArticleListImage.current.value) {
        remember(article.img, article.rawDescription, article.link) {
            articleThumbnail(article.img, article.rawDescription, article.link)
        }
    } else null

    ArticleItem(
        modifier = modifier,
        feedName = feed.name,
        feedIconUrl = feed.icon,
        feedUnreadCount = feedUnreadCount,
        isLead = isLead,
        title = article.title,
        shortDescription = article.shortDescription,
        timeString = article.dateString,
        imgData = image,
        isStarred = article.isStarred,
        isUnread = isUnread,
        onClick = { onClick(articleWithFeed) },
        onLongClick = onLongClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleItem(
    modifier: Modifier = Modifier,
    feedName: String = "",
    feedIconUrl: String? = null,
    feedUnreadCount: Int = 0,
    isLead: Boolean = false,
    title: String = "",
    shortDescription: String = "",
    timeString: String? = null,
    imgData: Any? = null,
    isStarred: Boolean = false,
    isUnread: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val articleListFeedIcon = LocalFlowArticleListFeedIcon.current
    val articleListFeedName = LocalFlowArticleListFeedName.current
    val articleListImage = LocalFlowArticleListImage.current
    val articleListDesc = LocalFlowArticleListDesc.current
    val articleListDate = LocalFlowArticleListTime.current
    val articleListReadIndicator = LocalFlowArticleListReadIndicator.current

    val showReadIndicator = articleListReadIndicator != FlowArticleReadIndicatorPreference.None
    val showImage = articleListImage.value && imgData != null &&
        (imgData !is String || imgData.isNotBlank())
    val showSource = articleListFeedIcon.value || articleListFeedName.value ||
        isStarred || (showReadIndicator && isUnread)
    // An editorial block, not a dashboard card. The list owns the rules between rows.
    // No fixed text height: large type and longer headlines remain reachable by scrolling.
    BoxWithConstraints(modifier.fillMaxWidth().combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Button,
        onClick = onClick, onLongClick = onLongClick,
    )) {
        val compactColumn = !isLead && maxWidth < 220.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isLead) 10.dp else 8.dp),
        ) {
            if (isLead && showImage) {
                ArticlePhoto(data = imgData, isLead = true)
            }
            Text(
                modifier = Modifier.semantics { heading() },
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = if (isLead) 28.sp else if (compactColumn) 17.sp else 22.sp,
                    lineHeight = if (isLead) 34.sp else if (compactColumn) 22.sp else 28.sp,
                ).applyTextDirection(title.requiresBidi()),
                fontFamily = FontFamily.Serif,
                fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
            )
            if (!isLead && showImage) {
                ArticlePhoto(data = imgData, isLead = false)
            }
            if (articleListDesc != FlowArticleListDescPreference.NONE && shortDescription.isNotBlank()) {
                Text(
                    text = shortDescription,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = if (isLead) 16.sp else if (compactColumn) 13.sp else 15.sp,
                        lineHeight = if (isLead) 23.sp else if (compactColumn) 19.sp else 21.sp,
                    ).applyTextDirection(shortDescription.requiresBidi()),
                    fontFamily = FontFamily.Serif,
                    maxLines = if (articleListDesc == FlowArticleListDescPreference.LONG) {
                        if (isLead) 5 else 4
                    } else {
                        if (isLead) 3 else 2
                    },
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Keep provenance and status legible, but secondary to the photograph and headline.
            // Reading state never reduces contrast: the existing printed marker replaces fading.
            if (showSource) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (articleListFeedIcon.value) {
                        FeedIcon(feedName = feedName, iconUrl = feedIconUrl, animate = false)
                    }
                    if (articleListFeedName.value) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = if (feedUnreadCount > 0) "$feedName ($feedUnreadCount)" else feedName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else Spacer(Modifier.weight(1f))
                    if (isStarred) StarredIcon()
                    if (showReadIndicator && isUnread) {
                        Icon(
                            imageVector = Icons.Outlined.FiberManualRecord,
                            contentDescription = stringResource(R.string.unread),
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (articleListDate.value && !timeString.isNullOrBlank()) {
                Text(
                    text = timeString,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ArticlePhoto(data: Any?, isLead: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = resolveArticleImageHeightDp(maxWidth.value, isLead).dp
        val density = LocalDensity.current
        // Match the decoded image to the actual column, including high-density BOOX screens.
        val imageSize = with(density) {
            coil.size.Size(maxWidth.roundToPx().coerceAtLeast(1), height.roundToPx().coerceAtLeast(1))
        }
        RYAsyncImage(
            modifier = Modifier.fillMaxWidth().height(height).testTag("article-photo"),
            data = data,
            scale = Scale.FILL,
            precision = Precision.INEXACT,
            size = imageSize,
            contentScale = ContentScale.Crop,
            // Preserve source colors for color e-ink displays; never animate editorial images.
            animate = false,
            hideOnError = true,
        )
    }
}

@Composable
fun StarredIcon(modifier: Modifier = Modifier) {
    val fontSize = LocalTextStyle.current.fontSize
    val iconSize = with(LocalDensity.current) { fontSize.toDp() }

    Icon(
        modifier = modifier
            .size(iconSize)
            .padding(end = 2.dp),
        imageVector = Icons.Rounded.Star,
        contentDescription = stringResource(R.string.starred),
        tint = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun SwipeableArticleItem(
    articleWithFeed: ArticleWithFeed,
    isUnread: Boolean = articleWithFeed.article.isUnread,
    feedUnreadCount: Int = 0,
    isLead: Boolean = false,
    articleListTonalElevation: Int = 0,
    onClick: (ArticleWithFeed) -> Unit = {},
    isSwipeEnabled: () -> Boolean = { true },
    isMenuEnabled: Boolean = true,
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
) {

    var isMenuExpanded by remember { mutableStateOf(false) }

    val onLongClick =
        if (isMenuEnabled) {
            { isMenuExpanded = true }
        } else {
            null
        }
    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }

    SwipeActionBox(
        articleWithFeed = articleWithFeed,
        isSwipeEnabled = isSwipeEnabled,
        onToggleStarred = onToggleStarred,
        onToggleRead = onToggleRead,
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .pointerInput(isMenuExpanded) {
                        awaitEachGesture {
                            while (true) {
                                awaitFirstDown(requireUnconsumed = false).let {
                                    menuOffset = it.position.round()
                                }
                            }
                        }
                    }
                    .background(
                        MaterialTheme.colorScheme.surfaceColorAtElevation(
                            articleListTonalElevation.dp
                        ) onDark MaterialTheme.colorScheme.surface
                    )
                    .wrapContentSize()
        ) {
            ArticleItem(
                articleWithFeed = articleWithFeed,
                isUnread = isUnread,
                feedUnreadCount = feedUnreadCount,
                isLead = isLead,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            with(articleWithFeed.article) {
                if (isMenuEnabled) {
                    AnimatedDropdownMenu(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        expanded = isMenuExpanded,
                        animated = false,
                        onDismissRequest = { isMenuExpanded = false },
                        offset = menuOffset,
                    ) {
                        ArticleItemMenuContent(
                            articleWithFeed = articleWithFeed,
                            isStarred = isStarred,
                            isRead = !isUnread,
                            onToggleStarred = onToggleStarred,
                            onToggleRead = onToggleRead,
                            onMarkAboveAsRead = onMarkAboveAsRead,
                            onMarkBelowAsRead = onMarkBelowAsRead,
                            onShare = onShare,
                        ) {
                            isMenuExpanded = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeActionBox(
    modifier: Modifier = Modifier,
    articleWithFeed: ArticleWithFeed,
    isSwipeEnabled: () -> Boolean,
    onToggleStarred: (ArticleWithFeed) -> Unit,
    onToggleRead: (ArticleWithFeed) -> Unit,
    content: @Composable () -> Unit,
) {
    val swipeToStartAction = LocalArticleListSwipeStartAction.current
    val swipeToEndAction = LocalArticleListSwipeEndAction.current

    val onSwipeEndToStart =
        when (swipeToStartAction) {
            SwipeStartActionPreference.None -> null
            SwipeStartActionPreference.ToggleRead -> onToggleRead
            SwipeStartActionPreference.ToggleStarred -> onToggleStarred
        }

    val onSwipeStartToEnd =
        when (swipeToEndAction) {
            SwipeEndActionPreference.None -> null
            SwipeEndActionPreference.ToggleRead -> onToggleRead
            SwipeEndActionPreference.ToggleStarred -> onToggleStarred
        }

    if (onSwipeStartToEnd == null && onSwipeEndToStart == null) {
        content()
        return
    }

    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    // Preserve configured swipe shortcuts without translating cards, ripples, or spring reset.
    Box(modifier.pointerInput(articleWithFeed, onSwipeStartToEnd, onSwipeEndToStart, threshold, direction) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                when {
                    !isSwipeEnabled() -> Unit
                    distance * direction > threshold -> onSwipeStartToEnd?.invoke(articleWithFeed)
                    distance * direction < -threshold -> onSwipeEndToStart?.invoke(articleWithFeed)
                }
                distance = 0f
            },
            onHorizontalDrag = { change, amount ->
                change.consume()
                distance += amount
            },
        )
    }) { content() }

}

@Composable
fun ArticleItemMenuContent(
    articleWithFeed: ArticleWithFeed,
    iconSize: DpSize = DpSize(width = 20.dp, height = 20.dp),
    isStarred: Boolean = false,
    isRead: Boolean = false,
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
    onItemClick: (() -> Unit)? = null,
) {
    val starImageVector =
        remember(isStarred) { if (isStarred) Icons.Outlined.StarOutline else Icons.Rounded.Star }

    val readImageVector =
        remember(isRead) {
            if (isRead) Icons.Outlined.FiberManualRecord else Icons.Rounded.FiberManualRecord
        }

    val starText =
        stringResource(if (isStarred) R.string.mark_as_unstar else R.string.mark_as_starred)

    val readText = stringResource(if (isRead) R.string.mark_as_unread else R.string.mark_as_read)

    DropdownMenuItem(
        text = { Text(text = readText) },
        onClick = {
            onToggleRead(articleWithFeed)
            onItemClick?.invoke()
        },
        leadingIcon = {
            Icon(
                imageVector = readImageVector,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        },
    )
    DropdownMenuItem(
        text = { Text(text = starText) },
        onClick = {
            onToggleStarred(articleWithFeed)
            onItemClick?.invoke()
        },
        leadingIcon = {
            Icon(
                imageVector = starImageVector,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        },
    )

    if (onMarkAboveAsRead != null || onMarkBelowAsRead != null) {
        HorizontalDivider()
    }
    onMarkAboveAsRead?.let {
        DropdownMenuItem(
            text = { Text(text = stringResource(id = R.string.mark_above_as_read)) },
            onClick = {
                onMarkAboveAsRead(articleWithFeed)
                onItemClick?.invoke()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            },
        )
    }
    onMarkBelowAsRead?.let {
        DropdownMenuItem(
            text = { Text(text = stringResource(id = R.string.mark_below_as_read)) },
            onClick = {
                onMarkBelowAsRead(articleWithFeed)
                onItemClick?.invoke()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            },
        )
    }
    onShare?.let {
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(text = stringResource(id = R.string.share)) },
            onClick = {
                onShare(articleWithFeed)
                onItemClick?.invoke()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            },
        )
    }
}

@Preview
@Composable
fun MenuContentPreview() {
    MaterialTheme {
        Surface() {
            Column(modifier = Modifier.padding()) {
                ArticleItemMenuContent(
                    articleWithFeed = generateArticleWithFeedPreview(),
                    onMarkBelowAsRead = {},
                    onMarkAboveAsRead = {},
                    onShare = {},
                )
            }
        }
    }
}
