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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.ash.reader.R

/** Original editorial composition: a section title, real directory digest, and separate disclosure. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderSectionBlock(
    section: FolderSection,
    expanded: Boolean,
    featured: Boolean,
    preview: FolderPreview,
    countLabel: String,
    modifier: Modifier = Modifier,
    measuring: Boolean = false,
    extraPublicationNames: Int = 0,
    onOpen: () -> Unit,
    onExpand: () -> Unit,
    onOptions: () -> Unit,
) {
    val minimal = preview == FolderPreview.Minimal
    val color = MaterialTheme.colorScheme.onSurface
    val feedsLabel = stringResource(R.string.feeds)
    if (preview == FolderPreview.HeadlineOnly) {
        Row(modifier.fillMaxWidth().then(if (measuring) Modifier else Modifier.testTag("folder-block:${section.id}")),
            verticalAlignment = Alignment.CenterVertically) {
            Text(section.name, Modifier.weight(1f).heightIn(min = 48.dp)
                .then(if (measuring) Modifier else Modifier.testTag("folder-open:${section.name}"))
                .semantics {
                    heading()
                    contentDescription = "${section.name}, ${section.publications.size} $feedsLabel, ${section.count} $countLabel"
                }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() },
                    indication = null, role = Role.Button, onClick = onOpen, onLongClick = onOptions)
                .padding(vertical = 4.dp),
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(48.dp).then(if (measuring) Modifier else Modifier.testTag("folder-expand:${section.name}"))
                .clickable(interactionSource = remember { MutableInteractionSource() },
                    indication = null, role = Role.Button, onClick = onExpand),
                contentAlignment = Alignment.Center) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    "${stringResource(if (expanded) R.string.expand_less else R.string.expand_more)} ${section.name}", tint = color)
            }
        }
        return
    }
    Column(modifier.then(if (measuring) Modifier else Modifier.testTag("folder-block:${section.id}"))) {
        HorizontalDivider(thickness = if (featured) 3.dp else 1.dp, color = color)
        Column(
            Modifier.fillMaxWidth().then(if (measuring) Modifier else Modifier.testTag("folder-open:${section.name}"))
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null, role = Role.Button,
                    onClick = onOpen, onLongClick = onOptions,
                ).padding(top = if (minimal) 4.dp else 10.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (minimal) 2.dp else 8.dp),
        ) {
            Text(section.name,
                Modifier.semantics { heading() },
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = if (minimal) 18.sp else if (featured) 30.sp else 23.sp,
                lineHeight = if (minimal) 22.sp else if (featured) 34.sp else 27.sp,
                color = color, maxLines = if (minimal) 1 else 3,
                overflow = TextOverflow.Ellipsis)
            Text("${section.count} · $countLabel",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (preview == FolderPreview.Full && section.publications.isNotEmpty()) {
                HorizontalDivider(thickness = 0.5.dp, color = color)
                // These are actual subscribed publications, not fabricated news teasers.
                section.publications.take((if (featured) 3 else 2) + extraPublicationNames).forEach { publication ->
                    Text(publication.name,
                        modifier = if (measuring) Modifier else Modifier.testTag("folder-digest:${section.id}:${publication.id}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${stringResource(R.string.feeds)} · ${section.publications.size}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(48.dp).then(if (measuring) Modifier else Modifier.testTag("folder-expand:${section.name}"))
                .clickable(interactionSource = remember { MutableInteractionSource() },
                    indication = null, role = Role.Button, onClick = onExpand),
                contentAlignment = Alignment.Center) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = "${stringResource(if (expanded) R.string.expand_less else R.string.expand_more)} ${section.name}",
                    tint = color)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = color)
    }
}

@Composable
internal fun FolderNewspaperRowContent(
    row: FolderNewspaperRow,
    spacing: Dp,
    preview: FolderPreview,
    expanded: Set<String>,
    countLabel: String,
    measuring: Boolean = false,
    onOpenFolder: (String) -> Unit,
    onToggleFolder: (String) -> Unit,
    onFolderOptions: (String) -> Unit,
    onOpenPublication: (String) -> Unit,
    onPublicationOptions: (String) -> Unit,
    extraPublicationNames: Int = 0,
) {
    val color = MaterialTheme.colorScheme.onSurface
    val direction = LocalLayoutDirection.current
    Column(Modifier.fillMaxWidth().then(if (measuring) Modifier else Modifier.testTag("folder-row:${row.key}"))
        .padding(vertical = if (preview == FolderPreview.HeadlineOnly) 0.dp else 8.dp)) {
        row.heading?.let { section ->
            if (!row.continuation) {
                NewspaperFolderHeader(section.name, section.count, section.publications.size,
                    isExpanded = true, inset = false,
                    compact = preview == FolderPreview.Minimal || preview == FolderPreview.HeadlineOnly,
                    countLabel = countLabel, measuring = measuring,
                    modifier = if (measuring) Modifier else Modifier.testTag("folder-block:${section.id}"),
                    onClick = { onOpenFolder(section.id) },
                    onExpanded = { onToggleFolder(section.id) },
                    onLongClick = { onFolderOptions(section.id) })
            } else if (preview == FolderPreview.Full || preview == FolderPreview.Compact) {
                // Repeated real section context, not another folder in the source partition.
                Text(section.name, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge, color = color,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(thickness = 0.5.dp, color = color)
            }
        }
        // A short final row uses its real columns, not a reserved empty publication lane.
        val weights = row.slots.map { it.span }
        Row(Modifier.fillMaxWidth().drawBehind {
            val gap = spacing.toPx()
            val unit = (size.width - gap * (weights.size - 1)) / weights.sum().coerceAtLeast(1)
            var x = 0f
            weights.dropLast(1).forEach { weight ->
                x += unit * weight + gap / 2f
                val ruleX = if (direction == LayoutDirection.Rtl) size.width - x else x
                drawLine(color, Offset(ruleX, 0f), Offset(ruleX, size.height), 0.5.dp.toPx())
                x += gap / 2f
            }
        }, horizontalArrangement = Arrangement.spacedBy(spacing)) {
            row.slots.forEach { slot ->
                if (slot.publication == null) {
                    FolderSectionBlock(slot.section, slot.section.id in expanded, slot.featured,
                        preview, countLabel, Modifier.weight(slot.span.toFloat()), measuring = measuring,
                        extraPublicationNames = extraPublicationNames,
                        onOpen = { onOpenFolder(slot.section.id) },
                        onExpand = { onToggleFolder(slot.section.id) },
                        onOptions = { onFolderOptions(slot.section.id) })
                } else {
                    PublicationBlock(slot.publication.name, slot.publication.iconUrl,
                        slot.publication.count, slot.publication.source,
                        Modifier.weight(slot.span.toFloat()).then(if (measuring) Modifier else Modifier.testTag("folder-publication:${slot.publication.id}")),
                        onClick = { onOpenPublication(slot.publication.id) },
                        onLongClick = { onPublicationOptions(slot.publication.id) },
                        featured = slot.featured, preview = preview, measuring = measuring,
                        countLabel = countLabel)
                }
            }
        }
        if (row.heading != null) HorizontalDivider(thickness = 0.5.dp, color = color)
    }
}
