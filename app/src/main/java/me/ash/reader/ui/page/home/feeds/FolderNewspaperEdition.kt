package me.ash.reader.ui.page.home.feeds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalReadingColumns
import me.ash.reader.infrastructure.preference.LocalReadingLayoutPreset
import me.ash.reader.ui.layout.resolveReadingLayout
import me.ash.reader.ui.component.base.eInkVerticalPageGesture

/** The currently visible subscription is the anchor, not a fragile pixel scroll offset. */
class FolderNewspaperState {
    internal var pages: List<FolderNewspaperPage> by mutableStateOf(emptyList())
        private set
    var currentPage by mutableIntStateOf(0)
        private set
    val pageCount: Int get() = pages.size
    internal var layoutIdentity: Any? by mutableStateOf(null)
        private set
    private var requestedSection: String? = null
    private var restoredAnchor: String? = null
    private var restoredSection: String? = null
    internal var presentationAvailable by mutableStateOf(false)
        private set

    internal fun suspendPresentation() { presentationAvailable = false }

    companion object {
        val Saver = listSaver<FolderNewspaperState, String>(
            save = { state ->
                val row = state.pages.getOrNull(state.currentPage)?.rows?.firstOrNull()?.row
                listOf(state.currentPage.toString(), row?.keys?.firstOrNull().orEmpty(),
                    row?.sectionIds?.firstOrNull().orEmpty())
            },
            restore = { saved -> FolderNewspaperState().also {
                it.currentPage = saved[0].toIntOrNull() ?: 0
                it.restoredAnchor = saved[1].takeIf(String::isNotEmpty)
                it.restoredSection = saved[2].takeIf(String::isNotEmpty)
            } },
        )
    }

    fun anchorFolder(id: String) { requestedSection = id }

    fun firstPage() {
        requestedSection = null
        currentPage = 0
    }

    fun turn(forward: Boolean): Boolean {
        if (!presentationAvailable) return false
        val next = currentPage + if (forward) 1 else -1
        if (next !in pages.indices) return false
        requestedSection = null
        currentPage = next
        return true
    }

    internal fun updatePages(next: List<FolderNewspaperPage>, identity: Any) {
        presentationAvailable = true
        if (pages == next && layoutIdentity == identity && requestedSection == null && restoredAnchor == null) return
        val old = pages.getOrNull(currentPage)?.rows?.firstOrNull()?.row
        val anchor = requestedSection?.let { "folder:$it" } ?: restoredAnchor ?: old?.keys?.firstOrNull()
        val section = requestedSection ?: restoredSection ?: old?.sectionIds?.firstOrNull()
        val target = folderPageForAnchor(next, anchor, section, currentPage)
        pages = next
        currentPage = target
        requestedSection = null
        if (next.isNotEmpty()) { restoredAnchor = null; restoredSection = null }
        layoutIdentity = identity
    }
}

private data class FolderMeasureKey(val row: FolderNewspaperRow, val width: Int, val preview: FolderPreview, val extraNames: Int)
private data class FolderMeasureSlot(val key: String, val preview: FolderPreview, val extraNames: Int)

/**
 * Real directory UI, also used by native tests. Insets/chrome are outside the measured viewport.
 * There is no scroll offset in paged mode: only complete measured rows are ever placed.
 */
@Composable
fun FolderNewspaperEdition(
    sections: List<FolderSection>,
    expanded: Set<String>,
    countLabel: String,
    modifier: Modifier = Modifier,
    state: FolderNewspaperState = remember { FolderNewspaperState() },
    paged: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    onOpenFolder: (String) -> Unit = {},
    onToggleFolder: (String) -> Unit = {},
    onFolderOptions: (String) -> Unit = {},
    onOpenPublication: (String) -> Unit = {},
    onPublicationOptions: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val direction = LocalLayoutDirection.current
    val previousLabel = stringResource(R.string.previous_page)
    val nextLabel = stringResource(R.string.next_page)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val paneWidth = maxWidth
        val paneHeight = maxHeight
        val metrics = resolveReadingLayout(paneWidth.value, density.fontScale,
            LocalReadingLayoutPreset.current.preset, LocalReadingColumns.current.columns)
        val rows = remember(sections, expanded, metrics.publicationColumns) {
            folderNewspaperRows(sections, expanded, metrics.publicationColumns)
        }
        val openFolder by rememberUpdatedState(onOpenFolder)
        val toggleFolder by rememberUpdatedState(onToggleFolder)
        val folderOptions by rememberUpdatedState(onFolderOptions)
        val openPublication by rememberUpdatedState(onOpenPublication)
        val publicationOptions by rememberUpdatedState(onPublicationOptions)
        val refresh by rememberUpdatedState(onRefresh)
        val render: @Composable (FolderNewspaperRow, FolderPreview, Int, Boolean) -> Unit = { row, preview, extraNames, measuring ->
            FolderNewspaperRowContent(row, metrics.columnSpacingDp.dp,
                preview, expanded, countLabel, measuring,
                onOpenFolder = openFolder,
                onToggleFolder = { id -> state.anchorFolder(id); toggleFolder(id) },
                onFolderOptions = folderOptions,
                onOpenPublication = openPublication,
                onPublicationOptions = publicationOptions, extraPublicationNames = extraNames)
        }
        if (!paged) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = metrics.horizontalPaddingDp.dp), state = listState) {
                items(rows, key = { it.key }) { render(it, FolderPreview.Full, 0, false) }
            }
        } else {
            val cache = remember(density.density, density.fontScale, typography, direction, metrics, countLabel, rows, paneWidth) {
                mutableMapOf<FolderMeasureKey, Int>()
            }
            val gestureKey = listOf(state.currentPage, state.layoutIdentity, paneWidth, paneHeight, density.fontScale)
            Column(Modifier.fillMaxSize()) {
                SubcomposeLayout(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = metrics.horizontalPaddingDp.dp)
                        .testTag("folder-viewport")
                        .semantics {
                            verticalScrollAxisRange = ScrollAxisRange(
                                value = { state.currentPage.toFloat() },
                                maxValue = { (state.pageCount - 1).coerceAtLeast(0).toFloat() })
                            scrollBy { _, y -> y != 0f && state.turn(y > 0f) }
                            customActions = listOf(
                                CustomAccessibilityAction(previousLabel) { state.turn(false) },
                                CustomAccessibilityAction(nextLabel) { state.turn(true) })
                        }
                        .eInkVerticalPageGesture(
                            gestureKey = gestureKey,
                            currentGestureKey = { listOf(state.currentPage, state.layoutIdentity, paneWidth, paneHeight, density.fontScale) },
                            onTurn = { forward ->
                                // This preserves the existing feeds-page pull-to-refresh boundary.
                                if (!state.turn(forward) && !forward && state.currentPage == 0 && state.presentationAvailable) refresh()
                            },
                        ),
                ) { constraints ->
                    val height = constraints.maxHeight.coerceAtLeast(1)
                    val width = constraints.maxWidth
                    val natural = Constraints(minWidth = width, maxWidth = width, maxHeight = Constraints.Infinity)
                    fun measureVariant(row: FolderNewspaperRow, preview: FolderPreview, extraNames: Int): MeasuredFolderRow {
                        val key = FolderMeasureKey(row, width, preview, extraNames)
                        val measured = cache.getOrPut(key) {
                            subcompose(FolderMeasureSlot(row.key, preview, extraNames)) {
                                render(row, preview, extraNames, true)
                            }.single().measure(natural).height
                        }
                        return MeasuredFolderRow(row, measured, preview, extraNames)
                    }
                    fun measure(row: FolderNewspaperRow): MeasuredFolderRow {
                        var result = measureVariant(row, FolderPreview.Full, 0)
                        for (preview in FolderPreview.entries.drop(1)) {
                            if (result.heightPx <= height) break
                            result = measureVariant(row, preview, 0)
                        }
                        return result
                    }
                    val measured = rows.flatMap { row ->
                        val original = measure(row)
                        if (original.heightPx <= height || row.heading == null || row.continuation) {
                            listOf(original)
                        } else {
                            // Extremely short panes can separate a complete section block from
                            // its publication row, but can never crop either at the page edge.
                            listOf(
                                measure(FolderNewspaperRow(listOf(FolderNewspaperSlot(row.heading,
                                    span = metrics.publicationColumns)))),
                                measure(row.copy(continuation = true)),
                            )
                        }
                    }
                    if (measured.any { it.heightPx > height }) {
                        // A transient pane smaller than a touch target cannot show a complete
                        // subscription. Retain every source and its anchor; do not clip/drop it.
                        state.suspendPresentation()
                        val notice = subcompose("folder-insufficient-space") {
                            Text("More space is needed to show subscriptions", maxLines = 1,
                                modifier = Modifier.semantics {
                                    stateDescription = "Enlarge the subscriptions pane to resume this page"
                                })
                        }.single().measure(Constraints(maxWidth = width.coerceAtLeast(0)))
                        return@SubcomposeLayout layout(width, height) {
                            if (notice.height <= height) notice.placeRelative(0, 0)
                        }
                    }
                    val pages = fillFolderNewspaperPages(measured, height, ::measureVariant)
                    state.updatePages(pages, listOf(width, height, density.fontScale, rows))
                    val visible = pages.getOrNull(state.currentPage)?.rows.orEmpty()
                    val placeables = visible.map { row ->
                        subcompose("visible:${row.row.key}") {
                            render(row.row, row.preview, row.extraPublicationNames, false)
                        }.single().measure(natural)
                    }
                    layout(width, height) {
                        var y = 0
                        placeables.forEach { placeable ->
                            placeable.placeRelative(0, y)
                            y += placeable.height
                        }
                    }
                }
                // On unusually short/large-type panes gestures and accessibility actions stay
                // available, while removing page furniture preserves complete content blocks.
                if (paneHeight.value >= 144f * density.fontScale.coerceAtLeast(1f)) {
                    FolderEditionControls(state, previousLabel, nextLabel)
                }
            }
        }
    }
}

@Composable
private fun FolderEditionControls(state: FolderNewspaperState, previous: String, next: String) {
    val color = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().testTag("folder-page-controls")) {
        HorizontalDivider(color = color, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(false, true).forEach { forward ->
                if (forward) Text("${if (state.pageCount == 0) 0 else state.currentPage + 1} / ${state.pageCount}",
                    Modifier.weight(1f).testTag("folder-page-count"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge, color = color)
                val enabled = state.presentationAvailable &&
                    if (forward) state.currentPage + 1 < state.pageCount else state.currentPage > 0
                Box(Modifier.size(48.dp).testTag(if (forward) "folder-next-page" else "folder-previous-page")
                    .clickable(interactionSource = remember { MutableInteractionSource() },
                        indication = null, enabled = enabled, role = Role.Button) { state.turn(forward) },
                    contentAlignment = Alignment.Center) {
                    Icon(if (forward) Icons.AutoMirrored.Rounded.ArrowForward else Icons.AutoMirrored.Rounded.ArrowBack,
                        if (forward) next else previous, tint = if (enabled) color else color.copy(alpha = 0.4f))
                }
            }
        }
    }
}
