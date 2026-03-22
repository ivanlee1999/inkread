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
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
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

@SuppressLint("SetJavaScriptEnabled")
@OptIn(FlowPreview::class)
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

    var currentPage by rememberSaveable(content, einkFontSize, horizontalPadding, lineHeight, letterSpacing, wordSpacing) { mutableIntStateOf(0) }
    var totalPages by rememberSaveable(content, einkFontSize, horizontalPadding, lineHeight, letterSpacing, wordSpacing) { mutableIntStateOf(1) }
    var horizontalDrag by remember { mutableFloatStateOf(0f) }
    var showLeftArrow by remember { mutableStateOf(false) }
    var showRightArrow by remember { mutableStateOf(false) }
    var dragVisualTarget by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val maxDragPx = remember(density) { with(density) { 30.dp.toPx() } }

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
    val dragVisualOffset by animateFloatAsState(
        targetValue = dragVisualTarget,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "dragVisual",
    )
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        VolumeKeyEventBus.events
            .debounce(300)
            .collect { event ->
                when (event) {
                    VolumeKeyEvent.NEXT -> nextPage()
                    VolumeKeyEvent.PREV -> prevPage()
                }
            }
    }

    fun nextPage() {
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
        }
    }

    fun prevPage() {
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
        }
    }

    val htmlContent = remember(content, einkFontSize, einkEnglishFont, einkChineseFont, englishFontFilePath, chineseFontFilePath, title, feedName, author, publishedDate, horizontalPadding, lineHeight, letterSpacing, wordSpacing) {
        buildArticleHtml(content, einkFontSize, einkEnglishFont, einkChineseFont, englishFontFilePath, chineseFontFilePath, title, feedName, author, publishedDate, horizontalPadding, lineHeight, letterSpacing, wordSpacing)
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
            key(content, einkFontSize, einkEnglishFont, einkChineseFont, englishFontFilePath, chineseFontFilePath, horizontalPadding, lineHeight, letterSpacing, wordSpacing) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            @Suppress("DEPRECATION")
                            settings.allowFileAccessFromFileURLs = true
                            setBackgroundColor(android.graphics.Color.WHITE)
                            isHorizontalScrollBarEnabled = false
                            isVerticalScrollBarEnabled = false
                            isFocusable = false
                            isFocusableInTouchMode = false
                            setOnKeyListener { _, _, _ ->
                                // Return false so WebView never consumes key events;
                                // volume keys are handled by Activity.dispatchKeyEvent
                                false
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    url: String?,
                                ) = true
                            }
                            addJavascriptInterface(
                                EInkJsInterface { pages ->
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
                                "Android",
                            )
                            webViewRef.value = this
                            loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = dragVisualOffset },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (horizontalDrag < -200f && onNextArticle != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onNextArticle.invoke()
                                } else if (horizontalDrag > 200f && onPrevArticle != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPrevArticle.invoke()
                                }
                                horizontalDrag = 0f
                                dragVisualTarget = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                horizontalDrag += dragAmount
                                dragVisualTarget = horizontalDrag.coerceIn(-maxDragPx, maxDragPx)
                            },
                        )
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

        // Thin progress bar — always visible at the bottom
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Color.Black,
            trackColor = Color(0xFFE0E0E0),
        )

        // Full bottom bar — hidden by default, shown on tap, auto-hides after 3s
        AnimatedVisibility(
            visible = bottomBarVisible,
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
                fontSize = einkFontSize,
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

private class EInkJsInterface(private val onTotalPages: (Int) -> Unit) {
    @JavascriptInterface
    fun onTotalPages(pages: Int) = onTotalPages.invoke(pages)
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
        val fileUrl = "file://$filePath"
        return """
@font-face {
    font-family: '$cssName';
    src: url('$fileUrl') format('$format');
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
p, li, blockquote {

    orphans: 3;
    widows: 3;
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
function setupPagination() {
    _vw = window.innerWidth;
    var vh = window.innerHeight;
    document.body.style.height = vh + 'px';
    var padding = ${horizontalPadding};
    document.body.style.columnWidth = (_vw - padding * 2) + 'px';
    // Recount after images load and a delay for layout
    recountPages();
    window.addEventListener('load', function() { recountPages(); });
    // Also recount after longer delay for lazy-loaded content
    setTimeout(recountPages, 500);
    setTimeout(recountPages, 1500);
}
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
document.addEventListener('keydown', function(e) {
    if (e.key === 'AudioVolumeUp' || e.key === 'AudioVolumeDown' || e.key === 'VolumeUp' || e.key === 'VolumeDown') {
        e.preventDefault();
        e.stopPropagation();
    }
});
</script>
</head>
<body onload="setupPagination()">
<div class="eink-metadata">
  <h1>$escapedTitle</h1>
  <p class="eink-meta">$escapedMeta</p>
</div>
$content
</body>
</html>"""
}
