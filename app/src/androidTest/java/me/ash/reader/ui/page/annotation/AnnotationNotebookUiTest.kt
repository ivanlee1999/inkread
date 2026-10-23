package me.ash.reader.ui.page.annotation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.ash.reader.domain.annotation.*
import me.ash.reader.domain.annotation.Annotation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Offline native production-screen fixtures. No account, credential, HTTP or synchronization. */
class AnnotationNotebookUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun filtersSearchAndBoundedPagesUseActualNotebookScreen() {
        val fixture = Fixture((0..64).map { annotation("h$it").copy(createdAt = it.toLong(), note = "Notebook note") })
        compose.setContent { fixture.Screen() }
        me.ash.reader.ui.page.home.NativePageDensityCapture.capture(compose,
            "annotation-notebook", "annotation_notebook", recordDensity = false)
        compose.onNodeWithTag("notebook_next").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, fixture.selection.page) }
        compose.onNodeWithTag("notebook_search").performTextInput("Notebook")
        closeSoftKeyboard()
        compose.onNodeWithTag("notebook_filter_notes").performClick()
        compose.runOnIdle {
            assertEquals(0, fixture.selection.page)
            assertEquals("Notebook", fixture.selection.search)
            assertTrue(fixture.selection.notesOnly)
        }
        compose.onNodeWithTag("notebook_filter_unsynced").performClick()
        compose.onNodeWithText("No highlights match. Try another search or turn off a filter.").assertIsDisplayed()
        compose.onNodeWithText("Queued, sending, failed, paused or needs review. Intentionally local-only highlights are not included.").assertIsDisplayed()
    }

    @Test fun missingOriginalKeepsFullQuoteNoteAndLocalActionsAvailable() {
        val entry = annotation("missing")
        val fixture = Fixture(listOf(entry), sourceError = NotebookSourceAvailability.MISSING.message)
        compose.setContent { fixture.Screen() }
        compose.onNodeWithTag("notebook_item_missing").performClick()
        compose.onNodeWithTag("notebook_details").assertIsDisplayed()
        compose.onNode(hasText(entry.quote) and hasAnyAncestor(hasTestTag("notebook_details"))).assertIsDisplayed()
        compose.onNode(hasText(entry.note) and hasAnyAncestor(hasTestTag("notebook_details"))).assertIsDisplayed()
        compose.onNodeWithText(NotebookSourceAvailability.MISSING.message!!).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("notebook_edit_note").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_note_input").performTextReplacement("Still editable locally")
        compose.onNodeWithTag("annotation_note_save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("missing" to "Still editable locally"), fixture.actions.notes); assertTrue(fixture.opens.isEmpty()) }
    }

    @Test fun anotherAccountsSameArticleCannotNavigateAndUncertainStatusHasNoRetry() {
        val fixture = Fixture(listOf(annotation("cross").copy(exportState = AnnotationExportState.REVIEW_REQUIRED)),
            sourceError = NotebookSourceAvailability.OTHER_ACCOUNT.message)
        compose.setContent { fixture.Screen() }
        compose.onNodeWithTag("notebook_item_cross").performClick()
        compose.onNodeWithText(NotebookSourceAvailability.OTHER_ACCOUNT.message!!).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("notebook_retry").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.opens.isEmpty()) }
    }

    @Test fun openDetailsFollowLiveExportStatusAndRetryCapability() {
        val entry = annotation("live").copy(exportState = AnnotationExportState.QUEUED)
        val fixture = Fixture(listOf(entry))
        compose.setContent { fixture.Screen() }
        compose.onNodeWithTag("notebook_actions_live").performClick()
        compose.runOnIdle { fixture.actions.state.value = AnnotationDocumentState(listOf(entry.copy(exportState = AnnotationExportState.FAILED, canRetry = true)), loading = false) }
        compose.onNodeWithTag("notebook_retry").performScrollTo().assertIsEnabled()
        compose.runOnIdle { fixture.actions.state.value = AnnotationDocumentState(listOf(entry.copy(exportState = AnnotationExportState.EXPORTED, canRetry = false)), loading = false) }
        compose.onNodeWithTag("notebook_retry").assertDoesNotExist()
        compose.onNode(hasText("Saved locally · Exported to Readwise") and hasAnyAncestor(hasTestTag("notebook_details"))).performScrollTo().assertIsDisplayed()
    }

    @Test fun deletionDuringNoteEditPreservesTypedDraftAndDoesNotWrite() {
        val fixture = Fixture(listOf(annotation("removed")))
        compose.setContent { fixture.Screen() }
        compose.onNodeWithTag("notebook_actions_removed").performClick()
        compose.onNodeWithTag("notebook_edit_note").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_note_input").performTextReplacement("Keep this unsaved draft")
        compose.runOnIdle { fixture.actions.state.value = AnnotationDocumentState(emptyList(), loading = false) }
        compose.onNodeWithTag("annotation_note_save").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_note_input").assertTextEquals("Keep this unsaved draft")
        compose.runOnIdle { assertTrue(fixture.actions.notes.isEmpty()) }
    }

    @Test fun deleteRequiresLocalConfirmationAndNoteCancelDoesNotWrite() {
        val fixture = Fixture(listOf(annotation("one")))
        compose.setContent { fixture.Screen() }
        compose.onNodeWithTag("notebook_actions_one").performClick()
        compose.onNodeWithTag("notebook_edit_note").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_note_input").performTextInput("Unsaved")
        closeSoftKeyboard()
        pressBack()
        compose.runOnIdle { assertTrue(fixture.actions.notes.isEmpty()) }
        compose.onNodeWithTag("notebook_actions_one").performClick()
        compose.onNodeWithTag("notebook_delete").performScrollTo().performClick()
        compose.onNodeWithText("This removes the highlight and note from this device only. An exported copy stays in Readwise and must be deleted there separately.").assertIsDisplayed()
        compose.runOnIdle { assertTrue(fixture.actions.removed.isEmpty()) }
        compose.onNodeWithTag("notebook_confirm_delete").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("one"), fixture.actions.removed) }
    }

    @Test fun backRestoresNotebookSearchFilterPageAndLazyPosition() {
        val fixture = Fixture((0..94).map { annotation("h$it").copy(createdAt = it.toLong()) })
        compose.setContent { fixture.Screen() }
        compose.onNodeWithTag("notebook_search").performTextInput("Saved")
        closeSoftKeyboard()
        compose.onNodeWithTag("notebook_filter_notes").performClick()
        compose.onNodeWithTag("notebook_next").performClick()
        compose.onNodeWithTag("notebook_list").performScrollToIndex(9)
        compose.waitForIdle()
        val position = compose.runOnIdle { fixture.position }
        // On page 2: h64 is first, h55 is index 9.
        compose.onNodeWithTag("notebook_item_h55").performClick()
        compose.onNodeWithText("Original passage").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("annotation_notebook").assertIsDisplayed()
        compose.onNodeWithTag("notebook_search").assertTextEquals("Saved")
        compose.onNodeWithText("✓ Notes").assertIsDisplayed()
        compose.onNodeWithTag("notebook_item_h55").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, fixture.selection.page); assertEquals(position, fixture.position) }
    }

    @Test fun loadingFirstFrameDoesNotEraseRestoredListPosition() {
        val fixture = Fixture((0..94).map { annotation("h$it").copy(createdAt = it.toLong()) })
        fixture.selection = NotebookSelection(page = 1, listIndex = 9, scrollOffset = 13)
        fixture.position = 9 to 13
        fixture.loading = true
        compose.setContent { fixture.Screen() }
        compose.onNodeWithText("Loading highlights…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(9 to 13, fixture.position); fixture.loading = false }
        compose.onNodeWithTag("notebook_item_h55").assertIsDisplayed()
        compose.runOnIdle { assertEquals(9 to 13, fixture.position) }
    }

    @Test fun lateSourceLookupAfterBackCannotOpenAReader() {
        val gate = CompletableDeferred<String?>()
        val fixture = Fixture(listOf(annotation("late")), sourceCheck = { gate.await() })
        compose.setContent { fixture.Screen() }
        compose.onNodeWithTag("notebook_item_late").performClick()
        compose.runOnIdle { fixture.showNotebook = false }
        compose.runOnIdle { gate.complete(null) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(fixture.opens.isEmpty()) }
    }

    private class Fixture(
        private val entries: List<Annotation>,
        private val sourceError: String? = null,
        private val sourceCheck: (suspend (Annotation) -> String?)? = null,
    ) {
        var selection by mutableStateOf(NotebookSelection())
        var showNotebook by mutableStateOf(true)
        var loading by mutableStateOf(false)
        val actions = Actions(entries)
        val opens = mutableListOf<Annotation>()
        var position = 0 to 0
        @Composable fun Screen() {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                if (showNotebook) holder.SaveableStateProvider("notebook") {
                    AnnotationNotebookScreen(annotationNotebookPage(entries, selection).copy(loading = loading), onBack = {},
                        onOpen = { opens += it; showNotebook = false }, actions = actions,
                        checkSource = sourceCheck ?: { sourceError },
                        onSearch = { selection = selection.copy(search = it, page = 0) },
                        onNotes = { selection = selection.copy(notesOnly = it, page = 0) },
                        onUnsynced = { selection = selection.copy(unsyncedOnly = it, page = 0) },
                        onPage = { selection = selection.copy(page = it) },
                        onPosition = { index, offset -> position = index to offset })
                } else {
                    BackHandler { showNotebook = true }
                    Text("Original passage")
                }
            }
        }
    }
    private class Actions(entries: List<Annotation>) : AnnotationReaderActions {
        val state = MutableStateFlow(AnnotationDocumentState(entries, loading = false))
        val notes = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<String>()
        override fun observe(source: AnnotationSource?): Flow<AnnotationDocumentState> = if (source == null) flowOf(AnnotationDocumentState(loading = false)) else state
        override fun create(draft: AnnotationDraft, onResult: (String?) -> Unit) { error("Notebook never creates highlights") }
        override fun updateNote(id: String, note: String, onResult: (String?) -> Unit) { notes += id to note; onResult(null) }
        override fun remove(id: String, onResult: (String?) -> Unit) { removed += id; onResult(null) }
        override fun retry(id: String, onResult: (String?) -> Unit) { onResult(null) }
    }
    companion object {
        private fun annotation(id: String) = Annotation(id, "document", AnnotationSource(AnnotationSourceKind.RSS,
            "source", 7, title = "Saved article", sourceName = "Paper"), "A preserved passage", "{}", "A preserved note",
            1, 1, 1, 0, null, AnnotationExportState.LOCAL_ONLY, null, null, false)
    }
}
