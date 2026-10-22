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

    private fun awaitReady(fixture: Fixture) { compose.waitUntil(5_000) { fixture.ui.available } }

    private class Fixture {
        var source by mutableStateOf<AnnotationSource?>(AnnotationSource(AnnotationSourceKind.RSS, "article-one", 7, "https://example.org/article", "Article"))
        var revision by mutableStateOf("first")
        val actions = Actions()
        val readerOwner = Any()
        var exits = 0
        lateinit var ui: AnnotationReaderUi
        @Composable fun Screen() {
            MaterialTheme {
                EInkReaderVolumeReservation(true, readerOwner)
                BackHandler { exits += 1 }
                ui = rememberAnnotationReaderUi(source, revision, actions)
                AnnotationReaderDialogs(ui)
            }
        }
    }

    private class Actions : AnnotationReaderActions {
        val state = MutableStateFlow(AnnotationDocumentState(loading = false))
        val created = mutableListOf<AnnotationDraft>()
        val removed = mutableListOf<String>()
        var pending: ((String?) -> Unit)? = null
        override fun observe(source: AnnotationSource?): Flow<AnnotationDocumentState> =
            if (source == null) flowOf(AnnotationDocumentState(loading = false)) else state
        override fun create(draft: AnnotationDraft, onResult: (String?) -> Unit) { created += draft; pending = onResult }
        override fun updateNote(id: String, note: String, onResult: (String?) -> Unit) { pending = onResult }
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
