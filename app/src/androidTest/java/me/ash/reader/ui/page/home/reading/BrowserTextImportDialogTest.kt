package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import me.ash.reader.infrastructure.rss.BrowserTextImport
import me.ash.reader.ui.page.adaptive.ReaderContentPresentationGate
import me.ash.reader.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BrowserTextImportDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewStartsEmptyAndCancelDoesNotImport() {
        val showing = mutableStateOf(true)
        var imports = 0
        compose.setContent {
            AppTheme(useDarkTheme = false) {
                if (showing.value) BrowserTextImportDialog(
                    onDismiss = { showing.value = false },
                    onApply = { imports++; true },
                )
            }
        }
        compose.onNodeWithTag("browser_text_apply").assertIsNotEnabled()
        compose.onNodeWithTag("browser_text_preview").performTextInput("这是预览。")
        compose.onNodeWithTag("browser_text_preview").assertTextContains("这是预览。")
        compose.onNodeWithTag("browser_text_apply").assertIsEnabled()
        compose.onNodeWithTag("browser_text_cancel").performClick()
        compose.onNodeWithTag("browser_text_preview").assertDoesNotExist()
        assertEquals(0, imports)
    }

    @Test fun applyRechecksLiveOwnershipAndRetainsPreviewWhenBlocked() {
        val showing = mutableStateOf(true)
        val gate = ReaderContentPresentationGate<Pair<Int, String>, String> { it }
        val identity = 1 to "article"
        gate.present(identity, "Feed body")
        gate.onInteractionStarted()
        var idle = false // A native selection/editor took ownership after the menu opened.
        var imported: String? = null
        compose.setContent {
            AppTheme(useDarkTheme = false) {
                if (showing.value) BrowserTextImportDialog(
                    onDismiss = { showing.value = false },
                    onApply = { text ->
                        if (gate.onExplicitSourceChoice { idle }) {
                            imported = text
                            showing.value = false
                            true
                        } else false
                    },
                )
            }
        }
        compose.onNodeWithTag("browser_text_preview").performTextInput("Copied article text")
        compose.onNodeWithTag("browser_text_apply").performClick()
        compose.onNodeWithTag("browser_text_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("browser_text_preview").assertTextContains("Copied article text")
        compose.runOnIdle {
            assertEquals(null, imported)
            assertEquals("Feed body", gate.present(identity, "A delayed fetch"))
            idle = true
        }
        compose.onNodeWithTag("browser_text_apply").performClick()
        compose.onNodeWithTag("browser_text_preview").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("Copied article text", imported)
            assertEquals("Copied article text", gate.present(identity, imported!!))
        }
    }

    @Test fun oversizedPasteIsRejectedRatherThanTruncatedOrApplied() {
        var imports = 0
        compose.setContent {
            AppTheme(useDarkTheme = false) {
                BrowserTextImportDialog(onDismiss = {}, onApply = { imports++; true })
            }
        }
        compose.onNodeWithTag("browser_text_preview").performTextInput("Short preview")
        compose.onNodeWithTag("browser_text_preview")
            .performTextInput("x".repeat(BrowserTextImport.MAX_UTF8_BYTES + 1))
        compose.onNodeWithTag("browser_text_preview").assertTextContains("Short preview")
        compose.onNodeWithTag("browser_text_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("browser_text_apply").assertIsNotEnabled()
        assertEquals(0, imports)
    }
}
