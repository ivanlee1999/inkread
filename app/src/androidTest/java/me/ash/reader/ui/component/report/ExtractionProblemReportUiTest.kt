package me.ash.reader.ui.component.report

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import me.ash.reader.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExtractionProblemReportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun openingPreviewDoesNothingUntilUserChoosesSaveCopyOrShare() {
        val actions = mutableListOf<ExtractionReportAction>()
        var notes by mutableStateOf("")
        compose.setContent { MaterialTheme {
            ExtractionProblemReportDialog(notes, "Frozen diagnostic preview", false, null, false,
                { notes = it }, actions::add, {}, {})
        } }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("extraction_report_notes").performTextInput("Only a short summary")
        compose.onNodeWithText(compose.activity.getString(R.string.extraction_report_save)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.extraction_report_copy)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.extraction_report_share)).performClick()
        compose.runOnIdle {
            assertEquals("Only a short summary", notes)
            assertEquals(listOf(ExtractionReportAction.SAVE, ExtractionReportAction.COPY, ExtractionReportAction.SHARE), actions)
        }
    }

    @Test fun savingDisablesDuplicateActionsAndEditingButCloseRemainsAvailable() {
        var dismissed = false
        compose.setContent { MaterialTheme {
            ExtractionProblemReportDialog("Saved note", "Report", true, null, true,
                {}, { error("Unexpected action") }, { error("Unexpected removal") }, { dismissed = true })
        } }
        compose.onNodeWithTag("extraction_report_notes").assertIsNotEnabled()
        listOf(R.string.extraction_report_save, R.string.extraction_report_remove).forEach {
            compose.onNodeWithText(compose.activity.getString(it)).performScrollTo().assertIsNotEnabled()
        }
        listOf(R.string.extraction_report_copy, R.string.extraction_report_share).forEach {
            compose.onNodeWithText(compose.activity.getString(it)).assertIsNotEnabled()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.close)).performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun notesAreBoundedWithoutSplittingAnEmoji() {
        var notes by mutableStateOf("")
        compose.setContent { MaterialTheme {
            ExtractionProblemReportDialog(notes, "Report", false, null, false, { notes = it }, {}, {}, {})
        } }
        compose.onNodeWithTag("extraction_report_notes").performTextReplacement("a".repeat(1999) + "\uD83D\uDE42")
        compose.runOnIdle { assertEquals("a".repeat(1999), notes) }
    }

    @Test fun shareIntentContainsOnlyPlainTextAndNoRecipientOrAttachment() {
        val report = "# InkRead extraction problem report\nPublic article URL: https://example.com/story"
        val intent = extractionProblemShareIntent(report, "Report extraction problem")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(report, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT, Intent.EXTRA_SUBJECT), intent.extras!!.keySet())
        assertNull(intent.`package`)
        assertNull(intent.component)
        assertNull(intent.data)
        assertNull(intent.clipData)
        assertTrue(runCatching { extractionProblemShareIntent("a".repeat(65_537), "Report") }.isFailure)
    }
}
