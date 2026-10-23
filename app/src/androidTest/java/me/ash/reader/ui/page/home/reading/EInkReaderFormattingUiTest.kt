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
import androidx.compose.ui.platform.ViewRootForTest
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
    private val toolbarWidthFraction = mutableStateOf(1f)
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

    @Test fun independentScriptSizesPreviewWithoutChangingTextNodesOrLogicalAnchor() {
        reportName = "reader-formatting-script-sizes"
        releaseLate.countDown()
        startReader()
        goToMiddlePage()
        rememberAnchor()
        val textBefore = js("document.getElementById('eink-content').textContent")
        val runsBefore = js("document.querySelectorAll('.eink-script-cjk').length")
        fun renderedSizes(): JSONObject = JSONObject(js("""(function(){
            var p=document.querySelector('p.format-text'),c=p.querySelector('.eink-script-cjk');
            function width(n,i){var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+1);return r.getBoundingClientRect().width;}
            var walker=document.createTreeWalker(p,NodeFilter.SHOW_TEXT),latin,offset,n;
            while((n=walker.nextNode())){var match=/[A-Za-z]/.exec(n.data);if(match){latin=n;offset=match.index;break;}}
            return JSON.stringify({english:parseFloat(getComputedStyle(p).fontSize),
                chinese:parseFloat(getComputedStyle(c).fontSize),englishGlyph:width(latin,offset),chineseGlyph:width(c.firstChild,0)});
        })()""".trimIndent()) as String)
        val initialGlyphs = renderedSizes()
        fun assertSizes(english: Int, chinese: Int) {
            val scale = context.resources.configuration.fontScale.toDouble()
            val sizes = renderedSizes()
            assertEquals(english * scale, sizes.getDouble("english"), .1)
            assertEquals(chinese * scale, sizes.getDouble("chinese"), .1)
            // Actual glyph boxes, not only computed styles: display:contents must
            // still transmit the independent inherited size to native text layout.
            assertEquals(initialGlyphs.getDouble("englishGlyph") * english / 18.0,
                sizes.getDouble("englishGlyph"), .75)
            assertEquals(initialGlyphs.getDouble("chineseGlyph") * chinese / 18.0,
                sizes.getDouble("chineseGlyph"), .75)
            assertEquals(textBefore, js("document.getElementById('eink-content').textContent"))
            assertEquals(runsBefore, js("document.querySelectorAll('.eink-script-cjk').length"))
        }
        openPanel()
        section("text")
        step("chinese_font_size", increase = true, times = 6)
        assertSizes(18, 24)
        assertEquals(ReaderFormatting.Default, readPersisted())
        assertAnchor("chinese-size-preview-anchor")
        step("font_size", increase = true, times = 2)
        assertSizes(20, 24)
        assertAnchor("english-size-preview-anchor")
        setControlValue("font_size", 12f)
        setControlValue("chinese_font_size", 48f)
        settled("large-chinese-small-english")
        assertSizes(12, 48)
        assertAnchor("large-chinese-anchor")
        setControlValue("font_size", 48f)
        setControlValue("chinese_font_size", 12f)
        settled("large-english-small-chinese")
        assertSizes(48, 12)
        assertAnchor("large-english-anchor")
        setControlValue("font_size", 20f)
        setControlValue("chinese_font_size", 24f)
        settled("restore-independent-draft")
        clickTag("reader_formatting_apply")
        panelClosed()
        val applied = awaitPersisted { it.fontSize == 20 && it.chineseFontSize == 24 }
        assertSizes(20, 24)
        openPanel()
        clickTag("reader_formatting_reset")
        settled("script-size-reset")
        assertSizes(18, 18)
        clickTag("reader_formatting_cancel")
        panelClosed()
        assertSizes(20, 24)
        assertEquals(applied, readPersisted())
        assertEquals("Live size preview must never reload article HTML", 1, htmlRequests.get())
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
        verifyPanelScrollReachability()
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
        // Repeat actual changed-value writes after reopening. A persisted value alone is not
        // success: each Apply must dismiss the panel and leave the live article at its anchor.
        repeat(2) { index ->
            openPanel()
            section("text")
            step("font_size", increase = index == 0)
            val expected = if (index == 0) persisted.copy(fontSize = persisted.fontSize + 1,
                marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0) else persisted
            section("layout")
            listOf("left" to expected.marginLeft, "right" to expected.marginRight,
                "top" to expected.marginTop, "bottom" to expected.marginBottom).forEach { (edge, value) ->
                setControlValue("margin_$edge", value.toFloat())
            }
            if (index == 0) capture("zero-margin-layout-fixture")
            clickTag("reader_formatting_apply")
            panelClosed()
            assertEquals(expected, awaitPersisted { it == expected })
            assertStyleMatches(expected, settled("repeated-apply-${index + 1}"))
            assertAnchor("repeated-apply-${index + 1}-anchor")
            if (index == 0) {
                assertFormattingActionOutsideArticle()
                clickTag("reader_controls_toggle")
                settleBarrier()
                compose.onNodeWithTag("reader_formatting_open").assertDoesNotExist()
                compose.onNodeWithTag("reader_controls_toggle").assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Hidden"))
                assertNoPermanentReaderSpacer()
                val zeroMargins = settled("zero-margin-controls-hidden")
                assertStyleMatches(expected, zeroMargins)
                assertAnchor("zero-margin-controls-hidden-anchor")
                assertFullAnchorInk(zeroMargins)
                capture("zero-margin-article-fixture")
                revealReaderControls()
                assertAnchor("zero-margin-controls-revealed-anchor")
            }
        }
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
        verifyNarrowToolbar()
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
        capture("selected-fonts-tab-platform-fixture")
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
        assertFormattingActionOutsideArticle()
        pressBack()
        compose.onNodeWithTag("reader_formatting_open").assertDoesNotExist()
        assertEquals("Second Back hides restored reading controls, not the article", 0, readerBacks.get())
        assertAnchor("back-hides-toolbar")
        val beforeTurn = page.get()
        dispatchVolume(KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.waitUntil(5_000) { page.get() == beforeTurn + 1 }
        compose.onNodeWithTag("formatting-reader").performTouchInput {
            swipe(Offset(width * .8f, height * .5f), Offset(width * .2f, height * .5f), 400)
        }
        compose.waitUntil(5_000) { articleChanges.get() == 1 }
        pressBack()
        compose.onNodeWithTag("formatting-reader-closed").assertIsDisplayed()
        assertEquals("Third Back exits the reader exactly once", 1, readerBacks.get())
        // Dispose/reopen the real composable with preferences from DataStore, not a draft copy.
        // Its new WebView needs the same test-only ordinary photo interceptor as the first;
        // otherwise the offline .invalid photos fail and the tablet fixture has only 7 pages.
        compose.runOnIdle {
            content.value = "<p>Preparing an ordinary reading article.</p>"
            visible.value = true; page.set(0); count.set(0)
        }
        compose.waitUntil(15_000) { count.get() > 0 }
        installOrdinaryResources()
        compose.runOnIdle { page.set(0); count.set(0); content.value = fixture() }
        awaitReader()
        compose.waitUntil(15_000) {
            js("document.images.length === 2 && Array.from(document.images).every(function(i){return i.complete && i.naturalWidth > 0;})") == true
        }
        assertEquals("A reopened article loads once into its new WebView", 2, htmlRequests.get())
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
            assertEquals(expected.getInt("chineseFontSize"), readPersisted().chineseFontSize)
            assertEquals(expected.getInt("marginLeft"), readPersisted().marginLeft)
            assertStyleMatches(readPersisted(), loaded)
            openPanel()
            clickTag("reader_formatting_cancel")
            panelClosed()
            assertEquals(expected.getInt("fontSize"), readPersisted().fontSize)
            assertEquals(expected.getInt("chineseFontSize"), readPersisted().chineseFontSize)
            check(File(context.filesDir, PERSISTENCE_MARKER).delete())
        } else {
            openPanel()
            section("text")
            step("font_size", increase = true)
            step("chinese_font_size", increase = true, times = 3)
            section("layout")
            step("margin_left", increase = true, times = 2)
            clickTag("reader_formatting_apply")
            panelClosed()
            val saved = awaitPersisted { it.fontSize > ReaderFormatting.Default.fontSize && it.marginLeft > 24 }
            assertStyleMatches(saved, settled("persisted-before-restart"))
            if (phase == "seed") File(context.filesDir, PERSISTENCE_MARKER).writeText(JSONObject()
                .put("pid", android.os.Process.myPid()).put("fontSize", saved.fontSize).put("chineseFontSize", saved.chineseFontSize).put("marginLeft", saved.marginLeft).toString())
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
                                modifier = Modifier.fillMaxWidth(viewportFraction.value * toolbarWidthFraction.value)
                                    .fillMaxHeight(viewportFraction.value).testTag("formatting-reader"),
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
        revealReaderControls()
        clickTag("reader_formatting_open")
        compose.onNodeWithTag("reader_formatting_panel").assertIsDisplayed()
        activeSection = "text"
        settled("panel-visible")
        assertFooterReachable()
        if (!capturedInitialPanel) {
            capture("initial-panel-fixture")
            capturedInitialPanel = true
        }
    }

    private fun revealReaderControls() {
        val toggle = compose.onNodeWithTag("reader_controls_toggle")
            .assertContentDescriptionEquals("Reading controls").assertHasClickAction()
        if (compose.onAllNodesWithTag("reader_formatting_open").fetchSemanticsNodes().isEmpty()) {
            toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Hidden"))
                .performTouchInput { click(center) }
            settleBarrier()
        }
        compose.onNodeWithTag("reader_controls_toggle").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Shown"))
        assertFormattingActionOutsideArticle()
    }

    private fun assertFormattingActionOutsideArticle() {
        val action = compose.onNodeWithTag("reader_formatting_open").assertIsDisplayed().assertIsEnabled()
        assertTouchTarget("reader_formatting_open")
        assertFullyInside("reader_formatting_open", "formatting-reader")
        assertOutsideArticle(action, "Aa")
    }

    private fun assertOutsideArticle(action: SemanticsNodeInteraction, label: String) {
        val semantics = action.fetchSemanticsNode()
        instrumentation.runOnMainSync {
            val rootOrigin = IntArray(2)
            (semantics.root as ViewRootForTest).view.getLocationOnScreen(rootOrigin)
            val articleOrigin = IntArray(2)
            val article = webView()
            article.getLocationOnScreen(articleOrigin)
            assertTrue("$label must be in the on-demand toolbar, never over the article, even at zero margins",
                semantics.boundsInRoot.top + rootOrigin[1] >= articleOrigin[1] + article.height - 1)
        }
    }

    private fun assertNoPermanentReaderSpacer() {
        val reader = compose.onNodeWithTag("formatting-reader").fetchSemanticsNode()
        instrumentation.runOnMainSync {
            val rootOrigin = IntArray(2)
            (reader.root as ViewRootForTest).view.getLocationOnScreen(rootOrigin)
            val article = webView()
            val origin = IntArray(2)
            article.getLocationOnScreen(origin)
            assertEquals("Hidden controls must not leave an article header spacer",
                reader.boundsInRoot.top + rootOrigin[1], origin[1].toFloat(), 1f)
            assertEquals("Hidden controls must not reserve side space", reader.boundsInRoot.width, article.width.toFloat(), 1f)
            assertEquals("Only the existing 3 dp progress line remains below the article, not a hidden toolbar spacer",
                reader.boundsInRoot.height - 3f * context.resources.displayMetrics.density, article.height.toFloat(), 1f)
        }
    }

    /**
     * A Range includes font ascent/descent whitespace, which can overhang a zero-margin line.
     * Keep the normal box assertion. The only fallback is this fixture's leading 第, proven
     * complete against another real, fully visible 第 in the same native screenshot. No canvas
     * font substitution, guessed baseline, translation search or approximate mask matching.
     */
    @Suppress("DEPRECATION") // WebView.scale is its actual CSS-to-native scale, not rounded viewport width.
    private fun assertFullAnchorInk(value: JSONObject) {
        val glyph = value.getJSONObject("anchorGlyph")
        if (glyph.getDouble("left") >= -1.25 && glyph.getDouble("right") <= value.getDouble("viewportWidth") + 1.25 &&
            glyph.getDouble("top") >= -1.25 && glyph.getDouble("bottom") <= value.getDouble("viewportHeight") + 1.25) return
        settleBarrier()
        val evidence = JSONObject(js(INK_REFERENCE_PROBE) as String)
        evidence.put("label", "zero-margin-native-ink-proof")
        samples.put(evidence)
        writeReport()
        assertEquals("Only the fixture's known complete Han glyph can use the native ink oracle", "第", evidence.getString("character"))
        assertTrue("Canonical source must be the first character of an ordinary fixture paragraph", evidence.getBoolean("sourceValid"))
        assertTrue("Reference selection must use the live, settled fallback fonts", evidence.getBoolean("fontsReady"))
        val references = evidence.getJSONArray("references")
        assertTrue("A fully visible, identically styled source glyph is required, never skipped", references.length() > 0)
        val source = evidence.getJSONObject("source")
        val cssStyle = evidence.getJSONObject("style")
        var scale = 0.0
        val origin = IntArray(2)
        val clip = android.graphics.Rect()
        instrumentation.runOnMainSync {
            val view = webView()
            view.getLocationOnScreen(origin)
            scale = view.scale.toDouble()
            assertTrue("The actual WebView must have a visible native clip", view.getLocalVisibleRect(clip))
            clip.offset(origin[0], origin[1])
            assertTrue(clip.intersect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height))
        }
        assertTrue("CSS/native scale must be finite and positive", scale.isFinite() && scale > 0)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertTrue("Intersect the actual screenshot/window, not only layout bounds", clip.intersect(0, 0, bitmap.width, bitmap.height))
            File(outputDirectory(), "$reportName-zero-margin-native-ink-proof.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            fun pixelFloor(number: Double) = kotlin.math.floor(number + .0001).toInt() // Float serialization noise only.
            fun mapped(rect: JSONObject): android.graphics.Rect {
                val left = pixelFloor(origin[0] + rect.getDouble("left") * scale)
                val top = pixelFloor(origin[1] + rect.getDouble("top") * scale)
                // A fixed size is shared by source/reference; the extra pixel covers fractional edges.
                val width = kotlin.math.ceil((source.getDouble("right") - source.getDouble("left")) * scale).toInt() + 1
                val height = kotlin.math.ceil((source.getDouble("bottom") - source.getDouble("top")) * scale).toInt() + 1
                return android.graphics.Rect(left, top, left + width, top + height)
            }
            fun rectJson(rect: android.graphics.Rect) = JSONObject().put("left", rect.left).put("top", rect.top)
                .put("right", rect.right).put("bottom", rect.bottom)
            fun ink(x: Int, y: Int, threshold: Int, bounds: android.graphics.Rect): Boolean {
                if (!bounds.contains(x, y)) return false // System bars/out-of-window pixels cannot supply missing ink.
                val color = bitmap.getPixel(x, y)
                return maxOf(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)) < threshold
            }
            val sourcePixels = mapped(source)
            evidence.put("nativeScale", scale).put("nativeClip", rectJson(clip)).put("sourcePixels", rectJson(sourcePixels))
            val attempts = JSONArray()
            evidence.put("pixelComparisons", attempts)
            var matched = false
            for (index in 0 until references.length()) {
                val reference = references.getJSONObject(index)
                val referencePixels = mapped(reference)
                if (!clip.contains(referencePixels)) continue
                val width = referencePixels.width()
                val height = referencePixels.height()
                // Compare the ENTIRE Range-sized native rectangle, including any neighboring
                // line ink at low leading. Never segment a CJK glyph at a blank row: that could
                // silently discard a disconnected stroke. Different neighboring ink fails closed.
                val sourceCrop = sourcePixels
                val referenceCrop = referencePixels
                val em = cssStyle.getString("fontSize").removeSuffix("px").toDouble() * scale
                assertTrue("A complete native glyph rectangle must have substantial dimensions",
                    width >= em * .75 && height >= em * .8)
                val thresholds = listOf(64, 128, 224)
                val masks = thresholds.map { threshold -> BooleanArray(width * height) { offset ->
                    ink(referenceCrop.left + offset % width, referenceCrop.top + offset / width, threshold, clip)
                } }
                fun compare(bounds: android.graphics.Rect): JSONArray = JSONArray().also { comparisons ->
                    thresholds.forEachIndexed { maskIndex, threshold ->
                        val expected = masks[maskIndex]
                        var missing = 0; var extra = 0
                        expected.indices.forEach { offset ->
                            val actual = ink(sourceCrop.left + offset % width, sourceCrop.top + offset / width, threshold, bounds)
                            if (expected[offset] && !actual) missing++
                            if (!expected[offset] && actual) extra++
                        }
                        comparisons.put(JSONObject().put("threshold", threshold).put("referenceInk", expected.count { it })
                            .put("missingInk", missing).put("extraInk", extra).put("equal", missing == 0 && extra == 0))
                    }
                }
                val comparisons = compare(clip)
                val attempt = JSONObject().put("reference", reference).put("referenceCrop", rectJson(referenceCrop))
                    .put("sourceCrop", rectJson(sourceCrop)).put("comparisons", comparisons)
                attempts.put(attempt)
                writeReport()
                if ((0 until comparisons.length()).any { !comparisons.getJSONObject(it).getBoolean("equal") ||
                        comparisons.getJSONObject(it).getInt("referenceInk") < 100 }) continue
                // Crop genuine painted rows/columns out of the captured source pixels. These are
                // oracle negative controls, not claims that the app physically changed its clip.
                val negatives = JSONArray()
                attempt.put("croppedNativePixelNegatives", negatives)
                for (edge in listOf("top", "bottom", "left", "right")) {
                    val cropped = android.graphics.Rect(clip)
                    // Cut through a quarter of the darkest painted extent, not blank font-box
                    // padding or just a few adjacent-line pixels. Every threshold must lose ink.
                    val dark = masks.first().indices.filter { masks.first()[it] }
                    val left = dark.minOf { it % width }; val right = dark.maxOf { it % width } + 1
                    val top = dark.minOf { it / width }; val bottom = dark.maxOf { it / width } + 1
                    when (edge) {
                        "top" -> cropped.top = sourceCrop.top + top + (bottom - top) / 4
                        "bottom" -> cropped.bottom = sourceCrop.top + bottom - (bottom - top) / 4
                        "left" -> cropped.left = sourceCrop.left + left + (right - left) / 4
                        "right" -> cropped.right = sourceCrop.left + right - (right - left) / 4
                    }
                    val rejected = compare(cropped)
                    negatives.put(JSONObject().put("edge", edge).put("croppedClip", rectJson(cropped)).put("comparisons", rejected))
                    writeReport()
                    for (j in 0 until rejected.length()) {
                        val result = rejected.getJSONObject(j)
                        assertTrue("Cropping $edge must remove genuine painted pixels at every threshold", result.getInt("missingInk") > 0)
                        assertFalse("The same native mask comparator must reject $edge clipping", result.getBoolean("equal"))
                    }
                }
                matched = true
                break
            }
            writeReport()
            assertTrue("The full canonical glyph must exactly match a wholly visible native reference, not just contain some ink: $evidence", matched)
        } finally { bitmap.recycle() }
        assertAnchor("zero-margin-native-ink-anchor")
    }

    private fun verifyNarrowToolbar() {
        rememberAnchor()
        revealReaderControls()
        val full = compose.onNodeWithTag("formatting-reader").getUnclippedBoundsInRoot()
        compose.runOnIdle { toolbarWidthFraction.value = 240f / (full.right.value - full.left.value) }
        settled("narrow-toolbar")
        val row = compose.onNodeWithTag("reader_secondary_controls").getUnclippedBoundsInRoot()
        assertOutsideArticle(compose.onNodeWithTag("reader_secondary_controls"), "The complete secondary control row")
        assertEquals("The toolbar regression exercises an actual 240 dp window", 240f, row.right.value - row.left.value, .5f)
        val controls = listOf(
            compose.onNodeWithContentDescription(context.getString(me.ash.reader.R.string.back)),
            compose.onNodeWithText("⏮"), compose.onNodeWithText("A↓"), compose.onNodeWithText("A↑"),
            compose.onNodeWithTag("reader_formatting_open"), compose.onNodeWithText("⏭"))
        val bounds = controls.mapIndexed { index, control ->
            control.assertIsDisplayed().assertIsEnabled()
            assertTouchTarget(control, "secondary toolbar control $index")
            control.getUnclippedBoundsInRoot().also { b ->
                assertTrue("All six toolbar controls must remain fully inside the wrapping row",
                    b.left.value >= row.left.value - .5f && b.right.value <= row.right.value + .5f &&
                        b.top.value >= row.top.value - .5f && b.bottom.value <= row.bottom.value + .5f)
            }
        }
        for (first in bounds.indices) for (second in first + 1 until bounds.size) {
            val a = bounds[first]; val b = bounds[second]
            assertTrue("Toolbar touch targets must not overlap after wrapping",
                a.right.value <= b.left.value + .5f || b.right.value <= a.left.value + .5f ||
                    a.bottom.value <= b.top.value + .5f || b.bottom.value <= a.top.value + .5f)
        }
        assertTrue("Six 48 dp controls must wrap rather than shrink in a 240 dp row", bounds.last().top.value > bounds.first().top.value)
        assertFormattingActionOutsideArticle()
        val currentPage = page.get()
        controls.last().performTouchInput { click(center) }
        settleBarrier()
        assertEquals("The wrapped trailing physical target invokes only next article", 1, articleChanges.get())
        assertEquals(currentPage, page.get())
        controls[1].performTouchInput { click(center) }
        settleBarrier()
        assertEquals("The previous-article physical target is distinct", 0, articleChanges.get())
        assertEquals(currentPage, page.get())
        openPanel()
        clickTag("reader_formatting_cancel")
        panelClosed()
        clickTag("reader_controls_toggle")
        compose.runOnIdle { toolbarWidthFraction.value = 1f }
        assertAnchor("narrow-toolbar-restored")
    }

    private fun section(name: String) { clickTag("reader_formatting_section_$name"); activeSection = name }
    private fun verifyPanelScrollReachability() {
        val sections = mapOf(
            "text" to listOf("font_size", "chinese_font_size", "line_height", "text_darkness", "text_weight", "letter_spacing", "word_spacing", "image_contrast"),
            "layout" to listOf("margin_left", "margin_right", "margin_top", "margin_bottom", "paragraph_gap", "paragraph_indent"))
        sections.forEach { (name, controls) ->
            section(name)
            if (name == "layout") capture("default-layout-tab-fixture")
            controls.forEach { control ->
                scrollToTag("reader_formatting_${control}_value").assert(!hasSetTextAction())
                for (direction in listOf("decrease", "increase")) {
                    val tag = "reader_formatting_${control}_$direction"
                    scrollToTag(tag)
                    assertTouchTarget(tag)
                }
            }
        }
        for (alignment in listOf("start", "end", "center", "justify"))
            scrollToTag("reader_formatting_alignment_$alignment").assertHasClickAction()
        section("text")
        for (mode in listOf("color", "grayscale")) scrollToTag("reader_formatting_image_$mode").assertHasClickAction()
        assertFooterReachable()
    }

    private fun assertTouchTarget(tag: String) {
        assertTouchTarget(compose.onNodeWithTag(tag), tag)
    }

    private fun assertTouchTarget(node: SemanticsNodeInteraction, label: String) {
        node.assertHasClickAction()
        if (!node.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
            node.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$label must retain a 48 dp touch/focus target", bounds.right.value - bounds.left.value >= 47.5f &&
            bounds.bottom.value - bounds.top.value >= 47.5f)
    }

    private fun assertFooterReachable() {
        for (action in listOf("reset", "cancel", "apply")) {
            val tag = "reader_formatting_$action"
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
            assertFullyInside(tag, "reader_formatting_panel")
            assertTouchTarget(tag)
        }
    }

    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        val isControl = tag.startsWith("reader_formatting_font_") && tag != "reader_formatting_font_list" ||
            tag.endsWith("_increase") || tag.endsWith("_decrease") || tag.endsWith("_value") ||
            tag.startsWith("reader_formatting_image_") || tag.startsWith("reader_formatting_alignment_")
        if (isControl) {
            val container = if (activeSection == "fonts") "reader_formatting_font_list" else "reader_formatting_${activeSection}_controls"
            compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo()
            assertFullyInside(tag, container)
            val controls = compose.onNodeWithTag(container).getUnclippedBoundsInRoot()
            val footer = compose.onNodeWithTag("reader_formatting_apply").getUnclippedBoundsInRoot()
            assertTrue("Scrollable controls must not extend underneath the pinned footer", controls.bottom.value <= footer.top.value)
        }
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun clickTag(tag: String) {
        scrollToTag(tag).assertHasClickAction().performTouchInput { click(center) }
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
        setControlValue(name, expectedMaximum)
    }

    private fun setControlValue(name: String, value: Float) {
        val control = scrollToTag("reader_formatting_${name}_value")
        control.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue("The real accessible control must accept $name=$value", setProgress(value))
        }
        advanceComposeDraftClock()
        assertEquals(value,
            control.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
    }

    private fun verifyMaximumFormattingInShortViewport(persisted: ReaderFormatting) {
        openPanel()
        section("text")
        setMaximum("font_size", 48f)
        setMaximum("chinese_font_size", 48f)
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
        assertEquals("Chinese size remains independently restored", expected.chineseFontSize.toDouble() *
            context.resources.configuration.fontScale, actual.getDouble("chineseFontSize"), .1)
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
                // The first page can have a short paragraph followed by an image that starts
                // in the next column. A fixed middle-of-WebView crop then tests empty paper,
                // not rendering. Validate actual visible article text, never panel/system ink.
                val dom = snapshot()
                val width = dom.getDouble("viewportWidth").toFloat()
                val height = dom.getDouble("viewportHeight").toFloat()
                val fragments = dom.getJSONArray("fragments")
                val text = (0 until fragments.length()).map { index ->
                    val fragment = fragments.getJSONObject(index)
                    Rect(fragment.getDouble("left").toFloat().coerceIn(0f, width),
                        fragment.getDouble("top").toFloat().coerceIn(0f, height),
                        fragment.getDouble("right").toFloat().coerceIn(0f, width),
                        fragment.getDouble("bottom").toFloat().coerceIn(0f, height))
                }.filter { it.width > 0f && it.height > 0f }
                    .maxByOrNull { it.width * it.height }
                checkNotNull(text) { "The fixture must have visible article text for screenshot validation" }
                var bounds: Rect? = null
                instrumentation.runOnMainSync {
                    val view = webView(); val origin = IntArray(2); view.getLocationOnScreen(origin)
                    val scaleX = view.width / width
                    val scaleY = view.height / height
                    bounds = Rect(origin[0] + text.left * scaleX, origin[1] + text.top * scaleY,
                        origin[0] + text.right * scaleX, origin[1] + text.bottom * scaleY)
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
        private val INK_REFERENCE_PROBE = """
            (function(){
                var frame=document.getElementById('eink-content'), expected=window.__formattingExpectedAnchor;
                var model=InkReadScriptFonts.install(frame), nodes=model.readingNodes, n;
                function nativeRange(node,start,end){var a=model.point(node,start,false), b=model.point(node,end,true), r=document.createRange();
                    r.setStart(a.node,a.offset);r.setEnd(b.node,b.offset);return r;}
                n=nodes[expected.node];
                var character=n.data.slice(expected.offset,expected.offset+1);
                function style(node,offset){var c=getComputedStyle(model.point(node,offset||0,false).node.parentElement), result={};
                    ['fontFamily','fontSize','fontWeight','fontStyle','fontStretch','fontVariant','fontFeatureSettings',
                     'fontVariationSettings','fontKerning','fontSynthesis','textRendering','lineHeight','letterSpacing','wordSpacing','color','opacity','filter',
                     'textShadow','textTransform','textIndent','whiteSpace','writingMode','direction'].forEach(function(key){result[key]=c[key];});
                    return result;}
                function glyph(node,offset){var r=nativeRange(node,offset,offset+1);
                    var rects=Array.from(r.getClientRects());if(rects.length!==1)return null;
                    var r=rects[0];return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height};}
                var source=glyph(n,expected.offset), selectedStyle=style(n,expected.offset), references=[], width=innerWidth,height=innerHeight;
                nodes.forEach(function(candidate,index){
                    if(candidate===n||!candidate.parentElement.matches('p.format-text')||!/^第 [0-9]+ 段/.test(candidate.data))return;
                    if(JSON.stringify(style(candidate))!==JSON.stringify(selectedStyle))return;
                    var r=glyph(candidate,0);
                    if(r&&r.left>=0&&r.top>=0&&r.right<=width&&r.bottom<=height&&
                        Math.abs(r.width-source.width)<.001&&Math.abs(r.height-source.height)<.001){
                        r.node=index;r.text=candidate.data.slice(0,32);references.push(r);}
                });
                return JSON.stringify({character:character,sourceValid:expected.offset===0&&n.parentElement.matches('p.format-text')&&/^第 [0-9]+ 段/.test(n.data),
                    source:source,sourceText:n.data.slice(0,64),expectedAnchor:expected,style:selectedStyle,references:references,
                    fontsReady:document.fonts.status==='loaded',faces:Array.from(document.fonts).map(function(f){return {family:f.family,status:f.status,range:f.unicodeRange};})});
            })()
        """.trimIndent()
        private val PROBE = """
            (function() {
                var frame=document.getElementById('eink-content');
                if(!frame || !window.InkReadFormatting) return JSON.stringify({ready:false});
                var c=getComputedStyle(frame), b=getComputedStyle(document.body), w=document.documentElement.getBoundingClientRect().width, h=innerHeight;
                function visible(r){return r.width>0&&r.height>0&&r.right>1&&r.left<w-1&&r.bottom>0&&r.top<h;}
                function rect(r){return {left:r.left,right:r.right,top:r.top,bottom:r.bottom};}
                var anchor=InkReadFormatting.currentAnchor(), expected=window.__formattingExpectedAnchor;
                var model=InkReadScriptFonts.install(frame), nodes=model.readingNodes, n;
                function nativeRange(node,start,end){var a=model.point(node,start,false), b=model.point(node,end,true), r=document.createRange();
                    r.setStart(a.node,a.offset);r.setEnd(b.node,b.offset);return r;}
                var anchorVisible=false, anchorText='', anchorGlyph=null;
                if(expected&&nodes[expected.node]){
                    n=nodes[expected.node]; var offset=Math.min(expected.offset,n.length-1), r=nativeRange(n,offset,offset+1);anchorText=n.data.slice(Math.max(0,offset-10),offset+30);
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
                    chineseFontSize:parseFloat(getComputedStyle(p.querySelector('.eink-script-cjk')).fontSize),
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
