package me.ash.reader.ui.page.annotation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ash.reader.domain.annotation.Annotation
import me.ash.reader.domain.annotation.AnnotationExportState
import me.ash.reader.domain.annotation.AnnotationSource
import me.ash.reader.domain.annotation.AnnotationSourceKind
import me.ash.reader.ui.page.nav3.navigateAppBack
import me.ash.reader.ui.page.nav3.showRetainedDestination
import org.junit.Assert.*
import org.junit.Test

class AnnotationNotebookModelTest {
    @Test fun searchCoversQuoteNoteTitleSourceAndCombinesTermsAcrossFields() {
        val entry = annotation("a").copy(quote = "Useful QUOTE", note = "Remember this", source = source.copy(title = "Long article", sourceName = "Daily paper"))
        listOf("quote", "REMEMBER", "article", "daily", "quote daily", "  article   remember ").forEach {
            assertEquals(listOf(entry), annotationNotebookPage(listOf(entry), NotebookSelection(search = it)).items)
        }
        assertTrue(annotationNotebookPage(listOf(entry), NotebookSelection(search = "missing")).items.isEmpty())
    }

    @Test fun notesAndUnsyncedFiltersIntersectAndLocalOnlyIsNotPending() {
        val entries = AnnotationExportState.entries.map { annotation(it.name, it).copy(note = if (it == AnnotationExportState.FAILED) "Fix" else "") }
        val pending = annotationNotebookPage(entries, NotebookSelection(unsyncedOnly = true)).items
        assertEquals(setOf(AnnotationExportState.QUEUED, AnnotationExportState.SENDING, AnnotationExportState.FAILED,
            AnnotationExportState.REVIEW_REQUIRED, AnnotationExportState.PAUSED), pending.map { it.exportState }.toSet())
        assertEquals(listOf("FAILED"), annotationNotebookPage(entries, NotebookSelection(notesOnly = true, unsyncedOnly = true)).items.map { it.id })
        assertFalse(annotation("blank").copy(note = "  \n").let { annotationNotebookPage(listOf(it), NotebookSelection(notesOnly = true)).items.isNotEmpty() })
    }

    @Test fun editAndExportUpdatesNeverReorderEqualCreationTimes() {
        val entries = listOf(annotation("b"), annotation("a"), annotation("new").copy(createdAt = 2))
        assertEquals(listOf("new", "a", "b"), annotationNotebookPage(entries, NotebookSelection()).items.map { it.id })
        val edited = entries.map { it.copy(updatedAt = 900, exportState = AnnotationExportState.SENDING, note = "Edited") }
        assertEquals(listOf("new", "a", "b"), annotationNotebookPage(edited.reversed(), NotebookSelection()).items.map { it.id })
    }

    @Test fun presentationIsBoundedAndRemovedLastPageClampsSafely() {
        val entries = (0..90).map { annotation("item-$it").copy(createdAt = it.toLong()) }
        val page = annotationNotebookPage(entries, NotebookSelection(page = 2))
        assertEquals(NOTEBOOK_PAGE_SIZE, page.items.size)
        assertEquals(4, page.pageCount)
        assertEquals("item-30", page.items.first().id)
        val smaller = annotationNotebookPage(entries.take(10), NotebookSelection(page = 99))
        assertEquals(0, smaller.page)
        assertEquals(10, smaller.items.size)
        val empty = annotationNotebookPage(emptyList(), NotebookSelection(page = -9))
        assertEquals(0, empty.page)
        assertEquals(1, empty.pageCount)
    }

    @Test fun missingSourceNeverMutatesOrDiscardsItsQuoteAndNote() {
        val original = annotation("missing").copy(note = "Keep this note")
        assertEquals(NotebookSourceAvailability.MISSING, notebookSourceAvailability(original.source, null))
        assertEquals(original, annotationNotebookPage(listOf(original), NotebookSelection()).items.single())
        assertTrue(NotebookSourceAvailability.MISSING.message!!.contains("still saved"))
    }

    @Test fun sameArticleIdNeverAllowsCrossAccountOpening() {
        assertEquals(NotebookSourceAvailability.IDENTITY_MISMATCH, notebookSourceAvailability(source, "article", 99, 7))
        assertEquals(NotebookSourceAvailability.OTHER_ACCOUNT, notebookSourceAvailability(source, "article", 7, 99))
        assertEquals(NotebookSourceAvailability.IDENTITY_MISMATCH, notebookSourceAvailability(source.copy(accountId = null), "article", 7, 7))
        assertEquals(NotebookSourceAvailability.AVAILABLE, notebookSourceAvailability(source, "article", 7, 7))
        assertFalse(sameNotebookSource(source, source.copy(accountId = 99)))
        assertFalse(sameNotebookSource(source, source.copy(kind = AnnotationSourceKind.SAVED)))
        assertEquals(NotebookSourceAvailability.AVAILABLE, notebookSourceAvailability(source.copy(kind = AnnotationSourceKind.SAVED, accountId = null), "article"))
    }

    @Test fun backAndReentryRetainNotebookSearchFiltersAndPosition() {
        val retained = NotebookSelection(search = "passage", notesOnly = true, unsyncedOnly = true, page = 2, listIndex = 7, scrollOffset = 19)
        val snapshots = mapOf("notebook" to retained)
        val stack = mutableListOf("feeds", "saved", "notebook", "reader:article:7:highlight")
        navigateAppBack(stack, "reader:article:7:highlight", "feeds")
        assertEquals("notebook", stack.last())
        assertEquals(retained, snapshots[stack.last()])
        stack.add("reader:article:7:highlight")
        showRetainedDestination(stack, "notebook")
        assertEquals(listOf("feeds", "saved", "notebook"), stack)
        assertEquals(retained, snapshots[stack.last()])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun livePositionIsRetainedForANewCollectorWithoutRepeatingFiltering() = runTest {
        val index = MutableStateFlow(7)
        val offset = MutableStateFlow(19)
        val entries = MutableStateFlow(listOf(annotation("one")))
        var computations = 0
        val filtered = entries.map {
            computations += 1
            annotationNotebookPage(it, NotebookSelection(search = "Saved", notesOnly = false, page = 0))
        }
        val state = notebookWithPosition(filtered, index, offset)
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5_000), AnnotationNotebookState())
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
        runCurrent()
        assertEquals(7, state.value.selection.listIndex)
        assertEquals(19, state.value.selection.scrollOffset)
        assertEquals(1, computations)
        val rows = state.value.items
        index.value = 9
        offset.value = 31
        runCurrent()
        assertEquals(9, state.value.selection.listIndex)
        assertEquals(31, state.value.selection.scrollOffset)
        assertSame(rows, state.value.items)
        assertEquals(1, computations)
        first.cancel()
        // The retained entry is collected again after returning from its reader.
        val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
        runCurrent()
        assertEquals("Saved", state.value.selection.search)
        assertEquals(9, state.value.selection.listIndex)
        assertEquals(31, state.value.selection.scrollOffset)
        assertEquals(1, computations)
        second.cancel()
    }

    @Test fun staleReaderBackDoesNotPopNewNotebookReader() {
        val stack = mutableListOf("notebook", "reader:one", "reader:two")
        navigateAppBack(stack, "reader:one", "feeds")
        assertEquals("reader:two", stack.last())
    }

    companion object {
        private val source = AnnotationSource(AnnotationSourceKind.RSS, "article", 7, title = "Article")
        internal fun annotation(id: String, export: AnnotationExportState = AnnotationExportState.LOCAL_ONLY) = Annotation(
            id, "doc", source, "Saved passage", "{}", "", 1, 1, 1, 0, null, export, null, null, false)
    }
}
