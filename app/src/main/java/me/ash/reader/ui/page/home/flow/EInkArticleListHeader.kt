package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import me.ash.reader.R
import me.ash.reader.domain.model.general.Filter

/** A single edition header. Its measured height, including system insets, belongs to Scaffold. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EInkArticleListHeader(
    title: String,
    filter: Filter,
    currentPage: Int,
    totalPages: Int,
    totalArticles: Int,
    isSyncing: Boolean,
    onNavigateUp: () -> Unit,
    onFilterChange: (Filter) -> Unit,
    onRefresh: () -> Unit,
    onMarkAllAsRead: () -> Unit,
    onSearch: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    lastSyncTime: Long? = null,
    navigationKey: Any? = null,
    markAllSelected: Boolean = false,
    searchSelected: Boolean = false,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    var actionsExpanded by remember(navigationKey) { mutableStateOf(false) }
    val filterName = filter.toName()
    val moreLabel = stringResource(R.string.more)
    val syncingLabel = stringResource(R.string.syncing)

    Column(
        modifier = modifier
            .testTag("article-list-header")
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // Unlike a TopAppBar followed by a masthead, this consumes the top inset once.
            .windowInsetsPadding(windowInsets)
            .padding(horizontal = 8.dp),
    ) {
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Reserve real, non-overlapping touch targets before allocating the title. The
            // compact count remains readable without the translated word "articles" competing
            // with long CJK titles; its full description is available to accessibility and on tap.
            val countWidth = minOf(136.dp, maxWidth * 0.35f).coerceAtLeast(48.dp)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HeaderIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    label = stringResource(R.string.back),
                    modifier = Modifier.testTag("article-list-back"),
                    onClick = onNavigateUp,
                )
                Text(
                    text = title,
                    modifier = Modifier.weight(1f).testTag("article-list-title")
                        .semantics { heading() },
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                EInkArticlePageControl(
                    currentPage = currentPage,
                    totalPages = totalPages,
                    totalArticles = totalArticles,
                    onPrev = onPrev,
                    onNext = onNext,
                    modifier = Modifier.width(countWidth),
                    lastSyncTime = lastSyncTime,
                    navigationKey = navigationKey,
                    compactCount = true,
                )
                HeaderIconButton(
                    icon = Icons.Rounded.MoreVert,
                    label = moreLabel,
                    modifier = Modifier.testTag("article-list-more").semantics {
                        stateDescription = if (isSyncing) "$filterName, $syncingLabel" else filterName
                    },
                    onClick = { actionsExpanded = true },
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface)
    }

    if (actionsExpanded) {
        Dialog(
            onDismissRequest = { actionsExpanded = false },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            // Keep both Compose and platform dialogs static on e-ink, including dismissal.
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setWindowAnimations(0) }
            Column(
                modifier = Modifier.testTag("article-list-actions-dialog")
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // The complete real title remains available when the compact header elides it.
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(Modifier.selectableGroup()) {
                    listOf(Filter.All to "all", Filter.Unread to "unread", Filter.Starred to "starred")
                        .forEach { (option, tag) ->
                            HeaderMenuAction(
                                label = option.toName(),
                                icon = if (option == filter) option.iconFilled else option.iconOutline,
                                modifier = Modifier.testTag("article-list-filter-$tag"),
                                selected = option == filter,
                            ) {
                                actionsExpanded = false
                                if (option != filter) onFilterChange(option)
                            }
                        }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface)
                HeaderMenuAction(
                    label = if (isSyncing) syncingLabel else stringResource(R.string.refresh),
                    icon = Icons.Rounded.Refresh,
                    enabled = !isSyncing,
                    modifier = Modifier.testTag("article-list-refresh"),
                ) {
                    actionsExpanded = false
                    onRefresh()
                }
                if (!filter.isStarred()) {
                    HeaderMenuAction(
                        label = stringResource(R.string.mark_all_as_read),
                        icon = Icons.Rounded.DoneAll,
                        active = markAllSelected,
                        modifier = Modifier.testTag("article-list-mark-all"),
                    ) {
                        actionsExpanded = false
                        onMarkAllAsRead()
                    }
                }
                HeaderMenuAction(
                    label = stringResource(R.string.search),
                    icon = Icons.Rounded.Search,
                    active = searchSelected,
                    modifier = Modifier.testTag("article-list-search"),
                ) {
                    actionsExpanded = false
                    onSearch()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface)
                HeaderMenuAction(
                    label = stringResource(R.string.close),
                    modifier = Modifier.testTag("article-list-actions-close"),
                ) { actionsExpanded = false }
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.size(48.dp).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = onClick,
        ).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun HeaderMenuAction(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean? = null,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val actionModifier = if (selected != null) {
        Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
    }
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).then(actionModifier)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = color)
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected == true || active) FontWeight.Bold else FontWeight.Normal,
            color = color,
        )
        if (selected == true || active) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(24.dp), tint = color)
        }
    }
}
