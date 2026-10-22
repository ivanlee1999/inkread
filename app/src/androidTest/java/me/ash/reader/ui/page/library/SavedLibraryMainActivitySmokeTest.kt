package me.ash.reader.ui.page.library

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONTokener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.library.SavedContentState
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.android.AndroidApp
import me.ash.reader.infrastructure.android.MainActivity
import me.ash.reader.infrastructure.android.consumeLaunchAction
import me.ash.reader.infrastructure.library.*
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Bounded real Activity/Hilt/Nav3 smoke, separate from the exhaustive production-composable UI
 * harness. Run only on a fresh disposable offline AVD with savedRouteSmokeFixture=true. Uses
 * real Room/files and synthetic image-free snapshots; it never logs in or calls a provider.
 */
class SavedLibraryMainActivitySmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var database: SavedLibraryDatabase
    private lateinit var repository: DefaultSavedLibraryRepository
    private var scenario: ActivityScenario<MainActivity>? = null
    private val ownedIds = mutableListOf<String>()
    private lateinit var originalPreferences: Preferences
    private lateinit var initialFeeds: List<Feed>

    @Before fun seedDisposableApp() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("savedRouteSmokeFixture") == "true") {
            "MainActivity smoke requires the explicit disposable, offline Saved workflow fixture"
        }
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = context.applicationContext as AndroidApp
        withTimeout(15_000) {
            val account = requireNotNull(app.accountService.currentAccountFlow.first { it != null })
            // Current-account publication can precede the final synthetic bootstrap feed insert.
            while (app.androidDatabase.feedDao().queryAll(account.id!!).isEmpty()) delay(25)
        }
        database = SavedLibraryModule.database(context)
        check(database.savedLibraryDao().all().isEmpty()) { "Refusing to seed an existing Saved library" }
        repository = DefaultSavedLibraryRepository(database.savedLibraryDao(), SavedLibraryModule.files(context, SavedStorageLimits()),
            object : SavedArticleDownloader {
                override suspend fun extract(url: String, titleHint: String?): SavedExtractedArticle = error("Smoke fixture must remain offline")
                override suspend fun fetchImage(url: String): ByteArray = error("Smoke fixture has no images")
            }, object : SavedDownloadScheduler {
                override fun enqueue(id: String, token: String, replace: Boolean) = error("Image-free ready snapshots need no download")
                override fun cancel(id: String) = Unit
            })
        initialFeeds = app.androidDatabase.accountDao().queryAll().flatMap { app.androidDatabase.feedDao().queryAll(it.id!!) }
        originalPreferences = context.dataStore.data.first()
        context.dataStore.put(DataStoreKey.isFirstLaunch, false)
        context.dataStore.put(DataStoreKey.initialPage, 0)
        context.dataStore.put(DataStoreKey.einkMode, 1)
        repeat(28) { index ->
            ownedIds += repository.saveRssSnapshot("https://example.test/main-route-$index", "Route fixture article $index",
                "Ada", "Local fixture publication", HTML)
        }
    }

    @After fun cleanOnlyOwnedFixture() {
        scenario?.close()
        runBlocking {
            if (::repository.isInitialized) ownedIds.forEach { repository.remove(it) }
            if (::originalPreferences.isInitialized) context.dataStore.edit { current ->
                val first = booleanPreferencesKey(DataStoreKey.isFirstLaunch)
                val page = intPreferencesKey(DataStoreKey.initialPage)
                val ink = intPreferencesKey(DataStoreKey.einkMode)
                originalPreferences[first]?.let { current[first] = it } ?: current.remove(first)
                originalPreferences[page]?.let { current[page] = it } ?: current.remove(page)
                originalPreferences[ink]?.let { current[ink] = it } ?: current.remove(ink)
            }
        }
        if (::database.isInitialized) database.close()
    }

    @Test fun coldShareCancelRecreationAndSavedReaderBackUseTheRealActivityRoutes() {
        // Cold external share is a choice, not a silent save or subscription.
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.test/main-route-0")
        scenario = ActivityScenario.launch(intent)
        scenario!!.onActivity { activity ->
            assertEquals("text/plain", activity.intent.type)
            assertNull(activity.intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            assertNull(activity.intent.clipData)
            assertNull(activity.intent.consumeLaunchAction())
        }
        compose.onNodeWithTag("saved-capture-dialog").assertIsDisplayed()
        assertSavedRowsUnchanged()
        scenario!!.recreate()
        compose.onNodeWithTag("saved-capture-dialog").assertIsDisplayed()
        compose.onNodeWithTag("saved-capture-cancel").performScrollTo().performClick()
        scenario!!.recreate()
        compose.onNodeWithTag("saved-capture-dialog").assertDoesNotExist()
        assertSavedRowsUnchanged()

        compose.onNodeWithTag("feeds-open-saved").assertIsDisplayed().performClick()
        compose.onNodeWithTag("saved-library").assertIsDisplayed()
        compose.waitUntil(15_000) { placedIds().isNotEmpty() }
        compose.onNodeWithTag("article-page-control").performClick()
        compose.onNodeWithTag("article-page-next").performScrollTo().performClick()
        val page = compose.onNodeWithTag("article-page-control").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        val ids = placedIds()
        assertTrue(ids.isNotEmpty())
        compose.onNodeWithTag("saved-item-${ids.first()}", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("saved_library_reader").assertIsDisplayed()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("reader_controls_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(20_000) { js("typeof _didFinishInitialPagination !== 'undefined' && _didFinishInitialPagination") == true }
        val readerPage = (js("_currentPage") as Number).toInt()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.waitUntil(5_000) { (js("_currentPage") as Number).toInt() > readerPage }
        val progressedReaderPage = (js("_currentPage") as Number).toInt()
        val persistedAnchor = js("InkReadFormatting.exportReadingAnchor()") as String
        capture("saved-main-activity-reader")
        pressBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("saved-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("saved-library").assertIsDisplayed()
        assertEquals("Nav3 retains the exact printed page, not just the Saved destination", ids, placedIds())
        assertEquals(page, compose.onNodeWithTag("article-page-control").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        runBlocking {
            assertEquals("The real reader exit drains its progress into Room", persistedAnchor,
                database.savedLibraryDao().get(ids.first())!!.readingAnchor)
        }
        compose.onNodeWithTag("saved-item-${ids.first()}", useUnmergedTree = true).performClick()
        compose.waitUntil(20_000) { js("typeof _didFinishInitialPagination !== 'undefined' && _didFinishInitialPagination") == true }
        assertEquals("Reopening the real Saved reader restores its exact page", progressedReaderPage,
            (js("_currentPage") as Number).toInt())
        assertEquals("Restoration uses the durable logical anchor", persistedAnchor,
            js("InkReadFormatting.exportReadingAnchor()"))
        pressBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("saved-library").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ids, placedIds())
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("feeds-open-saved").assertIsDisplayed()
        assertFeedsUnchanged()

        // Warm duplicate share goes through onNewIntent and commits the same durable identity.
        scenario!!.onActivity { activity -> activity.startActivity(Intent(activity, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Intent.EXTRA_TEXT, "https://example.test/main-route-0")) }
        compose.onNodeWithTag("saved-capture-dialog").assertIsDisplayed()
        compose.onNodeWithTag("saved-capture-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("saved-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("saved-library").assertIsDisplayed()
        compose.onNodeWithTag("saved-capture-dialog").assertDoesNotExist()
        assertSavedRowsUnchanged()
        compose.onNodeWithTag("saved-capture-success").assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        scenario!!.recreate()
        compose.onNodeWithTag("saved-capture-success").assertDoesNotExist()
        compose.onNodeWithTag("saved-library").assertIsDisplayed()
        compose.onNodeWithTag("saved-capture-dialog").assertDoesNotExist()
        assertFeedsUnchanged()
        capture("saved-main-activity-library-return")
    }

    private fun placedIds(): List<String> = compose.onAllNodes(SemanticsMatcher("Real saved article block") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("saved-item-")
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("saved-item-") }
    private fun assertSavedRowsUnchanged() = runBlocking {
        val rows = database.savedLibraryDao().all()
        assertEquals(ownedIds.toSet(), rows.map { it.id }.toSet())
        assertTrue(rows.all { it.contentState == SavedContentState.READY.name && it.downloadToken == null })
    }
    private fun assertFeedsUnchanged() = runBlocking {
        val rss = (context.applicationContext as AndroidApp).androidDatabase
        val feeds = rss.accountDao().queryAll().flatMap { rss.feedDao().queryAll(it.id!!) }
        assertEquals("Saved capture and route actions do not subscribe, remove or edit RSS feeds", initialFeeds, feeds)
    }
    private fun pressBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun js(expression: String): Any? {
        val result = AtomicReference<Any?>()
        val ready = CountDownLatch(1)
        scenario!!.onActivity { activity ->
            val webView = findWebView(activity.window.decorView)
            if (webView == null) ready.countDown() else webView.evaluateJavascript(expression) {
                result.set(JSONTokener(it).nextValue())
                ready.countDown()
            }
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "Saved reader JavaScript callback timed out" }
        return result.get()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val argument = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = if (argument.isNullOrBlank()) File(context.getExternalFilesDir(null), "newspaper-screenshots") else File(argument)
        check(directory.isDirectory || directory.mkdirs())
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }
    companion object {
        private val HTML = "<article>" + (0 until 55).joinToString("") {
            "<p>Local fixture paragraph $it. This article was saved independently of RSS accounts and remains readable without a network connection.</p>"
        } + "</article>"
    }
}
