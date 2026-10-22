package me.ash.reader.ui.page.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import me.ash.reader.domain.library.SavedContentState
import me.ash.reader.domain.library.SavedImageState
import me.ash.reader.domain.library.SavedItem
import me.ash.reader.infrastructure.preference.LocalSettings
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedName
import me.ash.reader.infrastructure.preference.LocalFlowArticleListReadIndicator
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime
import me.ash.reader.ui.component.base.eInkVerticalPageGesture
import me.ash.reader.ui.page.home.flow.ArticleItem
import me.ash.reader.ui.page.home.flow.MeasuredNewspaperRow
import me.ash.reader.ui.page.home.flow.NewspaperPreview
import me.ash.reader.ui.page.home.flow.NewspaperRow
import me.ash.reader.ui.page.home.flow.NewspaperSlot
import me.ash.reader.ui.page.home.flow.enrichNewspaperViewportPage
import me.ash.reader.ui.page.home.flow.newspaperRows
import me.ash.reader.ui.page.home.flow.newspaperViewportTemplates
import me.ash.reader.ui.page.home.flow.packNewspaperViewportVariants

/** Production typography is measured and printed whole, using the RSS edition's ordered planner. */
@Composable
internal fun SavedLibraryViewport(
    items: List<SavedItem>,
    state: SavedLibraryEditionState,
    columns: Int,
    horizontalPadding: Dp,
    columnSpacing: Dp,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
    onOptions: (String) -> Unit,
    navigationEnabled: Boolean = true,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val settings = listOf(LocalSettings.current, LocalFlowArticleListImage.current, LocalFlowArticleListDesc.current,
        LocalFlowArticleListFeedIcon.current, LocalFlowArticleListFeedName.current,
        LocalFlowArticleListReadIndicator.current, LocalFlowArticleListTime.current)
    val typography = MaterialTheme.typography
    val source = remember(items) { items.toList() }
    val rows = remember(source, columns) { newspaperRows(source.size, columns) { false } }
    val geometryInputs = listOf(source, columns, horizontalPadding, columnSpacing, density.density,
        density.fontScale, settings, typography, direction)
    val measuredHeights = remember(geometryInputs) { mutableMapOf<List<Any>, Int>() }
    val pagePlans = remember(geometryInputs) { mutableMapOf<Pair<Int, Int>, List<me.ash.reader.ui.page.home.flow.NewspaperViewportPage>>() }
    val enrichedPages = remember(geometryInputs) { mutableMapOf<List<Any>, me.ash.reader.ui.page.home.flow.NewspaperViewportPage>() }
    val currentNavigationEnabled by rememberUpdatedState(navigationEnabled)
    val gestureKey = listOf(source, state.currentPage, columns, density.fontScale)
    val turn: (Boolean) -> Boolean = { currentNavigationEnabled && state.turn(it) }
    val body: @Composable (MeasuredNewspaperRow, Boolean, Dp) -> Unit = { row, measurementOnly, height ->
        SavedLibraryRow(source, state, row, columns, horizontalPadding, columnSpacing, height, measurementOnly, onOpen, onOptions)
    }
    SubcomposeLayout(modifier.fillMaxSize().testTag("saved-library-viewport")
        .semantics {
            verticalScrollAxisRange = ScrollAxisRange({ state.currentPage.toFloat() }, { (state.pages.size - 1).coerceAtLeast(0).toFloat() })
            scrollBy { _, y -> y != 0f && turn(y > 0f) }
            customActions = listOf(CustomAccessibilityAction("Previous page") { turn(false) },
                CustomAccessibilityAction("Next page") { turn(true) })
        }.eInkVerticalPageGesture(gestureKey = gestureKey,
            currentGestureKey = { listOf(source, state.currentPage, columns, density.fontScale) }, onTurn = { forward -> turn(forward) })) { constraints ->
        val width = constraints.maxWidth.coerceAtLeast(0)
        val height = constraints.maxHeight.coerceAtLeast(0)
        val geometry = geometryInputs + listOf(width, height)
        fun notice(message: String) = subcompose("notice") {
            Text(message, Modifier.padding(horizontal = horizontalPadding), style = typography.bodyMedium)
        }.single().measure(Constraints(maxWidth = width))
        if (source.isEmpty()) {
            state.adopt(source, emptyList(), geometry)
            val empty = notice("Nothing here yet. Save a link or move an article into this location.")
            return@SubcomposeLayout layout(width, height) { if (empty.height <= height) empty.placeRelative(0, minOf(16.dp.roundToPx(), height - empty.height)) }
        }
        if (height <= 0 || width <= 2) {
            state.suspendPresentation()
            return@SubcomposeLayout layout(width, height) {}
        }
        val measureConstraints = Constraints(minWidth = width, maxWidth = width)
        fun measureRow(candidate: MeasuredNewspaperRow): MeasuredNewspaperRow {
            val key = listOf(candidate.row, candidate.preview, candidate.extraDescriptionLines,
                candidate.extraHeadlineLines, width, height)
            val rowHeight = measuredHeights.getOrPut(key) {
                val slotHeight = ((height - 2).coerceAtLeast(1) / candidate.row.slots.maxOf { it.indices.size }).toDp()
                subcompose(listOf("measure", key)) { body(candidate, true, slotHeight) }.single().measure(measureConstraints).height
            }
            return candidate.copy(heightPx = rowHeight)
        }
        fun variants(row: NewspaperRow): List<MeasuredNewspaperRow> {
            val templates = newspaperViewportTemplates(row, columns)
            val ordinary = listOf(NewspaperPreview.Full, NewspaperPreview.Compact).flatMap { preview ->
                templates.map { measureRow(MeasuredNewspaperRow(it, 0, preview)) }.filter { it.heightPx <= height }
            }
            if (ordinary.isNotEmpty()) return ordinary
            for (preview in listOf(NewspaperPreview.Minimal, NewspaperPreview.HeadlineOnly)) {
                val fitted = templates.map { measureRow(MeasuredNewspaperRow(it, 0, preview)) }.filter { it.heightPx <= height }
                if (fitted.isNotEmpty()) return fitted
            }
            return emptyList()
        }
        val pages = pagePlans[width to height] ?: run {
            var cannotFit = false
            val choices = rows.flatMap { row ->
                variants(row).takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: row.indices.mapNotNull { index ->
                    variants(NewspaperRow(listOf(index), false, false, listOf(NewspaperSlot(listOf(index), columns))))
                        .takeIf { it.isNotEmpty() }.also { if (it == null) cannotFit = true }
                }
            }
            if (cannotFit) {
                state.suspendPresentation()
                val tooSmall = notice("More space is needed to show saved articles. Enlarge this pane to continue.")
                return@SubcomposeLayout layout(width, height) { if (tooSmall.height <= height) tooSmall.placeRelative(0, 0) }
            }
            packNewspaperViewportVariants(choices.size, height) { choices[it] }.also { pagePlans[width to height] = it }
        }
        state.adopt(source, pages, geometry)
        val page = pages.getOrNull(state.currentPage)?.let {
            enrichedPages.getOrPut(listOf(width, height, it)) { enrichNewspaperViewportPage(it, height, ::measureRow) }
        }
        val placed = page?.rows.orEmpty().map { row ->
            val slotHeight = ((height - 2).coerceAtLeast(1) / row.row.slots.maxOf { it.indices.size }).toDp()
            subcompose(listOf("print", row.row.indices)) { body(row, false, slotHeight) }.single().measure(measureConstraints)
        }
        layout(width, height) {
            var y = 0
            placed.forEach { it.placeRelative(0, y); y += it.height }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedLibraryRow(
    items: List<SavedItem>,
    state: SavedLibraryEditionState,
    measured: MeasuredNewspaperRow,
    columns: Int,
    horizontalPadding: Dp,
    columnSpacing: Dp,
    previewHeight: Dp,
    measurementOnly: Boolean,
    onOpen: (String) -> Unit,
    onOptions: (String) -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val lanes = columns.coerceIn(1, 4)
            val laneWidth = ((maxWidth - columnSpacing * (lanes - 1)) / lanes).coerceAtLeast(1.dp)
            Row(Modifier.fillMaxWidth().drawBehind {
                var preceding = 0
                measured.row.slots.dropLast(1).forEach { slot ->
                    preceding += slot.span
                    val boundary = preceding * laneWidth.toPx() + (preceding - 0.5f) * columnSpacing.toPx()
                    val x = if (rtl) size.width - boundary else boundary
                    drawLine(color, Offset(x, 10.dp.toPx()), Offset(x, (size.height - 12.dp.toPx()).coerceAtLeast(10.dp.toPx())), 0.5.dp.toPx())
                }
            }, horizontalArrangement = Arrangement.spacedBy(columnSpacing)) {
                measured.row.slots.forEach { slot ->
                    Column(Modifier.width(laneWidth * slot.span + columnSpacing * (slot.span - 1))) {
                        slot.indices.forEachIndexed { position, index ->
                            if (position > 0) HorizontalDivider(color = color, thickness = 0.5.dp)
                            val item = items[index]
                            val status = savedOfflineStatus(item)
                            val reserveImage = state.reserveImage(item)
                            Column(if (measurementOnly) Modifier else Modifier.testTag("saved-item-${item.id}")
                                .combinedClickable(role = Role.Button, indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { onOpen(item.id) }, onLongClick = { onOptions(item.id) })
                                .semantics {
                                stateDescription = "${item.location.name.lowercase()}, ${if (item.isRead) "read" else "unread"}, $status"
                                customActions = listOf(CustomAccessibilityAction("Article actions") { onOptions(item.id); true })
                            }) {
                                // Scalar overload is presentation only: no RSS identifiers, caches, swipe commands or HTTP image discovery.
                                ArticleItem(title = item.title, feedName = item.sourceName ?: runCatching { java.net.URI(item.url).host }.getOrNull().orEmpty(), author = item.author,
                                    shortDescription = item.excerpt.orEmpty(), isUnread = !item.isRead,
                                    emphasizeUnread = state.emphasizeUnread(item),
                                    imgData = if (measurementOnly || !reserveImage) null else item.thumbnailPath?.let(::File),
                                    reserveImage = reserveImage, measurementOnly = measurementOnly,
                                    isLead = measured.row.isLead, storyStyle = slot.style, preview = measured.preview,
                                    previewHeight = previewHeight, extraDescriptionLines = measured.extraDescriptionLines[index] ?: 0,
                                    extraHeadlineLines = measured.extraHeadlineLines[index] ?: 0,
                                    onClick = { onOpen(item.id) }, onLongClick = { onOptions(item.id) })
                                Text(status, Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = color, thickness = 0.5.dp)
    }
}

internal fun savedOfflineStatus(item: SavedItem): String = when (item.contentState) {
    SavedContentState.QUEUED -> "Saved link · Waiting to download"
    SavedContentState.DOWNLOADING -> "Downloading text"
    SavedContentState.FAILED -> "Saved link · Download failed"
    SavedContentState.READY -> when (item.imageState) {
        SavedImageState.NONE -> "Text available offline"
        SavedImageState.PENDING -> "Text offline · Images pending"
        SavedImageState.READY -> "Text and images offline"
        SavedImageState.PARTIAL -> "Text offline · Some images unavailable"
        SavedImageState.FAILED -> "Text offline · Images unavailable"
    }
}
