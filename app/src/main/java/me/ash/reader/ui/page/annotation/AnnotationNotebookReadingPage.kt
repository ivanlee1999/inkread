package me.ash.reader.ui.page.annotation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.domain.annotation.AnnotationSourceKind
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.NavigationAction
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.home.reading.ReadingPage
import me.ash.reader.ui.page.library.SavedLibraryReadingPage

/** Notebook-origin RSS routes close directly to the retained notebook, never a hidden RSS list. */
@Composable
fun AnnotationNotebookReadingPage(
    annotationId: String,
    sourceId: String,
    accountId: Int,
    onBack: () -> Unit,
    onNavigateToStylePage: () -> Unit,
    viewModel: ArticleListReaderViewModel = hiltViewModel(),
    notebook: AnnotationNotebookViewModel = hiltViewModel(),
) {
    val document by remember(annotationId) { notebook.observeAnnotation(annotationId) }
        .collectAsState(AnnotationDocumentState())
    val annotation = document.annotations.singleOrNull()
    val activeAccount by notebook.currentAccountId.collectAsState()
    val reader by viewModel.readerStateStateFlow.collectAsState()
    val verified = annotation?.source?.let {
        it.kind == AnnotationSourceKind.RSS && it.sourceId == sourceId && it.accountId == accountId
    } == true
    LaunchedEffect(sourceId, accountId, verified, activeAccount) {
        if (verified && activeAccount == accountId) viewModel.initData(sourceId, expectedAccountId = accountId)
        else viewModel.clearReadingData()
    }
    DisposableEffect(viewModel) { onDispose { viewModel.clearReadingData() } }
    BackHandler(onBack = onBack)
    if (verified && activeAccount == accountId && reader.articleId == sourceId &&
        reader.sourceAccountId == accountId && reader.content !is ReaderState.Error) {
        ReadingPage(viewModel = viewModel, navigationAction = NavigationAction.Close,
            onLoadArticle = { _, _ -> /* A notebook jump owns one exact article, not the RSS list. */ },
            onNavAction = { onBack() }, onNavigateToStylePage = onNavigateToStylePage,
            requestedAnnotationId = annotationId, expectedAnnotationSourceId = sourceId,
            expectedAnnotationAccountId = accountId)
    } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(20.dp).testTag("notebook_reader_status")) {
            AnnotationAction("Back to Highlights", onClick = onBack)
            Text(when {
                document.loading -> "Opening highlight…"
                document.error != null -> document.error.orEmpty()
                annotation == null -> "This highlight is no longer available on this device."
                !verified -> NotebookSourceAvailability.IDENTITY_MISMATCH.message.orEmpty()
                activeAccount != accountId -> NotebookSourceAvailability.OTHER_ACCOUNT.message.orEmpty()
                reader.content is ReaderState.Error -> "The original article could not be opened. Your quote and note are still saved."
                else -> "Opening original article…"
            }, style = MaterialTheme.typography.bodyLarge)
            annotation?.let {
                Text(it.source.title, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
                Text(it.quote, Modifier.padding(top = 12.dp))
                if (it.note.isNotBlank()) Text(it.note, Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** Keep the saved quotation readable even if its article was removed after navigation/restoration. */
@Composable
fun AnnotationNotebookSavedReadingPage(
    annotationId: String,
    itemId: String,
    onBack: () -> Unit,
    notebook: AnnotationNotebookViewModel = hiltViewModel(),
) {
    val document by remember(annotationId) { notebook.observeAnnotation(annotationId) }
        .collectAsState(AnnotationDocumentState())
    val available by remember(itemId) { notebook.savedSourceAvailable(itemId) }.collectAsState(initial = null)
    val annotation = document.annotations.singleOrNull()
    val verified = annotation?.source?.let { it.kind == AnnotationSourceKind.SAVED && it.sourceId == itemId } == true
    BackHandler(onBack = onBack)
    if (verified && available == true) {
        SavedLibraryReadingPage(itemId = itemId, onBack = onBack, requestedAnnotationId = annotationId)
    } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)
            .testTag("notebook_reader_status")) {
            AnnotationAction("Back to Highlights", onClick = onBack)
            Text(when {
                document.loading || available == null -> "Opening highlight…"
                document.error != null -> document.error.orEmpty()
                annotation == null -> "This highlight is no longer available on this device."
                !verified -> NotebookSourceAvailability.IDENTITY_MISMATCH.message.orEmpty()
                else -> NotebookSourceAvailability.MISSING.message.orEmpty()
            })
            annotation?.let {
                Text(it.source.title, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
                Text(it.quote, Modifier.padding(top = 12.dp))
                if (it.note.isNotBlank()) Text(it.note, Modifier.padding(top = 12.dp))
            }
        }
    }
}
