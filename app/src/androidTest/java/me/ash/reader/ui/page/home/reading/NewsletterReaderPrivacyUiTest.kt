package me.ash.reader.ui.page.home.reading

import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.ByteArrayInputStream
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.EInkModePreference
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.infrastructure.preference.LocalReaderFormatting
import me.ash.reader.infrastructure.preference.ReaderFormatting
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.adaptive.savedLibrarySnapshot
import me.ash.reader.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real production media boundary and Source control, with every fixture request intercepted. */
class NewsletterReaderPrivacyUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newsletterMediaRequiresExplicitConsentAndResetsForArticleAndAccount() {
        val incoming = mutableStateOf(newsletter("first", 1))
        val presented = AtomicReference<ReaderState>()
        val consent = AtomicBoolean(false)
        val requests = ConcurrentLinkedQueue<String>()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalReaderFormatting provides ReaderFormatting.Default) {
                AppTheme(useDarkTheme = false) {
                    var imagesAllowed by rememberNewsletterImageConsent(incoming.value)
                    val display = incoming.value.copy(content = rememberNewsletterReaderContent(incoming.value, imagesAllowed))
                    SideEffect { presented.set(display); consent.set(imagesAllowed) }
                    Column(Modifier.fillMaxSize()) {
                        if (display.content.documentBody != null && !imagesAllowed) {
                            NewsletterImageControls { imagesAllowed = true }
                        }
                        display.content.documentBody?.let { html ->
                            EInkPaginatedContent(modifier = Modifier.weight(1f), content = html,
                                articleId = "${display.sourceAccountId}:${display.articleId}", title = display.title.orEmpty(),
                                feedName = "Synthetic newsletter", publishedDate = Date(0), link = display.link,
                                interceptResource = { url ->
                                    if (url.startsWith("https://newsletter.example.invalid/")) {
                                        requests.add(url)
                                        WebResourceResponse("image/png", null, ByteArrayInputStream(PIXEL))
                                    } else null
                                })
                        } ?: Text("Preparing newsletter")
                    }
                }
            }
        }

        awaitDocument("first", images = false)
        assertTrue("Blocked HTML must not attempt a remote media request", requests.isEmpty())
        assertFalse(consent.get())
        val original = incoming.value.content.documentBody!!
        assertTrue(original.contains("<img"))
        assertFalse(presented.get().savedLibrarySnapshot()!!.html!!.contains("<img"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.reader_newsletter_images_notice)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.reader_newsletter_load_images)).performClick()
        compose.waitUntil(15_000) { requests.any { it.contains("first-1.png") } }
        awaitDocument("first", images = true)
        assertTrue(consent.get())
        assertTrue("Image consent must allow only image requests", requests.all { it.endsWith(".png") })
        assertFalse(presented.get().savedLibrarySnapshot()!!.html!!.contains("/confirm"))
        assertFalse(presented.get().savedLibrarySnapshot()!!.html!!.contains("/unsubscribe"))
        assertEquals("Rendering must leave the authoritative feed body unchanged", original, incoming.value.content.documentBody)

        compose.runOnIdle { incoming.value = newsletter("second", 1) }
        awaitDocument("second", images = false)
        assertFalse(consent.get())
        assertFalse(requests.any { it.contains("second-1.png") })
        // Same article ID in another account cannot inherit the previous account's choice.
        compose.onNodeWithText(context.getString(R.string.reader_newsletter_load_images)).performClick()
        compose.waitUntil(15_000) { requests.any { it.contains("second-1.png") } }
        compose.runOnIdle { incoming.value = newsletter("second", 2) }
        awaitDocument("second", images = false)
        assertFalse(consent.get())
        assertFalse(requests.any { it.contains("second-2.png") })
        assertTrue("Embedded documents and styles remain blocked after every consent", requests.all { it.endsWith(".png") })
        assertNotNull(presented.get().savedLibrarySnapshot())
    }

    @Test fun newsletterLoadingAndBoundedPreparationFailureHaveNoSaveSnapshot() {
        val incoming = mutableStateOf(newsletter("oversized", 1).copy(content = ReaderState.Loading))
        val presented = AtomicReference<ReaderState>()
        compose.setContent {
            // Even previous image consent must not bypass parsing bounds or turn an error into a body.
            val display = incoming.value.copy(content = rememberNewsletterReaderContent(incoming.value, imagesAllowed = true))
            SideEffect { presented.set(display) }
            Text(if (display.content is ReaderState.Error) "Cannot display newsletter" else "Loading newsletter")
        }
        compose.waitForIdle()
        assertNull(presented.get().savedLibrarySnapshot())
        compose.runOnIdle { incoming.value = incoming.value.copy(content = ReaderState.Description("x".repeat(4 * 1024 * 1024 + 1))) }
        compose.waitUntil(15_000) { presented.get().content is ReaderState.Error }
        assertNull(presented.get().content.documentBody)
        assertNull("Failure must never enqueue a publisher URL download", presented.get().savedLibrarySnapshot())
    }

    private fun awaitDocument(title: String, images: Boolean) {
        compose.waitUntil(15_000) {
            val result = AtomicReference<String>()
            val done = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
                val webView = activity?.window?.decorView?.let(::findWebView)
                if (webView == null) done.countDown()
                else webView.evaluateJavascript("""
                    (function() {
                        var frame = document.getElementById('eink-content');
                        return document.readyState === 'complete' && frame &&
                            getComputedStyle(frame).visibility === 'visible' &&
                            window._didFinishInitialPagination === true &&
                            document.body.textContent.indexOf('$title body') >= 0 &&
                            (frame.querySelectorAll('img').length > 0) === $images;
                    })()
                """.trimIndent()) { result.set(it); done.countDown() }
            }
            assertTrue("WebView readiness query returned", done.await(5, TimeUnit.SECONDS))
            result.get() == "true"
        }
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun newsletter(id: String, account: Int) = ReaderState(articleId = id, sourceAccountId = account,
        title = id, link = "https://newsletter.example.invalid/article/$id", isNewsletter = true,
        content = ReaderState.Description("<p style=\"background-image:url(https://newsletter.example.invalid/style)\">$id body from account $account</p>" +
            "<img src='https://newsletter.example.invalid/$id-$account.png' alt='Newsletter photograph'>" +
            "<iframe src='https://newsletter.example.invalid/confirm'></iframe>" +
            "<object data='https://newsletter.example.invalid/unsubscribe'></object>" +
            "<meta http-equiv='refresh' content='0;url=https://newsletter.example.invalid/redirect'>" +
            "<link rel='stylesheet' href='https://newsletter.example.invalid/stylesheet'>"))

    private companion object {
        val PIXEL = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jB1kAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
    }
}
