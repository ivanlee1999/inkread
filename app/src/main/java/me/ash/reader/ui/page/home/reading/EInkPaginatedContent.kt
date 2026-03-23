package me.ash.reader.ui.page.home.reading

import android.annotation.SuppressLint
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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

    // Stable (unkeyed) state so the JS bridge always writes to the same objects.
    // Reset inline when htmlContent changes (same pattern as isInitialPaginationReady).
    val currentPageState = rememberSaveable { mutableIntStateOf(0) }
    var currentPage by currentPageState
    val totalPagesState = rememberSaveable { mutableIntStateOf(0) }
    var totalPages by totalPagesState
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
    var bottomBarVisible by remember { mutableStateOf(false) }

    // Auto-hide the bottom bar after 3 seconds of no interaction
    LaunchedEffect(bottomBarVisible) {
        if (bottomBarVisible) {
            delay(3000)
            bottomBarVisible = false
        }
    }
    val haptic = LocalHapticFeedback.current
    // Use direct state (not animated) — e-ink displays can't show smooth animation
    // and animateFloatAsState can leave stale offsets on e-ink refresh.
    val dragVisualOffset = dragVisualTarget
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

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
    val htmlContent = remember(content, einkFontSize, einkEnglishFont, einkChineseFont, englishFontFilePath, chineseFontFilePath, title, feedName, author, publishedDate, horizontalPadding, lineHeight, letterSpacing, wordSpacing) {
        buildArticleHtml(content, einkFontSize, einkEnglishFont, einkChineseFont, englishFontFilePath, chineseFontFilePath, title, feedName, author, publishedDate, horizontalPadding, lineHeight, letterSpacing, wordSpacing)
    }

    // Track whether initial pagination is complete so we can hide WebView until ready.
    // Use a STABLE (unkeyed) state so the JS bridge callback always writes to the same object.
    // Reset it via SideEffect when htmlContent changes so the placeholder shows immediately.
    val isInitialPaginationReadyState = remember { mutableStateOf(false) }
    var isInitialPaginationReady by isInitialPaginationReadyState
    // Track the last htmlContent to detect changes and reset pagination ready state.
    val lastHtmlContentForReady = remember { mutableStateOf(htmlContent) }
    if (lastHtmlContentForReady.value != htmlContent) {
        lastHtmlContentForReady.value = htmlContent
        isInitialPaginationReady = false
        currentPage = 0
        totalPages = 0
    }

    fun nextPage() {
        // Only act if pagination is ready — totalPages=0 during loading would cause
        // premature article switching (0 < 0-1 is false, falls through to onNextArticle).
        if (!isInitialPaginationReady) return
        if (currentPage < totalPages - 1) {
            currentPage++
            Log.d("InkRead", "nextPage -> currentPage=$currentPage totalPages=$totalPages")
            webViewRef.value?.evaluateJavascript("goToPage($currentPage)", null)
            onPageChanged?.invoke(currentPage + 1, totalPages)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            coroutineScope.launch {
                showRightArrow = true
                delay(500)
                showRightArrow = false
            }
        } else if (onNextArticle != null) {
            Log.d("InkRead", "nextPage -> last page, switching to next article")
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onNextArticle.invoke()
        } else {
            // No more articles - show boundary feedback
            coroutineScope.launch {
                showBoundaryText = "No more articles"
                delay(1000)
                showBoundaryText = null
            }
        }
    }

    fun prevPage() {
        if (!isInitialPaginationReady) return
        if (currentPage > 0) {
            currentPage--
            Log.d("InkRead", "prevPage -> currentPage=$currentPage totalPages=$totalPages")
            webViewRef.value?.evaluateJavascript("goToPage($currentPage)", null)
            onPageChanged?.invoke(currentPage + 1, totalPages)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            coroutineScope.launch {
                showLeftArrow = true
                delay(500)
                showLeftArrow = false
            }
        } else if (onPrevArticle != null) {
            Log.d("InkRead", "prevPage -> first page, switching to previous article")
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onPrevArticle.invoke()
        } else {
            // No previous articles - show boundary feedback
            coroutineScope.launch {
                showBoundaryText = "No previous articles"
                delay(1000)
                showBoundaryText = null
            }
        }
    }

    LaunchedEffect(Unit) {
        volumeKeyFlow.collect { event ->
            if (VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH)) {
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
                            ): Boolean = true

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                            ): Boolean = true
                        }
                        addJavascriptInterface(
                            EInkJsInterface(
                                onTotalPages = { pages ->
                                    Handler(Looper.getMainLooper()).post {
                                        totalPages = maxOf(1, pages)
                                        // Clamp currentPage if totalPages decreased (e.g. after image load recount)
                                        if (currentPage >= totalPages) {
                                            currentPage = totalPages - 1
                                            webViewRef.value?.evaluateJavascript("goToPage($currentPage)", null)
                                        }
                                        onPageChanged?.invoke(currentPage + 1, totalPages)
                                    }
                                },
                                onInitialPaginationReady = { pages ->
                                    Handler(Looper.getMainLooper()).post {
                                        totalPages = maxOf(1, pages)
                                        currentPage = 0
                                        isInitialPaginationReady = true
                                        onPageChanged?.invoke(currentPage + 1, totalPages)
                                    }
                                },
                            ),
                            "Android",
                        )
                        webViewRef.value = this
                        lastLoadedHtmlKey = htmlContent
                        loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                    }
                },
                update = { webView ->
                    // Reload content when html changes (style/content change) without
                    // destroying and recreating the entire WebView composable.
                    // Note: isInitialPaginationReady is already reset synchronously via
                    // remember(htmlContent) — no need to reset it here.
                    if (htmlContent != lastLoadedHtmlKey) {
                        lastLoadedHtmlKey = htmlContent
                        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
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
                            // Gesture ended — only switch article if pagination is ready
                            if (isDragConfirmed && isInitialPaginationReady) {
                                if (totalDragX < -100f && onNextArticle != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onNextArticle.invoke()
                                } else if (totalDragX > 100f && onPrevArticle != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPrevArticle.invoke()
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

private fun buildArticleHtml(
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
): String {
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

    return """<!DOCTYPE html>
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
    max-width: 100%;
    overflow-x: auto;
    break-inside: avoid;
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
var _vw, _totalPages = 1;
function recountPages() {
    var sw = document.body.scrollWidth;
    // scrollWidth can be slightly larger than content due to rounding
    // Use floor + check if there's actual content on the last page
    var n = Math.max(1, Math.round(sw / _vw));
    // Verify last page has content by checking if scrolling there shows anything
    if (n > 1) {
        // Check if actual content extends to the last page
        var lastPageStart = (n - 1) * _vw;
        if (sw <= lastPageStart + 1) {
            n = n - 1;  // Last page is empty
        }
    }
    _totalPages = Math.max(1, n);
    Android.onTotalPages(_totalPages);
}
function goToPage(n) {
    // Clamp to valid range
    if (n < 0) n = 0;
    if (n >= _totalPages) n = _totalPages - 1;
    document.body.style.transform = 'translateX(-' + (n * _vw) + 'px)';
}
function finishInitialPagination() {
    _vw = window.innerWidth;
    var vh = window.innerHeight;
    document.body.style.height = vh + 'px';
    var padding = ${horizontalPadding};
    document.body.style.columnWidth = (_vw - padding * 2) + 'px';
    recountPages();
    goToPage(0);
    document.body.style.visibility = 'visible';
    Android.onInitialPaginationReady(_totalPages);
    // Delayed recounts for image reflow — do NOT re-hide body
    setTimeout(recountPages, 500);
    setTimeout(recountPages, 1500);
    // Recount when images finish loading — handles images that arrive after the
    // fixed timeouts above (slow network, large files).
    document.querySelectorAll('img').forEach(function(img) {
        if (!img.complete) {
            img.onload = function() { recountPages(); };
        }
    });
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
<body onload="finishInitialPagination()">
<div class="eink-metadata">
  <h1>$escapedTitle</h1>
  <p class="eink-meta">$escapedMeta</p>
</div>
${sanitizeHtml(content)}
</body>
</html>"""
}

/**
 * HTML sanitization to strip script injection vectors before
 * loading content into the WebView with JavaScript enabled.
 *
 * Handles:
 * - `<script>` tags and their content (including unclosed tags)
 * - Quoted event handlers: `onclick="..."` / `onclick='...'`
 * - Unquoted event handlers: `onclick=alert(1)`
 * - `javascript:` scheme including HTML-entity-encoded variants
 */
private fun sanitizeHtml(html: String): String {
    // First decode common HTML entities that could hide "javascript:" scheme
    // e.g. java&#x73;cript: , java&#115;cript: , java&Tab;script:
    val decoded = html
        .replace(Regex("&#x[0-9a-fA-F]+;"), { m ->
            val code = m.value.removePrefix("&#x").removeSuffix(";").toIntOrNull(16)
            if (code != null) code.toChar().toString() else m.value
        })
        .replace(Regex("&#[0-9]+;"), { m ->
            val code = m.value.removePrefix("&#").removeSuffix(";").toIntOrNull()
            if (code != null) code.toChar().toString() else m.value
        })

    return decoded
        // Strip <script> tags and all content between them (including nested / unclosed)
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<script[^>]*/?>", RegexOption.IGNORE_CASE), "")
        // Strip quoted event handlers: onclick="..." / onclick='...'
        .replace(Regex("\\bon\\w+\\s*=\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\bon\\w+\\s*=\\s*'[^']*'", RegexOption.IGNORE_CASE), "")
        // Strip unquoted event handlers: onclick=alert(1)  (value ends at space or >)
        .replace(Regex("\\bon\\w+\\s*=\\s*[^\"'\\s>]+", RegexOption.IGNORE_CASE), "")
        // Strip javascript: scheme (after entity decoding above)
        .replace(Regex("javascript\\s*:", RegexOption.IGNORE_CASE), "")
}
