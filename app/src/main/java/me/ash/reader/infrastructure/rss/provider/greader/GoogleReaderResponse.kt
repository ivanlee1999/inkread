package me.ash.reader.infrastructure.rss.provider.greader

import java.io.IOException
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import me.ash.reader.infrastructure.exception.GoogleReaderAPIException
import me.ash.reader.infrastructure.net.TimeoutException
import me.ash.reader.infrastructure.rss.readByteArrayLimited
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

/** Only explicit single-item refresh uses this bound; bulk sync keeps its existing transport. */
internal fun ResponseBody.readGoogleReaderBodyLimited(maxBytes: Long): String {
    require(maxBytes in 1..Int.MAX_VALUE.toLong())
    return source().use { source ->
        if (contentLength() > maxBytes) throw IOException("Feed response exceeds limit")
        source.readByteArrayLimited(maxBytes).toResponseBody(contentType()).string()
    }
}

/** Raw server/transport/parser errors can echo bearer URLs; retain only safe diagnostic facts. */
internal fun safeGoogleReaderFailure(error: Throwable, endpoint: String): Throwable {
    if (error is CancellationException) return error
    val message = when (error) {
        is GoogleReaderHttpFailure -> "Google Reader request failed (HTTP ${error.status})"
        is JsonParseException -> "Google Reader returned an invalid response"
        is TimeoutException, is java.net.SocketTimeoutException -> "Google Reader request timed out"
        is IOException -> "Google Reader network request failed"
        else -> "Google Reader request failed"
    }
    // Do not attach cause/suppressed errors, parameters, form values, or response fragments.
    return GoogleReaderAPIException(message, query = endpoint)
}

internal class GoogleReaderHttpFailure(val status: Int) : IOException("HTTP $status")
