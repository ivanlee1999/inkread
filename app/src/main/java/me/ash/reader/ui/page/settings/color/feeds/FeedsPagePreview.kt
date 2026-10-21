package me.ash.reader.ui.page.settings.color.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.preference.FeedsGroupListExpandPreference
import me.ash.reader.infrastructure.preference.FeedsTopBarTonalElevationPreference
import me.ash.reader.ui.component.FilterBar
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.page.home.feeds.PublicationBlock
import me.ash.reader.ui.page.home.feeds.NewspaperFolderHeader
import me.ash.reader.ui.page.home.feeds.GroupWithFeedsContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsPagePreview(
    topBarTonalElevation: FeedsTopBarTonalElevationPreference,
    groupListExpand: FeedsGroupListExpandPreference,
    filterBarStyle: Int,
    filterBarFilled: Boolean,
    filterBarPadding: Dp,
    filterBarTonalElevation: Dp,
) {
    var filter by remember { mutableStateOf(Filter.Unread) }
    var expanded by remember(groupListExpand) { mutableStateOf(groupListExpand.value) }

    Column(
        modifier = Modifier
            .testTag("feeds-style-preview")
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        TopAppBar(
            title = {},
            navigationIcon = {
                FeedbackIconButton(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            actions = {
                FeedbackIconButton(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                FeedbackIconButton(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.subscribe),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }, colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    topBarTonalElevation.value.dp
                ),
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        GroupWithFeedsContainer {
            NewspaperFolderHeader(
                name = generateGroupPreview().name,
                important = 250,
                isExpanded = expanded,
                onExpanded = { expanded = !expanded },
            )
            FeedItemExpandSwitcher(isExpanded = expanded)
        }
        Spacer(modifier = Modifier.height(12.dp))
        FilterBar(
            filter = filter,
            filterBarStyle = filterBarStyle,
            filterBarFilled = filterBarFilled,
            filterBarPadding = filterBarPadding,
            filterBarTonalElevation = filterBarTonalElevation,
        ) {
            filter = it
        }
    }
}

@Stable
@Composable
fun FeedItemExpandSwitcher(isExpanded: Boolean) {
    FeedPreview(
        isExpanded = isExpanded
    )
}

@Stable
@Composable
fun FeedPreview(isExpanded: Boolean) {
    if (isExpanded) {
        val feed = generateFeedPreview()
        // Style previews render the real presentation without account/navigation ViewModels.
        PublicationBlock(
            name = feed.name,
            iconUrl = feed.icon,
            count = feed.important,
        )
    }
}

@Stable
@Composable
fun generateFeedPreview(): Feed =
    Feed(
        id = "",
        name = stringResource(R.string.preview_feed_name),
        icon = "",
        accountId = 0,
        groupId = "",
        url = "",
        important = 100
    )

@Stable
@Composable
fun generateGroupPreview(): Group =
    Group(
        id = "",
        name = stringResource(R.string.defaults),
        accountId = 0,
    )
