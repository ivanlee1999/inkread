package me.ash.reader.ui.page.home.feeds

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.page.home.feeds.drawer.feed.FeedOptionViewModel

/** A publication, rather than another settings-style row. No animated visibility or ripple. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PublicationBlock(
    name: String,
    iconUrl: String? = null,
    count: Int = 0,
    source: String = "",
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 144.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FeedIcon(feedName = name, iconUrl = iconUrl, animate = false)
            Spacer(Modifier.weight(1f))
            if (count != 0) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp),
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (source.isNotBlank()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = source,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun FeedItem(
    feed: Feed,
    isLastItem: () -> Boolean = { false },
    isExpanded: () -> Boolean,
    feedOptionViewModel: FeedOptionViewModel = hiltViewModel(),
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Keep the existing entry point for settings previews and feed-option ownership.
    if (isExpanded()) {
        PublicationBlock(
            modifier = modifier,
            name = feed.name,
            iconUrl = feed.icon,
            count = feed.important,
            source = remember(feed.url) { Uri.parse(feed.url).host.orEmpty().removePrefix("www.") },
            onClick = onClick,
            onLongClick = {
                scope.launch {
                    feedOptionViewModel.fetchFeed(feedId = feed.id)
                    onLongClick()
                }
            },
        )
    }
}
