package me.ash.reader.ui.page.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.domain.library.SavedContentState
import me.ash.reader.domain.library.SavedImageState
import me.ash.reader.domain.library.SavedItem
import me.ash.reader.domain.library.SavedLocation
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.ui.page.home.flow.ArticleListPaneLayout
import me.ash.reader.ui.page.home.flow.EInkArticlePageControl
import me.ash.reader.ui.component.base.EInkListVolumePageKeys

@Composable
fun SavedLibraryPage(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCapture: (String) -> Unit,
    onHighlights: () -> Unit = {},
    viewModel: SavedLibraryViewModel = hiltViewModel(),
) {
    val allItems by viewModel.items.collectAsState()
    val locationName by viewModel.location.collectAsState()
    val filterName by viewModel.readFilter.collectAsState()
    val message by viewModel.message.collectAsState()
    val location = SavedLocation.entries.firstOrNull { it.name == locationName } ?: SavedLocation.INBOX
    val filter = SavedReadFilter.entries.firstOrNull { it.name == filterName } ?: SavedReadFilter.ALL
    val items = remember(allItems, location, filter) { allItems.orEmpty().filter {
        it.location == location && when (filter) {
            SavedReadFilter.ALL -> true
            SavedReadFilter.UNREAD -> !it.isRead
            SavedReadFilter.READ -> it.isRead
        }
    } }
    val state = viewModel.edition("${location.name}_${filter.name}")
    var pasteOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = allItems?.firstOrNull { it.id == selectedId }
    val removing = allItems?.firstOrNull { it.id == removeId }
    val navigationEnabled = !pasteOpen && !filtersOpen && selected == null && removing == null
    EInkListVolumePageKeys(enabled = LocalEInkMode.current.isEInkMode() && navigationEnabled,
        ownerKey = state, onTurn = state::turn)
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("saved-library")) {
            if (message != null) SavedLibraryAction(message.orEmpty() + " Dismiss.", onClick = viewModel::clearMessage)
            SavedLibraryContent(
                items = items, state = state, location = location, filter = filter,
                loading = allItems == null, navigationEnabled = navigationEnabled,
                onBack = onBack, onOpen = onOpen, onOptions = { selectedId = it },
                onSaveLink = { pasteOpen = true }, onShowFilters = { filtersOpen = true },
                onLocation = viewModel::selectLocation,
            )
        }
    }
    if (pasteOpen) SavedPasteDialog(onSave = { pasteOpen = false; onCapture(it) }, onDismiss = { pasteOpen = false })
    if (filtersOpen) SavedLibraryDialog("Your saved library", { filtersOpen = false }) {
        SavedLibraryAction("Highlights and notes", Modifier.testTag("saved-library-highlights")) {
            filtersOpen = false; onHighlights()
        }
        HorizontalDivider()
        Text("One on-device library, independent of RSS accounts. Inbox, Later and Archive are locations, not read states.")
        Text("Tap an article to read. Touch and hold it for actions. Page controls are in the article count; swipe up or down to turn a whole page.")
        HorizontalDivider()
        SavedReadFilter.entries.forEach { option ->
            SavedLibraryAction(if (filter == option) "✓ ${option.label}" else option.label,
                Modifier.testTag("saved-library-filter-${option.name}")) {
                viewModel.selectReadFilter(option); filtersOpen = false
            }
        }
        SavedLibraryAction("Close") { filtersOpen = false }
    }
    if (selected != null) SavedLibraryItemDialog(selected, onDismiss = { selectedId = null },
        onOpen = { selectedId = null; onOpen(selected.id) },
        onMove = { selectedId = null; viewModel.move(selected.id, it) },
        onRead = { selectedId = null; viewModel.setRead(selected.id, !selected.isRead) },
        onRetry = { selectedId = null; viewModel.retry(selected.id) },
        onRemove = { selectedId = null; removeId = selected.id })
    if (removing != null) SavedLibraryDialog("Remove saved article?", { removeId = null }) {
        Text(removing.title)
        Text("This removes the saved text, downloaded images and reading position from this device. It does not change RSS favorites or subscriptions.")
        SavedLibraryAction("Remove article", Modifier.testTag("saved-library-confirm-remove")) {
            removeId = null; viewModel.remove(removing.id)
        }
        SavedLibraryAction("Keep article") { removeId = null }
    }
}

/** Stateless production surface for phone/tablet/font-scale tests without an RSS account. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavedLibraryContent(
    items: List<SavedItem>,
    state: SavedLibraryEditionState,
    location: SavedLocation,
    filter: SavedReadFilter = SavedReadFilter.ALL,
    loading: Boolean = false,
    navigationEnabled: Boolean = true,
    onBack: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onOptions: (String) -> Unit = {},
    onSaveLink: () -> Unit = {},
    onShowFilters: () -> Unit = {},
    onLocation: (SavedLocation) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SavedHeaderButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Text("Saved", Modifier.weight(1f).semantics { heading() }, fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            SavedHeaderButton(Icons.Rounded.Add, "Save a link", Modifier.testTag("saved-link-open"), onSaveLink)
            SavedHeaderButton(Icons.Rounded.MoreVert, "Library filters and help", Modifier.testTag("saved-library-filters"), onShowFilters)
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.Center) {
            SavedLocation.entries.forEach { option ->
                Box(Modifier.heightIn(min = 48.dp).testTag("saved-library-location-${option.name}")
                    .selectable(location == option, role = Role.Tab, interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = { onLocation(option) }).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center) {
                    Text(option.label(), fontWeight = if (location == option) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.titleSmall)
                }
            }
            EInkArticlePageControl(currentPage = state.currentPage + 1, totalPages = state.pages.size.coerceAtLeast(1),
                totalArticles = items.size, onPrev = { if (navigationEnabled) state.turn(false) },
                onNext = { if (navigationEnabled) state.turn(true) }, navigationKey = state, compactCount = true)
        }
        if (filter != SavedReadFilter.ALL) Text(filter.label, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium)
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface)
        if (loading) {
            Text("Loading saved articles…", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        } else ArticleListPaneLayout(Modifier.weight(1f)) { metrics ->
            SavedLibraryViewport(items = items, state = state, columns = metrics.columns,
                horizontalPadding = metrics.horizontalPaddingDp.dp, columnSpacing = metrics.columnSpacingDp.dp,
                onOpen = onOpen, onOptions = onOptions, navigationEnabled = navigationEnabled)
        }
    }
}

@Composable
private fun SavedHeaderButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(48.dp).semantics { contentDescription = label }.clickable(
        role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
    ), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
}

internal fun SavedLocation.label(): String = when (this) {
    SavedLocation.INBOX -> "Inbox"
    SavedLocation.LATER -> "Later"
    SavedLocation.ARCHIVE -> "Archive"
}

@Composable
private fun SavedLibraryItemDialog(
    item: SavedItem,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onMove: (SavedLocation) -> Unit,
    onRead: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    SavedLibraryDialog("Article actions", onDismiss, tag = "saved-library-item-actions") {
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        Text(item.sourceName ?: item.url, style = MaterialTheme.typography.bodySmall)
        Text("${item.location.label()} · ${if (item.isRead) "Read" else "Unread"} · ${savedOfflineStatus(item)}")
        item.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        SavedLibraryAction("Open article", onClick = onOpen)
        SavedLocation.entries.filterNot { it == item.location }.forEach { destination ->
            SavedLibraryAction("Move to ${destination.label()}", Modifier.testTag("saved-library-move-${destination.name}")) { onMove(destination) }
        }
        SavedLibraryAction(if (item.isRead) "Mark unread" else "Mark read", Modifier.testTag("saved-library-toggle-read"), onClick = onRead)
        if (item.contentState == SavedContentState.FAILED || item.imageState in setOf(SavedImageState.PARTIAL, SavedImageState.FAILED)) {
            SavedLibraryAction("Retry download", Modifier.testTag("saved-library-retry"), onClick = onRetry)
        }
        SavedLibraryAction("Remove from device", Modifier.testTag("saved-library-remove"), onClick = onRemove)
        SavedLibraryAction("Close", onClick = onDismiss)
    }
}
