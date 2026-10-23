package me.ash.reader.ui.page.annotation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.closeSoftKeyboard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.ash.reader.domain.annotation.*
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority
import me.ash.reader.ui.page.home.reading.EInkReaderVolumeReservation
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Native UI only: no credentials, HTTP, RSS services or real account required. */
class AnnotationReaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun resetKeys() = VolumeKeyEventBus.resetForTest()
    @After fun releaseKeys() = VolumeKeyEventBus.resetForTest()

    @Test fun sourceGuardReadsSelectionModalAndSaveOwnershipBeforeRecomposition() {
        val fixture = Fixture()
        fixture.actions.state.value = AnnotationDocumentState(loading = false, annotations = listOf(annotation()))
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.runOnIdle {
            val guard = fixture.ui.canReplaceDocument
            assertTrue(guard())
            fixture.ui.onAnnotationClick("highlight-one")
            assertEquals("An accepted annotation tap pins immediately", 1, fixture.interactions)
            assertFalse(guard())
            fixture.ui.close()
            assertTrue(guard())
            fixture.ui.onSelectionModeChange(true)
            assertFalse("Native selection owns input immediately", guard())
            fixture.ui.onSelectionModeChange(false)
            assertTrue(guard())
            fixture.ui.onCreateAnnotation(ANCHOR, true)
            assertFalse("An accepted note owns input before its dialog recomposes", guard())
            fixture.ui.close()
            assertTrue(guard())
            fixture.ui.onCreateAnnotation(ANCHOR, false)
            assertFalse("An accepted save owns input before its busy state recomposes", guard())
            fixture.actions.pending!!.invoke(null)
            assertTrue(guard())
        }
    }

    @Test fun interactionHookIgnoresRejectedAndRevokedCallbacksButPinsAcceptedRequests() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        lateinit var previous: AnnotationReaderUi
        compose.runOnIdle {
            fixture.ui.onCreateAnnotation("{}", false)
            fixture.ui.onAnnotationClick("unknown-highlight")
            assertEquals(0, fixture.interactions)
            fixture.ui.onCreateAnnotation(ANCHOR, false)
            assertEquals(1, fixture.interactions)
            fixture.ui.onCreateAnnotation(ANCHOR, false) // Rejected while this manual save is busy.
            assertEquals(1, fixture.interactions)
            fixture.actions.pending!!.invoke(null)
            fixture.ui.resize(annotation())
            assertEquals("Pin synchronously at resize request, before the reader effect", 2, fixture.interactions)
            assertFalse("A queued resize owns input before the reader recomposes", fixture.ui.canReplaceDocument())
            fixture.ui.onResizeResult("unrelated-highlight", false)
            assertFalse("Unrelated results cannot release a queued resize", fixture.ui.canReplaceDocument())
            fixture.ui.onResizeResult("highlight-one", false)
            assertTrue("A failed matching resize releases pending ownership", fixture.ui.canReplaceDocument())
            fixture.ui.resize(annotation())
            assertFalse(fixture.ui.canReplaceDocument())
            fixture.ui.onSelectionModeChange(true)
            assertFalse("The native selection now owns the acknowledged resize", fixture.ui.canReplaceDocument())
            fixture.ui.onSelectionModeChange(false)
            assertTrue("Native cancellation must not permanently disable Source", fixture.ui.canReplaceDocument())
            previous = fixture.ui
            fixture.source = null
            fixture.revision = "unavailable"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            previous.onCreateAnnotation(ANCHOR, true)
            previous.resize(annotation())
            assertEquals(3, fixture.interactions)
            assertFalse("An obsolete document cannot authorize a source change", previous.canReplaceDocument())
            assertFalse(fixture.ui.available)
            assertTrue("Annotation availability is not required for ordinary source choices", fixture.ui.canReplaceDocument())
        }
    }

    @Test fun automaticSavePinsImmediatelyAndBlocksSourceUntilItSettles() {
        val fixture = Fixture()
        fixture.actions.autoHighlight.value = true
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.waitUntil(5_000) { fixture.ui.autoHighlightEnabled }
        compose.runOnIdle {
            val guard = fixture.ui.canReplaceDocument
            fixture.ui.onAutoCreateAnnotation(ANCHOR)
            assertEquals(1, fixture.interactions)
            assertFalse(guard())
            fixture.ui.onAutoCreateAnnotation(ANCHOR)
            assertEquals("Rejected duplicate saves do not acquire new ownership", 1, fixture.interactions)
            fixture.actions.automaticPending!!("auto-one", null)
            assertTrue(guard())
            fixture.ui.onAutoCreateAnnotation(ANCHOR)
            assertEquals("An already accepted anchor is not saved twice", 1, fixture.interactions)
        }
    }

    @Test fun noteBackClosesModalFirstAndNeverCreatesOrMovesReader() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.runOnIdle { fixture.ui.onCreateAnnotation(ANCHOR, true) }
        compose.onNodeWithTag("annotation_note_editor").assertIsDisplayed()
        me.ash.reader.ui.page.home.NativePageDensityCapture.capture(compose,
            "annotation-note-editor", "annotation_note_editor", recordDensity = false)
        compose.onNodeWithTag("annotation_note_input").performTextInput("A local note")
        compose.runOnIdle {
            assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH, fixture.readerOwner))
        }
        closeSoftKeyboard() // IME Back is distinct from dismissing the annotation modal.
        pressBack()
        compose.onNodeWithTag("annotation_note_editor").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, fixture.exits)
            assertTrue(fixture.actions.created.isEmpty())
            assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH, fixture.readerOwner))
        }
    }

    @Test fun duplicateSaveIsFencedAndSourceSnapshotSurvivesNavigation() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.runOnIdle { fixture.ui.onCreateAnnotation(ANCHOR, true) }
        compose.onNodeWithTag("annotation_note_input").performTextInput("Do not attach to the next article")
        compose.onNodeWithTag("annotation_note_save").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_note_save").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, fixture.actions.created.size)
            assertEquals("article-one", fixture.actions.created.single().source.sourceId)
            assertEquals(7, fixture.actions.created.single().source.accountId)
            assertEquals("  selected\ntext  ", fixture.actions.created.single().quote)
            fixture.source = fixture.source!!.copy(sourceId = "article-two", title = "Next article")
            fixture.revision = "second"
        }
        compose.waitForIdle()
        compose.runOnIdle { fixture.actions.pending!!.invoke(null) }
        compose.onNodeWithTag("annotation_note_editor").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fixture.actions.created.size) }
    }

    @Test fun oversizedSelectionShowsPersistentNoticeWithoutSavingOrTruncating() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.runOnIdle { fixture.ui.onCreateAnnotation("""{"error":"selection_too_large"}""", true) }
        compose.onNodeWithTag("annotation_notice").assertIsDisplayed()
        compose.onNodeWithText("Selection is too large. Select a shorter passage.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithTag("annotation_notice").assertIsDisplayed()
        compose.runOnIdle { assertTrue(fixture.actions.created.isEmpty()) }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("annotation_notice").assertDoesNotExist()
    }

    @Test fun oversizedNoteKeepsItsFullInputAndShowsActionableStorageError() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.runOnIdle { fixture.ui.onCreateAnnotation(ANCHOR, true) }
        val note = "Keep this complete note, including its final words."
        compose.onNodeWithTag("annotation_note_input").performTextInput(note)
        compose.onNodeWithTag("annotation_note_save").performScrollTo().performClick()
        compose.runOnIdle { fixture.actions.pending!!.invoke(annotationSaveError(AnnotationSizeException())) }
        compose.onNodeWithText("This annotation is too large to save. Select a shorter passage or shorten the note. Nothing was changed.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("annotation_note_input").assertTextEquals(note)
        compose.onNodeWithTag("annotation_note_save").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(note, fixture.actions.created.single().note)
            assertEquals("Could not save this change on your device. Please try again.",
                annotationSaveError(IllegalArgumentException("An internal error must not be exposed")))
        }
    }

    @Test fun staleSelectionAndLoadingCannotCreateAnnotations() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        val oldCallback = compose.runOnIdle { fixture.ui.onCreateAnnotation }
        compose.runOnIdle { fixture.source = null; fixture.revision = "loading" }
        compose.waitForIdle()
        compose.runOnIdle {
            oldCallback(ANCHOR, false)
            fixture.ui.onCreateAnnotation(ANCHOR, false)
            assertFalse(fixture.ui.available)
            assertTrue(fixture.actions.created.isEmpty())
        }
    }

    @Test fun localDeleteExplainsRemoteCopyAndUncertainExportHasNoRetry() {
        val fixture = Fixture()
        fixture.actions.state.value = AnnotationDocumentState(listOf(annotation()), loading = false)
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.runOnIdle { fixture.ui.onAnnotationsClick() }
        compose.onNodeWithTag("annotation_retry_highlight-one").assertDoesNotExist()
        compose.onNodeWithTag("annotation_delete_highlight-one").performScrollTo().performClick()
        compose.onNodeWithText("This removes the highlight and note from this device only. An exported copy stays in Readwise and must be deleted there separately.").assertIsDisplayed()
        compose.onNodeWithText("Keep annotation").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(fixture.actions.removed.isEmpty()) }
        compose.runOnIdle { fixture.ui.onAnnotationsClick() }
        compose.onNodeWithTag("annotation_delete_highlight-one").performScrollTo().performClick()
        compose.onNodeWithText("Delete locally").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("highlight-one"), fixture.actions.removed) }
    }

    @Test fun tappingHighlightOpensItsActionsAndResizeKeepsImmutableIdentity() {
        val fixture = Fixture()
        fixture.actions.state.value = AnnotationDocumentState(listOf(annotation()), loading = false)
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.runOnIdle { fixture.ui.onAnnotationClick("highlight-one") }
        compose.onNodeWithTag("annotation_detail").assertIsDisplayed()
        compose.onNodeWithTag("annotation_resize_highlight-one").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_detail").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("highlight-one", fixture.ui.resizeRequest?.first)
            fixture.ui.onResizeSelection("wrong-id", ANCHOR)
            assertTrue(fixture.actions.resized.isEmpty())
            fixture.ui.onResizeSelection("highlight-one", ANCHOR)
            assertEquals(listOf("highlight-one"), fixture.actions.resized)
        }
    }

    @Test fun automaticHighlightDefaultsOffAndUndoIsLocalExplicitAction() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.runOnIdle {
            fixture.ui.onAutoCreateAnnotation(ANCHOR)
            assertTrue(fixture.actions.automatic.isEmpty())
            fixture.actions.autoHighlight.value = true
        }
        compose.waitUntil { fixture.ui.autoHighlightEnabled }
        compose.runOnIdle { fixture.ui.onAutoCreateAnnotation(ANCHOR) }
        compose.runOnIdle {
            assertEquals(1, fixture.actions.automatic.size)
            fixture.actions.automaticPending!!("auto-one", null)
        }
        compose.waitUntil { fixture.ui.undoAutoHighlight != null }
        compose.runOnIdle { fixture.ui.undoAutoHighlight!!.invoke() }
        compose.runOnIdle {
            assertEquals(listOf("auto-one"), fixture.actions.removed)
            fixture.actions.pending!!.invoke(null)
        }
        compose.waitUntil { fixture.ui.undoAutoHighlight == null }
        compose.runOnIdle { assertTrue(fixture.ui.status!!.contains("Readwise copy")) }
    }

    @Test fun automaticThenAddNoteEditsSameIdEvenBeforeObservationArrives() {
        val fixture = Fixture()
        fixture.actions.autoHighlight.value = true
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.waitUntil { fixture.ui.autoHighlightEnabled }
        compose.runOnIdle { fixture.ui.onAutoCreateAnnotation(ANCHOR) }
        compose.runOnIdle { fixture.actions.automaticPending!!("auto-one", null) }
        compose.waitUntil { fixture.ui.undoAutoHighlight != null }
        compose.runOnIdle { fixture.ui.onCreateAnnotation(ANCHOR, true) }
        compose.onNodeWithTag("annotation_note_input").performTextInput("One note, not a second highlight")
        compose.onNodeWithTag("annotation_note_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("auto-one"), fixture.actions.edited)
            assertTrue(fixture.actions.created.isEmpty())
            assertEquals(1, fixture.actions.automatic.size)
        }
    }

    @Test fun staleAutomaticCallbackCannotExposeUndoInAnotherArticle() {
        val fixture = Fixture()
        fixture.actions.autoHighlight.value = true
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.waitUntil { fixture.ui.autoHighlightEnabled }
        compose.runOnIdle { fixture.ui.onAutoCreateAnnotation(ANCHOR) }
        compose.runOnIdle { fixture.source = fixture.source!!.copy(sourceId = "next"); fixture.revision = "next" }
        compose.waitForIdle()
        compose.runOnIdle { fixture.actions.automaticPending!!("old-auto", null) }
        compose.runOnIdle { assertNull(fixture.ui.undoAutoHighlight) }
    }

    @Test fun requestedNotebookJumpWaitsForAnnotationsAndRunsOnlyOnce() {
        val fixture = Fixture()
        fixture.requested = "highlight-one"
        fixture.actions.state.value = AnnotationDocumentState(loading = true)
        compose.setContent { fixture.Screen() }
        compose.runOnIdle { assertNull(fixture.ui.jumpRequest) }
        compose.runOnIdle { fixture.actions.state.value = AnnotationDocumentState(listOf(annotation()), loading = false) }
        compose.waitUntil { fixture.ui.jumpRequest != null }
        val first = compose.runOnIdle { fixture.ui.jumpRequest }
        compose.runOnIdle { fixture.actions.state.value = AnnotationDocumentState(listOf(annotation().copy(note = "updated")), loading = false) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(first, fixture.ui.jumpRequest) }
    }

    @Test fun readerStatusFollowsExistingExportStateWithoutNewSyncMechanism() {
        val fixture = Fixture()
        fixture.actions.state.value = AnnotationDocumentState(listOf(annotation().copy(exportState = AnnotationExportState.QUEUED)), loading = false)
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.runOnIdle { assertTrue(fixture.ui.status!!.contains("queued")) }
        compose.runOnIdle { fixture.actions.state.value = AnnotationDocumentState(listOf(annotation().copy(exportState = AnnotationExportState.EXPORTED)), loading = false) }
        compose.waitUntil { fixture.ui.status?.contains("exported") == true }
    }

    @Test fun repeatedQuoteAtAnotherOffsetIsNotTheAutomaticHighlight() {
        val fixture = Fixture()
        fixture.actions.autoHighlight.value = true
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.waitUntil { fixture.ui.autoHighlightEnabled }
        compose.runOnIdle { fixture.ui.onAutoCreateAnnotation(ANCHOR) }
        compose.runOnIdle { fixture.actions.automaticPending!!("auto-one", null) }
        compose.waitUntil { fixture.ui.undoAutoHighlight != null }
        compose.runOnIdle { fixture.ui.onCreateAnnotation(ANCHOR.replace("\"start\":3", "\"start\":103").replace("\"end\":22", "\"end\":122"), false) }
        compose.runOnIdle { assertEquals(1, fixture.actions.created.size) }
    }

    @Test fun successfulLatestExportDoesNotHideAnOlderFailure() {
        val first = annotation().copy(exportState = AnnotationExportState.FAILED)
        val second = annotation().copy(id = "second", updatedAt = 100, exportState = AnnotationExportState.EXPORTED)
        assertTrue(annotationReaderStatus(listOf(first, second))!!.contains("1 failed"))
        assertTrue(annotationReaderStatus(listOf(first, second))!!.contains("1 exported"))
    }

    @Test fun addNoteDuringAutomaticCommitWaitsAndEditsAcceptedId() {
        val fixture = Fixture()
        fixture.actions.autoHighlight.value = true
        compose.setContent { fixture.Screen() }
        awaitReady(fixture)
        compose.waitUntil { fixture.ui.autoHighlightEnabled }
        compose.runOnIdle { fixture.ui.onAutoCreateAnnotation(ANCHOR) }
        compose.runOnIdle { fixture.ui.onCreateAnnotation(ANCHOR, true) }
        compose.onNodeWithTag("annotation_note_editor").assertDoesNotExist()
        compose.runOnIdle {
            val guard = fixture.ui.canReplaceDocument
            fixture.actions.automaticPending!!("auto-delayed", null)
            assertFalse("The accepted note handoff owns input before its Compose effect runs", guard())
        }
        compose.waitUntil { fixture.ui.dialog is AnnotationDialogState.Note }
        compose.onNodeWithTag("annotation_note_input").performTextInput("Keep this explicit action")
        compose.onNodeWithTag("annotation_note_save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("auto-delayed"), fixture.actions.edited)
            assertEquals(1, fixture.actions.automatic.size)
            assertTrue(fixture.actions.created.isEmpty())
        }
    }

    private fun awaitReady(fixture: Fixture) { compose.waitUntil(5_000) { fixture.ui.available } }

    private class Fixture {
        var source by mutableStateOf<AnnotationSource?>(AnnotationSource(AnnotationSourceKind.RSS, "article-one", 7, "https://example.org/article", "Article"))
        var revision by mutableStateOf("first")
        var requested by mutableStateOf<String?>(null)
        val actions = Actions()
        val readerOwner = Any()
        var exits = 0
        var interactions = 0
        lateinit var ui: AnnotationReaderUi
        @Composable fun Screen() {
            MaterialTheme {
                EInkReaderVolumeReservation(true, readerOwner)
                BackHandler { exits += 1 }
                ui = rememberAnnotationReaderUi(source, revision, actions, requested,
                    onDocumentInteractionStarted = { interactions += 1 })
                AnnotationReaderDialogs(ui)
            }
        }
    }

    private class Actions : AnnotationReaderActions {
        override val autoHighlight = MutableStateFlow(false)
        val automatic = mutableListOf<AnnotationDraft>()
        val resized = mutableListOf<String>()
        val edited = mutableListOf<String>()
        var automaticPending: ((String?, String?) -> Unit)? = null
        override fun createAutomatic(draft: AnnotationDraft, onResult: (String?, String?) -> Unit) { automatic += draft; automaticPending = onResult }
        override fun updateSelection(id: String, quote: String, anchorJson: String, onResult: (String?) -> Unit) { resized += id; pending = onResult }
        val state = MutableStateFlow(AnnotationDocumentState(loading = false))
        val created = mutableListOf<AnnotationDraft>()
        val removed = mutableListOf<String>()
        var pending: ((String?) -> Unit)? = null
        override fun observe(source: AnnotationSource?): Flow<AnnotationDocumentState> =
            if (source == null) flowOf(AnnotationDocumentState(loading = false)) else state
        override fun create(draft: AnnotationDraft, onResult: (String?) -> Unit) { created += draft; pending = onResult }
        override fun updateNote(id: String, note: String, onResult: (String?) -> Unit) { edited += id; pending = onResult }
        override fun remove(id: String, onResult: (String?) -> Unit) { removed += id; pending = onResult }
        override fun retry(id: String, onResult: (String?) -> Unit) { pending = onResult }
    }

    companion object {
        private const val ANCHOR = """{"version":1,"quote":"  selected\ntext  ","start":3,"end":22}"""
        private fun annotation() = Annotation(
            id = "highlight-one", documentId = "document-one",
            source = AnnotationSource(AnnotationSourceKind.SAVED, "saved-one", url = "https://example.org/article", title = "Article"),
            quote = "A preserved quote", anchorJson = ANCHOR, note = "A preserved note", createdAt = 1, updatedAt = 1,
            localRevision = 1, exportedRevision = 0, remoteId = null,
            exportState = AnnotationExportState.REVIEW_REQUIRED, exportError = "Check Readwise: the response was uncertain.",
            exportEpoch = 1, canRetry = false,
        )
    }
}
