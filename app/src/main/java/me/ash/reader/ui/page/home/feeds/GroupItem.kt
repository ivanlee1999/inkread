package me.ash.reader.ui.page.home.feeds

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.preference.LocalReadingColumns
import me.ash.reader.infrastructure.preference.LocalReadingLayoutPreset
import me.ash.reader.ui.layout.resolveReadingLayout
import me.ash.reader.ui.page.home.feeds.drawer.group.GroupOptionViewModel

/**
 * A newspaper section, independent of navigation, storage, and Hilt so the real header can be
 * exercised in native UI tests. Opening a folder and expanding its publications are separate
 * sibling targets: a tap on the disclosure never accidentally opens the article list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewspaperFolderHeader(
    name: String,
    important: Int = 0,
    feedCount: Int? = null,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onExpanded: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onClick: () -> Unit = {},
    inset: Boolean = true,
    compact: Boolean = false,
    countLabel: String? = null,
    measuring: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = resolveReadingLayout(
            availableWidthDp = maxWidth.value,
            fontScale = fontScale,
            preset = LocalReadingLayoutPreset.current.preset,
            columnPreference = LocalReadingColumns.current.columns,
        )
        Column(Modifier.padding(horizontal = if (inset) layout.horizontalPaddingDp.dp else 0.dp)) {
            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .then(if (measuring) Modifier else Modifier.testTag("folder-open:$name"))
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                        .padding(vertical = if (compact) 4.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            modifier = Modifier.semantics { heading() },
                            text = name,
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (compact) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (feedCount != null && !compact) {
                            Text(
                                text = "$feedCount ${stringResource(R.string.feeds)}" +
                                    if (countLabel == null) "" else " · $important $countLabel",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (important != 0 && countLabel == null) {
                        Text(
                            text = important.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .then(if (measuring) Modifier else Modifier.testTag("folder-expand:$name"))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onExpanded,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "${stringResource(if (isExpanded) R.string.expand_less else R.string.expand_more)} $name",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun GroupItem(
    group: Group,
    important: Int = 0,
    isExpanded: () -> Boolean,
    groupOptionViewModel: GroupOptionViewModel = hiltViewModel(),
    onExpanded: () -> Unit = {},
    onLongClick: () -> Unit = {},
    feedCount: Int? = null,
    groupOnClick: () -> Unit = {},
) {
    NewspaperFolderHeader(
        name = group.name,
        important = important,
        feedCount = feedCount,
        isExpanded = isExpanded(),
        onExpanded = onExpanded,
        onLongClick = {
            groupOptionViewModel.fetchGroup(groupId = group.id)
            onLongClick()
        },
        onClick = groupOnClick,
    )
}

@Composable
inline fun GroupWithFeedsContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Header and publication grid each resolve their gutters against the full measured pane.
    // Insetting this container as well would double-count phone padding and lose a column.
    Column(modifier = modifier.padding(top = 20.dp), content = { content() })
}
