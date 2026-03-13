package me.ash.reader.ui.page.home.reading

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.preference.EInkFontSizePreference
import me.ash.reader.infrastructure.preference.LocalEInkFontSize
import me.ash.reader.ui.page.home.flow.EInkPaginationBar

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
    onPageChanged: ((currentPage: Int, totalPages: Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val einkFontSize = LocalEInkFontSize.current
    val fontSizeIndex = EInkFontSizePreference.values.indexOf(einkFontSize)
        .takeIf { it >= 0 } ?: EInkFontSizePreference.values.indexOf(EInkFontSizePreference.default)

    var currentPage by rememberSaveable(content, einkFontSize) { mutableIntStateOf(0) }
    var totalPages by rememberSaveable(content, einkFontSize) { mutableIntStateOf(1) }
    var showTapHints by remember { mutableStateOf(true) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        delay(2000)
        showTapHints = false
    }

    LaunchedEffect(Unit) {
        VolumeKeyEventBus.events.collect { event ->
            when (event) {
                VolumeKeyEvent.VOLUME_DOWN -> {
                    if (currentPage < totalPages - 1) {
                        currentPage++
                        webViewRef.value?.evaluateJavascript("goToPage($currentPage)", null)
                        onPageChanged?.invoke(currentPage + 1, totalPages)
                    }
                }
                VolumeKeyEvent.VOLUME_UP -> {
                    if (currentPage > 0) {
                        currentPage--
                        webViewRef.value?.evaluateJavascript("goToPage($currentPage)", null)
                        onPageChanged?.invoke(currentPage + 1, totalPages)
                    }
                }
            }
        }
    }

    fun nextPage() {
        if (currentPage < totalPages - 1) {
            currentPage++
            webViewRef.value?.evaluateJavascript("goToPage($currentPage)", null)
            onPageChanged?.invoke(currentPage + 1, totalPages)
        }
    }

    fun prevPage() {
        if (currentPage > 0) {
            currentPage--
            webViewRef.value?.evaluateJavascript("goToPage($currentPage)", null)
            onPageChanged?.invoke(currentPage + 1, totalPages)
        }
    }

    val htmlContent = remember(content, einkFontSize, title, feedName, author, publishedDate) {
        buildArticleHtml(content, einkFontSize, title, feedName, author, publishedDate)
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
            key(content, einkFontSize) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            setBackgroundColor(android.graphics.Color.WHITE)
                            isHorizontalScrollBarEnabled = false
                            isVerticalScrollBarEnabled = false
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
                                        onPageChanged?.invoke(currentPage + 1, totalPages)
                                    }
                                },
                                "Android",
                            )
                            webViewRef.value = this
                            loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.4f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { prevPage() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (showTapHints) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "< Prev",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.6f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { nextPage() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (showTapHints) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Next >",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }

        EInkPaginationBar(
            currentPage = currentPage + 1,
            totalPages = totalPages,
            onPrev = { prevPage() },
            onNext = { nextPage() },
            fontSize = einkFontSize,
            canDecreaseFontSize = fontSizeIndex > 0,
            canIncreaseFontSize = fontSizeIndex < EInkFontSizePreference.values.lastIndex,
            onDecreaseFontSize = {
                if (fontSizeIndex > 0) {
                    EInkFontSizePreference.put(
                        context, coroutineScope,
                        EInkFontSizePreference.values[fontSizeIndex - 1],
                    )
                }
            },
            onIncreaseFontSize = {
                if (fontSizeIndex < EInkFontSizePreference.values.lastIndex) {
                    EInkFontSizePreference.put(
                        context, coroutineScope,
                        EInkFontSizePreference.values[fontSizeIndex + 1],
                    )
                }
            },
        )
    }
}

private class EInkJsInterface(private val onTotalPages: (Int) -> Unit) {
    @JavascriptInterface
    fun onTotalPages(pages: Int) = onTotalPages.invoke(pages)
}

private fun buildArticleHtml(
    content: String,
    fontSize: Int,
    title: String,
    feedName: String,
    author: String?,
    publishedDate: Date,
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

    return """<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body {
    padding: 16px;
    font-family: Georgia, serif;
    font-size: ${fontSize}px;
    line-height: 1.6;
    color: #000;
    background: #fff;
    column-gap: 32px;
    column-fill: auto;
    overflow: hidden;
    -webkit-text-size-adjust: none;
    text-size-adjust: none;
}
img {
    max-width: calc(100vw - 32px);
    max-height: calc(100vh - 32px);
    width: auto;
    height: auto;
    break-inside: avoid;
    page-break-inside: avoid;
    display: block;
    margin: 0.5em auto;
}
p, h1, h2, h3, h4, h5, h6, li, blockquote {
    break-inside: avoid-column;
    orphans: 2;
    widows: 2;
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
function setupPagination() {
    var vw = window.innerWidth;
    var vh = window.innerHeight;
    document.body.style.height = vh + 'px';
    document.body.style.columnWidth = (vw - 32) + 'px';
    setTimeout(function() {
        var n = Math.ceil(document.body.scrollWidth / vw);
        Android.onTotalPages(Math.max(1, n));
    }, 250);
}
function goToPage(n) {
    document.body.style.transform = 'translateX(-' + (n * window.innerWidth) + 'px)';
}
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
