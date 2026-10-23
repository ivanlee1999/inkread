package me.ash.reader.ui.page.settings.readwise

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import me.ash.reader.infrastructure.readwise.ReadwiseConnectionState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadwiseSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tokenIsMaskedClearedOnConnectAndAutoExportRequiresOptIn() {
        val connects = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            MaterialTheme {
                ReadwiseSettingsContent(ReadwiseConnectionState(busy = false), ReadwiseQueueSummary(), null, false,
                    onBack = {}, onOpenTokenPage = {}, onConnect = { token, enabled -> connects += token to enabled },
                    onExportEnabled = {}, onDisconnect = {})
            }
        }
        me.ash.reader.ui.page.home.NativePageDensityCapture.capture(compose,
            "annotation-readwise-settings", "readwise_settings", recordDensity = false)
        compose.onNodeWithTag("readwise_token_input").performScrollTo().performTextInput("test-token-not-real")
        compose.onNodeWithTag("readwise_token_input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("readwise_connect").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("test-token-not-real" to false), connects) }
        compose.onNodeWithTag("readwise_token_input").assertTextEquals("")
        compose.onNodeWithTag("readwise_connect").assertIsNotEnabled()
        compose.onNodeWithTag("readwise_token_input").performScrollTo().performTextInput("another-test-token")
        compose.onNodeWithTag("readwise_connect_auto_export").performScrollTo().performClick()
        compose.onNodeWithTag("readwise_connect").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("another-test-token" to true, connects.last()) }
    }

    @Test fun automaticSelectionIsIndependentOfExportOptIn() {
        var automatic by mutableStateOf(false)
        val connects = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            MaterialTheme {
                ReadwiseSettingsContent(ReadwiseConnectionState(busy = false), ReadwiseQueueSummary(), null, false,
                    onBack = {}, onOpenTokenPage = {}, onConnect = { token, export -> connects += token to export },
                    onExportEnabled = {}, onDisconnect = {}, autoHighlight = automatic, onAutoHighlight = { automatic = it })
            }
        }
        compose.onNodeWithTag("annotation_auto_highlight").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("readwise_token_input").performScrollTo().performTextInput("fake-not-a-secret")
        compose.onNodeWithTag("readwise_connect").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(automatic); assertEquals(listOf("fake-not-a-secret" to false), connects) }
    }

    @Test fun restorationNeverRestoresAnEnteredToken() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                ReadwiseSettingsContent(ReadwiseConnectionState(busy = false), ReadwiseQueueSummary(), null, false,
                    onBack = {}, onOpenTokenPage = {}, onConnect = { _, _ -> }, onExportEnabled = {}, onDisconnect = {})
            }
        }
        compose.onNodeWithTag("readwise_token_input").performScrollTo().performTextInput("temporary-test-token")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("readwise_token_input").performScrollTo().assertTextEquals("")
    }

    @Test fun pauseAndDisconnectAreExplicitAndQueueDoesNotClaimReaderMirroring() {
        var pause: Boolean? = null
        var disconnected = false
        compose.setContent {
            MaterialTheme {
                ReadwiseSettingsContent(ReadwiseConnectionState(connected = true, exportEnabled = true, busy = false),
                    ReadwiseQueueSummary(queued = 2, reviewRequired = 1), null, false,
                    onBack = {}, onOpenTokenPage = {}, onConnect = { _, _ -> },
                    onExportEnabled = { pause = it }, onDisconnect = { disconnected = true })
            }
        }
        compose.onNodeWithText("Exports go to the Readwise highlights library, not to Reader as mirrored documents.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("readwise_toggle_export").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(false, pause); assertFalse(disconnected) }
        compose.onNodeWithTag("readwise_disconnect").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(disconnected) }
    }
}
