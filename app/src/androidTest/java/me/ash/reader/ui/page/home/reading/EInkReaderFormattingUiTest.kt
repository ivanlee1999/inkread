package me.ash.reader.ui.page.home.reading

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.dispatchEInkVolumeKey
import me.ash.reader.infrastructure.preference.EInkChineseFontPreference
import me.ash.reader.infrastructure.preference.EInkEnglishFontPreference
import me.ash.reader.infrastructure.preference.EInkModePreference
import me.ash.reader.infrastructure.preference.EInkFontSizePreference
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.infrastructure.preference.LocalReaderFormatting
import me.ash.reader.infrastructure.preference.LocalReaderFormattingLoadError
import me.ash.reader.infrastructure.preference.LocalRetryReaderFormatting
import me.ash.reader.infrastructure.preference.ReaderFormatting
import me.ash.reader.infrastructure.preference.ReaderFormattingPreference
import me.ash.reader.infrastructure.preference.saveReaderFormatting
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.page.home.NativePageDensityCapture
import me.ash.reader.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Native controls over the real production article, not a preview renderer or HTML mockup.
 * Every reflow keeps a canonical logical text anchor AND its independently measured glyph on
 * screen. A window sentinel, document identity and request count detect hidden HTML reloads.
 *
 * Fonts are explicit test-only cached Android font files, restored after each test. This proves
 * offline face loading/script routing, not the identity or download of production catalog fonts.
 * The dedicated workflow runs fresh instrumentation processes for the persistence phase.
 */
class EInkReaderFormattingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("formattingPersistencePhase")
    private val page = AtomicInteger()
    private val count = AtomicInteger()
    private val articleChanges = AtomicInteger()
    private val readerBacks = AtomicInteger()
    private val htmlRequests = AtomicInteger()
    private val remoteRequests = mutableListOf<String>()
    private val samples = JSONArray()
    private val content = mutableStateOf("<p>Preparing an ordinary reading article.</p>")
    private val visible = mutableStateOf(true)
    private val viewportFraction = mutableStateOf(1f)
    private val preferenceError = mutableStateOf<Throwable?>(null)
    private val preferenceRetries = AtomicInteger()
    private val successfulRetrySnapshots = AtomicInteger()
    private val lateRequested = CountDownLatch(1)
    private val releaseLate = CountDownLatch(1)
    private val fontRequested = CountDownLatch(1)
    private val releaseFont = CountDownLatch(1)
    private var delayFonts = false
    private var activeSection = "text"
    private var original = ReaderFormatting.Default
    private var reportName = "reader-formatting"
    private var capturedInitialPanel = false
    private val fontBackups = mutableMapOf<File, ByteArray?>()

    @Before fun prepare() {
        VolumeKeyEventBus.resetForTest()
        original = readPersisted()
        if (phase != "restore") runBlocking { saveReaderFormatting(context, ReaderFormatting.Default) }
    }

    @After fun cleanup() {
        // Keep genuine native evidence even if a control assertion fails before the normal
        // capture points. These diagnostic frames deliberately make no claim that a test passed.
        runCatching { writeFinalDiagnostics() }
        releaseLate.countDown()
        releaseFont.countDown()
        writeReport()
        // The seed phase intentionally leaves only its approved fixture preferences for the
        // immediately following fresh-process restore phase on the disposable test emulator.
        if (phase != "seed") runBlocking { saveReaderFormatting(context, original) }
        fontBackups.forEach { (file, previous) ->
            if (previous == null) check(!file.exists() || file.delete()) else file.writeBytes(previous)
        }
        VolumeKeyEventBus.resetForTest()
    }

    @Test fun liveDraftKeepsTextAnchorMarginsAndDocumentThroughApplyCancelAndReset() {
        reportName = "reader-formatting-live"
        startReader()
        awaitLateRequest()
        goToMiddlePage()
        val baseline = snapshot("before-panel")
        rememberAnchor()
        openPanel()
        assertAnchor("panel-open")
        section("text")
        step("font_size", increase = true, times = 2)
        step("line_height", increase = true, times = 2)
        step("text_darkness", increase = false, times = 2)
        step("text_weight", increase = true, times = 2)
        val textDraft = settled("text-draft")
        assertTrue(textDraft.getDouble("fontSize") > baseline.getDouble("fontSize"))
        assertTrue(textDraft.getDouble("lineHeight") / textDraft.getDouble("fontSize") >
            baseline.getDouble("lineHeight") / baseline.getDouble("fontSize"))
        assertNotEquals("Darkness changes actual text ink", baseline.getString("color"), textDraft.getString("color"))
        assertTrue(textDraft.getInt("fontWeight") > baseline.getInt("fontWeight"))
        assertEquals("Draft controls must not persist early", ReaderFormatting.Default, readPersisted())

        // Numeric values are deliberately read-only. Fine +/- steps remain usable without an
        // IME taking away the native preview, including the large-system-font matrix entries.
        scrollToTag("reader_formatting_font_size_value").assert(!hasSetTextAction())
            .performTouchInput { click(center) }
        settleBarrier()
        assertFalse("Formatting values must not summon the keyboard", imeVisible())
        assertFullyInside("reader_formatting_apply", "reader_formatting_panel")
        assertFullyInside("reader_formatting_cancel", "reader_formatting_panel")
        assertAnchor("stepper-no-ime")

        section("layout")
        // Four deliberately unequal margins detect accidental shared-padding implementations.
        step("margin_left", increase = true)
        step("margin_right", increase = true, times = 2)
        step("margin_top", increase = true, times = 3)
        step("margin_bottom", increase = true, times = 4)
        val margins = settled("four-independent-margins")
        val edges = listOf("marginLeft", "marginRight", "marginTop", "marginBottom").map { margins.getDouble(it) }
        assertEquals("Each margin is independently adjustable", 4, edges.distinct().size)
        edges.forEach { assertTrue(it > baseline.getDouble("marginLeft")) }
        assertFrame(margins)

        section("text")
        val beforeImageContrast = settled("before-image-contrast")
        step("image_contrast", increase = true, times = 2)
        clickTag("reader_formatting_image_grayscale")
        val afterImageContrast = settled("image-only-contrast")
        assertNotEquals(beforeImageContrast.getString("imageFilter"), afterImageContrast.getString("imageFilter"))
        assertTrue(afterImageContrast.getString("imageFilter").contains("grayscale(1)"))
        assertEquals("Image contrast cannot filter article text", beforeImageContrast.getString("textFilter"), afterImageContrast.getString("textFilter"))
        assertEquals(beforeImageContrast.getString("color"), afterImageContrast.getString("color"))
        assertEquals("The article frame must not receive the photo filter", "none", afterImageContrast.getString("frameFilter"))
        assertEquals("The document body must not receive the photo filter", "none", afterImageContrast.getString("bodyFilter"))
        assertAnchor("all-draft-controls")

        // Ordinary delayed photo above the saved reading position arrives during a live draft.
        releaseLate.countDown()
        compose.waitUntil(15_000) { snapshot().getJSONObject("lateImage").getInt("naturalWidth") > 0 }
        assertAnchor("late-image-during-draft")
        val fullWidth = snapshot().getDouble("viewportWidth")
        compose.runOnIdle { viewportFraction.value = .83f }
        compose.waitUntil(10_000) { snapshot().getDouble("viewportWidth") < fullWidth - 5 }
        scrollToTag("reader_formatting_font_size_value").assert(!hasSetTextAction())
        assertFullyInside("reader_formatting_apply", "reader_formatting_panel")
        assertFullyInside("reader_formatting_cancel", "reader_formatting_panel")
        assertFalse("Narrow formatting controls remain keyboard-free", imeVisible())
        assertAnchor("panel-resized")
        capture("narrow-panel")
        compose.runOnIdle { viewportFraction.value = 1f }
        compose.waitUntil(10_000) { abs(snapshot().getDouble("viewportWidth") - fullWidth) < 1 }
        assertAnchor("panel-restored")
        capture("live-panel")
        clickTag("reader_formatting_apply")
        panelClosed()
        val applied = settled("applied")
        assertAnchor("apply")
        val persisted = awaitPersisted { it.fontSize > ReaderFormatting.Default.fontSize && it.marginBottom > it.marginTop }
        assertStyleMatches(persisted, applied)
        assertEquals("Live formatting must not load HTML", 1, htmlRequests.get())
        verifyMiddlePageFrames("applied")

        rememberAnchor()
        openPanel()
        clickTag("reader_formatting_reset")
        val resetDraft = settled("reset-preview")
        assertStyleMatches(ReaderFormatting.Default, resetDraft)
        assertEquals("Reset is still a draft until Apply", persisted, readPersisted())
        assertAnchor("reset-preview")
        clickTag("reader_formatting_cancel")
        panelClosed()
        val cancelledReset = settled("cancel-reset")
        assertStyleMatches(persisted, cancelledReset)
        assertEquals("Cancel restores text darkness", applied.getString("color"), cancelledReset.getString("color"))
        assertEquals("Cancel restores image-only filtering", applied.getString("imageFilter"), cancelledReset.getString("imageFilter"))
        assertAnchor("cancel-reset")
        openPanel()
        section("text")
        step("font_size", increase = false)
        clickTag("reader_formatting_cancel")
        panelClosed()
        assertStyleMatches(persisted, settled("cancel-edit"))
        assertAnchor("cancel-edit")
        assertEquals(persisted, readPersisted())
        // Exercise the existing shared setting writer after Apply has fully settled. A stale
        // committed-draft override must not hide later changes from another settings surface.
        val externalSize = EInkFontSizePreference.values.first { it > persisted.fontSize }
        runBlocking { EInkFontSizePreference.put(context, this, externalSize) }
        val external = awaitPersisted { it.fontSize == externalSize }
        advanceComposeDraftClock()
        compose.waitUntil(10_000) { snapshot().optDouble("specifiedFontSize") == externalSize.toDouble() }
        assertStyleMatches(external, settled("external-shared-setting"))
        assertAnchor("external-shared-setting-anchor")
        openPanel()
        section("text")
        step("font_size", increase = true)
        val interruptedDraft = external.copy(fontSize = external.fontSize + 1)
        // The real provider retains its last successful snapshot on a later read failure.
        // Inject only that error state here; the article, draft controls and retry action are real.
        compose.runOnIdle { preferenceError.value = java.io.IOException("Native fixture late preference read failure") }
        compose.onNodeWithTag("reader_formatting_panel").assertIsDisplayed()
        compose.onNodeWithTag("reader_formatting_retry").assertIsDisplayed()
        assertStyleMatches(interruptedDraft, settled("late-preference-error-keeps-draft"))
        assertAnchor("late-preference-error-keeps-anchor")
        clickTag("reader_formatting_retry")
        compose.waitUntil(5_000) { preferenceRetries.get() == 1 && successfulRetrySnapshots.get() == 1 && preferenceError.value == null }
        compose.onNodeWithTag("reader_formatting_retry").assertDoesNotExist()
        assertStyleMatches(interruptedDraft, settled("retry-keeps-draft"))
        assertAnchor("retry-keeps-anchor")
        clickTag("reader_formatting_cancel")
        panelClosed()
        assertStyleMatches(external, settled("cancel-after-retry"))
        assertEquals(external, readPersisted())
        verifyMaximumFormattingInShortViewport(external)
        assertEquals("No Apply, Cancel, Reset, retry or resize may recreate the article", 1, htmlRequests.get())
        assertTrue("No ordinary formatting interaction downloads remote resources", synchronized(remoteRequests) { remoteRequests.isEmpty() })
        capture("applied-article")
    }

    @Test fun offlineFontsUseBothScriptFacesAndModalOwnsBackVolumeAndGestures() {
        reportName = "reader-formatting-fonts-modal"
        seedPlatformFontFixtures()
        delayFonts = true
        releaseLate.countDown()
        startReader()
        goToMiddlePage()
        rememberAnchor()
        openPanel()
        section("fonts")
        clickTag("reader_formatting_font_english_1")
        assertTrue("Selecting a locally available face must actually request its font bytes", fontRequested.await(10, TimeUnit.SECONDS))
        // Font readiness is intentionally pending, not simulated by a pre-resolved promise.
        assertFalse(snapshot().getBoolean("latinFaceLoaded"))
        releaseFont.countDown()
        compose.waitUntil(15_000) { snapshot().getBoolean("latinFaceLoaded") }
        assertAnchor("latin-face-ready")
        clickTag("reader_formatting_font_language_chinese")
        clickTag("reader_formatting_font_chinese_2")
        compose.waitUntil(15_000) { snapshot().getBoolean("cjkFaceLoaded") }
        assertAnchor("cjk-face-ready")
        val fonts = settled("offline-script-faces")
        assertTrue(fonts.getBoolean("latinFaceLoaded"))
        assertTrue(fonts.getBoolean("cjkFaceLoaded"))
        assertTrue("The selected Latin file is bound to the active script face", fonts.getString("fontCss").contains("SourceSerif4-Regular.ttf"))
        assertTrue("The selected CJK file is bound to the active script face", fonts.getString("fontCss").contains("NotoSansSC-Regular.otf"))
        val stack = fonts.getString("fontFamily")
        assertTrue("Both script faces precede generic fallback: $stack", stack.indexOf("InkReadLatin") >= 0 &&
            stack.indexOf("InkReadCjk") >= 0 && stack.indexOf("InkReadCjk") < stack.lastIndexOf("serif"))
        assertTrue("Latin glyphs render through the real selected face", fonts.getBoolean("latinPaintMatchesFace"))
        assertTrue("Chinese glyphs render through the real selected face", fonts.getBoolean("cjkPaintMatchesFace"))
        assertTrue("The fixture's Latin face must actually differ from generic sans fallback", fonts.getBoolean("latinPaintDiffersFromSans"))
        assertTrue("Emoji and code fallback remain measurable", fonts.getDouble("emojiWidth") > 0 && fonts.getDouble("codeWidth") > 0)
        assertFalse("Code must not use the article's selected font stack", fonts.getString("codeFont").contains("InkReadLatin"))
        assertScriptRanges(fonts.getJSONArray("faces"))
        val filesBefore = fontFileNames()
        // Available selection is not an implicit request to download any unavailable face.
        clickTag("reader_formatting_font_language_english")
        scrollToTag("reader_formatting_font_english_9").assertIsNotEnabled()
        assertEquals(filesBefore, fontFileNames())
        assertTrue(synchronized(remoteRequests) { remoteRequests.isEmpty() })
        clickTag("reader_formatting_apply")
        panelClosed()
        val applied = awaitPersisted { it.englishFont == 1 && it.chineseFont == 2 }
        assertAnchor("font-apply")

        rememberAnchor()
        openPanel()
        section("text")
        step("font_size", increase = true)
        val modalPage = page.get()
        dispatchVolume(KeyEvent.KEYCODE_VOLUME_DOWN)
        // Check each direction separately: a leaked next+previous pair could cancel itself.
        settleBarrier()
        assertEquals("Volume Down must not page underneath the formatting modal", modalPage, page.get())
        dispatchVolume(KeyEvent.KEYCODE_VOLUME_UP)
        settleBarrier()
        assertEquals("Volume Up must not page underneath the formatting modal", modalPage, page.get())
        assertEquals(0, articleChanges.get())
        compose.onNodeWithTag("reader_formatting_panel").performTouchInput {
            swipe(Offset(width * .8f, height * .55f), Offset(width * .2f, height * .55f), 400)
        }
        settleBarrier()
        assertEquals("Panel gestures must not invoke reader article swipes", 0, articleChanges.get())
        assertAnchor("modal-volume-and-swipe")
        capture("fonts-and-modal")
        pressBack()
        panelClosed()
        assertEquals("First Back dismisses only the draft", 0, readerBacks.get())
        assertStyleMatches(applied, settled("back-cancels-draft"))
        assertAnchor("back-cancels-draft")
        assertEquals(applied, readPersisted())
        assertEquals("Font readiness and modal cycles must not reload article HTML", 1, htmlRequests.get())
        val beforeTurn = page.get()
        dispatchVolume(KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.waitUntil(5_000) { page.get() == beforeTurn + 1 }
        compose.onNodeWithTag("formatting-reader").performTouchInput {
            swipe(Offset(width * .8f, height * .5f), Offset(width * .2f, height * .5f), 400)
        }
        compose.waitUntil(5_000) { articleChanges.get() == 1 }
        pressBack()
        compose.onNodeWithTag("formatting-reader-closed").assertIsDisplayed()
        assertEquals("Second Back exits the reader exactly once", 1, readerBacks.get())
        // Dispose/reopen the real composable with preferences from DataStore, not a draft copy.
        compose.runOnIdle { visible.value = true; page.set(0); count.set(0) }
        awaitReader()
        assertStyleMatches(applied, settled("reader-reopened"))
        openPanel()
        section("fonts")
        scrollToTag("reader_formatting_font_english_1").assertIsEnabled()
        clickTag("reader_formatting_cancel")
        panelClosed()
        assertEquals(applied, readPersisted())
    }

    @Test fun appliedFormattingSurvivesARealInstrumentationProcessRestart() {
        reportName = "reader-formatting-persistence-${phase ?: "same-process"}"
        releaseLate.countDown()
        startReader()
        if (phase == "restore") {
            val expected = JSONObject(File(context.filesDir, PERSISTENCE_MARKER).readText())
            assertNotEquals("Workflow must start a new application process", expected.getInt("pid"), android.os.Process.myPid())
            assertTrue("The settings file survives process death", readPersisted().fontSize > ReaderFormatting.Default.fontSize)
            val loaded = settled("fresh-process-restored")
            assertEquals(expected.getInt("fontSize"), readPersisted().fontSize)
            assertEquals(expected.getInt("marginLeft"), readPersisted().marginLeft)
            assertStyleMatches(readPersisted(), loaded)
            openPanel()
            clickTag("reader_formatting_cancel")
            panelClosed()
            assertEquals(expected.getInt("fontSize"), readPersisted().fontSize)
            check(File(context.filesDir, PERSISTENCE_MARKER).delete())
        } else {
            openPanel()
            section("text")
            step("font_size", increase = true)
            section("layout")
            step("margin_left", increase = true, times = 2)
            clickTag("reader_formatting_apply")
            panelClosed()
            val saved = awaitPersisted { it.fontSize > ReaderFormatting.Default.fontSize && it.marginLeft > 24 }
            assertStyleMatches(saved, settled("persisted-before-restart"))
            if (phase == "seed") File(context.filesDir, PERSISTENCE_MARKER).writeText(JSONObject()
                .put("pid", android.os.Process.myPid()).put("fontSize", saved.fontSize).put("marginLeft", saved.marginLeft).toString())
        }
    }

    private fun startReader() {
        compose.setContent {
            val formatting = remember { mutableStateOf<ReaderFormatting?>(null) }
            val retryScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                context.dataStore.data.collect { formatting.value = ReaderFormattingPreference.fromPreferences(it) }
            }
            CompositionLocalProvider(
                LocalEInkMode provides EInkModePreference.ON,
                LocalReaderFormatting provides formatting.value,
                LocalReaderFormattingLoadError provides preferenceError.value,
                LocalRetryReaderFormatting provides {
                    preferenceRetries.incrementAndGet()
                    retryScope.launch {
                        // A genuine new subscription must yield a successful DataStore snapshot
                        // before the test provider can clear its injected late-read error.
                        val snapshot = ReaderFormattingPreference.fromPreferences(context.dataStore.data.first())
                        formatting.value = snapshot
                        successfulRetrySnapshots.incrementAndGet()
                        preferenceError.value = null
                    }
                },
            ) {
                AppTheme(useDarkTheme = false) {
                    if (visible.value) {
                        BackHandler { readerBacks.incrementAndGet(); visible.value = false }
                        Box(Modifier.fillMaxSize()) {
                            EInkPaginatedContent(
                                modifier = Modifier.fillMaxWidth(viewportFraction.value).fillMaxHeight(viewportFraction.value).testTag("formatting-reader"),
                                articleId = "ordinary-formatting-fixture", content = content.value,
                                title = "A quiet afternoon · 安静的午后", feedName = "Native formatting fixture", publishedDate = Date(0),
                                onPageChanged = { current, total -> page.set(current); count.set(total) },
                                onNextArticle = { articleChanges.incrementAndGet() },
                                onPrevArticle = { articleChanges.decrementAndGet() },
                                onBack = { readerBacks.incrementAndGet(); visible.value = false },
                            )
                        }
                    } else Text("Reader closed", Modifier.testTag("formatting-reader-closed"))
                }
            }
        }
        compose.waitUntil(15_000) { count.get() > 0 }
        installOrdinaryResources()
        compose.runOnIdle { page.set(0); count.set(0); content.value = fixture() }
        awaitReader()
        js("window.__formattingDocumentSentinel = {}; true")
    }

    private fun awaitReader() {
        compose.waitUntil(20_000) { count.get() >= 8 && page.get() > 0 }
        compose.waitUntil(10_000) { snapshot().optBoolean("ready") }
        settleBarrier()
    }

    private fun goToMiddlePage() {
        repeat(4) {
            val expected = page.get() + 1
            dispatchVolume(KeyEvent.KEYCODE_VOLUME_DOWN)
            compose.waitUntil(5_000) { page.get() == expected }
        }
        settled("middle-page")
    }

    private fun openPanel() {
        // Open the real reader bar by its middle tap zone, then the production Aa action.
        if (compose.onAllNodesWithTag("reader_formatting_open").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("formatting-reader").performTouchInput { click(Offset(width * .5f, height * .85f)) }
        }
        clickTag("reader_formatting_open")
        compose.onNodeWithTag("reader_formatting_panel").assertIsDisplayed()
        activeSection = "text"
        settled("panel-visible")
        if (!capturedInitialPanel) {
            capture("initial-panel-fixture")
            capturedInitialPanel = true
        }
    }

    private fun section(name: String) { clickTag("reader_formatting_section_$name"); activeSection = name }
    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        val isControl = tag.startsWith("reader_formatting_font_") && tag != "reader_formatting_font_list" ||
            tag.endsWith("_increase") || tag.endsWith("_decrease") || tag.endsWith("_value") || tag.startsWith("reader_formatting_image_")
        if (isControl) {
            val container = if (activeSection == "fonts") "reader_formatting_font_list" else "reader_formatting_${activeSection}_controls"
            compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo()
            assertFullyInside(tag, container)
        }
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun clickTag(tag: String) {
        scrollToTag(tag).performClick()
        advanceComposeDraftClock()
    }

    private fun advanceComposeDraftClock() {
        compose.waitForIdle()
        // Reader drafts are debounced for 70 ms in LaunchedEffect. Wall-clock WebView waits
        // cannot advance Compose's test scheduler; otherwise each probe can read the old draft.
        compose.mainClock.advanceTimeBy(120)
        compose.waitForIdle()
    }
    private fun assertFullyInside(tag: String, container: String) {
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag(container).getUnclippedBoundsInRoot()
        assertTrue("$tag must remain fully reachable inside $container: $bounds / $viewport",
            bounds.right.value > bounds.left.value && bounds.bottom.value > bounds.top.value && bounds.left.value >= viewport.left.value - 1f && bounds.right.value <= viewport.right.value + 1f &&
                bounds.top.value >= viewport.top.value - 1f && bounds.bottom.value <= viewport.bottom.value + 1f)
    }
    private fun imeVisible(): Boolean {
        var result = false
        instrumentation.runOnMainSync {
            result = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return result
    }
    private fun step(name: String, increase: Boolean, times: Int = 1) {
        repeat(times) { clickTag("reader_formatting_${name}_${if (increase) "increase" else "decrease"}") }
        settled("control-$name")
    }

    private fun setMaximum(name: String, expectedMaximum: Float) {
        val control = scrollToTag("reader_formatting_${name}_value")
        assertEquals("The advertised formatting endpoint remains available", expectedMaximum,
            control.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive, .001f)
        control.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue("The real accessible control must accept its maximum", setProgress(expectedMaximum))
        }
        advanceComposeDraftClock()
        assertEquals(expectedMaximum,
            control.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
    }

    private fun verifyMaximumFormattingInShortViewport(persisted: ReaderFormatting) {
        openPanel()
        section("text")
        setMaximum("font_size", 48f)
        setMaximum("line_height", 2.5f)
        section("layout")
        for (edge in listOf("left", "right", "top", "bottom")) setMaximum("margin_$edge", 96f)
        val fullWidth = snapshot().getDouble("viewportWidth")
        compose.runOnIdle { viewportFraction.value = .55f }
        compose.waitUntil(10_000) { snapshot().getDouble("viewportWidth") < fullWidth * .6 }
        val extreme = settled("maximum-formatting-short-viewport")
        assertEquals(48.0, extreme.getDouble("specifiedFontSize"), .1)
        assertEquals(48.0 * context.resources.configuration.fontScale, extreme.getDouble("fontSize"), .1)
        assertEquals(2.5, extreme.getDouble("lineHeight") / extreme.getDouble("fontSize"), .02)
        assertTrue("Legitimate large-format articles must not be truncated at 500 columns", extreme.getInt("totalPages") > 500)
        // Use actual WebView font metrics, not the requested CSS size. Android fontScale=1.6
        // makes the 48 px setting render at 76.8 px; requested-only minimums clip a full line.
        val requiredLine = maxOf(extreme.getDouble("lineHeight"), extreme.getDouble("fontSize") * 1.4) + 8
        assertTrue("The fixture must have room for one actual requested line", extreme.getDouble("viewportHeight") >= requiredLine)
        assertTrue("Effective top/bottom margins must leave an entire scaled line plus leading",
            extreme.getDouble("frameHeight") >= requiredLine - .5)
        assertTrue("Effective side margins must leave at least one full scaled CJK glyph plus spacing",
            extreme.getDouble("frameWidth") >= extreme.getDouble("fontSize") * 1.5 - .5)
        for (edge in listOf("Left", "Right", "Top", "Bottom")) {
            assertTrue("Effective margin cannot exceed the 96 px requested maximum",
                extreme.getDouble("margin$edge") in 0.0..96.1)
        }
        assertAnchor("maximum-formatting-anchor")
        val glyph = extreme.getJSONObject("anchorGlyph")
        assertTrue("The original anchored glyph must fit wholly after the left inset",
            glyph.getDouble("left") >= extreme.getDouble("marginLeft") - 1.25)
        assertTrue("The original anchored glyph must fit wholly before the right inset",
            glyph.getDouble("right") <= extreme.getDouble("viewportWidth") - extreme.getDouble("marginRight") + 1.25)
        assertTrue("The original anchored glyph must fit wholly below the top inset",
            glyph.getDouble("top") >= extreme.getDouble("marginTop") - 1.25)
        assertTrue("The original anchored glyph must fit wholly above the bottom inset",
            glyph.getDouble("bottom") <= extreme.getDouble("viewportHeight") - extreme.getDouble("marginBottom") + 1.25)
        assertEquals("Maximum previews cannot persist temporary effective insets", persisted, readPersisted())
        capture("maximum-formatting-fixture")
        compose.runOnIdle { viewportFraction.value = 1f }
        compose.waitUntil(10_000) { abs(snapshot().getDouble("viewportWidth") - fullWidth) < 1 }
        clickTag("reader_formatting_cancel")
        panelClosed()
        assertStyleMatches(persisted, settled("cancel-maximum-formatting"))
        assertAnchor("cancel-maximum-formatting-anchor")
    }
    private fun panelClosed() { compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader_formatting_panel").fetchSemanticsNodes().isEmpty() }; settleBarrier() }
    private fun dispatchVolume(code: Int) = compose.runOnIdle {
        assertTrue(dispatchEInkVolumeKey(true, code, KeyEvent.ACTION_DOWN, 0))
        assertTrue(dispatchEInkVolumeKey(true, code, KeyEvent.ACTION_UP, 0))
    }

    private fun rememberAnchor() {
        js("window.__formattingExpectedAnchor = InkReadFormatting.currentAnchor(); window.__formattingExpectedDocument = InkReadFormatting.documentId; true")
        assertAnchor("anchor-saved")
    }

    private fun assertAnchor(label: String) {
        val value = settled(label)
        assertTrue("$label must retain the original JS window, not reload HTML", value.getBoolean("documentSentinel"))
        assertTrue("$label must retain the same article document ID", value.getBoolean("sameDocument"))
        assertTrue("$label must retain exact logical text-node + UTF16 offset: $value", value.getBoolean("sameAnchor"))
        assertTrue("$label must keep the actual anchored source glyph visible, not just report an unchanged anchor", value.getBoolean("anchorVisible"))
        assertFrame(value)
    }

    private fun settled(label: String): JSONObject {
        settleBarrier()
        compose.waitUntil(10_000) {
            snapshot().let { it.optBoolean("ready") && it.optInt("currentPage") + 1 == page.get() &&
                it.optInt("totalPages") == count.get() && abs(it.optDouble("stride") - it.optDouble("viewportWidth")) < 1 }
        }
        return snapshot(label)
    }

    private fun settleBarrier() {
        advanceComposeDraftClock()
        // A visual callback alone precedes a delayed repagination. Two actual timer/rAF turns
        // also let a mistakenly forwarded native volume event become observable.
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { webView().postDelayed({
            webView().postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { done.countDown() }
            })
        }, 300) }
        assertTrue("WebView must commit its live layout", done.await(10, TimeUnit.SECONDS))
        compose.waitForIdle()
    }

    private fun verifyMiddlePageFrames(label: String) {
        val originalCount = count.get()
        repeat(3) { index ->
            val expected = page.get() + 1
            dispatchVolume(KeyEvent.KEYCODE_VOLUME_DOWN)
            compose.waitUntil(5_000) { page.get() == expected }
            assertFrame(settled("$label-middle-${index + 1}"))
        }
        repeat(3) {
            val expected = page.get() - 1
            dispatchVolume(KeyEvent.KEYCODE_VOLUME_UP)
            compose.waitUntil(5_000) { page.get() == expected }
            assertFrame(settled("$label-return-${it + 1}"))
        }
        assertEquals("Page count is invariant during pure page turns", originalCount, count.get())
    }

    private fun assertFrame(value: JSONObject) {
        val tolerance = 1.25 // CSS px, as in the independent alignment regression suite.
        assertTrue("No root horizontal scrolling", abs(value.getDouble("scrollX")) <= tolerance)
        assertTrue("No root vertical scrolling", abs(value.getDouble("scrollY")) <= tolerance)
        assertEquals(value.getDouble("viewportWidth"), value.getDouble("stride"), tolerance)
        val left = value.getDouble("marginLeft")
        val right = value.getDouble("viewportWidth") - value.getDouble("marginRight")
        val top = value.getDouble("marginTop")
        val bottom = value.getDouble("viewportHeight") - value.getDouble("marginBottom")
        assertEquals("Inset frame width uses both independent margins", right - left, value.getDouble("frameWidth"), tolerance)
        assertEquals("Every page reserves both vertical margins", bottom - top, value.getDouble("frameHeight"), tolerance)
        val fragments = value.getJSONArray("fragments")
        assertTrue("Middle page contains actual paragraph fragments", fragments.length() > 0)
        for (i in 0 until fragments.length()) {
            val rect = fragments.getJSONObject(i)
            assertEquals("Paragraph column left margin", left, rect.getDouble("left"), tolerance)
            assertEquals("Paragraph column right margin", right, rect.getDouble("right"), tolerance)
            assertTrue("Paragraph cannot draw in the top margin", rect.getDouble("top") >= top - tolerance)
            assertTrue("Paragraph cannot draw in the bottom margin", rect.getDouble("bottom") <= bottom + tolerance)
        }
        val images = value.getJSONArray("images")
        for (i in 0 until images.length()) {
            val rect = images.getJSONObject(i)
            assertEquals("Image is centered within the asymmetric content frame", left + right,
                rect.getDouble("left") + rect.getDouble("right"), tolerance * 2)
        }
    }

    private fun assertStyleMatches(expected: ReaderFormatting, actual: JSONObject) {
        assertEquals("Stored font size is applied as the exact CSS setting", expected.fontSize.toDouble(),
            actual.getDouble("specifiedFontSize"), .1)
        assertEquals("Android accessibility font scaling remains effective", expected.fontSize.toDouble() *
            context.resources.configuration.fontScale, actual.getDouble("fontSize"), .1)
        assertEquals(expected.lineHeight.toDouble(), actual.getDouble("lineHeight") / actual.getDouble("fontSize"), .02)
        assertEquals(expected.textWeight, actual.getInt("fontWeight"))
        assertEquals(expected.marginLeft.toDouble(), actual.getDouble("marginLeft"), .1)
        assertEquals(expected.marginRight.toDouble(), actual.getDouble("marginRight"), .1)
        assertEquals(expected.marginTop.toDouble(), actual.getDouble("marginTop"), .1)
        assertEquals(expected.marginBottom.toDouble(), actual.getDouble("marginBottom"), .1)
    }

    private fun assertScriptRanges(faces: JSONArray) {
        fun rangeFor(name: String) = (0 until faces.length()).map { faces.getJSONObject(it) }
            .first { it.getString("family").trim('"', '\'') == name }.getString("range")
        fun covers(ranges: String, point: Int) = ranges.split(',').any { token ->
            val parts = token.trim().removePrefix("U+").removePrefix("u+").split('-')
            val low = parts[0].replace('?', '0').toInt(16)
            val high = (parts.getOrNull(1) ?: parts[0]).replace('?', 'F').toInt(16)
            point in low..high
        }
        val latin = rangeFor("InkReadLatin")
        val cjk = rangeFor("InkReadCjk")
        assertTrue(covers(latin, 'A'.code)); assertFalse(covers(latin, '中'.code))
        assertTrue(covers(cjk, '中'.code)); assertFalse(covers(cjk, 'A'.code))
        assertTrue("CJK punctuation is routed with the CJK face", covers(cjk, '。'.code))
    }

    private fun readPersisted(): ReaderFormatting = runBlocking { ReaderFormattingPreference.fromPreferences(context.dataStore.data.first()) }
    private fun awaitPersisted(predicate: (ReaderFormatting) -> Boolean): ReaderFormatting {
        compose.waitUntil(10_000) { predicate(readPersisted()) }
        return readPersisted()
    }
    private fun awaitLateRequest() = assertTrue("Ordinary late image must actually request bytes", lateRequested.await(10, TimeUnit.SECONDS))

    private fun seedPlatformFontFixtures() {
        val cjk = File("/system/fonts").listFiles().orEmpty().firstOrNull {
            it.name.contains("NotoSansCJK", ignoreCase = true) && it.extension.lowercase() in setOf("ttc", "otf", "ttf")
        } ?: error("This native fixture requires an Android system CJK font; do not silently substitute a Latin font")
        listOf(File("/system/fonts/NotoSerif-Regular.ttf") to EInkEnglishFontPreference.fontFileNames[1],
            cjk to EInkChineseFontPreference.fontFileNames[2]).forEach { (source, name) ->
            assertTrue("Platform font fixture must exist: $source", source.isFile)
            val destination = File(context.filesDir, "fonts/${requireNotNull(name)}")
            fontBackups[destination] = destination.takeIf { it.isFile }?.readBytes()
            destination.parentFile!!.mkdirs()
            destination.writeBytes(standaloneTestFont(source.readBytes()))
        }
    }

    private fun fontFileNames() = File(context.filesDir, "fonts").listFiles().orEmpty().map { it.name }.sorted()

    private fun installOrdinaryResources() {
        val jpeg = instrumentation.context.assets.open("nasa-earthrise.jpg").use { it.readBytes() }
        instrumentation.runOnMainSync {
            val view = webView()
            val production = view.webViewClient
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = production.shouldOverrideUrlLoading(view, url)
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = production.shouldOverrideUrlLoading(view, request)
                override fun onPageFinished(view: WebView?, url: String?) = production.onPageFinished(view, url)
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url ?: return production.shouldInterceptRequest(view, request)
                    if (request.isForMainFrame) htmlRequests.incrementAndGet()
                    if (url.host == "formatting-fixture.invalid") {
                        if (url.path == "/late.jpg") {
                            lateRequested.countDown()
                            check(releaseLate.await(120, TimeUnit.SECONDS)) { "Test never released its ordinary late photo" }
                        }
                        return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(jpeg))
                    }
                    if (url.host == "inkread.local" && url.path.orEmpty().startsWith("/fonts/")) {
                        if (delayFonts) { fontRequested.countDown(); check(releaseFont.await(30, TimeUnit.SECONDS)) }
                    } else if (url.scheme == "http" || url.scheme == "https") {
                        if (url.host != "inkread.local") synchronized(remoteRequests) { remoteRequests += url.toString() }
                    }
                    return production.shouldInterceptRequest(view, request)
                }
            }
        }
    }

    private fun snapshot(label: String? = null): JSONObject {
        val value = JSONObject(js(PROBE) as String)
        if (label != null) { samples.put(value.put("label", label)); writeReport() }
        return value
    }

    private fun js(script: String): Any {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { webView().evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("Production WebView JavaScript callback", done.await(10, TimeUnit.SECONDS))
        return JSONTokener(result.get()).nextValue()
    }

    private fun webView(): WebView {
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return requireNotNull(find(compose.activity.window.decorView))
    }

    private fun capture(label: String) {
        settleBarrier()
        NativePageDensityCapture.capture(compose, "$reportName-$label", "", recordDensity = false,
            contentBoundsOnScreen = {
                var bounds: Rect? = null
                instrumentation.runOnMainSync {
                    val view = webView(); val origin = IntArray(2); view.getLocationOnScreen(origin)
                    bounds = Rect(origin[0] + view.width * .1f, origin[1] + view.height * .2f,
                        origin[0] + view.width * .9f, origin[1] + view.height * .8f)
                }
                requireNotNull(bounds)
            }, beforeCaptureFrame = ::settleBarrier)
    }

    private fun outputDirectory(): File = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?.takeIf { it.isNotBlank() }?.let(::File) ?: File(context.getExternalFilesDir(null), "reader-formatting"))
        .apply { check(isDirectory || mkdirs()) }

    private fun writeFinalDiagnostics() {
        val directory = outputDirectory()
        runCatching { snapshot("diagnostic-final-state") }
        runCatching {
            File(directory, "$reportName-final-semantics.txt").writeText(
                compose.onRoot(useUnmergedTree = true).printToString())
        }
        runCatching {
            File(directory, "$reportName-final-preferences.txt").writeText(readPersisted().toString())
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                File(directory, "$reportName-diagnostic-final-state.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }

    private fun writeReport() {
        File(outputDirectory(), "$reportName.json").writeText(JSONObject().put("schema", 1)
            .put("densityDpi", context.resources.displayMetrics.densityDpi)
            .put("systemFontScale", context.resources.configuration.fontScale)
            .put("nativeWidth", context.resources.displayMetrics.widthPixels)
            .put("pid", android.os.Process.myPid()).put("htmlRequests", htmlRequests.get())
            .put("successfulRetrySnapshots", successfulRetrySnapshots.get())
            .put("remoteRequests", JSONArray(synchronized(remoteRequests) { remoteRequests.toList() }))
            .put("fontFixture", "Test-only cached Android NotoSerif and standalone NotoSansCJK face extracted from platform TTC, not production catalog binaries")
            .put("samples", samples).toString(2))
    }

    private fun fixture(): String = buildString {
        repeat(100) { index ->
            append("<p class=\"format-text\">第 ${index + 1} 段：午后的阳光落在纸页上。读者慢慢翻页，欣赏照片，也留意每句话的意思。 ")
            append("Paragraph ${index + 1}: The quiet library welcomes thoughtful readers. Mixed Chinese 中文 and Latin letters stay readable as the page changes. ")
            append("<code>reader.next_page()</code> 🌿 Every line has a place.</p>")
            if (index == 0) append("<img id=\"ordinary-image\" src=\"$IMAGE_ORIGIN/ordinary.jpg\" alt=\"Ordinary Earthrise photograph\">")
            if (index == 1) append("<img id=\"late-image\" src=\"$IMAGE_ORIGIN/late.jpg\" alt=\"Later Earthrise photograph\">")
        }
    }

    companion object {
        private const val IMAGE_ORIGIN = "https://formatting-fixture.invalid"
        private const val PERSISTENCE_MARKER = "reader-formatting-test-process.json"
        private val PROBE = """
            (function() {
                var frame=document.getElementById('eink-content');
                if(!frame || !window.InkReadFormatting) return JSON.stringify({ready:false});
                var c=getComputedStyle(frame), b=getComputedStyle(document.body), w=document.documentElement.getBoundingClientRect().width, h=innerHeight;
                function visible(r){return r.width>0&&r.height>0&&r.right>1&&r.left<w-1&&r.bottom>0&&r.top<h;}
                function rect(r){return {left:r.left,right:r.right,top:r.top,bottom:r.bottom};}
                var anchor=InkReadFormatting.currentAnchor(), expected=window.__formattingExpectedAnchor;
                var nodes=[], walker=document.createTreeWalker(frame,NodeFilter.SHOW_TEXT), n;
                while(n=walker.nextNode()) if(n.textContent.trim()&&!/^(STYLE|SCRIPT|NOSCRIPT)$/.test(n.parentElement.tagName)) nodes.push(n);
                var anchorVisible=false, anchorText='', anchorGlyph=null;
                if(expected&&nodes[expected.node]){
                    n=nodes[expected.node]; var r=document.createRange(), offset=Math.min(expected.offset,n.length-1);
                    r.setStart(n,offset);r.setEnd(n,offset+1);anchorText=n.textContent.slice(Math.max(0,offset-10),offset+30);
                    var glyphs=Array.from(r.getClientRects()).filter(visible);anchorVisible=glyphs.length>0;
                    anchorGlyph=glyphs.length?rect(glyphs[0]):null;
                }
                var p=frame.querySelector('p.format-text'), pc=getComputedStyle(p||frame), code=frame.querySelector('code'), image=document.getElementById('ordinary-image'), late=document.getElementById('late-image');
                var faces=Array.from(document.fonts).map(function(f){return {family:f.family,status:f.status,range:f.unicodeRange};});
                function loaded(name,text){return faces.some(function(f){return f.family.replace(/["']/g,'')===name&&f.status==='loaded';})&&document.fonts.check('24px '+name,text);}
                function paint(family,text){var canvas=document.createElement('canvas');canvas.width=700;canvas.height=80;var ctx=canvas.getContext('2d');ctx.font='32px '+family;ctx.fillText(text,4,50);return canvas.toDataURL();}
                var latin='Reading Wavy 123', cjk='中文书页。霞鹜文楷', latinLoaded=loaded('InkReadLatin',latin), cjkLoaded=loaded('InkReadCjk',cjk);
                var ctx=document.createElement('canvas').getContext('2d');ctx.font='24px '+pc.fontFamily;
                return JSON.stringify({ready:_didFinishInitialPagination,currentPage:_currentPage,totalPages:_totalPages,stride:_pageStride,
                    viewportWidth:w,viewportHeight:h,frameWidth:parseFloat(c.width),frameHeight:parseFloat(c.height),
                    marginLeft:parseFloat(c.left),marginRight:w-parseFloat(c.left)-parseFloat(c.width),marginTop:parseFloat(c.top),marginBottom:h-parseFloat(c.top)-parseFloat(c.height),
                    fontSize:parseFloat(pc.fontSize),specifiedFontSize:parseFloat(document.documentElement.style.getPropertyValue('--reader-font-size')),
                    lineHeight:parseFloat(pc.lineHeight),fontWeight:parseInt(pc.fontWeight),color:pc.color,fontFamily:pc.fontFamily,
                    textFilter:pc.filter,frameFilter:c.filter,bodyFilter:b.filter,imageFilter:image?getComputedStyle(image).filter:'',scrollX:scrollX,scrollY:scrollY,
                    documentSentinel:!!window.__formattingDocumentSentinel,sameDocument:!window.__formattingExpectedDocument||window.__formattingExpectedDocument===InkReadFormatting.documentId,
                    sameAnchor:!expected||(anchor&&anchor.node===expected.node&&anchor.offset===expected.offset),anchorVisible:anchorVisible,anchorGlyph:anchorGlyph,anchor:anchor,expectedAnchor:expected||null,anchorText:anchorText,
                    fragments:Array.from(frame.querySelectorAll('p.format-text')).flatMap(function(p){return Array.from(p.getClientRects()).filter(visible).map(rect);}),
                    images:Array.from(document.images).filter(function(i){return i.naturalWidth>0&&visible(i.getBoundingClientRect());}).map(function(i){return rect(i.getBoundingClientRect());}),
                    lateImage:{complete:late?late.complete:false,naturalWidth:late?late.naturalWidth:0},faces:faces,fontCss:document.getElementById('eink-font-faces').textContent,latinFaceLoaded:latinLoaded,cjkFaceLoaded:cjkLoaded,
                    latinPaintMatchesFace:latinLoaded&&paint(pc.fontFamily,latin)===paint('InkReadLatin',latin),
                    cjkPaintMatchesFace:cjkLoaded&&paint(pc.fontFamily,cjk)===paint('InkReadCjk',cjk),
                    latinPaintDiffersFromSans:latinLoaded&&paint('InkReadLatin',latin)!==paint('sans-serif',latin),
                    emojiWidth:ctx.measureText('🌿').width,codeWidth:code?code.getBoundingClientRect().width:0,codeFont:code?getComputedStyle(code).fontFamily:''});
            })()
        """.trimIndent()
    }
}

/**
 * Android ships CJK in a TrueType Collection; catalog files are standalone SFNT fonts. Extract
 * one genuine platform face into that same container rather than testing WebView TTC support.
 * Table offsets in a TTC are absolute, so slicing from its first face header is not sufficient.
 */
private fun standaloneTestFont(source: ByteArray): ByteArray {
    fun readInt(bytes: ByteArray, offset: Int): Int = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
    require(source.size >= 12) { "Truncated platform font fixture" }
    val collection = readInt(source, 0) == 0x74746366 // 'ttcf'.
    if (collection) {
        require(source.size >= 16 && readInt(source, 4) in setOf(0x00010000, 0x00020000))
        val count = readInt(source, 8)
        require(count > 0 && 12L + count * 4L <= source.size)
    }
    val face = if (collection) readInt(source, 12) else 0
    require(face >= 0 && face.toLong() + 12 <= source.size)
    require(readInt(source, face) in setOf(0x00010000, 0x4f54544f)) { "Unsupported platform SFNT fixture header" }
    val tables = java.nio.ByteBuffer.wrap(source, face + 4, 2).short.toInt() and 0xffff
    require(tables in 1..512 && face + 12 + tables * 16 <= source.size)
    val offsets = IntArray(tables)
    val lengths = IntArray(tables)
    var total = 12 + tables * 16
    repeat(tables) { index ->
        val record = face + 12 + index * 16
        offsets[index] = readInt(source, record + 8)
        lengths[index] = readInt(source, record + 12)
        require(offsets[index] >= 0 && lengths[index] >= 0 && offsets[index].toLong() + lengths[index] <= source.size)
        total = Math.addExact(total, (Math.addExact(lengths[index], 3) / 4) * 4)
    }
    if (!collection) return source
    val result = ByteArray(total)
    source.copyInto(result, 0, face, face + 12 + tables * 16)
    fun writeInt(offset: Int, value: Int) { java.nio.ByteBuffer.wrap(result, offset, 4).putInt(value) }
    fun checksum(offset: Int, length: Int): Long {
        var sum = 0L
        for (index in offset until offset + length step 4) sum = (sum + (readInt(result, index).toLong() and 0xffffffffL)) and 0xffffffffL
        return sum
    }
    var destination = 12 + tables * 16
    var head = -1
    repeat(tables) { index ->
        val record = 12 + index * 16
        val paddedLength = (lengths[index] + 3) / 4 * 4
        source.copyInto(result, destination, offsets[index], offsets[index] + lengths[index])
        if (readInt(result, record) == 0x68656164) { // 'head': adjustment is zero for its table checksum.
            require(lengths[index] >= 12)
            head = destination
            writeInt(head + 8, 0)
        }
        writeInt(record + 8, destination)
        writeInt(record + 4, checksum(destination, paddedLength).toInt())
        destination += paddedLength
    }
    require(head >= 0)
    writeInt(head + 8, (0xb1b0afbaL - checksum(0, result.size)).toInt())
    return result
}
