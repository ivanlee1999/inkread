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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** Cache for base64-encoded font data keyed by file path, so font files are only read once per app session. */
private object FontBase64Cache {
    private val cache = HashMap<String, String>()

    fun getOrPut(filePath: String): String = synchronized(cache) {
        cache.getOrPut(filePath) {
            val fontBytes = java.io.File(filePath).readBytes()
            android.util.Base64.encodeToString(fontBytes, android.util.Base64.NO_WRAP)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EInkPaginatedContent(
    modifier: Modifier = Modifier,
    content: String,
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

    var horizontalDrag by remember { mutableFloatStateOf(0f) }
    var showLeftArrow by remember { mutableStateOf(false) }
    var showRightArrow by remember { mutableStateOf(false) }
    var showBoundaryText by remember { mutableStateOf<String?>(null) }
    var dragVisualTarget by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val maxDragPx = remember(density) { with(density) { 80.dp.toPx() } }

    // Bottom bar auto-hide state
    var bottomBarVisible by rememberSaveable { mutableStateOf(false) }

    // Auto-hide the bottom bar after 3 seconds of no interaction
    // Bottom bar stays visible until user taps the middle area to dismiss.
    // No auto-hide timer — user controls visibility explicitly.
    val haptic = LocalHapticFeedback.current
    // Use direct state (not animated) — e-ink displays can't show smooth animation
    // and animateFloatAsState can leave stale offsets on e-ink refresh.
    val dragVisualOffset = dragVisualTarget
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
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
                onPageChanged?.invoke(page, total)
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
            onPageTurnFeedback = { direction ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                when (direction) {
                    VolumeKeyEvent.NEXT -> coroutineScope.launch {
                        showRightArrow = true
                        delay(500)
                        showRightArrow = false
                    }
                    VolumeKeyEvent.PREV -> coroutineScope.launch {
                        showLeftArrow = true
                        delay(500)
                        showLeftArrow = false
                    }
                }
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
    val volumeKeyFlow = remember { VolumeKeyEventBus.register(VolumeKeyPriority.HIGH) }
    DisposableEffect(Unit) {
        onDispose { VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH) }
    }
    val articleHtmlDocument = remember(content, einkFontSize, einkEnglishFont, einkChineseFont, englishFontFilePath, chineseFontFilePath, title, feedName, author, publishedDate, horizontalPadding, lineHeight, letterSpacing, wordSpacing) {
        buildArticleHtmlDocument(context.cacheDir, content, einkFontSize, einkEnglishFont, einkChineseFont, englishFontFilePath, chineseFontFilePath, title, feedName, author, publishedDate, horizontalPadding, lineHeight, letterSpacing, wordSpacing)
    }
    DisposableEffect(articleHtmlDocument) {
        onDispose { articleHtmlDocument.file.delete() }
    }
    val currentArticleHtmlDocument = rememberUpdatedState(articleHtmlDocument)

    // Track whether initial pagination is complete so we can hide WebView until ready.
    // Use a STABLE (unkeyed) state so the JS bridge callback always writes to the same object.
    // Reset it when the article document changes so the placeholder shows immediately.
    val isInitialPaginationReadyState = remember { mutableStateOf(false) }
    var isInitialPaginationReady by isInitialPaginationReadyState
    // Track the last article document to detect changes and reset pagination ready state.
    val lastHtmlKeyForReady = remember { mutableStateOf(articleHtmlDocument.cacheKey) }
    if (lastHtmlKeyForReady.value != articleHtmlDocument.cacheKey) {
        lastHtmlKeyForReady.value = articleHtmlDocument.cacheKey
        isInitialPaginationReady = false
        navController.onContentReset()
        currentPage = 0
        totalPages = 0
    }

    // Safety net: if JS never calls onInitialPaginationReady (WebView failure,
    // JS error, etc.), force the flag so the user can at least swipe away.
    LaunchedEffect(articleHtmlDocument.cacheKey) {
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
        Log.d("InkRead", "nextPage -> delegating to navController")
        navController.handleNavigation(VolumeKeyEvent.NEXT)
        // Sync Compose state from controller
        currentPage = navController.currentPage
        totalPages = navController.totalPages
    }

    fun prevPage() {
        Log.d("InkRead", "prevPage -> delegating to navController")
        navController.handleNavigation(VolumeKeyEvent.PREV)
        // Sync Compose state from controller
        currentPage = navController.currentPage
        totalPages = navController.totalPages
    }

    LaunchedEffect(Unit) {
        volumeKeyFlow.collect { event ->
            if (VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH)) {
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
                            ): Boolean = url != currentArticleHtmlDocument.value.url

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                            ): Boolean = request?.url?.toString() != currentArticleHtmlDocument.value.url

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                            ): WebResourceResponse? {
                                val document = currentArticleHtmlDocument.value
                                return if (request?.url?.toString() == document.url && document.file.exists()) {
                                    WebResourceResponse("text/html", "UTF-8", document.file.inputStream())
                                } else {
                                    super.shouldInterceptRequest(view, request)
                                }
                            }
                        }
                        addJavascriptInterface(
                            EInkJsInterface(
                                onTotalPages = { pages ->
                                    Handler(Looper.getMainLooper()).post {
                                        navController.onTotalPagesUpdated(pages)
                                        totalPages = navController.totalPages
                                        currentPage = navController.currentPage
                                    }
                                },
                                onInitialPaginationReady = { pages ->
                                    Handler(Looper.getMainLooper()).post {
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
                        lastLoadedHtmlKey = articleHtmlDocument.cacheKey
                        loadUrl(articleHtmlDocument.url)
                    }
                },
                update = { webView ->
                    // Reload content when html changes (style/content change) without
                    // destroying and recreating the entire WebView composable.
                    // Note: isInitialPaginationReady is already reset synchronously via
                    // remember(articleHtmlDocument.cacheKey) — no need to reset it here.
                    if (articleHtmlDocument.cacheKey != lastLoadedHtmlKey) {
                        lastLoadedHtmlKey = articleHtmlDocument.cacheKey
                        webView.loadUrl(articleHtmlDocument.url)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = dragVisualOffset
                        alpha = if (isInitialPaginationReady) 1f else 0f
                    },
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Use awaitEachGesture so each gesture starts fresh.
                        // Only consume events after touch slop is crossed as a horizontal drag —
                        // this lets child clickable tap zones still receive short taps for page turning.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Do NOT consume the down yet — let child clickables see it too.
                            var totalDragX = 0f
                            var totalDragY = 0f
                            var isDragConfirmed = false
                            var pointer = down
                            while (true) {
                                val event = awaitPointerEvent()
                                val dragChange = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!dragChange.pressed) break // finger lifted
                                val delta = dragChange.positionChange()
                                totalDragX += delta.x
                                totalDragY += delta.y
                                if (!isDragConfirmed) {
                                    val distX = kotlin.math.abs(totalDragX)
                                    val distY = kotlin.math.abs(totalDragY)
                                    if (distX > viewConfiguration.touchSlop && distX > distY * 1.2f) {
                                        // Confirmed horizontal drag — now start consuming
                                        isDragConfirmed = true
                                    } else if (distY > viewConfiguration.touchSlop && distY > distX * 2f) {
                                        // Clearly vertical intent — bail out, let child handle it
                                        break
                                    }
                                }
                                if (isDragConfirmed) {
                                    dragChange.consume()
                                    horizontalDrag = totalDragX
                                    dragVisualTarget = totalDragX.coerceIn(-maxDragPx, maxDragPx)
                                }
                            }
                            // Gesture ended — switch article if pagination is ready or
                            // page count is known (totalPages > 0 means JS ran successfully)
                            if (isDragConfirmed && (isInitialPaginationReady || totalPages > 0)) {
                                if (totalDragX < -100f && currentOnNextArticle != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnNextArticle?.invoke()
                                } else if (totalDragX > 100f && currentOnPrevArticle != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnPrevArticle?.invoke()
                                }
                            }
                            horizontalDrag = 0f
                            dragVisualTarget = 0f
                        }
                    },
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

            // Left arrow overlay - page turn feedback
            androidx.compose.animation.AnimatedVisibility(
                visible = showLeftArrow,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
            ) {
                Text(
                    text = "←",
                    fontSize = 48.sp,
                    color = Color.Black.copy(alpha = 0.5f),
                )
            }

            // Right arrow overlay - page turn feedback
            androidx.compose.animation.AnimatedVisibility(
                visible = showRightArrow,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            ) {
                Text(
                    text = "→",
                    fontSize = 48.sp,
                    color = Color.Black.copy(alpha = 0.5f),
                )
            }

            // Boundary indicator - no more articles
            androidx.compose.animation.AnimatedVisibility(
                visible = showBoundaryText != null,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    text = showBoundaryText ?: "",
                    fontSize = 16.sp,
                    color = Color.Black.copy(alpha = 0.4f),
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

        // Full bottom bar — hidden by default, shown on tap, auto-hides after 3s
        // Also hidden when pagination is not yet ready (totalPages == 0)
        AnimatedVisibility(
            visible = bottomBarVisible && totalPages > 0,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(animationSpec = tween(200)),
        ) {
            EInkPaginationBar(
                currentPage = currentPage + 1,
                totalPages = totalPages,
                onPrev = {
                    prevPage()
                    bottomBarVisible = true // Reset auto-hide timer on interaction
                },
                onNext = {
                    nextPage()
                    bottomBarVisible = true // Reset auto-hide timer on interaction
                },
                canDecreaseFontSize = fontSizeIndex > 0,
                canIncreaseFontSize = fontSizeIndex < EInkFontSizePreference.values.lastIndex,
                onDecreaseFontSize = {
                    bottomBarVisible = true // Reset auto-hide timer
                    if (fontSizeIndex > 0) {
                        EInkFontSizePreference.put(
                            context, coroutineScope,
                            EInkFontSizePreference.values[fontSizeIndex - 1],
                        )
                    }
                },
                onIncreaseFontSize = {
                    bottomBarVisible = true // Reset auto-hide timer
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
    private val onTotalPages: (Int) -> Unit,
    private val onInitialPaginationReady: (Int) -> Unit,
) {
    @JavascriptInterface
    fun onTotalPages(pages: Int) = onTotalPages.invoke(pages)

    @JavascriptInterface
    fun onInitialPaginationReady(pages: Int) = onInitialPaginationReady.invoke(pages)
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
): EInkArticleHtmlDocument {
    val outputDir = File(cacheDir, "eink-articles").apply { mkdirs() }
    deleteStaleArticleHtmlFiles(outputDir)

    val cacheKey = listOf(
        content.length,
        content.hashCode(),
        fontSize,
        englishFont,
        chineseFont,
        englishFontFilePath.orEmpty(),
        chineseFontFilePath.orEmpty(),
        title,
        feedName,
        author.orEmpty(),
        publishedDate.time,
        horizontalPadding,
        lineHeight,
        letterSpacing,
        wordSpacing,
    ).joinToString(separator = "|")
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
        val mimeType = if (filePath.endsWith(".otf")) "font/otf" else "font/ttf"
        val base64 = FontBase64Cache.getOrPut(filePath)
        return """
@font-face {
    font-family: '$cssName';
    src: url('data:$mimeType;base64,$base64') format('$format');
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
html, body { margin: 0; padding: 0; }
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
var _vw = 0, _pageStride = 0, _totalPages = 1, _didFinishInitialPagination = false;
function recountPages() {
    try {
        var sw = document.body.scrollWidth;
        if (!sw || !_pageStride || _pageStride <= 0 || !isFinite(sw) || !isFinite(_pageStride)) {
            _totalPages = 1;
            Android.onTotalPages(_totalPages);
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
        Android.onTotalPages(_totalPages);
    } catch (e) {
        _totalPages = 1;
        try { Android.onTotalPages(_totalPages); } catch (e2) {}
    }
}
function goToPage(n) {
    // Clamp to valid range
    if (n < 0) n = 0;
    if (n >= _totalPages) n = _totalPages - 1;
    document.body.style.transform = 'translateX(-' + (n * _pageStride) + 'px)';
}
function finishInitialPagination() {
    if (_didFinishInitialPagination) return;
    _didFinishInitialPagination = true;
    try {
        _vw = window.innerWidth;
        var vh = window.innerHeight;
        if (!_vw || _vw <= 0) _vw = document.documentElement.clientWidth || 360;
        if (!vh || vh <= 0) vh = document.documentElement.clientHeight || 640;
        _pageStride = _vw;
        document.body.style.height = vh + 'px';
        var padding = ${horizontalPadding};
        document.body.style.columnGap = (padding * 2) + 'px';
        document.body.style.columnWidth = (_pageStride - padding * 2) + 'px';
        recountPages();
        goToPage(0);
        document.body.style.visibility = 'visible';
    } catch (e) {
        _totalPages = 1;
        try { document.body.style.visibility = 'visible'; } catch (e2) {}
    }
    // ALWAYS fire — this is the call that unblocks Kotlin gesture handling.
    // Must be outside the try block so it executes even if setup failed.
    try { Android.onInitialPaginationReady(_totalPages); } catch (e) {}
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
    window.requestAnimationFrame(function() {
        finishInitialPagination();
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

        val decoded = decodeHtmlEntityAt(html, index)
        if (decoded != null) {
            appendChar(decoded.char)
            index += decoded.length
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
