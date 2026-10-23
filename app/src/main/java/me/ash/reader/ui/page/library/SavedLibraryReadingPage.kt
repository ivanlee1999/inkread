package me.ash.reader.ui.page.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.io.File
import java.util.Date
import me.ash.reader.domain.annotation.AnnotationSource
import me.ash.reader.domain.annotation.AnnotationSourceKind
import me.ash.reader.ui.page.annotation.AnnotationReaderUi
import me.ash.reader.ui.page.annotation.AnnotationReaderViewModel
import me.ash.reader.ui.page.annotation.AnnotationReaderDialogs
import me.ash.reader.ui.page.annotation.rememberAnnotationReaderUi
import me.ash.reader.domain.library.SavedContentState
import me.ash.reader.domain.library.SavedLocation
import me.ash.reader.ui.page.home.reading.EInkPaginatedContent
import me.ash.reader.ui.page.home.reading.EInkReaderVolumeReservation

@Composable
fun SavedLibraryReadingPage(
    itemId: String,
    onBack: () -> Unit,
    viewModel: SavedLibraryReadingViewModel = hiltViewModel(),
    annotationViewModel: AnnotationReaderViewModel = hiltViewModel(),
    requestedAnnotationId: String? = null,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(itemId) { viewModel.open(itemId) }
    val document = state.document.takeIf { state.itemId == itemId && !state.loading && state.error == null }
    val source = document?.item?.let { item ->
        AnnotationSource(AnnotationSourceKind.SAVED, item.id, url = item.url,
            title = item.title, author = item.author, sourceName = item.sourceName)
    }
    val annotations = rememberAnnotationReaderUi(source, document?.contentRevision, annotationViewModel, requestedAnnotationId)
    // Key by the route immediately, not by the previous VM's asynchronous state.
    SavedLibraryReadingContent(
        state = if (state.itemId == itemId) state else SavedLibraryReadingState(itemId = itemId),
        onBack = { viewModel.exit(onBack) },
        onMove = viewModel::move,
        onSetRead = viewModel::setRead,
        onRetry = viewModel::retry,
        onReadingAnchorChanged = viewModel::saveAnchor,
        onRetryPositionSave = viewModel::retryPositionSave,
        onExitWithoutSaving = onBack,
        annotations = annotations,
    )
    AnnotationReaderDialogs(annotations)
}

/** Test seam uses the real production reader and never needs an RSS view model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavedLibraryReadingContent(
    state: SavedLibraryReadingState,
    onBack: () -> Unit,
    onMove: (SavedLocation) -> Unit,
    onSetRead: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onReadingAnchorChanged: (String, String, String) -> Unit,
    onRetryPositionSave: () -> Unit = {},
    onExitWithoutSaving: (() -> Unit)? = null,
    annotations: AnnotationReaderUi? = null,
) {
    val context = LocalContext.current
    val readerOwner = remember(state.itemId) { Any() }
    // Retain exclusive reader ownership through document and formatting loading,
    // errors and content retries. There is no hidden RSS list consumer here.
    EInkReaderVolumeReservation(true, readerOwner)
    BackHandler(onBack = onBack)
    var actionsVisible by remember(state.itemId) { mutableStateOf(false) }
    val document = state.document
    val item = state.item ?: document?.item
    var restoreNotice by remember(state.itemId, document?.contentRevision) { mutableStateOf<String?>(null) }
    val assets = remember(document?.item?.id, document?.html) {
        document?.let { SavedLibraryReaderAssets(it.html, File(context.filesDir, "saved-library"), it.item.id) }
    }
    // The route is edge-to-edge. Apply and consume safe insets once outside every
    // state, so zero reader margins and loading/error Back stay above system bars.
    // Descendant navigationBarsPadding sees these insets as already consumed.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)
        .safeDrawingPadding().testTag("saved_library_reader")) {
        val actionsMaxHeight = maxHeight * .45f
        Column(Modifier.fillMaxSize()) {
            if (document == null || assets == null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SavedReaderAction("Back", "saved_reader_back", onBack)
                    Text("Saved article", color = Color.Black)
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(when {
                            state.loading -> "Opening saved article…"
                            state.error != null -> state.error
                            item == null -> "This saved article is no longer available."
                            item.contentState == SavedContentState.FAILED -> "This article could not be saved."
                            else -> "Saving article for offline reading…"
                        }, color = Color.Black)
                        if (!state.loading && (item != null || state.error != null)) SavedReaderAction("Retry", "saved_reader_retry", onRetry)
                    }
                }
            } else {
                restoreNotice?.let { notice ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(notice, color = Color.Black, modifier = Modifier.weight(1f))
                        SavedReaderAction("Dismiss", "saved_reader_resume_notice") { restoreNotice = null }
                    }
                }
                state.error?.let { error ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, color = Color.Black, modifier = Modifier.weight(1f))
                        SavedReaderAction("Actions", "saved_reader_error_actions") { actionsVisible = true }
                    }
                }
                if (state.positionError) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Reading position could not be saved.", color = Color.Black, modifier = Modifier.weight(1f))
                        SavedReaderAction("Retry", "saved_reader_position_retry", onRetryPositionSave)
                        onExitWithoutSaving?.let { SavedReaderAction("Leave anyway", "saved_reader_leave_anyway", it) }
                    }
                }
                // Saved articles intentionally use this same paginated engine in both
                // display modes. This does not claim parity with the legacy RSS scroller.
                EInkPaginatedContent(
                    modifier = Modifier.weight(1f),
                    articleId = document.item.id,
                    content = assets.html,
                    feedName = document.item.sourceName.orEmpty(),
                    title = document.item.title,
                    author = document.item.author,
                    link = document.item.url,
                    publishedDate = Date(document.item.savedAt),
                    dateLabel = "Saved",
                    selectionMode = annotations?.selectionMode ?: false,
                    onSelectionModeChange = annotations?.onSelectionModeChange?.takeIf { annotations?.available == true },
                    annotationEditing = annotations?.annotationEditing == true || actionsVisible,
                    annotationsJson = annotations?.annotationsJson ?: "[]",
                    onCreateAnnotation = annotations?.onCreateAnnotation?.takeIf { annotations?.available == true },
                    onAnnotationLocationsChanged = annotations?.onAnnotationLocationsChanged,
                    onAnnotationsClick = annotations?.onAnnotationsClick?.takeIf { sourceReadyForAnnotations(state) },
                    onAnnotationClick = annotations?.onAnnotationClick,
                    annotationResizeRequest = annotations?.resizeRequest,
                    onAnnotationResizeResult = annotations?.onResizeResult ?: { _, _ -> },
                    onResizeSelection = annotations?.onResizeSelection,
                    autoHighlightEnabled = annotations?.autoHighlightEnabled == true,
                    onAutoCreateAnnotation = annotations?.onAutoCreateAnnotation,
                    onUndoAutoHighlight = annotations?.undoAutoHighlight,
                    annotationStatus = annotations?.status,
                    annotationJumpRequest = annotations?.jumpRequest,
                    onAnnotationJumpResult = annotations?.onAnnotationJumpResult,
                    reserveVolumeKeys = false,
                    volumeKeyOwner = readerOwner,
                    onBack = onBack,
                    onLibraryActions = { actionsVisible = !actionsVisible },
                    readingContentRevision = document.contentRevision,
                    initialReadingAnchor = document.readingAnchor,
                    onReadingAnchorChanged = onReadingAnchorChanged,
                    onReadingAnchorRestore = { status ->
                        restoreNotice = when (status) {
                            "changed" -> "Saved content changed. Opened at the beginning."
                            "invalid" -> "The saved position was unavailable. Opened at the beginning."
                            else -> null
                        }
                    },
                    interceptResource = assets::intercept,
                    resourceRevision = assets.revision,
                )
                if (actionsVisible && item != null) {
                    // A higher-priority owner registered last absorbs keys while these
                    // actions are open; it never forwards them to the page or list.
                    val actionsOwner = remember(state.itemId) { Any() }
                    EInkReaderVolumeReservation(true, actionsOwner)
                    BackHandler { actionsVisible = false }
                    HorizontalDivider(color = Color.Black)
                    Column(Modifier.fillMaxWidth().heightIn(max = actionsMaxHeight)
                        .verticalScroll(rememberScrollState()).padding(12.dp).testTag("saved_reader_actions")) {
                        Text("Saved · ${item.location.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (item.isRead) "Read" else "Unread"}", color = Color.Black)
                        Text("Text: ${item.contentState.name.lowercase()} · Images: ${item.imageState.name.lowercase()}", color = Color.DarkGray)
                        state.error?.let { Text(it, color = Color.Black) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SavedLocation.entries.filter { it != item.location }.forEach { location ->
                                SavedReaderAction("Move to ${location.name.lowercase()}", "saved_move_${location.name.lowercase()}") {
                                    onMove(location); actionsVisible = false
                                }
                            }
                            SavedReaderAction(if (item.isRead) "Mark unread" else "Mark read", "saved_reader_read") {
                                onSetRead(!item.isRead); actionsVisible = false
                            }
                            SavedReaderAction("Retry download", "saved_reader_retry", onRetry)
                            SavedReaderAction("Close", "saved_reader_actions_close") { actionsVisible = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedReaderAction(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).testTag(tag).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClick = onClick,
        ).padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.Black) }
}

private fun sourceReadyForAnnotations(state: SavedLibraryReadingState): Boolean =
    !state.loading && state.error == null && state.document != null
