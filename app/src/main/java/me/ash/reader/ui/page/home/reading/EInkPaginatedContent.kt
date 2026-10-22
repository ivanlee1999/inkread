package me.ash.reader.ui.page.home.reading

import android.annotation.SuppressLint
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.Writer
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import me.ash.reader.ui.component.articleByline
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority
import androidx.compose.runtime.DisposableEffect
import me.ash.reader.infrastructure.preference.EInkChineseFontPreference
import me.ash.reader.infrastructure.preference.EInkEnglishFontPreference
import me.ash.reader.infrastructure.preference.EInkFontSizePreference
import me.ash.reader.ui.page.home.flow.EInkPaginationBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONTokener
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import me.ash.reader.infrastructure.preference.LocalReaderFormatting
import me.ash.reader.infrastructure.preference.LocalReaderFormattingLoadError
import me.ash.reader.infrastructure.preference.LocalRetryReaderFormatting
import me.ash.reader.infrastructure.preference.ReaderFormatting
import me.ash.reader.infrastructure.preference.saveReaderFormatting

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EInkPaginatedContent(
    modifier: Modifier = Modifier,
    content: String,
    articleId: String? = null,
    feedName: String,
    title: String,
    author: String? = null,
    link: String? = null,
    publishedDate: Date,
    contentPadding: PaddingValues = PaddingValues(),
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    onLinkClick: ((url: String) -> Unit)? = null,
    onPageChanged: ((currentPage: Int, totalPages: Int) -> Unit)? = null,
    onPrevArticle: (() -> Unit)? = null,
    onNextArticle: (() -> Unit)? = null,
    onNavigateToStylePage: (() -> Unit)? = null,
    currentArticleIndex: Int? = null,
    totalArticleCount: Int? = null,
    reserveVolumeKeys: Boolean = true,
    volumeKeyOwner: Any? = null,
    onBack: (() -> Unit)? = null,
    onSaveArticle: (() -> Unit)? = null,
    onLibraryActions: (() -> Unit)? = null,
    readingContentRevision: String? = null,
    initialReadingAnchor: String? = null,
    onReadingAnchorChanged: ((itemId: String, contentRevision: String, anchorJson: String) -> Unit)? = null,
    onReadingAnchorRestore: ((status: String) -> Unit)? = null,
    interceptResource: ((url: String) -> WebResourceResponse?)? = null,
    resourceRevision: String? = null,
    dateLabel: String? = null,
    selectionMode: Boolean = false,
    onSelectionModeChange: ((Boolean) -> Unit)? = null,
    annotationEditing: Boolean = false,
    annotationsJson: String = "[]",
    onCreateAnnotation: ((anchorJson: String, addNote: Boolean) -> Unit)? = null,
    onAnnotationLocationsChanged: ((statusJson: String) -> Unit)? = null,
    onAnnotationsClick: (() -> Unit)? = null,
    annotationJumpRequest: Pair<String, Long>? = null,
    onAnnotationJumpResult: ((id: String, attached: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Saved storage uses a semantic body revision. Image-only file rewrites keep
    // that identity and refresh local resources without recreating the article DOM.
    val contentIdentity = readingContentRevision ?: content
    val persistedFormatting = LocalReaderFormatting.current
    val loadError = LocalReaderFormattingLoadError.current
    val retry = LocalRetryReaderFormatting.current
    if (persistedFormatting == null) {
        Column(modifier.fillMaxSize().background(Color.White), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (loadError == null) "Loading reading format…" else "Reading format could not be loaded.")
            if (loadError != null) Text("Retry", modifier = Modifier.padding(16.dp).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { retry() })
        }
        return
    }
    var formattingDraft by remember(articleId, contentIdentity) { mutableStateOf<ReaderFormatting?>(null) }
    var originalFormatting by remember(articleId, contentIdentity) { mutableStateOf<ReaderFormatting?>(null) }
    var committedFormatting by remember { mutableStateOf<ReaderFormatting?>(null) }
    var formattingOpening by remember(articleId, contentIdentity) { mutableStateOf(false) }
    var formattingSaving by remember(articleId, contentIdentity) { mutableStateOf(false) }
    var formattingError by remember(articleId, contentIdentity) { mutableStateOf<String?>(null) }
    var fontAvailability by remember { mutableIntStateOf(0) }
    LaunchedEffect(persistedFormatting, committedFormatting) {
        if (committedFormatting == persistedFormatting) committedFormatting = null
    }
    val liveFormatting = formattingDraft ?: committedFormatting ?: persistedFormatting
    val editingFormatting = formattingDraft != null || formattingOpening
    val currentEditingFormatting by rememberUpdatedState(editingFormatting)
    var nativeSelectionActive by remember { mutableStateOf(false) }
    val selectionActive = selectionMode || nativeSelectionActive
    val currentSelectionMode by rememberUpdatedState(selectionActive)
    val currentAnnotationEditing by rememberUpdatedState(annotationEditing)
    val currentOnSelectionModeChange by rememberUpdatedState(onSelectionModeChange)
    val currentOnCreateAnnotation by rememberUpdatedState(onCreateAnnotation)
    val currentOnAnnotationLocationsChanged by rememberUpdatedState(onAnnotationLocationsChanged)
    val currentOnAnnotationJumpResult by rememberUpdatedState(onAnnotationJumpResult)
    val currentAnnotationJumpRequest by rememberUpdatedState(annotationJumpRequest)
    val annotationCaptureToken = remember { AtomicLong(0) }
    var annotationCapturePending by remember { mutableStateOf(false) }
    val fontsDir = remember { File(context.filesDir, "fonts") }
    fun fontFile(index: Int, names: List<String?>): File? = names.getOrNull(index)
        ?.let { File(fontsDir, it) }?.takeIf { it.isFile && it.length() > 0 }
    val englishFontFile = remember(liveFormatting.englishFont, fontAvailability) {
        fontFile(liveFormatting.englishFont, EInkEnglishFontPreference.fontFileNames)
    }
    val chineseFontFile = remember(liveFormatting.chineseFont, fontAvailability) {
        fontFile(liveFormatting.chineseFont, EInkChineseFontPreference.fontFileNames)
    }
    val formattingPayload = remember(liveFormatting, englishFontFile, chineseFontFile, fontAvailability) {
        EInkReaderFormatting.payload(liveFormatting, EInkReaderFormatting.fontFaces(englishFontFile, chineseFontFile))
    }
    fun payloadFor(value: ReaderFormatting): String = EInkReaderFormatting.payload(value,
        EInkReaderFormatting.fontFaces(fontFile(value.englishFont, EInkEnglishFontPreference.fontFileNames),
            fontFile(value.chineseFont, EInkChineseFontPreference.fontFileNames)))

    // Track article identity only; formatting updates never reload this document.
    var lastLoadedHtmlKey by remember { mutableStateOf("") }

    var showBoundaryText by remember { mutableStateOf<String?>(null) }

    // The reader explicitly opens/closes this bar; it has no auto-hide timer.
    var bottomBarVisible by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    val currentOnNextArticle by rememberUpdatedState(onNextArticle)
    val currentOnPrevArticle by rememberUpdatedState(onPrevArticle)
    val currentOnReadingAnchorChanged by rememberUpdatedState(onReadingAnchorChanged)
    val currentOnReadingAnchorRestore by rememberUpdatedState(onReadingAnchorRestore)
    val activeResourceInterceptor = remember { AtomicReference<((String) -> WebResourceResponse?)?>(null) }
    activeResourceInterceptor.set(interceptResource)
    val latestReadingAnchor = remember { AtomicReference<EInkReadingAnchorSnapshot?>(null) }

    // Compose state wrappers that the navigation controller drives.
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var totalPages by rememberSaveable { mutableIntStateOf(0) }

    // Formatting is deliberately not part of document identity: only article changes reload HTML.
    val documentGeneration = remember(articleId, link, contentIdentity, title, feedName, author, publishedDate, dateLabel) {
        UUID.randomUUID().toString()
    }
    val currentGeneration = rememberUpdatedState(documentGeneration)
    // Extracted navigation controller — keeps page-state decisions testable
    // and queues volume key events received while pagination is resetting.
    val navController = remember {
        EInkPageNavigationController(
            onPageChanged = { page, total ->
                currentOnPageChanged?.invoke(page, total)
            },
            onApplyPageToWebView = { page, commandId ->
                val generation = EInkReaderFormatting.quote(currentGeneration.value)
                webViewRef.value?.evaluateJavascript("goToPage($page,$commandId,$generation)", null)
            },
            onNextArticle = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnNextArticle?.invoke()
            },
            onPrevArticle = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnPrevArticle?.invoke()
            },
            onBoundary = { message ->
                coroutineScope.launch {
                    showBoundaryText = message
                    delay(1000)
                    showBoundaryText = null
                }
            },
            onPageTurnFeedback = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            hasNextArticle = { currentOnNextArticle != null },
            hasPrevArticle = { currentOnPrevArticle != null },
        )
    }

    // Register as HIGH-priority volume key consumer so that when the article
    // (ReadingPage) is visible it receives events exclusively, preventing the
    // FlowPage (list) from also reacting in two-pane layouts.
    val localVolumeKeyOwner = remember(articleId) { Any() }
    val owner = volumeKeyOwner ?: localVolumeKeyOwner
    val volumeKeyFlow = remember(owner) { VolumeKeyEventBus.events(VolumeKeyPriority.HIGH, owner) }
    DisposableEffect(reserveVolumeKeys, owner) {
        if (reserveVolumeKeys) VolumeKeyEventBus.register(VolumeKeyPriority.HIGH, owner)
        onDispose {
            if (reserveVolumeKeys) VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH, owner)
        }
    }
    // A successful accepted turn is published immediately by JS. Keep that exact
    // canonical position available for disposal even if the WebView cannot run JS.
    fun persistLatestReadingAnchor() {
        latestReadingAnchor.get()?.takeIf { it.generation == currentGeneration.value }?.let {
            currentOnReadingAnchorChanged?.invoke(it.itemId, it.contentRevision, it.json)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, documentGeneration) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                persistLatestReadingAnchor()
                webViewRef.value?.evaluateJavascript(
                    "window.InkReadFormatting && InkReadFormatting.publishReadingAnchor()", null)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Clean up WebView when leaving composition to prevent memory leaks
    DisposableEffect(Unit) {
        onDispose {
            persistLatestReadingAnchor()
            webViewRef.value?.let { wv ->
                wv.removeJavascriptInterface("Android")
                wv.stopLoading()
                wv.loadUrl("about:blank")
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
            webViewRef.value = null
        }
    }

    val fontResources = remember(englishFontFile, chineseFontFile, fontAvailability) {
        listOfNotNull(englishFontFile, chineseFontFile).map { EInkFontResource(it) }
    }
    val activeFontResources = remember { AtomicReference<List<EInkFontResource>>(emptyList()) }
    val articleHtmlDocument by produceState<EInkArticleHtmlDocument?>(null, documentGeneration) {
        // Complete file creation even if navigation cancels this producer, then clean up.
        val document = withContext(Dispatchers.IO + NonCancellable) {
            buildArticleHtmlDocument(context.cacheDir, content, formattingPayload,
                title, feedName, author, publishedDate, documentGeneration,
                context.assets.open("eink-formatting.js").bufferedReader().use { it.readText() },
                articleId, readingContentRevision, initialReadingAnchor, dateLabel,
                context.assets.open("eink-annotations.js").bufferedReader().use { it.readText() })
        }
        try {
            ensureActive()
            value = document
            awaitDispose { document.file.delete() }
        } catch (failure: Throwable) {
            document.file.delete()
            throw failure
        }
    }
    // WebView requests run on another thread. Publish the resources before loadUrl,
    // rather than reading a Compose snapshot that may still contain the previous document.
    val activeArticleHtmlDocument = remember { AtomicReference<EInkArticleHtmlDocument?>() }

    // Track whether initial pagination is complete so we can hide WebView until ready.
    // Use a STABLE (unkeyed) state so the JS bridge callback always writes to the same object.
    // Reset it when the article document changes so the placeholder shows immediately.
    val isInitialPaginationReadyState = remember { mutableStateOf(false) }
    var isInitialPaginationReady by isInitialPaginationReadyState
    // Track the last article document to detect changes and reset pagination ready state.
    val lastHtmlKeyForReady = remember { mutableStateOf(documentGeneration) }
    if (lastHtmlKeyForReady.value != documentGeneration) {
        lastHtmlKeyForReady.value = documentGeneration
        isInitialPaginationReady = false
        nativeSelectionActive = false
        annotationCapturePending = false
        annotationCaptureToken.incrementAndGet()
        navController.onContentReset()
        currentPage = 0
        totalPages = 0
    }

    // Safety net: if JS never calls onInitialPaginationReady (WebView failure,
    // JS error, etc.), force the flag so the user can at least swipe away.
    LaunchedEffect(documentGeneration) {
        delay(5000L)
        if (!isInitialPaginationReady) {
            Log.w("InkRead", "isInitialPaginationReady timeout — forcing true (totalPages=$totalPages)")
            isInitialPaginationReady = true
            navController.forceReady()
            totalPages = navController.totalPages
            currentPage = navController.currentPage
        }
    }

    LaunchedEffect(documentGeneration, formattingPayload, isInitialPaginationReady) {
        if (isInitialPaginationReady) {
            if (editingFormatting) delay(70) // Coalesce rapid stepper/slider drafts, not article reloads.
            activeFontResources.set(fontResources)
            webViewRef.value?.evaluateJavascript("window.InkReadFormatting && InkReadFormatting.apply($formattingPayload)", null)
        }
    }

    LaunchedEffect(documentGeneration, resourceRevision, isInitialPaginationReady) {
        if (isInitialPaginationReady && resourceRevision != null) {
            webViewRef.value?.evaluateJavascript(
                "window.InkReadFormatting && InkReadFormatting.refreshLocalImages(${EInkReaderFormatting.quote(resourceRevision)})", null)
        }
    }

    fun selectionOwnsInput(): Boolean = nativeSelectionActive || currentSelectionMode ||
        (webViewRef.value as? EInkAnnotationWebView)?.hasActiveSelection == true

    fun finishReaderSelection() {
        nativeSelectionActive = false
        annotationCaptureToken.incrementAndGet()
        annotationCapturePending = false
        (webViewRef.value as? EInkAnnotationWebView)?.finishSelection()
        webViewRef.value?.evaluateJavascript("window.InkReadAnnotations && InkReadAnnotations.setSelectionMode(false, ${EInkReaderFormatting.quote(currentGeneration.value)})", null)
        currentOnSelectionModeChange?.invoke(false)
    }
    LaunchedEffect(documentGeneration, selectionActive, isInitialPaginationReady) {
        if (isInitialPaginationReady) {
            val view = webViewRef.value
            view?.isFocusable = true
            view?.isFocusableInTouchMode = true
            if (selectionActive) {
                view?.requestFocus()
            } else {
                annotationCaptureToken.incrementAndGet()
                annotationCapturePending = false
                (view as? EInkAnnotationWebView)?.finishSelection()
            }
            view?.evaluateJavascript("window.InkReadAnnotations && InkReadAnnotations.setSelectionMode($selectionActive, ${EInkReaderFormatting.quote(documentGeneration)})", null)
        }
    }
    LaunchedEffect(onCreateAnnotation != null, annotationCapturePending) {
        (webViewRef.value as? EInkAnnotationWebView)?.refreshSelectionActions()
    }
    LaunchedEffect(documentGeneration, annotationsJson, isInitialPaginationReady) {
        if (isInitialPaginationReady) webViewRef.value?.evaluateJavascript(
            "window.InkReadAnnotations && InkReadAnnotations.setAnnotations(JSON.parse(${EInkReaderFormatting.quote(annotationsJson)}), ${EInkReaderFormatting.quote(documentGeneration)})", null)
    }
    var handledAnnotationJump by remember(documentGeneration) { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(documentGeneration, annotationJumpRequest, isInitialPaginationReady, annotationEditing, selectionActive) {
        val request = annotationJumpRequest ?: return@LaunchedEffect
        if (!isInitialPaginationReady || annotationEditing || selectionActive || handledAnnotationJump == request) return@LaunchedEffect
        handledAnnotationJump = request
        val generation = documentGeneration
        val view = webViewRef.value ?: return@LaunchedEffect
        val commandAtRequest = navController.lastIssuedCommandId
        view.evaluateJavascript("window.InkReadAnnotations && InkReadAnnotations.pageForAnnotation(${EInkReaderFormatting.quote(request.first)}, ${EInkReaderFormatting.quote(generation)})") { result ->
            if (generation != currentGeneration.value || view !== webViewRef.value || currentAnnotationEditing || selectionOwnsInput() || currentAnnotationJumpRequest != request ||
                navController.lastIssuedCommandId != commandAtRequest || currentEditingFormatting) return@evaluateJavascript
            val page = result.toIntOrNull()
            if (page != null) {
                if (!navController.navigateToPage(page, commandAtRequest)) return@evaluateJavascript
                currentPage = navController.currentPage
                totalPages = navController.totalPages
            }
            currentOnAnnotationJumpResult?.invoke(request.first, page != null)
        }
    }

    fun exitReader() {
        // A native ActionMode can precede recomposition of BackHandler.enabled.
        if (selectionOwnsInput()) { finishReaderSelection(); return }
        val action = onBack ?: return
        val view = webViewRef.value
        if (readingContentRevision == null || view == null) {
            persistLatestReadingAnchor()
            action()
            return
        }
        val generation = documentGeneration
        // This query is ordered after pending goToPage evaluations. Read its
        // result as well as the bridge so Back cannot outrun the last page turn.
        val finished = AtomicBoolean(false)
        fun finish(json: String?) {
            if (!finished.compareAndSet(false, true) || generation != currentGeneration.value || webViewRef.value !== view) return
            if (json != null && articleId != null) {
                latestReadingAnchor.set(EInkReadingAnchorSnapshot(generation, articleId, readingContentRevision, json))
            }
            persistLatestReadingAnchor()
            action()
        }
        // A stalled WebView must not trap Back. Accepted turns are already cached
        // natively; the fallback still drains that most recent known position.
        val timeout = coroutineScope.launch { delay(750); finish(null) }
        view.evaluateJavascript("window.InkReadFormatting && InkReadFormatting.publishReadingAnchor()") { result ->
            timeout.cancel()
            finish(runCatching { JSONTokener(result).nextValue() as? String }.getOrNull())
        }
    }
    BackHandler(enabled = onBack != null && !editingFormatting && !bottomBarVisible && !selectionActive && !annotationEditing && !annotationCapturePending) { exitReader() }

    fun closeFormatting(cancel: Boolean, value: ReaderFormatting) {
        activeFontResources.set(listOfNotNull(
            fontFile(value.englishFont, EInkEnglishFontPreference.fontFileNames),
            fontFile(value.chineseFont, EInkChineseFontPreference.fontFileNames),
        ).map { EInkFontResource(it) })
        webViewRef.value?.evaluateJavascript(
            "window.InkReadFormatting && InkReadFormatting.endSession($cancel, ${payloadFor(value)})", null)
        formattingDraft = null
        originalFormatting = null
        formattingOpening = false
        formattingError = null
    }
    fun cancelFormatting() {
        if (formattingSaving) return
        val original = originalFormatting ?: persistedFormatting
        closeFormatting(true, original)
    }
    fun openFormatting() {
        if (editingFormatting || selectionOwnsInput() || annotationEditing || !isInitialPaginationReady) return
        val view = webViewRef.value ?: return
        val generation = documentGeneration
        val snapshot = committedFormatting ?: persistedFormatting
        formattingOpening = true
        // Capture the anchor before reserving panel space changes the viewport.
        view.evaluateJavascript("window.InkReadFormatting && InkReadFormatting.beginSession()") { result ->
            if (formattingOpening && generation == currentGeneration.value && webViewRef.value === view) {
                if (result == "null" || result == "false") {
                    formattingOpening = false
                    return@evaluateJavascript
                }
                originalFormatting = snapshot
                formattingDraft = snapshot
                formattingError = null
                formattingOpening = false
            }
        }
    }
    // Dismiss the innermost reader surface first. The toolbar's explicit Back
    // action still navigates out; Android Back first restores an unobscured page.
    BackHandler(enabled = !annotationEditing && !selectionActive && (editingFormatting || bottomBarVisible)) {
        when {
            selectionOwnsInput() -> finishReaderSelection()
            editingFormatting -> cancelFormatting()
            else -> bottomBarVisible = false
        }
    }

    BackHandler(enabled = selectionActive && !annotationEditing) { finishReaderSelection() }

    fun nextPage() {
        if (currentEditingFormatting || selectionOwnsInput() || currentAnnotationEditing || annotationCapturePending) return
        navController.handleNavigation(VolumeKeyEvent.NEXT)
        // Sync Compose state from controller
        currentPage = navController.currentPage
        totalPages = navController.totalPages
    }

    fun prevPage() {
        if (currentEditingFormatting || selectionOwnsInput() || currentAnnotationEditing || annotationCapturePending) return
        navController.handleNavigation(VolumeKeyEvent.PREV)
        // Sync Compose state from controller
        currentPage = navController.currentPage
        totalPages = navController.totalPages
    }

    LaunchedEffect(volumeKeyFlow) {
        volumeKeyFlow.collect { event ->
            if (VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH, owner) && !currentEditingFormatting && !selectionOwnsInput() && !currentAnnotationEditing && !annotationCapturePending) {
                Log.d("InkRead", "Volume key event received: $event")
                when (event) {
                    VolumeKeyEvent.NEXT -> nextPage()
                    VolumeKeyEvent.PREV -> prevPage()
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().imePadding().background(Color.White)) {
        val panelHeight = minOf(maxHeight * .48f, 360.dp)
        Column(modifier = Modifier.fillMaxSize()) {
            // Keep the last successful snapshot and article visible if a later read fails.
            // This temporary, animation-free notice disappears only on a real success.
            if (loadError != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Reading format could not be refreshed.", modifier = Modifier.weight(1f), color = Color.Black)
                    Box(Modifier.size(64.dp, 48.dp).testTag("reader_formatting_retry").clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { retry() }, contentAlignment = Alignment.Center) { Text("Retry", color = Color.Black) }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // White placeholder shown while WebView pagination is not yet ready
                if (!isInitialPaginationReady) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White),
                    )
                }

                AndroidView(
                    factory = { ctx ->
                        EInkAnnotationWebView(ctx).apply {
                            selectionEnabled = { isInitialPaginationReadyState.value && !currentEditingFormatting &&
                                !currentAnnotationEditing && !annotationCapturePending }
                            annotationActionsEnabled = { currentOnCreateAnnotation != null && !annotationCapturePending }
                            isSelectionActive = { selectionOwnsInput() }
                            navigationEnabled = { isInitialPaginationReadyState.value && !currentEditingFormatting &&
                                !selectionOwnsInput() && !currentAnnotationEditing && !annotationCapturePending }
                            readerGestureKey = { Triple(currentGeneration.value, navController.currentPage, navController.lastIssuedCommandId) }
                            onReaderAction = { action ->
                                when (action) {
                                    EInkReaderTouchArbitrator.Action.DISMISS_SELECTION -> finishReaderSelection()
                                    EInkReaderTouchArbitrator.Action.PREVIOUS_PAGE -> prevPage()
                                    EInkReaderTouchArbitrator.Action.NEXT_PAGE -> nextPage()
                                    EInkReaderTouchArbitrator.Action.TOGGLE_CONTROLS -> if (navigationEnabled()) bottomBarVisible = !bottomBarVisible
                                    EInkReaderTouchArbitrator.Action.PREVIOUS_ARTICLE -> if (navigationEnabled()) currentOnPrevArticle?.let {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress); it()
                                    }
                                    EInkReaderTouchArbitrator.Action.NEXT_ARTICLE -> if (navigationEnabled()) currentOnNextArticle?.let {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress); it()
                                    }
                                }
                            }
                            onSelectionStarted = {
                                nativeSelectionActive = true
                                currentOnSelectionModeChange?.invoke(true)
                                // Queue this before any possible menu action. Capture also
                                // enables and reads its range atomically in one JS evaluation.
                                evaluateJavascript("window.InkReadAnnotations && InkReadAnnotations.setSelectionMode(true, ${EInkReaderFormatting.quote(currentGeneration.value)})", null)
                            }
                            onSelectionFinished = {
                                nativeSelectionActive = false
                                evaluateJavascript("window.InkReadAnnotations && InkReadAnnotations.setSelectionMode(false, ${EInkReaderFormatting.quote(currentGeneration.value)})", null)
                                // Native Back/action-mode dismissal can precede an asynchronous
                                // JS result. Revoke it just like the explicit Done action.
                                annotationCaptureToken.incrementAndGet()
                                annotationCapturePending = false
                                currentOnSelectionModeChange?.invoke(false)
                            }
                            onAnnotationAction = annotation@{ addNote, _ ->
                                if (currentOnCreateAnnotation == null) return@annotation
                                if (!annotationCapturePending && selectionOwnsInput()) {
                                    annotationCapturePending = true
                                    val generation = currentGeneration.value
                                    val token = annotationCaptureToken.incrementAndGet()
                                    val view = this
                                    val timeout = coroutineScope.launch {
                                        delay(1500)
                                        if (generation == currentGeneration.value && annotationCaptureToken.compareAndSet(token, token + 1)) {
                                            annotationCapturePending = false
                                            nativeSelectionActive = false
                                            view.finishSelection()
                                            evaluateJavascript("window.InkReadAnnotations && InkReadAnnotations.clearSelection()", null)
                                            currentOnSelectionModeChange?.invoke(false)
                                            showBoundaryText = "Selection could not be captured. Please try again."
                                            delay(1800)
                                            showBoundaryText = null
                                        }
                                    }
                                    // Do not finish ActionMode or open a dialog until the exact
                                    // DOM range has been serialized. Focus/IME would clear it.
                                    evaluateJavascript("window.InkReadAnnotations && InkReadAnnotations.setSelectionMode(true, ${EInkReaderFormatting.quote(generation)}) && InkReadAnnotations.captureSelection(${EInkReaderFormatting.quote(generation)})") { result ->
                                        timeout.cancel()
                                        if (generation != currentGeneration.value || webViewRef.value !== view || !selectionOwnsInput() || currentAnnotationEditing ||
                                            !annotationCaptureToken.compareAndSet(token, token + 1)) return@evaluateJavascript
                                        val json = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                                        nativeSelectionActive = false
                                        view.finishSelection()
                                        evaluateJavascript("window.InkReadAnnotations && InkReadAnnotations.clearSelection()", null)
                                        annotationCapturePending = false
                                        currentOnSelectionModeChange?.invoke(false)
                                        // Availability can change while JS serializes the
                                        // range. Never call a removed host callback.
                                        val callback = currentOnCreateAnnotation
                                        if (json != null && callback != null) callback(json, addNote)
                                        else coroutineScope.launch {
                                            showBoundaryText = if (json == null) "Select article text to annotate."
                                                else "Annotations are unavailable. Please try again."
                                            delay(1800)
                                            showBoundaryText = null
                                        }
                                    }
                                }
                            }
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            setBackgroundColor(android.graphics.Color.WHITE)
                            isHorizontalScrollBarEnabled = false
                            isVerticalScrollBarEnabled = false
                            isFocusable = true
                            isFocusableInTouchMode = true
                            overScrollMode = android.view.View.OVER_SCROLL_NEVER
                            setOnKeyListener { _, _, _ ->
                                // Let all keys propagate normally; the WebView
                                // onKeyDown/onKeyUp overrides already handle
                                // volume key passthrough.
                                false
                            }
                            webViewClient = object : WebViewClient() {
                                // Block all navigation away from the local content.
                                // This prevents javascript: scheme exploits and
                                // accidental navigation to external URLs.
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    url: String?,
                                ): Boolean = url != activeArticleHtmlDocument.get()?.url

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                ): Boolean = request?.url?.toString() != activeArticleHtmlDocument.get()?.url

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                ): WebResourceResponse? {
                                    val document = activeArticleHtmlDocument.get()
                                    return if (document != null && request?.url?.toString() == document.url && document.file.exists()) {
                                        val stream = runCatching { document.file.inputStream() }.getOrNull()
                                        if (stream != null) WebResourceResponse("text/html", "UTF-8", stream)
                                        else WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                    } else {
                                        val url = request?.url?.toString().orEmpty()
                                        activeResourceInterceptor.get()?.invoke(url)?.let { return it }
                                        val font = activeFontResources.get().firstOrNull { it.url == url }
                                        val stream = runCatching { font?.open(url) }.getOrNull()
                                        if (stream != null) WebResourceResponse(font!!.mimeType, null, stream)
                                        else if (request?.url?.host == "inkread.local")
                                            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                                        else super.shouldInterceptRequest(view, request)
                                    }
                                }
                            }
                            addJavascriptInterface(
                                EInkJsInterface(
                                    onTotalPages = { generation, pages, commandId ->
                                        Handler(Looper.getMainLooper()).post {
                                            if (generation != currentGeneration.value || webViewRef.value == null) return@post
                                            navController.onTotalPagesUpdated(pages, commandId)
                                            totalPages = navController.totalPages
                                            currentPage = navController.currentPage
                                        }
                                    },
                                    onRepaginated = { generation, pages, page, commandId ->
                                        Handler(Looper.getMainLooper()).post {
                                            if (generation != currentGeneration.value || webViewRef.value == null) return@post
                                            navController.onRepaginated(pages, page, commandId)
                                            totalPages = navController.totalPages
                                            currentPage = navController.currentPage
                                        }
                                    },
                                    onInitialPaginationReady = { generation, pages, restoredPage, commandId ->
                                        Handler(Looper.getMainLooper()).post {
                                            if (generation != currentGeneration.value || webViewRef.value == null) return@post
                                            if (!navController.onPaginationReady(pages, restoredPage, commandId)) return@post
                                            isInitialPaginationReady = true
                                            totalPages = navController.totalPages
                                            currentPage = navController.currentPage
                                        }
                                    },
                                    onReadingAnchor = { generation, itemId, revision, json ->
                                        Handler(Looper.getMainLooper()).post {
                                            if (generation != currentGeneration.value || webViewRef.value == null) return@post
                                            latestReadingAnchor.set(EInkReadingAnchorSnapshot(generation, itemId, revision, json))
                                            currentOnReadingAnchorChanged?.invoke(itemId, revision, json)
                                        }
                                    },
                                    onAnnotationLocations = { generation, json ->
                                        Handler(Looper.getMainLooper()).post {
                                            if (generation != currentGeneration.value || webViewRef.value == null) return@post
                                            currentOnAnnotationLocationsChanged?.invoke(json)
                                        }
                                    },
                                    onReadingAnchorRestore = { generation, status ->
                                        Handler(Looper.getMainLooper()).post {
                                            if (generation != currentGeneration.value || webViewRef.value == null) return@post
                                            currentOnReadingAnchorRestore?.invoke(status)
                                        }
                                    },
                                ),
                                "Android",
                            )
                            webViewRef.value = this
                            articleHtmlDocument?.takeIf { it.cacheKey == documentGeneration }?.let {
                                activeArticleHtmlDocument.set(it)
                                activeFontResources.set(fontResources)
                                lastLoadedHtmlKey = it.cacheKey
                                loadUrl(it.url)
                            }
                        }
                    },
                    update = { webView ->
                        // Reload only when the article document changes, without
                        // destroying and recreating the entire WebView composable.
                        // Note: isInitialPaginationReady is already reset synchronously via
                        // remember(articleHtmlDocument.cacheKey) — no need to reset it here.
                        articleHtmlDocument?.takeIf {
                            it.cacheKey == documentGeneration && it.cacheKey != lastLoadedHtmlKey
                        }?.let {
                            activeArticleHtmlDocument.set(it)
                            activeFontResources.set(fontResources)
                            lastLoadedHtmlKey = it.cacheKey
                            nativeSelectionActive = false
                            (webView as? EInkAnnotationWebView)?.invalidateReaderGesture()
                            (webView as? EInkAnnotationWebView)?.finishSelection()
                            if (currentSelectionMode) currentOnSelectionModeChange?.invoke(false)
                            webView.loadUrl(it.url)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = if (isInitialPaginationReady) 1f else 0f
                        },
                )

                if (editingFormatting) {
                    Box(Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { cancelFormatting() })
                }

                // Boundary indicator - no more articles
                androidx.compose.animation.AnimatedVisibility(
                    visible = showBoundaryText != null,
                    enter = androidx.compose.animation.EnterTransition.None,
                    exit = androidx.compose.animation.ExitTransition.None,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        text = showBoundaryText ?: "",
                        fontSize = 16.sp,
                        color = Color.Black,
                    )
                }

                // Accessibility owns a semantic action, not a transparent touch
                // overlay. Real bottom-area touches still reach the native WebView.
                if (!editingFormatting && !selectionActive && !annotationEditing && !annotationCapturePending) Box(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(.15f).align(Alignment.BottomCenter)
                        .testTag("reader_controls_toggle")
                        .semantics {
                            contentDescription = "Reading controls"
                            stateDescription = if (bottomBarVisible) "Shown" else "Hidden"
                            role = Role.Button
                            onClick(if (bottomBarVisible) "Hide reading controls" else "Show reading controls") {
                                if (!selectionOwnsInput()) bottomBarVisible = !bottomBarVisible
                                true
                            }
                        },
                )

            }

            val progress = if (totalPages > 0) ((currentPage + 1) * 100 / totalPages) else 0

            // Thin progress bar — visible at the bottom only after pagination is ready
            if (totalPages > 0) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Color.Black,
                    trackColor = Color(0xFFE0E0E0),
                )
            }

            // Full bottom bar — shown and dismissed explicitly by the reader
            // Also hidden when pagination is not yet ready (totalPages == 0)
            AnimatedVisibility(
                visible = bottomBarVisible && totalPages > 0 && !editingFormatting && !annotationEditing,
                enter = androidx.compose.animation.EnterTransition.None,
                exit = androidx.compose.animation.ExitTransition.None,
            ) {
                Box {
                    EInkPaginationBar(
                        modifier = if (selectionActive || annotationCapturePending) Modifier
                            .graphicsLayer { alpha = 0f }.clearAndSetSemantics {} else Modifier,
                        currentPage = currentPage + 1,
                        totalPages = totalPages,
                        onPrev = {
                            prevPage()
                            bottomBarVisible = true
                        },
                        onNext = {
                            nextPage()
                            bottomBarVisible = true
                        },
                        canDecreaseFontSize = liveFormatting.fontSize > ReaderFormatting.FontSizeRange.first,
                        canIncreaseFontSize = liveFormatting.fontSize < ReaderFormatting.FontSizeRange.last,
                        onDecreaseFontSize = decrease@{
                            if (selectionOwnsInput() || annotationCapturePending) return@decrease
                            bottomBarVisible = true
                            EInkFontSizePreference.put(context, coroutineScope,
                                (liveFormatting.fontSize - 2).coerceIn(ReaderFormatting.FontSizeRange))
                        },
                        onIncreaseFontSize = increase@{
                            if (selectionOwnsInput() || annotationCapturePending) return@increase
                            bottomBarVisible = true
                            EInkFontSizePreference.put(context, coroutineScope,
                                (liveFormatting.fontSize + 2).coerceIn(ReaderFormatting.FontSizeRange))
                        },
                        onPrevArticle = onPrevArticle?.let { action -> { if (!selectionOwnsInput() && !annotationCapturePending) action() } },
                        onNextArticle = onNextArticle?.let { action -> { if (!selectionOwnsInput() && !annotationCapturePending) action() } },
                        progress = progress,
                        currentArticleIndex = currentArticleIndex,
                        totalArticleCount = totalArticleCount,
                        onBack = onBack?.let { { if (selectionOwnsInput()) finishReaderSelection() else exitReader() } },
                        onSaveArticle = onSaveArticle?.let { action -> { if (!selectionOwnsInput() && !annotationCapturePending) action() } },
                        onLibraryActions = onLibraryActions?.let { action -> { if (!selectionOwnsInput() && !annotationCapturePending) action() } },
                        onFormattingClick = if (isInitialPaginationReady) ::openFormatting else null,
                        onSelectText = if (isInitialPaginationReady && onCreateAnnotation != null && onSelectionModeChange != null) {
                            { if (!selectionOwnsInput() && !annotationCapturePending) onSelectionModeChange(true) }
                        } else null,
                        onAnnotationsClick = onAnnotationsClick?.let { action -> { if (!selectionOwnsInput() && !annotationCapturePending) { bottomBarVisible = false; action() } } },
                    )
                    // Reuse only an already-open control bar's measured space.
                    // Default selection adds no bar and covers no article text.
                    if (selectionActive || annotationCapturePending) Box(Modifier.matchParentSize()
                        .background(Color.White).testTag("reader_selection_done")
                        .semantics { contentDescription = "Done selecting text" }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            role = Role.Button, onClick = ::finishReaderSelection), contentAlignment = Alignment.Center) {
                        Text("Done", color = Color.Black)
                    }
                }
            }
            formattingDraft?.let { draft ->
                ReaderFormattingPanel(
                    draft = draft,
                    onDraftChange = { if (!formattingSaving) formattingDraft = it.clamped() },
                    onApply = {
                        if (!formattingSaving) {
                            formattingSaving = true
                            formattingError = null
                            val savingGeneration = documentGeneration
                            coroutineScope.launch {
                                applyEInkFormatting(
                                    persist = { saveReaderFormatting(context, draft) },
                                    onSaved = { saved ->
                                        committedFormatting = saved
                                        if (savingGeneration == currentGeneration.value) closeFormatting(false, saved)
                                    },
                                    onSaveFailed = { failure ->
                                        Log.w("InkRead", "Could not persist reading format", failure)
                                        formattingError = "Could not save reading format. Please try again."
                                    },
                                    onFinished = { formattingSaving = false },
                                )
                            }
                        }
                    },
                    onCancel = ::cancelFormatting,
                    onReset = { if (!formattingSaving) formattingDraft = ReaderFormatting.Default },
                    onFontAvailabilityChanged = { fontAvailability++ },
                    isSaving = formattingSaving,
                    errorMessage = formattingError,
                    modifier = Modifier.fillMaxWidth().height(panelHeight),
                )
            }
        }
    }
}

/** Keeps the system Copy action/selection handles and adds local annotation actions. */
internal class EInkAnnotationWebView(context: android.content.Context) : WebView(context) {
    var selectionEnabled: () -> Boolean = { false }
    var annotationActionsEnabled: () -> Boolean = { false }
    var isSelectionActive: () -> Boolean = { false }
    var navigationEnabled: () -> Boolean = { false }
    var readerGestureKey: () -> Any = { Unit }
    var onReaderAction: (EInkReaderTouchArbitrator.Action) -> Unit = {}
    var onAnnotationAction: (Boolean, ActionMode) -> Unit = { _, _ -> }
    var onSelectionStarted: () -> Unit = {}
    var onSelectionFinished: () -> Unit = {}
    private var selectionActionMode: ActionMode? = null
    val hasActiveSelection: Boolean get() = selectionActionMode != null
    private var finishingInternally = false
    private val touch = EInkReaderTouchArbitrator(
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        56f * resources.displayMetrics.density,
        ViewConfiguration.getLongPressTimeout().toLong(),
    )
    private var gestureSerial = 0L
    private var gestureKey: Any? = null
    private var activePointer = -1
    private var nativeStreamOpen = false
    private var interactiveTarget = false

    fun invalidateReaderGesture() {
        gestureSerial++
        touch.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) invalidateReaderGesture()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val decision = if (action == MotionEvent.ACTION_DOWN) {
            gestureSerial++
            gestureKey = readerGestureKey()
            activePointer = event.getPointerId(0)
            interactiveTarget = false
            nativeStreamOpen = true
            val edges = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemGestures())
            touch.down(event.x, event.y, event.eventTime, width.toFloat(), height.toFloat(),
                navigationEnabled(), hasActiveSelection || isSelectionActive(),
                (edges?.left ?: 0).toFloat(), (edges?.right ?: 0).toFloat())
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN ||
            event.pointerCount != 1 || event.getPointerId(0) != activePointer || gestureKey != readerGestureKey()) {
            gestureSerial++
            touch.cancel()
        } else {
            if (hasActiveSelection || isSelectionActive()) touch.claimSelection()
            when (action) {
                MotionEvent.ACTION_MOVE -> touch.move(event.x, event.y, event.eventTime)
                MotionEvent.ACTION_UP -> touch.up(event.x, event.y, event.eventTime)
                else -> EInkReaderTouchArbitrator.Result()
            }
        }
        when (decision.dispatch) {
            EInkReaderTouchArbitrator.Dispatch.WEBVIEW -> {
                super.onTouchEvent(event)
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) nativeStreamOpen = false
            }
            EInkReaderTouchArbitrator.Dispatch.CANCEL_WEBVIEW, EInkReaderTouchArbitrator.Dispatch.CONSUME -> {
                if (nativeStreamOpen) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                    nativeStreamOpen = false
                }
            }
        }
        // Read the current native stream's hit only after WebView processes UP,
        // never the previous link's stale target at a new DOWN.
        if (action == MotionEvent.ACTION_UP) {
            interactiveTarget = hitTestResult?.type?.let { it != HitTestResult.UNKNOWN_TYPE } == true
        }
        decision.action?.let { recognized ->
            when (recognized) {
                EInkReaderTouchArbitrator.Action.DISMISS_SELECTION -> onReaderAction(recognized)
                EInkReaderTouchArbitrator.Action.PREVIOUS_ARTICLE, EInkReaderTouchArbitrator.Action.NEXT_ARTICLE ->
                    if (navigationEnabled() && !hasActiveSelection) onReaderAction(recognized)
                else -> if (!interactiveTarget && navigationEnabled() && !hasActiveSelection) dispatchPlainTap(event.x, event.y, recognized)
            }
        }
        return true
    }

    private fun dispatchPlainTap(x: Float, y: Float, action: EInkReaderTouchArbitrator.Action) {
        if (width <= 0 || height <= 0) return
        val serial = gestureSerial
        val key = readerGestureKey()
        // Read only. In particular, link taps retain the existing blocked external
        // navigation policy; they must not fall through to a page or control tap.
        evaluateJavascript("""
            (function(){
                var s=window.getSelection(); if(s && !s.isCollapsed) return false;
                var e=document.elementFromPoint(${x / width}*innerWidth,${y / height}*innerHeight);
                if(!e) return false;
                while(e){
                    if(e.matches && e.matches('a,button,input,textarea,select,option,label,summary,video,audio,[contenteditable],[role="button"],[role="link"]')) return false;
                    e=e.parentElement;
                }
                return true;
            })()
        """.trimIndent()) { result ->
            if (result == "true" && serial == gestureSerial && key == readerGestureKey() &&
                navigationEnabled() && !hasActiveSelection && !isSelectionActive()) onReaderAction(action)
        }
    }

    fun refreshSelectionActions() { selectionActionMode?.invalidate() }

    fun finishSelection() {
        gestureSerial++
        touch.claimSelection()
        // Successful capture and explicit Done already own their token/state
        // changes. Only externally destroyed ActionModes need the cancel callback.
        finishingInternally = true
        try { selectionActionMode?.finish() } finally {
            selectionActionMode = null
            finishingInternally = false
        }
    }

    private fun annotationCallback(original: ActionMode.Callback): ActionMode.Callback =
        if (original is AnnotationActionModeCallback) original else AnnotationActionModeCallback(original)

    private inner class AnnotationActionModeCallback(private val original: ActionMode.Callback) : ActionMode.Callback2() {
        fun updateActions(menu: Menu): Boolean {
            val highlightPresent = menu.findItem(HIGHLIGHT) != null
            val notePresent = menu.findItem(ADD_NOTE) != null
            if (!annotationActionsEnabled()) {
                menu.removeItem(HIGHLIGHT)
                menu.removeItem(ADD_NOTE)
                return highlightPresent || notePresent
            }
            if (!highlightPresent) menu.add(Menu.NONE, HIGHLIGHT, 1, "Highlight").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if (!notePresent) menu.add(Menu.NONE, ADD_NOTE, 2, "Add note").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return !highlightPresent || !notePresent
        }
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            if (!selectionEnabled() || !touch.permitsNativeSelection || !original.onCreateActionMode(mode, menu)) return false
            selectionActionMode = mode
            gestureSerial++
            touch.claimSelection()
            onSelectionStarted()
            updateActions(menu)
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val result = original.onPrepareActionMode(mode, menu)
            return updateActions(menu) || result
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
            HIGHLIGHT, ADD_NOTE -> { if (selectionEnabled() && annotationActionsEnabled()) onAnnotationAction(item.itemId == ADD_NOTE, mode); true }
            else -> original.onActionItemClicked(mode, item)
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            original.onDestroyActionMode(mode)
            if (selectionActionMode === mode) {
                selectionActionMode = null
                gestureSerial++
                if (!finishingInternally) onSelectionFinished()
            }
        }
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            if (original is ActionMode.Callback2) original.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(annotationCallback(callback))
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(annotationCallback(callback), type)

    // Activity owns HIGH-priority volume events even while native selection has focus.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean =
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) false
        else super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean =
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) false
        else super.onKeyUp(keyCode, event)

    private companion object { const val HIGHLIGHT = 0x494E4B01; const val ADD_NOTE = 0x494E4B02 }
}

private data class EInkReadingAnchorSnapshot(
    val generation: String, val itemId: String, val contentRevision: String, val json: String,
)

private class EInkJsInterface(
    private val onTotalPages: (String, Int, Long) -> Unit,
    private val onInitialPaginationReady: (String, Int, Int, Long) -> Unit,
    private val onRepaginated: (String, Int, Int, Long) -> Unit,
    private val onReadingAnchor: (String, String, String, String) -> Unit,
    private val onReadingAnchorRestore: (String, String) -> Unit,
    private val onAnnotationLocations: (String, String) -> Unit,
) {
    @JavascriptInterface
    fun onAnnotationLocations(generation: String, json: String) = onAnnotationLocations.invoke(generation, json)

    @JavascriptInterface
    fun onTotalPages(generation: String, pages: Int, commandId: Long) = onTotalPages.invoke(generation, pages, commandId)

    @JavascriptInterface
    fun onRepaginated(generation: String, pages: Int, page: Int, commandId: Long) = onRepaginated.invoke(generation, pages, page, commandId)

    @JavascriptInterface
    fun onInitialPaginationReady(generation: String, pages: Int, page: Int, commandId: Long) = onInitialPaginationReady.invoke(generation, pages, page, commandId)

    @JavascriptInterface
    fun onReadingAnchor(generation: String, itemId: String, revision: String, json: String) =
        onReadingAnchor.invoke(generation, itemId, revision, json)

    @JavascriptInterface
    fun onReadingAnchorRestore(generation: String, status: String) = onReadingAnchorRestore.invoke(generation, status)
}

private data class EInkArticleHtmlDocument(
    val file: File,
    val cacheKey: String,
) {
    val url: String = "https://inkread.local/eink/${file.name}"
}

private fun buildArticleHtmlDocument(
    cacheDir: File,
    content: String,
    formattingPayload: String,
    title: String,
    feedName: String,
    author: String?,
    publishedDate: Date,
    generation: String,
    runtimeScript: String,
    itemId: String?,
    readingContentRevision: String?,
    initialReadingAnchor: String?,
    dateLabel: String?,
    annotationsScript: String,
): EInkArticleHtmlDocument {
    val outputDir = File(cacheDir, "eink-articles").apply { mkdirs() }
    deleteStaleArticleHtmlFiles(outputDir)
    val outputFile = File.createTempFile("article-", ".html", outputDir)
    outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
        writeArticleHtml(writer, content, formattingPayload, title, feedName, author,
            publishedDate, generation, runtimeScript, itemId, readingContentRevision, initialReadingAnchor, dateLabel, annotationsScript)
    }
    return EInkArticleHtmlDocument(file = outputFile, cacheKey = generation)
}

private fun deleteStaleArticleHtmlFiles(outputDir: File) {
    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
    outputDir.listFiles { file -> file.isFile && file.extension == "html" && file.lastModified() < cutoff }
        ?.forEach { file -> runCatching { file.delete() } }
}

private fun writeArticleHtml(
    writer: Writer,
    content: String,
    formattingPayload: String,
    title: String,
    feedName: String,
    author: String?,
    publishedDate: Date,
    generation: String,
    runtimeScript: String,
    itemId: String?,
    readingContentRevision: String?,
    initialReadingAnchor: String?,
    dateLabel: String?,
    annotationsScript: String,
) {
    val dateStr = einkReaderDate(publishedDate, dateLabel)
    val metaLine = listOf(articleByline(feedName, author), dateStr).filter { it.isNotBlank() }.joinToString(" · ")
    val readingIdentity = if (itemId != null && readingContentRevision != null) {
        // Storage's semantic revision covers the immutable body. Local image
        // transport URLs may change while its text/media node order stays fixed.
        val fingerprint = EInkReadingDocumentFingerprint.create(readingContentRevision, title, metaLine)
        """{"itemId":${EInkReaderFormatting.quote(itemId)},"contentRevision":${EInkReaderFormatting.quote(readingContentRevision)},"documentFingerprint":${EInkReaderFormatting.quote(fingerprint)}}"""
    } else "null"
    fun String.esc() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    writer.write("""<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style id="eink-font-faces"></style>
<style>${EInkReaderFormatting.stylesheet}</style>
<script>
window._inkreadGeneration = ${EInkReaderFormatting.quote(generation)};
window._inkreadInitialFormatting = $formattingPayload;
window._inkreadReadingIdentity = $readingIdentity;
window._inkreadInitialReadingAnchor = ${initialReadingAnchor?.let(EInkReaderFormatting::quote) ?: "null"};
$runtimeScript
$annotationsScript
</script>
</head><body><main id="eink-content">
<div class="eink-metadata"><h1>${title.esc()}</h1><p class="eink-meta">${metaLine.esc()}</p></div>
""")
    appendSanitizedHtml(writer, content)
    writer.write("</main></body></html>")
}

/**
 * HTML sanitization to strip script injection vectors before loading content
 * into the WebView with JavaScript enabled.
 *
 * This writes directly into the article HTML file instead of returning another
 * full-size String. Large RSS entries can already be tens of MB; making a
 * sanitized copy and then interpolating it into a second HTML String can exceed
 * Android's 256 MB heap on e-ink devices.
 *
 * Handles:
 * - `<script>` tags and their content (including unclosed tags)
 * - Quoted event handlers: `onclick="..."` / `onclick='...'`
 * - Unquoted event handlers: `onclick=alert(1)`
 * - `javascript:` scheme including HTML-entity-encoded variants
 */
internal fun appendSanitizedHtml(out: Appendable, html: String) {
    val buffer = StringBuilder(SANITIZED_HTML_BUFFER_SIZE)

    fun flush() {
        if (buffer.isNotEmpty()) {
            out.append(buffer)
            buffer.clear()
        }
    }

    fun appendChar(ch: Char) {
        buffer.append(ch)
        if (buffer.length >= SANITIZED_HTML_BUFFER_SIZE) flush()
    }

    var index = 0
    var inTag = false
    while (index < html.length) {
        if (startsWithScriptTag(html, index)) {
            flush()
            index = skipScriptElement(html, index)
            inTag = false
            continue
        }

        val javascriptSchemeEnd = matchJavascriptSchemeEnd(html, index)
        if (javascriptSchemeEnd != -1) {
            index = javascriptSchemeEnd
            continue
        }

        if (inTag && html[index].isWhitespace()) {
            appendChar(html[index])
            val attrStart = index + 1
            val eventAttrEnd = skipEventHandlerAttribute(html, attrStart)
            if (eventAttrEnd != -1) {
                index = eventAttrEnd
                continue
            }
            index++
            continue
        }

        val ch = html[index]
        appendChar(ch)
        if (ch == '<') inTag = true else if (ch == '>') inTag = false
        index++
    }
    flush()
}

private const val SANITIZED_HTML_BUFFER_SIZE = 4096

private data class DecodedHtmlEntity(val char: Char, val length: Int)

private fun decodeHtmlEntityAt(html: String, index: Int): DecodedHtmlEntity? {
    if (html[index] != '&') return null

    val semicolon = html.indexOf(';', startIndex = index + 1)
    if (semicolon == -1 || semicolon - index > 12) return null

    val entity = html.substring(index + 1, semicolon)
    val decoded = when {
        entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)?.toChar()
        entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.toChar()
        entity.equals("Tab", ignoreCase = true) -> '\t'
        entity.equals("NewLine", ignoreCase = true) -> '\n'
        else -> null
    } ?: return null

    return DecodedHtmlEntity(decoded, semicolon - index + 1)
}

private fun startsWithScriptTag(html: String, index: Int): Boolean {
    if (!html.startsWith("<script", index, ignoreCase = true)) return false
    val next = index + "<script".length
    return next >= html.length || html[next].isWhitespace() || html[next] == '>' || html[next] == '/'
}

private fun skipScriptElement(html: String, index: Int): Int {
    val closeStart = html.indexOf("</script", startIndex = index + "<script".length, ignoreCase = true)
    if (closeStart == -1) return html.length
    val closeEnd = html.indexOf('>', startIndex = closeStart)
    return if (closeEnd == -1) html.length else closeEnd + 1
}

private fun skipEventHandlerAttribute(html: String, index: Int): Int {
    if (index + 2 > html.length || !html.startsWith("on", index, ignoreCase = true)) return -1

    var cursor = index + 2
    while (cursor < html.length && (html[cursor].isLetterOrDigit() || html[cursor] == '_' || html[cursor] == '-')) {
        cursor++
    }
    if (cursor == index + 2) return -1

    while (cursor < html.length && html[cursor].isWhitespace()) cursor++
    if (cursor >= html.length || html[cursor] != '=') return -1
    cursor++
    while (cursor < html.length && html[cursor].isWhitespace()) cursor++
    if (cursor >= html.length) return cursor

    val quote = html[cursor]
    if (quote == '"' || quote == '\'') {
        cursor++
        while (cursor < html.length && html[cursor] != quote) cursor++
        return if (cursor < html.length) cursor + 1 else cursor
    }

    while (cursor < html.length && !html[cursor].isWhitespace() && html[cursor] != '>') cursor++
    return cursor
}

private fun matchJavascriptSchemeEnd(html: String, index: Int): Int {
    val keyword = "javascript"
    var cursor = index
    for (expected in keyword) {
        if (cursor >= html.length) return -1
        val decoded = decodeHtmlEntityAt(html, cursor)
        val ch = decoded?.char ?: html[cursor]
        if (!ch.equals(expected, ignoreCase = true)) return -1
        cursor += decoded?.length ?: 1
    }

    while (cursor < html.length) {
        val decoded = decodeHtmlEntityAt(html, cursor)
        val ch = decoded?.char ?: html[cursor]
        if (!ch.isWhitespace()) break
        cursor += decoded?.length ?: 1
    }

    return if (cursor < html.length && html[cursor] == ':') cursor + 1 else -1
}
