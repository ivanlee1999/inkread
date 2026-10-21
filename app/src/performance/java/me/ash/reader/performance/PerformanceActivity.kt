package me.ash.reader.performance

import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ash.reader.BuildConfig
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.layout.resolveReadingLayout
import me.ash.reader.ui.page.home.feeds.*
import me.ash.reader.ui.page.home.flow.*
import me.ash.reader.ui.page.home.reading.EInkPaginatedContent
import me.ash.reader.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import kotlin.coroutines.resume

/**
 * Lives only in the opt-in R8/nondebuggable performance APK. No instrumentation/Compose-test
 * idling, production hooks, network data, UI animations, or timing thresholds are introduced.
 * Actions use the production state/Back dispatchers, not synthetic reimplementations.
 */
class PerformanceActivity : ComponentActivity() {
    private enum class Screen { Articles, Folders, Reader }
    private var screen by mutableStateOf(Screen.Articles)
    private var expanded by mutableStateOf(emptySet<String>())
    private val articleState = NewspaperViewportState()
    private val folderState = FolderNewspaperState()
    private val editionIdentity = Any()
    private lateinit var articles: List<ArticleFlowItem>
    private lateinit var folders: List<FolderSection>
    private var displayedCallbacks = 0L
    private var draws = 0L
    private var readerCallbacks = 0L
    private var readerPages = 0
    private var readerGeneration = 0
    private var screenCompositions = 0L
    private val samples = JSONArray()
    private lateinit var startup: Snapshot
    private var counterMode = false
    private var activeScenario = "fixture_activity_ready"
    private lateinit var scale: PerformanceFixture.Scale

    private data class Snapshot(
        val wallNs: Long, val cpuMs: Long, val javaBytes: Long, val nativeBytes: Long,
        val displayedCallbacks: Long, val draws: Long, val readerCallbacks: Long,
        val screenCompositions: Long, val hashCalls: Long, val hashedElements: Long,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(!BuildConfig.DEBUG) { "Performance measurements require the nondebuggable APK" }
        counterMode = intent.getBooleanExtra("counters", false)
        startup = snapshot()
        scale = PerformanceFixture.scale(intent.getStringExtra("scale") ?: "small")
        articles = PerformanceFixture.articles(scale)
        folders = PerformanceFixture.folders(scale).map { section ->
            if (counterMode) section.copy(publications = CountingPublications(section.publications)) else section
        }
        articleState.acceptSnapshot(editionIdentity, articles)
        window.decorView.viewTreeObserver.addOnDrawListener { draws++ }
        setContent {
            CompositionLocalProvider(
                LocalEInkMode provides EInkModePreference.ON,
                LocalFlowArticleListImage provides FlowArticleListImagePreference.OFF,
                LocalFlowArticleListFeedIcon provides FlowArticleListFeedIconPreference.OFF,
                LocalFlowArticleListFeedName provides FlowArticleListFeedNamePreference.ON,
                LocalFlowArticleListReadIndicator provides FlowArticleReadIndicatorPreference.AllRead,
            ) {
                AppTheme(useDarkTheme = false) {
                    Box(Modifier.fillMaxSize()) {
                        Content()
                    }
                    LaunchedEffect(Unit) {
                        try {
                            await { articleState.pages.isNotEmpty() && displayedCallbacks > 0 }
                            awaitRendered()
                            record("fixture_activity_ready", 0, startup, snapshot(), warmup = false)
                            runScenarios()
                            writeResult(null)
                        } catch (failure: Throwable) {
                            writeResult(failure.stackTraceToString())
                            throw failure
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Content() {
        SideEffect { screenCompositions++ }
        when (screen) {
            Screen.Articles -> Articles()
            Screen.Folders -> RYScaffold(reserveBottomBarSpace = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    FolderEditionHeader("Performance subscriptions", folders.size,
                        scale.publications, folderState)
                    FolderNewspaperEdition(folders, expanded, "Unread", Modifier.weight(1f), folderState,
                        onToggleFolder = { id -> folderState.anchorFolder(id); expanded = if (id in expanded) expanded - id else expanded + id })
                }
            }
            Screen.Reader -> {
                val generation = readerGeneration
                BackHandler { screen = Screen.Articles }
                EInkPaginatedContent(content = PerformanceFixture.readerHtml,
                    articleId = "performance-reader", title = "Deterministic reader document",
                    feedName = "Independent Journal", author = "Fixture Reporter", publishedDate = Date(1_700_000_000_000L),
                    onPageChanged = { _, total ->
                        if (screen == Screen.Reader && generation == readerGeneration) { readerPages = total; readerCallbacks++ }
                    },
                    onBack = { onBackPressedDispatcher.onBackPressed() })
            }
        }
    }

    @Composable private fun Articles() {
        RYScaffold(reserveBottomBarSpace = false,
            topBar = {
                EInkArticleListHeader("Performance edition", Filter.All, articleState.currentPage + 1,
                    articleState.pages.size, scale.articles, false,
                    onNavigateUp = { screen = Screen.Folders }, onFilterChange = {}, onRefresh = {},
                    onMarkAllAsRead = {}, onSearch = {}, onPrev = { turn(false) }, onNext = { turn(true) })
            }) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val layout = resolveReadingLayout(maxWidth.value, LocalDensity.current.fontScale)
                EInkNewspaperViewport(articleState, layout.columns, layout.horizontalPaddingDp.dp,
                    layout.columnSpacingDp.dp, onTurn = ::turn, onClick = { _, _ -> openReader() },
                    onDisplayed = { displayedCallbacks++ })
            }
        }
    }

    private fun turn(forward: Boolean) = articleState.turn(forward, false) {}
    private fun openReader() { readerGeneration++; readerPages = 0; screen = Screen.Reader }

    private suspend fun runScenarios() {
        repeat(PerformanceFixture.WARMUPS + PerformanceFixture.SAMPLES) { iteration ->
            val warmup = iteration < PerformanceFixture.WARMUPS
            val sample = iteration - PerformanceFixture.WARMUPS
            val page = articleState.currentPage
            val sourceIds = articleState.displayedArticles.map { it.article.id }
            measure("article_page_next", sample, warmup, action = { check(turn(true)) },
                ready = { articleState.currentPage == page + 1 })
            measure("article_page_previous", sample, warmup, action = { check(turn(false)) },
                ready = { articleState.currentPage == page })
            measure("reader_open", sample, warmup, action = ::openReader, ready = { readerPages > 1 })
            val before = displayedCallbacks
            measure("reader_back", sample, warmup, action = { onBackPressedDispatcher.onBackPressed() },
                ready = { screen == Screen.Articles && displayedCallbacks > before && articleState.currentPage == page })
            check(articleState.displayedArticles.map { it.article.id } == sourceIds) { "Back must restore the exact article page" }
        }
        measure("folder_screen_open", 0, false, action = { screen = Screen.Folders },
            ready = { folderState.pageCount > 1 && folderState.presentationAvailable })
        repeat(PerformanceFixture.WARMUPS + PerformanceFixture.SAMPLES) { iteration ->
            val warmup = iteration < PerformanceFixture.WARMUPS
            val sample = iteration - PerformanceFixture.WARMUPS
            val first = folders.first().id
            val before = folderState.layoutIdentity
            measure("folder_expand", sample, warmup, action = {
                folderState.anchorFolder(first); expanded = setOf(first)
            }, ready = { folderState.presentationAvailable && folderState.layoutIdentity != before })
            val page = folderState.currentPage
            measure("folder_page_next", sample, warmup, action = { check(folderState.turn(true)) },
                ready = { folderState.currentPage == page + 1 && folderState.presentationAvailable })
            measure("folder_page_previous", sample, warmup, action = { check(folderState.turn(false)) },
                ready = { folderState.currentPage == page && folderState.presentationAvailable })
            val expandedIdentity = folderState.layoutIdentity
            measure("folder_collapse", sample, warmup, action = {
                folderState.anchorFolder(first); expanded = emptySet()
            }, ready = { folderState.presentationAvailable && folderState.layoutIdentity != expandedIdentity })
        }
    }

    private suspend fun measure(name: String, iteration: Int, warmup: Boolean, action: () -> Unit, ready: () -> Boolean) {
        activeScenario = "$name[$iteration], warmup=$warmup"
        val start = snapshot()
        action()
        await(ready)
        awaitRendered()
        check(draws > start.draws) { "$name must render the changed native screen" }
        val end = snapshot()
        record(name, iteration, start, end, warmup)
    }

    private suspend fun await(ready: () -> Boolean) = withTimeout(120_000) {
        while (!ready()) withFrameNanos { }
    }

    private suspend fun awaitRendered() {
        // Two display-clock barriers include Compose's next layout/draw. This adds a fixed
        // vsync floor and is NOT input-to-photon timing or a physical E-Ink refresh metric.
        repeat(2) { withFrameNanos { } }
        if (screen == Screen.Reader) {
            val webView = checkNotNull(findWebView(window.decorView))
            withTimeout(30_000) {
                suspendCancellableCoroutine { continuation ->
                    webView.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resume(Unit) }
                    })
                }
            }
            withFrameNanos { }
        }
    }

    private fun snapshot(): Snapshot {
        val runtime = Runtime.getRuntime()
        return Snapshot(SystemClock.elapsedRealtimeNanos(), Process.getElapsedCpuTime(),
            runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize(),
            displayedCallbacks, draws, readerCallbacks, screenCompositions,
            CountingPublications.hashCalls, CountingPublications.hashedElements)
    }

    private fun record(name: String, iteration: Int, before: Snapshot, after: Snapshot, warmup: Boolean) {
        samples.put(JSONObject().apply {
            put("scenario", name); put("iteration", iteration); put("warmup", warmup)
            put("wall_ms", (after.wallNs - before.wallNs) / 1_000_000.0)
            put("app_process_cpu_ms", after.cpuMs - before.cpuMs)
            put("java_heap_bytes", after.javaBytes); put("java_heap_delta_bytes", after.javaBytes - before.javaBytes)
            put("native_heap_bytes", after.nativeBytes); put("native_heap_delta_bytes", after.nativeBytes - before.nativeBytes)
            put("displayed_callbacks", after.displayedCallbacks - before.displayedCallbacks)
            put("root_draws", after.draws - before.draws)
            put("reader_page_callbacks", after.readerCallbacks - before.readerCallbacks)
            put("fixture_screen_compositions", after.screenCompositions - before.screenCompositions)
            put("publication_list_hash_calls", after.hashCalls - before.hashCalls)
            put("publication_elements_hashed", after.hashedElements - before.hashedElements)
            put("article_pages", articleState.pages.size); put("folder_pages", folderState.pageCount)
            put("article_page", articleState.currentPage); put("folder_page", folderState.currentPage)
            put("article_source_ids", JSONArray(articleState.displayedArticles.map { it.article.id }))
            put("folder_source_ids", JSONArray(folderState.pages.getOrNull(folderState.currentPage)?.keys.orEmpty()))
            put("reader_pages", readerPages); put("cached_enriched_article_pages", articleState.pageCache.enriched.size)
        })
    }

    private suspend fun writeResult(failure: String?) {
        val data = JSONObject().apply {
            put("schema", 1); put("fixture", PerformanceFixture.VERSION)
            put("mode", if (counterMode) "work_counters" else "timing")
            put("debuggable", BuildConfig.DEBUG); put("width_dp", resources.configuration.screenWidthDp)
            put("height_dp", resources.configuration.screenHeightDp); put("font_scale", resources.configuration.fontScale)
            put("density_dpi", resources.configuration.densityDpi)
            put("scale", scale.name); put("expanded_folder_publications", scale.firstFolderFeeds)
            put("articles", scale.articles); put("folders", scale.folders)
            put("publications", scale.publications)
            put("reader_html_bytes", PerformanceFixture.readerHtml.toByteArray().size)
            put("webview", WebView.getCurrentWebViewPackage()?.versionName ?: "unavailable")
            put("active_scenario", activeScenario)
            put("diagnostics", JSONObject().apply {
                put("screen", screen.name); put("article_page", articleState.currentPage)
                put("article_pages", articleState.pages.size); put("folder_page", folderState.currentPage)
                put("folder_pages", folderState.pageCount); put("folder_ready", folderState.presentationAvailable)
                put("reader_pages", readerPages); put("root_draws", draws)
            })
            put("samples", samples); put("failure", failure ?: JSONObject.NULL)
        }.toString(2)
        withContext(Dispatchers.IO) {
            val output = File(getExternalFilesDir(null), "performance/result.json")
            output.parentFile!!.mkdirs()
            File(output.parentFile, "result.tmp").writeText(data)
            check(File(output.parentFile, "result.tmp").renameTo(output))
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) repeat(view.childCount) { findWebView(view.getChildAt(it))?.let { found -> return found } }
        return null
    }
}

/** Separate counter-only run: identical List values/hash outputs, but intentionally nonzero observer cost. */
internal class CountingPublications(private val contents: List<FolderPublication>) : AbstractList<FolderPublication>() {
    override val size: Int get() = contents.size
    override fun get(index: Int) = contents[index]
    override fun hashCode(): Int {
        hashCalls++
        hashedElements += contents.size
        return contents.hashCode()
    }
    companion object { var hashCalls = 0L; var hashedElements = 0L }
}
