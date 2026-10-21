package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.ash.reader.ui.component.base.eInkVerticalPageGesture
import me.ash.reader.domain.data.Diff
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.infrastructure.preference.LocalSettings
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedName
import me.ash.reader.infrastructure.preference.LocalFlowArticleListReadIndicator
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime

/**
 * Production screen body. Measurement uses the very same text/columns as display, without any
 * image requests or interactive effects. Only complete measured rows are placed in this viewport.
 */
@Composable
internal fun EInkNewspaperViewport(
    state: NewspaperViewportState,
    columns: Int,
    horizontalPadding: Dp,
    columnSpacing: Dp,
    modifier: Modifier = Modifier,
    diffMap: Map<String, Diff> = emptyMap(),
    feedUnreadCounts: Map<String, Int> = emptyMap(),
    articleListTonalElevation: Int = 0,
    onTurn: (Boolean) -> Boolean = { false },
    onClick: (ArticleWithFeed, Int) -> Unit = { _, _ -> },
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
    onDisplayed: (List<Int>) -> Unit = {},
) {
    val density = LocalDensity.current
    val settings = listOf(LocalSettings.current,
        LocalFlowArticleListImage.current, LocalFlowArticleListDesc.current,
        LocalFlowArticleListFeedIcon.current, LocalFlowArticleListFeedName.current,
        LocalFlowArticleListReadIndicator.current, LocalFlowArticleListTime.current)
    val typography = MaterialTheme.typography
    val direction = LocalLayoutDirection.current
    val items = state.items
    val editionRevision = state.revision
    SideEffect { diffMap.forEach { (id, diff) -> state.recordReadStatus(id, diff.isUnread) } }
    val rows = remember(state, items, columns) { state.rows(columns) }
    val pageTurn by rememberUpdatedState(onTurn)
    val minimumPaneHeight = with(density) { maxOf(50.dp.toPx(), (23 * fontScale + 4).dp.toPx()).toInt() }
    // Invalidated by actual layout-affecting settings, not loading images or unread diffs.
    val measurements = remember(density.density, density.fontScale, settings, typography, direction,
        horizontalPadding, columnSpacing, columns, state.revision) {
        mutableMapOf<List<Any>, Int>()
    }
    val cacheBounds = remember(measurements) { intArrayOf(-1, -1) }
    val pageCache = state.pageCache
    val gestureKey = listOf(state.revision, state.layoutRevision, state.currentPage, columns, density.fontScale, settings)
    // Each subcomposition owns the immutable source that its row indexes. An existing slot can
    // recompose before the parent's next measure removes it after a filter/reset; it must never
    // resolve its old row against the shared lambda's newest (possibly empty) source.
    val body: @Composable (MeasuredNewspaperRow, List<ArticleFlowItem>, Boolean, Dp) -> Unit = { measured, source, measure, height ->
        NewspaperViewportRow(state, measured.row, source, columns, horizontalPadding, columnSpacing, measured.preview, height, measured.extraDescriptionLines, measured.extraHeadlineLines,
            measure, diffMap, feedUnreadCounts, articleListTonalElevation, onClick,
            onToggleStarred, onToggleRead, onMarkAboveAsRead, onMarkBelowAsRead, onShare)
    }
    SubcomposeLayout(
        modifier.fillMaxSize().testTag("newspaper-viewport")
            .semantics {
                verticalScrollAxisRange = ScrollAxisRange(
                    value = { state.currentPage.toFloat() },
                    maxValue = { (state.pages.size - 1).coerceAtLeast(0).toFloat() },
                )
                scrollBy { _, y -> y != 0f && pageTurn(y > 0f) }
                customActions = listOf(
                    CustomAccessibilityAction("Previous page") { pageTurn(false) },
                    CustomAccessibilityAction("Next page") { pageTurn(true) },
                )
            }
            .eInkVerticalPageGesture(
                gestureKey = gestureKey,
                currentGestureKey = { listOf(state.revision, state.layoutRevision, state.currentPage,
                    columns, density.fontScale, settings) },
                onTurn = { pageTurn(it) },
            ),
    ) { constraints ->
        val width = constraints.maxWidth
        if (!state.isCurrentSnapshot(items, editionRevision)) {
            return@SubcomposeLayout layout(width, constraints.maxHeight.coerceAtLeast(0)) {}
        }
        if (constraints.maxHeight < minimumPaneHeight || width <= 2) {
            state.suspendPresentation()
            val notice = subcompose("insufficient-space") {
                Text("More space is needed to show articles", maxLines = 1,
                    modifier = Modifier.semantics { stateDescription = "Enlarge the article pane to resume this page" })
            }.single().measure(Constraints(maxWidth = width.coerceAtLeast(0)))
            return@SubcomposeLayout layout(width.coerceAtLeast(0), constraints.maxHeight.coerceAtLeast(0)) {
                if (notice.height <= constraints.maxHeight) notice.placeRelative(0, 0)
            }
        }
        val height = constraints.maxHeight.coerceAtLeast(1)
        val geometry = listOf(width, height, density.density, density.fontScale, columns,
            horizontalPadding, columnSpacing, settings, typography, direction)
        if (cacheBounds[0] != width || cacheBounds[1] != height) {
            measurements.clear()
            cacheBounds[0] = width; cacheBounds[1] = height
        }
        val measureConstraints = Constraints(minWidth = width, maxWidth = width)
        fun measureRow(candidate: MeasuredNewspaperRow): MeasuredNewspaperRow {
            val row = candidate.row
            val cacheKey = listOf(row, candidate.preview, candidate.extraDescriptionLines, candidate.extraHeadlineLines,
                width, height, items[row.indices.first()])
            val rowHeight = measurements.getOrPut(cacheKey) {
                val slotHeight = ((height - 2).coerceAtLeast(1) / row.slots.maxOf { it.indices.size }).toDp()
                subcompose(listOf("measure", editionRevision, row, candidate.preview, candidate.extraDescriptionLines, candidate.extraHeadlineLines)) {
                    body(candidate, items, true, slotHeight)
                }.single().measure(measureConstraints).height
            }
            return candidate.copy(heightPx = rowHeight)
        }
        fun variants(row: NewspaperRow): List<MeasuredNewspaperRow> {
            if (row.isDate) return listOf(measureRow(MeasuredNewspaperRow(row, 0)))
                .filter { it.heightPx <= height }
            val templates = newspaperViewportTemplates(row, columns)
            val ordinary = listOf(NewspaperPreview.Full, NewspaperPreview.Compact).flatMap { preview ->
                templates.map { measureRow(MeasuredNewspaperRow(it, 0, preview)) }
                    .filter { it.heightPx <= height }
            }
            if (ordinary.isNotEmpty()) return ordinary
            for (preview in listOf(NewspaperPreview.Minimal, NewspaperPreview.HeadlineOnly)) {
                val fitted = templates.map { measureRow(MeasuredNewspaperRow(it, 0, preview)) }
                    .filter { it.heightPx <= height }
                if (fitted.isNotEmpty()) return fitted
            }
            return emptyList()
        }
        val breaks = state.pageBreaksFor(geometry)
        val basePages = pageCache.partition(rows, geometry, breaks) ?: run {
            var cannotFit = false
            val measured = rows.flatMap { row ->
                val fitted = variants(row)
                when {
                    fitted.isNotEmpty() -> listOf(fitted)
                    row.isDate -> emptyList()
                    else -> row.indices.mapNotNull { index ->
                        // A stacked mosaic may exceed even a headline-only viewport. Unstack whole
                        // stories instead of shrinking the user's chosen font or cutting a line.
                        variants(NewspaperRow(listOf(index), false, false,
                            listOf(NewspaperSlot(listOf(index), span = columns)))).takeIf { it.isNotEmpty() }
                            .also { if (it == null) cannotFit = true }
                    }
                }
            }
            if (cannotFit) {
                // Never omit an unfittable article or count it as displayed/read. This is only
                // possible in a transient/minuscule pane: keep the edition and wait for usable space.
                state.suspendPresentation()
                val notice = subcompose("insufficient-story-space") {
                    Text("More space is needed to show articles", maxLines = 1)
                }.single().measure(measureConstraints)
                return@SubcomposeLayout layout(width, height) { if (notice.height <= height) notice.placeRelative(0, 0) }
            }
            packNewspaperViewportVariants(measured.size, height, breaks) { measured[it] }.also {
                pageCache.retainPartition(rows, geometry, breaks, it)
            }
        }
        var pages = basePages.map { pageCache.enriched[it] ?: it }
        if (!state.updatePages(pages, geometry, items, editionRevision)) {
            return@SubcomposeLayout layout(width, height) {}
        }
        val baseCurrent = basePages.getOrNull(state.currentPage)
        val current = baseCurrent?.let { page ->
            pageCache.enriched.getOrPut(page) { enrichNewspaperViewportPage(page, height, ::measureRow) }
        }
        if (current != null && current != pages[state.currentPage]) {
            pages = pages.mapIndexed { index, page -> if (index == state.currentPage) current else page }
            if (!state.updatePages(pages, geometry, items, editionRevision)) {
                return@SubcomposeLayout layout(width, height) {}
            }
        }
        val displayed = current?.indices.orEmpty()
        val placeables = current?.rows.orEmpty().map { measuredRow ->
            val row = measuredRow.row
            val slotHeight = ((height - 2).coerceAtLeast(1) / row.slots.maxOf { it.indices.size }).toDp()
            subcompose(listOf("display", editionRevision, row.indices)) {
                body(measuredRow, items, false, slotHeight)
            }.single().measure(measureConstraints)
        }
        layout(width, height) {
            var y = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(0, y)
                y += placeable.height
            }
            if (state.presented(displayed, items, editionRevision)) onDisplayed(displayed)
        }
    }
}

@Composable
private fun NewspaperViewportRow(
    state: NewspaperViewportState,
    row: NewspaperRow,
    items: List<ArticleFlowItem>,
    columns: Int,
    horizontalPadding: Dp,
    columnSpacing: Dp,
    preview: NewspaperPreview,
    previewHeight: Dp,
    extraDescriptionLines: Map<Int, Int>,
    extraHeadlineLines: Map<Int, Int>,
    measurementOnly: Boolean,
    diffMap: Map<String, Diff>,
    feedUnreadCounts: Map<String, Int>,
    tonalElevation: Int,
    onClick: (ArticleWithFeed, Int) -> Unit,
    onToggleStarred: (ArticleWithFeed) -> Unit,
    onToggleRead: (ArticleWithFeed) -> Unit,
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)?,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)?,
    onShare: ((ArticleWithFeed) -> Unit)?,
) {
    if (row.isDate) {
        StickyHeader((items[row.indices.first()] as ArticleFlowItem.Date).date, false, tonalElevation)
        return
    }
    val color = MaterialTheme.colorScheme.onSurface
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val lanes = columns.coerceIn(1, 4)
            val laneWidth = ((maxWidth - columnSpacing * (lanes - 1)) / lanes).coerceAtLeast(1.dp)
            Row(
                Modifier.fillMaxWidth().then(if (measurementOnly) Modifier else Modifier.testTag("newspaper-row-${row.indices.first()}")).drawBehind {
                    val gap = columnSpacing.toPx()
                    val lane = (size.width - gap * (lanes - 1)) / lanes
                    var preceding = 0
                    row.slots.dropLast(1).forEach { slot ->
                        preceding += slot.span
                        val boundary = preceding * lane + (preceding - 0.5f) * gap
                        val x = if (rtl) size.width - boundary else boundary
                        // Keep printed-column guidance without joining it to row rules
                        // into a grid of boxes. Match the continuous article list.
                        drawLine(color, Offset(x, 10.dp.toPx()),
                            Offset(x, (size.height - 12.dp.toPx()).coerceAtLeast(10.dp.toPx())),
                            0.5.dp.toPx())
                    }
                },
                horizontalArrangement = Arrangement.spacedBy(columnSpacing),
            ) {
                row.slots.forEach { slot ->
                    Column(Modifier.width(laneWidth * slot.span + columnSpacing * (slot.span - 1))) {
                        slot.indices.forEachIndexed { position, index ->
                            if (position > 0) HorizontalDivider(color = color, thickness = 0.5.dp)
                            val item = (items[index] as ArticleFlowItem.Article).articleWithFeed
                            Box(if (measurementOnly) Modifier else Modifier.testTag("article-block-${item.article.id}")) {
                                if (measurementOnly) {
                                    ArticleItem(articleWithFeed = item, feedUnreadCount = 0,
                                        isLead = row.isLead, storyStyle = slot.style,
                                        preview = preview, previewHeight = previewHeight,
                                        extraDescriptionLines = extraDescriptionLines[index] ?: 0,
                                        extraHeadlineLines = extraHeadlineLines[index] ?: 0, measurementOnly = true)
                                } else {
                                    val current = state.currentArticle(item)
                                    SwipeableArticleItem(articleWithFeed = current,
                                        isUnread = diffMap[item.article.id]?.isUnread ?: current.article.isUnread,
                                        emphasizeUnread = item.article.isUnread,
                                        // Counts are deliberately not part of printed geometry.
                                        feedUnreadCount = feedUnreadCounts[item.feed.id] ?: 0,
                                        isLead = row.isLead, storyStyle = slot.style,
                                        preview = preview, previewHeight = previewHeight,
                                        extraDescriptionLines = extraDescriptionLines[index] ?: 0,
                                        extraHeadlineLines = extraHeadlineLines[index] ?: 0,
                                        articleListTonalElevation = tonalElevation,
                                        onClick = { onClick(it, index) },
                                        onToggleStarred = onToggleStarred, onToggleRead = onToggleRead,
                                        onMarkAboveAsRead = onMarkAboveAsRead,
                                        onMarkBelowAsRead = onMarkBelowAsRead, onShare = onShare)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (row.isLead) HorizontalDivider(color = color, thickness = 0.5.dp)
    }
}
