package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import me.ash.reader.R

/** Article-list navigation lives in the existing masthead count, never over the reading pane. */
@Composable
fun EInkArticlePageControl(
    currentPage: Int,
    totalPages: Int,
    totalArticles: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    lastSyncTime: Long? = null,
    navigationKey: Any? = null,
) {
    // A replacement feed/filter must not inherit the old feed's open controls.
    var expanded by remember(navigationKey) { mutableStateOf(false) }
    val pageCount = totalPages.coerceAtLeast(1)
    val page = currentPage.coerceIn(1, pageCount)
    val articleCount = totalArticles.coerceAtLeast(0)
    val description = stringResource(
        R.string.article_page_control_description, articleCount, page, pageCount,
    )
    val openLabel = stringResource(R.string.article_page_controls)
    Column(
        modifier = modifier
            .testTag("article-page-control")
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = openLabel,
                onClick = { expanded = true },
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.articles_count, articleCount),
            modifier = Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.article_page_position_short, page, pageCount),
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            // Compose Dialog has no Compose transition; also disable platform window animations.
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setWindowAnimations(0) }
            Column(
                modifier = Modifier
                    .testTag("article-page-dialog")
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = openLabel,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.article_page_position, page, pageCount),
                    modifier = Modifier.testTag("article-page-status"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.articles_count, articleCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PageAction(
                        label = stringResource(R.string.previous_page),
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        enabled = page > 1,
                        modifier = Modifier.weight(1f).testTag("article-page-previous"),
                    ) {
                        expanded = false
                        onPrev()
                    }
                    PageAction(
                        label = stringResource(R.string.next_page),
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        enabled = page < pageCount,
                        modifier = Modifier.weight(1f).testTag("article-page-next"),
                    ) {
                        expanded = false
                        onNext()
                    }
                }
                if (lastSyncTime != null && lastSyncTime > 0L) {
                    // Evaluate on open/update, with no timer or continuous e-ink refresh.
                    val elapsed = ((System.currentTimeMillis() - lastSyncTime) / 1000).coerceAtLeast(0)
                    val timeText = when {
                        elapsed < 60 -> stringResource(R.string.last_sync_just_now)
                        elapsed < 3600 -> stringResource(R.string.last_sync_min_ago, elapsed / 60)
                        elapsed < 86400 -> stringResource(R.string.last_sync_hours_ago, elapsed / 3600)
                        else -> stringResource(R.string.last_sync_days_ago, elapsed / 86400)
                    }
                    Text(
                        text = stringResource(R.string.last_sync_prefix, timeText),
                        modifier = Modifier.testTag("article-page-last-sync"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                PageAction(
                    label = stringResource(R.string.close),
                    modifier = Modifier.align(Alignment.End).testTag("article-page-close"),
                ) { expanded = false }
            }
        }
    }
}

@Composable
private fun PageAction(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Column(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color)
        Text(
            text = label,
            modifier = Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}
