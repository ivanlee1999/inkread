package me.ash.reader.ui.page.home.reading

import android.annotation.SuppressLint
import android.os.Handler
import android.util.Log
import android.os.Looper
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority
import androidx.compose.runtime.DisposableEffect
import me.ash.reader.infrastructure.preference.EInkChineseFontPreference
import me.ash.reader.infrastructure.preference.EInkEnglishFontPreference
import me.ash.reader.infrastructure.preference.EInkFontSizePreference
import me.ash.reader.infrastructure.preference.LocalEInkChineseFont
import me.ash.reader.infrastructure.preference.LocalEInkEnglishFont
import me.ash.reader.infrastructure.preference.LocalEInkFontSize
import me.ash.reader.infrastructure.preference.LocalEInkWordSpacing
import me.ash.reader.infrastructure.preference.LocalReadingTextHorizontalPadding
import me.ash.reader.infrastructure.preference.LocalReadingTextLetterSpacing
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.ui.page.home.flow.EInkPaginationBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

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
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val einkFontSize = LocalEInkFontSize.current
    val einkEnglishFont = LocalEInkEnglishFont.current
    val einkChineseFont = LocalEInkChineseFont.current
    val horizontalPadding = LocalReadingTextHorizontalPadding.current
    val lineHeight = LocalReadingTextLineHeight.current
    val letterSpacing = LocalReadingTextLetterSpacing.current
    val wordSpacing = LocalEInkWordSpacing.current
    val fontSizeIndex = EInkFontSizePreference.values.indexOf(einkFontSize)
        .takeIf { it >= 0 } ?: EInkFontSizePreference.values.indexOf(EInkFontSizePreference.default)

    val fontsDir = remember { File(context.filesDir, "fonts") }

    // Resolve font file paths from local storage (null = system font or file not yet downloaded)
    val englishFontFilePath = remember(einkEnglishFont) {
        EInkEnglishFontPreference.fontFileNames.getOrNull(einkEnglishFont)
            ?.let { File(fontsDir, it) }
            ?.takeIf { it.exists() }
            ?.absolutePath
    }
    val chineseFontFilePath = remember(einkChineseFont) {
        EInkChineseFontPreference.fontFileNames.getOrNull(einkChineseFont)
            ?.let { File(fontsDir, it) }
            ?.takeIf { it.exists() }
            ?.absolutePath
    }

    // Track the last loaded HTML key to detect content/style changes in the update callback
    var lastLoadedHtmlKey by remember { mutableStateOf("") }

    var showBoundaryText by remember { mutableStateOf<String?>(null) }

    // Bottom bar auto-hide state
    var bottomBarVisible by rememberSaveable { mutableStateOf(false) }

    // Auto-hide the bottom bar after 3 seconds of no interaction
    // Bottom bar stays visible until user taps the middle area to dismiss.
    // No auto-hide timer — user controls visibility explicitly.
    val haptic = LocalHapticFeedback.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    val currentOnNextArticle by rememberUpdatedState(onNextArticle)
    val currentOnPrevArticle by rememberUpdatedState(onPrevArticle)

    // Compose state wrappers that the navigation controller drives.
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var totalPages by rememberSaveable { mutableIntStateOf(0) }

    // Extracted navigation controller — keeps page-state decisions testable
    // and queues volume key events received while pagination is resetting.
    val navController = remember {
        EInkPageNavigationController(
            onPageChanged = { page, total ->
                currentOnPageChanged?.invoke(page, total)
            },
            onApplyPageToWebView = { page ->
                webViewRef.value?.evaluateJavascript("goToPage($page)", null)
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

    // Clean up WebView when leaving composition to prevent memory leaks
    DisposableEffect(Unit) {
        onDispose {
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
    val documentGeneration = remember(articleId, link, content, einkFontSize, einkEnglishFont, einkChineseFont,
        englishFontFilePath, chineseFontFilePath, title, feedName, author, publishedDate,
        horizontalPadding, lineHeight, letterSpacing, wordSpacing) { UUID.randomUUID().toString() }
    val currentGeneration = rememberUpdatedState(documentGeneration)
    val fontResources = remember(englishFontFilePath, chineseFontFilePath) {
        listOfNotNull(englishFontFilePath, chineseFontFilePath).map { EInkFontResource(File(it)) }
    }
    val activeFontResources = remember { AtomicReference<List<EInkFontResource>>(emptyList()) }
    val articleHtmlDocument by produceState<EInkArticleHtmlDocument?>(null, documentGeneration) {
        // Complete file creation even if navigation cancels this producer, then clean up.
        val document = withContext(Dispatchers.IO + NonCancellable) {
            buildArticleHtmlDocument(context.cacheDir, content, einkFontSize, einkEnglishFont,
                einkChineseFont, englishFontFilePath, chineseFontFilePath, title, feedName,
                author, publishedDate, horizontalPadding, lineHeight, letterSpacing, wordSpacing,
                documentGeneration)
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

    fun nextPage() {
        navController.handleNavigation(VolumeKeyEvent.NEXT)
        // Sync Compose state from controller
        currentPage = navController.currentPage
        totalPages = navController.totalPages
    }

    fun prevPage() {
        navController.handleNavigation(VolumeKeyEvent.PREV)
        // Sync Compose state from controller
        currentPage = navController.currentPage
        totalPages = navController.totalPages
    }

    LaunchedEffect(volumeKeyFlow) {
        volumeKeyFlow.collect { event ->
            if (VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH, owner)) {
                Log.d("InkRead", "Volume key event received: $event")
                when (event) {
                    VolumeKeyEvent.NEXT -> nextPage()
                    VolumeKeyEvent.PREV -> prevPage()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
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
                    object : WebView(ctx) {
                        // Override key handling so volume keys always pass
                        // through to Activity.dispatchKeyEvent, even if the
                        // WebView somehow gains focus.
                        override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
                            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                                return false // let Activity handle it
                            }
                            return super.onKeyDown(keyCode, event)
                        }
                        override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
                            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                                return false // let Activity handle it
                            }
                            return super.onKeyUp(keyCode, event)
                        }
                    }.apply {
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
                        isFocusable = false
                        isFocusableInTouchMode = false
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
                                onTotalPages = { generation, pages ->
                                    Handler(Looper.getMainLooper()).post {
                                        if (generation != currentGeneration.value || webViewRef.value == null) return@post
                                        navController.onTotalPagesUpdated(pages)
                                        totalPages = navController.totalPages
                                        currentPage = navController.currentPage
                                    }
                                },
                                onRepaginated = { generation, pages, page ->
                                    Handler(Looper.getMainLooper()).post {
                                        if (generation != currentGeneration.value || webViewRef.value == null) return@post
                                        navController.onRepaginated(pages, page)
                                        totalPages = navController.totalPages
                                        currentPage = navController.currentPage
                                    }
                                },
                                onInitialPaginationReady = { generation, pages ->
                                    Handler(Looper.getMainLooper()).post {
                                        if (generation != currentGeneration.value || webViewRef.value == null) return@post
                                        navController.onPaginationReady(pages)
                                        isInitialPaginationReady = true
                                        totalPages = navController.totalPages
                                        currentPage = navController.currentPage
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
                    // Reload content when html changes (style/content change) without
                    // destroying and recreating the entire WebView composable.
                    // Note: isInitialPaginationReady is already reset synchronously via
                    // remember(articleHtmlDocument.cacheKey) — no need to reset it here.
                    articleHtmlDocument?.takeIf {
                        it.cacheKey == documentGeneration && it.cacheKey != lastLoadedHtmlKey
                    }?.let {
                        activeArticleHtmlDocument.set(it)
                        activeFontResources.set(fontResources)
                        lastLoadedHtmlKey = it.cacheKey
                        webView.loadUrl(it.url)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (isInitialPaginationReady) 1f else 0f
                    },
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .readerArticleSwipe(
                        gestureKey = documentGeneration to currentPage,
                        enabled = isInitialPaginationReady || totalPages > 0,
                        consumeRejectedVertical = true,
                        onPrevious = currentOnPrevArticle?.let { action ->
                            { haptic.performHapticFeedback(HapticFeedbackType.LongPress); action() }
                        },
                        onNext = currentOnNextArticle?.let { action ->
                            { haptic.performHapticFeedback(HapticFeedbackType.LongPress); action() }
                        },
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.4f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { prevPage() },
                    contentAlignment = Alignment.Center,
                ) {}
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.6f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { nextPage() },
                    contentAlignment = Alignment.Center,
                ) {}
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

            // Tap detector on upper center 15% of content area to open reading style settings
            if (onNavigateToStylePage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .fillMaxHeight(0.15f)
                        .align(Alignment.TopCenter)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onNavigateToStylePage.invoke()
                        },
                )
            }

            // Tap detector on bottom 15% of content area to toggle bottom bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.15f)
                    .align(Alignment.BottomCenter)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        bottomBarVisible = !bottomBarVisible
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
            visible = bottomBarVisible && totalPages > 0,
            enter = androidx.compose.animation.EnterTransition.None,
            exit = androidx.compose.animation.ExitTransition.None,
        ) {
            EInkPaginationBar(
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
                canDecreaseFontSize = fontSizeIndex > 0,
                canIncreaseFontSize = fontSizeIndex < EInkFontSizePreference.values.lastIndex,
                onDecreaseFontSize = {
                    bottomBarVisible = true
                    if (fontSizeIndex > 0) {
                        EInkFontSizePreference.put(
                            context, coroutineScope,
                            EInkFontSizePreference.values[fontSizeIndex - 1],
                        )
                    }
                },
                onIncreaseFontSize = {
                    bottomBarVisible = true
                    if (fontSizeIndex < EInkFontSizePreference.values.lastIndex) {
                        EInkFontSizePreference.put(
                            context, coroutineScope,
                            EInkFontSizePreference.values[fontSizeIndex + 1],
                        )
                    }
                },
                onPrevArticle = onPrevArticle,
                onNextArticle = onNextArticle,
                progress = progress,
                currentArticleIndex = currentArticleIndex,
                totalArticleCount = totalArticleCount,
            )
        }
    }
}

private class EInkJsInterface(
    private val onTotalPages: (String, Int) -> Unit,
    private val onInitialPaginationReady: (String, Int) -> Unit,
    private val onRepaginated: (String, Int, Int) -> Unit,
) {
    @JavascriptInterface
    fun onTotalPages(generation: String, pages: Int) = onTotalPages.invoke(generation, pages)

    @JavascriptInterface
    fun onRepaginated(generation: String, pages: Int, page: Int) = onRepaginated.invoke(generation, pages, page)

    @JavascriptInterface
    fun onInitialPaginationReady(generation: String, pages: Int) = onInitialPaginationReady.invoke(generation, pages)
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
    fontSize: Int,
    englishFont: Int,
    chineseFont: Int,
    englishFontFilePath: String?,
    chineseFontFilePath: String?,
    title: String,
    feedName: String,
    author: String?,
    publishedDate: Date,
    horizontalPadding: Int,
    lineHeight: Float,
    letterSpacing: Float,
    wordSpacing: Float,
    generation: String,
): EInkArticleHtmlDocument {
    val outputDir = File(cacheDir, "eink-articles").apply { mkdirs() }
    deleteStaleArticleHtmlFiles(outputDir)

    val cacheKey = generation
    val outputFile = File.createTempFile("article-", ".html", outputDir)

    outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
        writeArticleHtml(
            writer = writer,
            content = content,
            fontSize = fontSize,
            englishFont = englishFont,
            chineseFont = chineseFont,
            englishFontFilePath = englishFontFilePath,
            chineseFontFilePath = chineseFontFilePath,
            title = title,
            feedName = feedName,
            author = author,
            publishedDate = publishedDate,
            horizontalPadding = horizontalPadding,
            lineHeight = lineHeight,
            letterSpacing = letterSpacing,
            wordSpacing = wordSpacing,
            generation = generation,
        )
    }

    return EInkArticleHtmlDocument(file = outputFile, cacheKey = cacheKey)
}

private fun deleteStaleArticleHtmlFiles(outputDir: File) {
    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
    outputDir.listFiles { file -> file.isFile && file.extension == "html" && file.lastModified() < cutoff }
        ?.forEach { file -> runCatching { file.delete() } }
}

private fun writeArticleHtml(
    writer: Writer,
    content: String,
    fontSize: Int,
    englishFont: Int,
    chineseFont: Int,
    englishFontFilePath: String?,
    chineseFontFilePath: String?,
    title: String,
    feedName: String,
    author: String?,
    publishedDate: Date,
    horizontalPadding: Int,
    lineHeight: Float,
    letterSpacing: Float,
    wordSpacing: Float,
    generation: String,
) {
    val dateStr = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(publishedDate)
    val metaLine = buildString {
        append(feedName)
        if (!author.isNullOrBlank()) append(" · $author")
        append(" · $dateStr")
    }
    fun String.esc() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val escapedTitle = title.esc()
    val escapedMeta = metaLine.esc()

    fun fontFaceBlock(cssName: String, filePath: String): String {
        val format = if (filePath.endsWith(".otf")) "opentype" else "truetype"
        val url = EInkFontResource(File(filePath)).url
        return """
@font-face {
    font-family: '$cssName';
    src: url('$url') format('$format');
    font-weight: normal;
    font-style: normal;
}"""
    }

    val englishFontFace = if (englishFontFilePath != null) {
        val cssName = EInkEnglishFontPreference.fontCssNames.getOrNull(englishFont)
        if (cssName != null) fontFaceBlock(cssName, englishFontFilePath) else ""
    } else ""

    val chineseFontFace = if (chineseFontFilePath != null) {
        val cssName = EInkChineseFontPreference.fontCssNames.getOrNull(chineseFont)
        if (cssName != null) fontFaceBlock(cssName, chineseFontFilePath) else ""
    } else ""

    val englishFamilyCss = EInkEnglishFontPreference.fontFamilyCss.getOrElse(englishFont) { "Georgia, serif" }
    val chineseFamilyCss = EInkChineseFontPreference.fontFamilyCss.getOrElse(chineseFont) { "" }
    val fontFamilyCss = if (chineseFamilyCss.isNotEmpty()) {
        "$englishFamilyCss, $chineseFamilyCss"
    } else {
        englishFamilyCss
    }

    writer.write("""<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
$englishFontFace
$chineseFontFace
* { box-sizing: border-box; }
*, *::before, *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; }
html, body { margin: 0; padding: 0; background: #fff !important; }
body * { color: #000 !important; background-color: transparent !important; }
a, a:visited { color: #000 !important; text-decoration: underline; }
body {
    padding: ${horizontalPadding}px;
    font-family: $fontFamilyCss;
    font-size: ${fontSize}px;
    line-height: ${lineHeight};
    letter-spacing: ${letterSpacing}px;
    word-spacing: ${wordSpacing}em;
    color: #000;
    background: #fff;
    column-gap: ${horizontalPadding * 2}px;
    column-fill: auto;
    overflow: hidden;
    -webkit-text-size-adjust: none;
    text-size-adjust: none;
    visibility: hidden;
}
img {
    max-width: 100% !important;
    width: auto !important;
    height: auto !important;
    max-height: 90vh;
    object-fit: contain;
    break-inside: avoid;
    display: block;
    margin: 8px auto;
}
h1, h2, h3, h4, h5, h6 {
    break-inside: avoid-column;
    break-after: avoid;
}
p, li, blockquote, div {
    max-width: 100%;
    word-wrap: break-word;
    overflow-wrap: break-word;
    orphans: 3;
    widows: 3;
}
pre {
    max-width: 100%;
    white-space: pre-wrap;
    word-wrap: break-word;
    overflow-wrap: break-word;
    break-inside: avoid;
}
code {
    max-width: 100%;
    word-wrap: break-word;
    overflow-wrap: break-word;
}
table {
    display: block;
    max-width: 100% !important;
    width: 100% !important;
    overflow-x: auto;
    table-layout: fixed;
    break-inside: avoid;
}
td, th {
    overflow-wrap: anywhere;
    word-break: break-word;
    max-width: 100%;
}
svg, canvas {
    max-width: 100% !important;
    height: auto !important;
}
iframe, video, embed, object {
    max-width: 100% !important;
    break-inside: avoid;
}
.eink-metadata {
    margin-bottom: 1.2em;
    padding-bottom: 0.8em;
    border-bottom: 1px solid #999;
    break-inside: avoid-column;
}
.eink-metadata h1 {
    font-size: 1.3em;
    line-height: 1.3;
    margin: 0 0 0.4em 0;
}
.eink-meta {
    font-size: 0.82em;
    color: #666;
    margin: 0;
}
</style>
<script>
var _vw = 0, _pageStride = 0, _totalPages = 1, _currentPage = 0, _didFinishInitialPagination = false;
function recountPages() {
    try {
        var sw = document.body.scrollWidth;
        if (!sw || !_pageStride || _pageStride <= 0 || !isFinite(sw) || !isFinite(_pageStride)) {
            _totalPages = 1;
            Android.onTotalPages('$generation', _totalPages);
            return;
        }
        var n = Math.max(1, Math.round(sw / _pageStride));
        // Sanity cap: no article should exceed ${EInkPaginationMath.MAX_REASONABLE_PAGES} pages.
        // If it does, scrollWidth is wrong (wide content, layout not settled).
        if (n > ${EInkPaginationMath.MAX_REASONABLE_PAGES}) n = 1;
        if (n > 1) {
            var lastPageStart = (n - 1) * _pageStride;
            if (sw <= lastPageStart + ${EInkPaginationMath.TRAILING_SLACK_TOLERANCE_PX}) {
                n = n - 1;
            }
        }
        _totalPages = Math.max(1, n);
        Android.onTotalPages('$generation', _totalPages);
    } catch (e) {
        _totalPages = 1;
        try { Android.onTotalPages('$generation', _totalPages); } catch (e2) {}
    }
}
function goToPage(n) {
    // Clamp to valid range
    if (n < 0) n = 0;
    if (n >= _totalPages) n = _totalPages - 1;
    _currentPage = n;
    document.body.style.transform = 'translateX(-' + (n * _pageStride) + 'px)';
}
function layoutViewport() {
    _vw = window.innerWidth || document.documentElement.clientWidth || 360;
    var vh = window.innerHeight || document.documentElement.clientHeight || 640;
    _pageStride = _vw;
    document.body.style.height = vh + 'px';
    var padding = ${horizontalPadding};
    document.body.style.columnGap = (padding * 2) + 'px';
    document.body.style.columnWidth = Math.max(1, _pageStride - padding * 2) + 'px';
}
var _resizeTimer;
window.addEventListener('resize', function() {
    clearTimeout(_resizeTimer);
    _resizeTimer = setTimeout(function() {
        if (!_didFinishInitialPagination) return;
        var fraction = _totalPages > 1 ? _currentPage / (_totalPages - 1) : 0;
        document.body.style.transform = 'none';
        layoutViewport();
        recountPages();
        goToPage(Math.round(fraction * (_totalPages - 1)));
        try { Android.onRepaginated('$generation', _totalPages, _currentPage); } catch (e) {}
    }, 100);
});
function finishInitialPagination() {
    if (_didFinishInitialPagination) return;
    _didFinishInitialPagination = true;
    try {
        layoutViewport();
        recountPages();
        goToPage(0);
        document.body.style.visibility = 'visible';
    } catch (e) {
        _totalPages = 1;
        try { document.body.style.visibility = 'visible'; } catch (e2) {}
    }
    // ALWAYS fire — this is the call that unblocks Kotlin gesture handling.
    // Must be outside the try block so it executes even if setup failed.
    try { Android.onInitialPaginationReady('$generation', _totalPages); } catch (e) {}
    // Delayed recounts for image reflow — do NOT re-hide body
    setTimeout(recountPages, 500);
    setTimeout(recountPages, 1500);
    // Recount when images finish loading — handles images that arrive after the
    // fixed timeouts above (slow network, large files).
    try {
        document.querySelectorAll('img').forEach(function(img) {
            if (!img.complete) {
                img.addEventListener('load', recountPages, { once: true });
                img.addEventListener('error', recountPages, { once: true });
            }
        });
    } catch (e) {}
}
function scheduleInitialPagination() {
    // Wait for selected fonts, with a bounded fallback for old/broken WebViews.
    var done = false;
    function ready() {
        if (done) return;
        done = true;
        window.requestAnimationFrame(finishInitialPagination);
    }
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(ready, ready);
    else ready();
    setTimeout(ready, 1500);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(function() {
        if (_didFinishInitialPagination) recountPages();
    });
}
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scheduleInitialPagination, { once: true });
} else {
    scheduleInitialPagination();
}
document.addEventListener('keydown', function(e) {
    if (e.key === 'AudioVolumeUp' || e.key === 'AudioVolumeDown' || e.key === 'VolumeUp' || e.key === 'VolumeDown') {
        e.preventDefault();
        e.stopPropagation();
    }
});
// Note: Image and link tap-to-open is intentionally disabled in E-Ink mode.
// The full-screen overlay captures all taps for page turning, so WebView
// never receives touch events. Links and images are displayed but not
// interactive.
</script>
</head>
<body>
<div class="eink-metadata">
  <h1>$escapedTitle</h1>
  <p class="eink-meta">$escapedMeta</p>
</div>
""")
    appendSanitizedHtml(writer, content)
    writer.write("""
</body>
</html>""")
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
