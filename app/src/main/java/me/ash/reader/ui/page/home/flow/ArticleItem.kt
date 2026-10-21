package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.round
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import me.ash.reader.infrastructure.rss.articleImageRequest
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.base.RYAsyncImage
import me.ash.reader.ui.component.menu.AnimatedDropdownMenu
import me.ash.reader.ui.ext.requiresBidi
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.layout.resolveArticleImageHeightDp
import me.ash.reader.ui.page.home.reading.readerArticleSwipe
import me.ash.reader.ui.page.settings.color.flow.generateArticleWithFeedPreview
import me.ash.reader.ui.theme.applyTextDirection
import me.ash.reader.ui.theme.palette.onDark

@Composable
fun ArticleItem(
    modifier: Modifier = Modifier,
    articleWithFeed: ArticleWithFeed,
    isUnread: Boolean = articleWithFeed.article.isUnread,
    emphasizeUnread: Boolean? = null,
    feedUnreadCount: Int = 0,
    isLead: Boolean = false,
    storyStyle: NewspaperStoryStyle = NewspaperStoryStyle.Standard,
    preview: NewspaperPreview? = null,
    previewHeight: Dp = Dp.Unspecified,
    measurementOnly: Boolean = false,
    onClick: (ArticleWithFeed) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val feed = articleWithFeed.feed
    val article = articleWithFeed.article
    val imagesEnabled = LocalFlowArticleListImage.current.value
    // Saved feed HTML/JSON can be substantial. Parse it away from the UI thread, and scope the
    // work to this exact visible article and image preference. A changed key cancels the old
    // producer, so disabling images cannot publish stale candidates or start a late lookup.
    val image = if (measurementOnly) null else key(imagesEnabled, article.img, article.rawDescription, article.link) {
        val request by produceState<Any?>(initialValue = null) {
            if (imagesEnabled) {
                value = withContext(Dispatchers.Default) {
                    articleImageRequest(article.img, article.rawDescription, article.link)
                }
            }
        }
        if (imagesEnabled) request else null
    }

    ArticleItem(
        modifier = modifier,
        feedName = feed.name,
        feedIconUrl = feed.icon,
        feedUnreadCount = feedUnreadCount,
        isLead = isLead,
        storyStyle = storyStyle,
        preview = preview,
        previewHeight = previewHeight,
        measurementOnly = measurementOnly,
        reserveImage = preview != null && (!article.img.isNullOrBlank() || article.link.startsWith("http", ignoreCase = true) || (article.rawDescription.contains("<img", ignoreCase = true) || article.rawDescription.contains("image", ignoreCase = true) || article.rawDescription.contains("<enclosure", ignoreCase = true))),
        emphasizeUnread = emphasizeUnread,
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
    storyStyle: NewspaperStoryStyle = NewspaperStoryStyle.Standard,
    preview: NewspaperPreview? = null,
    previewHeight: Dp = Dp.Unspecified,
    measurementOnly: Boolean = false,
    reserveImage: Boolean = false,
    emphasizeUnread: Boolean? = null,
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
    val showImage = articleListImage.value &&
        preview != NewspaperPreview.Minimal && preview != NewspaperPreview.HeadlineOnly &&
        (if (preview != null) reserveImage else (imgData != null && (imgData !is String || imgData.isNotBlank())))
    val showSource = preview != NewspaperPreview.HeadlineOnly &&
        (preview != null || articleListFeedIcon.value || articleListFeedName.value || isStarred ||
            (showReadIndicator && (isUnread || preview != null)))
    // An editorial block, not a dashboard card. The list owns the rules between rows.
    // Paged previews have whole-line limits; continuous mode keeps unbounded headlines.
    BoxWithConstraints(modifier.fillMaxWidth()
        .then(if (preview != null) Modifier.heightIn(min = 48.dp) else Modifier).combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Button,
        onClick = onClick, onLongClick = onLongClick,
    )) {
        val textScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val compactColumn = !isLead && maxWidth < 220.dp * textScale
        val isFeature = isLead || storyStyle == NewspaperStoryStyle.Feature
        val isBrief = storyStyle == NewspaperStoryStyle.Brief
        val showDescription = preview != NewspaperPreview.Minimal && preview != NewspaperPreview.HeadlineOnly &&
            articleListDesc != FlowArticleListDescPreference.NONE && shortDescription.isNotBlank()
        val sidePhoto = isFeature && showDescription && maxWidth >= 280.dp * textScale
        val wideLead = isLead && maxWidth >= 600.dp * textScale
        val description: @Composable () -> Unit = {
            if (showDescription) {
                Text(
                    modifier = Modifier.then(if (measurementOnly) Modifier else Modifier.testTag("article-description")),
                    text = shortDescription,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = if (wideLead) 17.sp else if (isLead) 15.sp else 14.sp,
                        lineHeight = if (wideLead) 24.sp else if (isLead) 21.sp else 20.sp,
                    ).applyTextDirection(shortDescription.requiresBidi()),
                    fontFamily = FontFamily.Serif,
                    maxLines = if (preview == NewspaperPreview.Compact) 2 else if (articleListDesc == FlowArticleListDescPreference.LONG) {
                        if (isLead) 6 else if (isBrief) 3 else 5
                    } else {
                        if (isLead) 4 else if (isBrief) 2 else 3
                    },
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                top = if (preview == NewspaperPreview.HeadlineOnly) 0.dp else 10.dp,
                bottom = if (preview == NewspaperPreview.HeadlineOnly) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isLead) 8.dp else 6.dp),
        ) {
            // The reference is a newspaper, not a photo feed: the headline always comes first.
            Text(
                modifier = Modifier.then(if (measurementOnly) Modifier else Modifier.testTag("article-headline")).semantics { heading() },
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = when { preview == NewspaperPreview.HeadlineOnly -> 18.sp; wideLead -> 34.sp; isLead -> 27.sp; isFeature -> 24.sp; compactColumn -> 18.sp; else -> 21.sp },
                    lineHeight = when { preview == NewspaperPreview.HeadlineOnly -> 23.sp; wideLead -> 39.sp; isLead -> 31.sp; isFeature -> 29.sp; compactColumn -> 23.sp; else -> 26.sp },
                ).applyTextDirection(title.requiresBidi()),
                fontFamily = FontFamily.Serif,
                fontWeight = if ((emphasizeUnread ?: isUnread) || isLead) FontWeight.Bold else FontWeight.Medium,
                maxLines = when (preview) {
                    NewspaperPreview.Full -> if (isLead) 5 else 4
                    NewspaperPreview.Compact -> 3
                    NewspaperPreview.Minimal -> 2
                    NewspaperPreview.HeadlineOnly -> 1
                    null -> Int.MAX_VALUE
                },
                overflow = TextOverflow.Ellipsis,
            )
            // A compact source line replaces card chrome. It uses real feed metadata only.
            if (showSource) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (articleListFeedIcon.value) {
                        if (measurementOnly) Spacer(Modifier.size(20.dp))
                        else FeedIcon(feedName = feedName, iconUrl = feedIconUrl, animate = false)
                    }
                    if (articleListFeedName.value) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = if (feedUnreadCount > 0) "$feedName ($feedUnreadCount)" else feedName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            fontWeight = FontWeight.Bold,
                            maxLines = if (preview != null) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else Spacer(Modifier.weight(1f))
                    if (isStarred) StarredIcon()
                    else if (preview != null) Spacer(Modifier.size(with(LocalDensity.current) { LocalTextStyle.current.fontSize.toDp() }))
                    if (showReadIndicator && !isUnread && preview != null) Spacer(Modifier.size(10.dp))
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
            if (sidePhoto && showImage) {
                Row(
                    Modifier.fillMaxWidth().then(if (measurementOnly) Modifier else Modifier.testTag(if (isLead) "article-lead-body" else "article-feature-body")),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.weight(0.58f)) { description() }
                    Box(Modifier.weight(0.42f)) { ArticlePhoto(data = imgData, isLead = isLead, compact = preview == NewspaperPreview.Compact, measurementOnly = measurementOnly, stableGeometry = preview != null) }
                }
            } else {
                description()
                if (showImage) ArticlePhoto(data = imgData, isLead = isLead, isBrief = isBrief, compact = preview == NewspaperPreview.Compact, measurementOnly = measurementOnly, stableGeometry = preview != null)
            }
            if (preview != NewspaperPreview.HeadlineOnly && articleListDate.value && !timeString.isNullOrBlank()) {
                Text(
                    modifier = Modifier.then(if (measurementOnly) Modifier else Modifier.testTag("article-time")),
                    text = timeString,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                )
            }
        }
    }
}

@Composable
private fun ArticlePhoto(data: Any?, isLead: Boolean, isBrief: Boolean = false, compact: Boolean = false, measurementOnly: Boolean = false, stableGeometry: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = resolveArticleImageHeightDp(maxWidth.value, isLead).dp.let {
            if (compact) it.coerceAtMost(72.dp) else if (isBrief) it.coerceAtMost(96.dp) else it
        }
        val density = LocalDensity.current
        // Match the decoded image to the actual column, including high-density BOOX screens.
        val imageSize = with(density) {
            coil.size.Size(maxWidth.roundToPx().coerceAtLeast(1), height.roundToPx().coerceAtLeast(1))
        }
        if (measurementOnly || data == null) {
            Spacer(Modifier.fillMaxWidth().height(height).then(if (measurementOnly) Modifier else Modifier.testTag("article-photo")))
            return@BoxWithConstraints
        }
        RYAsyncImage(
            modifier = Modifier.fillMaxWidth().height(height).then(if (measurementOnly) Modifier else Modifier.testTag("article-photo")),
            data = data,
            scale = Scale.FILL,
            precision = Precision.INEXACT,
            size = imageSize,
            contentScale = ContentScale.Crop,
            // Preserve source colors for color e-ink displays; never animate editorial images.
            animate = false,
            hideOnError = !stableGeometry,
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
    emphasizeUnread: Boolean? = null,
    feedUnreadCount: Int = 0,
    isLead: Boolean = false,
    storyStyle: NewspaperStoryStyle = NewspaperStoryStyle.Standard,
    preview: NewspaperPreview? = null,
    previewHeight: Dp = Dp.Unspecified,
    measurementOnly: Boolean = false,
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
        isSwipeEnabled = { !isMenuExpanded && isSwipeEnabled() },
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
                emphasizeUnread = emphasizeUnread,
                feedUnreadCount = feedUnreadCount,
                isLead = isLead,
                storyStyle = storyStyle,
                preview = preview,
                previewHeight = previewHeight,
                measurementOnly = measurementOnly,
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

    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val physicalRight = if (direction > 0f) onSwipeStartToEnd else onSwipeEndToStart
    val physicalLeft = if (direction > 0f) onSwipeEndToStart else onSwipeStartToEnd
    // Keep the user's exact 60dp start/end actions, but share cancellation/OS-edge safety
    // with the reader. Neither a second pointer nor a menu-open drag can mutate a story.
    Box(modifier.readerArticleSwipe(
        gestureKey = listOf(articleWithFeed, swipeToStartAction, swipeToEndAction, direction),
        enabled = true,
        onPrevious = physicalRight?.let { action -> { action(articleWithFeed) } },
        onNext = physicalLeft?.let { action -> { action(articleWithFeed) } },
        thresholdDp = 60.dp,
        isGestureEnabled = isSwipeEnabled,
    )) { content() }

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
