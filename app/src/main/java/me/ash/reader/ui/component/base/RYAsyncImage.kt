package me.ash.reader.ui.component.base

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import coil.ImageLoader
import coil.imageLoader
import me.ash.reader.infrastructure.rss.ArticleImageRequest
import me.ash.reader.infrastructure.rss.ArticleImageEnrichment
import me.ash.reader.infrastructure.rss.articleImageAlternates
import me.ash.reader.infrastructure.rss.ARTICLE_IMAGE_MAX_CANDIDATES
import coil.compose.AsyncImagePainter
import android.graphics.drawable.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Precision
import me.ash.reader.infrastructure.preference.LocalSyncOnlyOnWiFi
import me.ash.reader.infrastructure.rss.isTransientArticleImageFailure
import coil.size.Scale
import coil.size.Size
import me.ash.reader.ui.theme.isEInkMode
import me.ash.reader.ui.ext.extractDomain

val SIZE_1000 = Size(1000, 1000)

@Composable
fun RYAsyncImage(
    modifier: Modifier = Modifier,
    data: Any? = null,
    size: Size = Size.ORIGINAL,
    scale: Scale = Scale.FIT,
    precision: Precision = Precision.AUTOMATIC,
    contentScale: ContentScale = ContentScale.Fit,
    contentDescription: String? = null,
    @DrawableRes placeholder: Int? = null,
    @DrawableRes error: Int? = null,
    animate: Boolean = true,
    hideOnError: Boolean = false,
) {
    val einkMode = isEInkMode
    val context = LocalContext.current
    val articleRequest = data as? ArticleImageRequest
    val wifiOnly = LocalSyncOnlyOnWiFi.current.value
    val metadataNetworkAllowed = articleRequest != null && canFetchArticleMetadata(context, wifiOnly)
    var candidates by remember(data) { mutableStateOf(articleImageAlternates((ArticleImageEnrichment.preferredCandidates(articleRequest) + articleRequest?.candidates.orEmpty()).filterNot(ArticleImageEnrichment::failedRecently)).take(3)) }
    var attempted by remember(data) { mutableStateOf(emptySet<String>()) }
    var metadataChecked by remember(data) { mutableStateOf(false) }
    var enriching by remember(data) { mutableStateOf(false) }
    val candidate = candidates.firstOrNull { it !in attempted && !ArticleImageEnrichment.failedRecently(it) }
        ?.takeIf { attempted.size < ARTICLE_IMAGE_MAX_CANDIDATES }
    val model = if (articleRequest != null) candidate else data
    val loader = if (articleRequest != null) articlePhotoImageLoader(context.imageLoader) else context.imageLoader
    LaunchedEffect(articleRequest, candidate, metadataChecked, attempted.size, metadataNetworkAllowed) {
        if (articleRequest != null && candidate == null && !metadataChecked && attempted.size < ARTICLE_IMAGE_MAX_CANDIDATES) {
            enriching = true
            try {
                val extra = articleRequest.articleUrl?.let {
                    ArticleImageEnrichment.forCacheDirectory(context.cacheDir).candidates(it, allowNetwork = metadataNetworkAllowed)
                }.orEmpty()
                candidates = articleImageAlternates((extra + articleRequest.candidates).filterNot(ArticleImageEnrichment::failedRecently), attempted)
                metadataChecked = true
            } finally {
                enriching = false
            }
        }
    }
    val painter =
        rememberAsyncImagePainter(
            imageLoader = loader,
            model =
                ImageRequest.Builder(context)
                    .apply {
                        val domain = model.toString().extractDomain()
                        if (articleRequest == null && domain != null) {
                            addHeader("Referer", domain!!)
                        }
                    }
                    .data(data = model)
                    .apply {
                        if (placeholder != null) placeholder(placeholder)
                        if (error != null) error(error)
                        crossfade(articleRequest == null && animate && !einkMode)
                        if (articleRequest != null) memoryCacheKey("article-photo:$model")
                        scale(if (articleRequest != null) Scale.FIT else scale)
                        precision(precision)
                        // Editorial thumbnails never decode the original full-resolution photograph.
                        size(if (articleRequest != null) Size(
                            ((size.width as? Dimension.Pixels)?.px ?: 1000).coerceIn(1, 1600),
                            ((size.height as? Dimension.Pixels)?.px ?: 1000).coerceIn(1, 1600),
                        ) else size)
                    }
                    .build()
        )
    LaunchedEffect(painter.state, candidate) {
        if (articleRequest != null && candidate != null &&
            (painter.state as? AsyncImagePainter.State.Success)?.result?.request?.data == candidate) {
            ArticleImageEnrichment.recordSuccess(articleRequest, candidate)
        }
        if (articleRequest != null && candidate != null &&
            (painter.state as? AsyncImagePainter.State.Error)?.result?.request?.data == candidate) {
            val failure = (painter.state as? AsyncImagePainter.State.Error)?.result?.throwable
            if (failure != null && !isTransientArticleImageFailure(failure) &&
                (failure !is coil.network.HttpException || failure.response.code !in listOf(408, 429, 500, 502, 503, 504))) {
                ArticleImageEnrichment.recordFailure(candidate)
            }
            attempted = attempted + candidate
        }
    }
    LaunchedEffect(painter.state, einkMode, animate) {
        if (einkMode || !animate) {
            ((painter.state as? AsyncImagePainter.State.Success)?.result?.drawable as? Animatable)?.stop()
        }
    }
    // Editorial list photos may disappear on failure instead of reserving an empty panel.
    // Keep the loading slot stable, and leave other callers' error placeholders unchanged.
    val articleExhausted = articleRequest != null && candidate == null &&
        (metadataChecked || attempted.size >= ARTICLE_IMAGE_MAX_CANDIDATES) && !enriching
    if (hideOnError && (articleExhausted || articleRequest == null && painter.state is AsyncImagePainter.State.Error)) return
    Image(
        painter = painter,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
    )


}

private fun canFetchArticleMetadata(context: Context, wifiOnly: Boolean): Boolean {
    // WorkManager already contributes this normal permission. Never add or request a new one here.
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
        return !wifiOnly
    }
    return runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching false
        val network = manager.activeNetwork ?: return@runCatching false
        manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            (!wifiOnly || !manager.isActiveNetworkMetered)
    }.getOrDefault(false)
}

// Share Coil's disk/memory caches, but use static bounded raster decoders and strict public HTTP.
private var articlePhotoLoader: Pair<ImageLoader, ImageLoader>? = null
@Synchronized
private fun articlePhotoImageLoader(base: ImageLoader): ImageLoader {
    articlePhotoLoader?.takeIf { it.first === base }?.let { return it.second }
    return base.newBuilder().okHttpClient(ArticleImageEnrichment.imageClient)
        .components { add(ArticlePhotoDecoder) }.build().also {
        articlePhotoLoader = base to it
    }
}

// From: https://gist.github.com/colinrtwhite/c2966e0b8584b4cdf0a5b05786b20ae1

/**
 * Create and return a new [Painter] that wraps [painter] with its [alpha], [colorFilter], or
 * [onDraw] overwritten.
 */
fun forwardingPainter(
    painter: Painter,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    onDraw: DrawScope.(ForwardingDrawInfo) -> Unit = DefaultOnDraw,
): Painter = ForwardingPainter(painter, alpha, colorFilter, onDraw)

@Immutable
data class ForwardingDrawInfo(
    val painter: Painter,
    val alpha: Float,
    val colorFilter: ColorFilter?,
)

@Immutable
private class ForwardingPainter(
    private val painter: Painter,
    private var alpha: Float,
    private var colorFilter: ColorFilter?,
    private val onDraw: DrawScope.(ForwardingDrawInfo) -> Unit,
) : Painter() {

    private var info = newInfo()

    override val intrinsicSize
        get() = painter.intrinsicSize

    override fun applyAlpha(alpha: Float): Boolean {
        if (alpha == DefaultAlpha) {
            this.alpha = alpha
            this.info = newInfo()
        }
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        if (colorFilter == null) {
            this.colorFilter = colorFilter
            this.info = newInfo()
        }
        return true
    }

    override fun DrawScope.onDraw() = onDraw(info)

    private fun newInfo() = ForwardingDrawInfo(painter, alpha, colorFilter)
}

private val DefaultOnDraw: DrawScope.(ForwardingDrawInfo) -> Unit = { info ->
    with(info.painter) {
        draw(
            androidx.compose.ui.geometry.Size(size.width, size.height),
            info.alpha,
            info.colorFilter,
        )
    }
}
