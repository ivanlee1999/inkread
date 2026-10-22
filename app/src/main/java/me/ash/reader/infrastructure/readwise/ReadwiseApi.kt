package me.ash.reader.infrastructure.readwise

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Deliberately independent of cachingHttpClient, including its permissive certificate setting. */
internal fun readwiseHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(CookieJar.NO_COOKIES)
    .cache(null)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .eventListenerFactory { call ->
        object : EventListener() {
            override fun callStart(call: Call) { call.request().tag(ReadwiseDeliveryTrace::class.java)?.listenerObserved = true }
            override fun requestHeadersStart(call: Call) { call.request().tag(ReadwiseDeliveryTrace::class.java)?.requestStarted = true }
        }
    }
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .build()

/** In-memory delivery evidence only, never request content or credentials. */
internal class ReadwiseDeliveryTrace {
    @Volatile var listenerObserved: Boolean = false
    @Volatile var requestStarted: Boolean = false
}

internal data class ReadwiseHighlightPayload(
    val text: String,
    val note: String,
    val title: String,
    val author: String?,
    val sourceUrl: String?,
    val highlightedAt: Long,
    /** Local UTF-16 start offset for ordering only, not a Reader document anchor. */
    val location: Int? = null,
) {
    fun validationError(): String? = when {
        text.isBlank() -> "The highlight is empty."
        location != null && location < 0 -> "The highlight position is invalid."
        text.codePointCount(0, text.length) > 8191 -> "The highlight exceeds Readwise's 8,191-character limit."
        note.codePointCount(0, note.length) > 8191 -> "The note exceeds Readwise's 8,191-character limit."
        title.codePointCount(0, title.length) > 511 -> "The title exceeds Readwise's 511-character limit."
        (author?.codePointCount(0, author.length) ?: 0) > 1024 -> "The author exceeds Readwise's 1,024-character limit."
        (sourceUrl?.codePointCount(0, sourceUrl.length) ?: 0) > 2047 -> "The source URL exceeds Readwise's 2,047-character limit."
        else -> null
    }

    fun createJson(): JsonObject = JsonObject().apply {
        addProperty("text", text)
        addProperty("note", note)
        addProperty("title", title)
        if (author != null) addProperty("author", author)
        if (sourceUrl != null) addProperty("source_url", sourceUrl)
        addProperty("source_type", "inkread")
        addProperty("category", "articles")
        addProperty("highlighted_at", Instant.ofEpochMilli(highlightedAt).toString())
        if (location != null) {
            addProperty("location", location)
            addProperty("location_type", "offset")
        }
        // No fabricated public hostname or source fragment masquerading as a usable highlight URL.
        // v2 guarantees content de-duplication, not an application-owned idempotency key.
    }

    fun updateJson(): JsonObject = JsonObject().apply {
        addProperty("text", text)
        addProperty("note", note) // Empty string intentionally clears a note.
    }
}

internal sealed interface ReadwiseResult<out T> {
    data class Success<T>(val value: T) : ReadwiseResult<T>
    data object Unauthorized : ReadwiseResult<Nothing>
    data object ReconciliationUnauthorized : ReadwiseResult<Nothing>
    data class Invalid(val message: String) : ReadwiseResult<Nothing>
    data class RateLimited(val delayMillis: Long) : ReadwiseResult<Nothing>
    /** CREATE succeeded, but resolving its ID hit a rate limit before the first page. */
    data class ReconciliationDeferred(val delayMillis: Long) : ReadwiseResult<Nothing>
    /** A multi-page read was interrupted. Do not restart the entire library scan forever. */
    data class ReconciliationThrottled(val delayMillis: Long) : ReadwiseResult<Nothing>
    data object Retryable : ReadwiseResult<Nothing>
    data object NotSentRetryable : ReadwiseResult<Nothing>
    data object Cancelled : ReadwiseResult<Nothing>
    data class ReconciliationRequired(val message: String) : ReadwiseResult<Nothing>
}

/**
 * Readwise v2 only: https://readwise.io/api_deets . Tokens are transient arguments, never fields.
 * The injectable Call.Factory is for synthetic tests; production always uses the private TLS client.
 * Export calls are registered atomically against an epoch gate so disabling/replacing a connection
 * can cancel existing calls and prevent a stale caller from starting another one.
 */
internal class ReadwiseApi(private val calls: Call.Factory = readwiseHttpClient()) {
    private val lock = Any()
    private var activeEpoch: Long? = null
    private val activeCalls = mutableSetOf<Call>()

    fun activate(epoch: Long?) = synchronized(lock) {
        activeEpoch = epoch
        activeCalls.forEach(Call::cancel)
        activeCalls.clear()
    }

    fun authenticate(token: String): ReadwiseResult<Unit> =
        request("GET", "auth/", token, epoch = null, authentication = true).let { response ->
            when (response) {
                is ReadwiseResult.Success -> if (response.value.code == 204) ReadwiseResult.Success(Unit)
                    else ReadwiseResult.Invalid("Readwise returned an unexpected authentication response.")
                else -> response.failure()
            }
        }

    fun create(epoch: Long, token: String, payload: ReadwiseHighlightPayload): ReadwiseResult<String> {
        payload.validationError()?.let { return ReadwiseResult.Invalid(it) }
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(75)
        val body = JsonObject().apply { add("highlights", JsonArray().apply { add(payload.createJson()) }) }
        return when (val result = request("POST", "highlights/", token, epoch, body, deadlineNanos = deadlineNanos)) {
            is ReadwiseResult.Success -> try {
                val books = JsonParser.parseString(result.value.body).asJsonArray
                val ids = books.flatMap { book ->
                    book.asJsonObject.getAsJsonArray("modified_highlights")?.map { it.asLong.toString() }.orEmpty()
                }.distinct()
                when (ids.size) {
                    1 -> ReadwiseResult.Success(ids.single())
                    0 -> when (val reconciliation = reconcile(epoch, token, payload, deadlineNanos)) {
                        is ReadwiseResult.RateLimited -> ReadwiseResult.ReconciliationDeferred(reconciliation.delayMillis)
                        ReadwiseResult.NotSentRetryable -> ReadwiseResult.Retryable // POST already completed.
                        ReadwiseResult.Unauthorized -> ReadwiseResult.ReconciliationUnauthorized
                        is ReadwiseResult.Invalid -> ReadwiseResult.ReconciliationRequired("Readwise created or de-duplicated the highlight, but its remote ID could not be reconciled. " + reconciliation.message)
                        else -> reconciliation
                    }
                    else -> ReadwiseResult.ReconciliationRequired("Readwise returned multiple highlight IDs for one annotation. Export is paused for this item.")
                }
            } catch (_: RuntimeException) {
                // The server may have committed even though the acknowledgement cannot be read.
                ReadwiseResult.Retryable
            }
            else -> result.failure()
        }
    }

    fun update(epoch: Long, token: String, remoteId: String, payload: ReadwiseHighlightPayload): ReadwiseResult<String> {
        payload.validationError()?.let { return ReadwiseResult.Invalid(it) }
        if (!remoteId.matches(Regex("[1-9][0-9]*"))) return ReadwiseResult.Invalid("The saved Readwise highlight ID is invalid.")
        return when (val result = request("PATCH", "highlights/$remoteId/", token, epoch, payload.updateJson())) {
            is ReadwiseResult.Success -> ReadwiseResult.Success(remoteId)
            else -> result.failure()
        }
    }

    /**
     * An uncertain CREATE is reconciled read-only using the frozen payload. A documented content
     * de-dup response may also return no modified IDs; EXPORT resolves exact content without relying on
     * array order. Multiple matches, changed remote notes, or a very large scan require review.
     * This is at-least-once delivery with reconciliation, not an exactly-once guarantee.
     */
    fun reconcileCreate(epoch: Long, token: String, payload: ReadwiseHighlightPayload): ReadwiseResult<String> =
        reconcile(epoch, token, payload, System.nanoTime() + TimeUnit.SECONDS.toNanos(75))

    private fun reconcile(epoch: Long, token: String, payload: ReadwiseHighlightPayload, deadlineNanos: Long): ReadwiseResult<String> {
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        val candidates = mutableSetOf<String>()
        repeat(100) {
            val suffix = cursor?.let { "?pageCursor=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            when (val result = request("GET", "export/$suffix", token, epoch, deadlineNanos = deadlineNanos)) {
                is ReadwiseResult.Success -> try {
                    val page = JsonParser.parseString(result.value.body).asJsonObject
                    page.getAsJsonArray("results").forEach { element ->
                        val book = element.asJsonObject
                        if (book.boolean("is_deleted")) return@forEach
                        if (book.string("title") != payload.title || book.string("source_url").orEmpty() != payload.sourceUrl.orEmpty()) return@forEach
                        val remoteAuthor = book.string("author").orEmpty()
                        if (payload.author != null) {
                            if (remoteAuthor != payload.author) return@forEach
                        } else {
                            // Readwise documents blank/domain defaults when author is omitted.
                            // Do not bind a similarly named book with an unrelated known author.
                            val sourceHost = runCatching { java.net.URI(payload.sourceUrl.orEmpty()).host }.getOrNull()
                            if (remoteAuthor.isNotEmpty() && remoteAuthor != sourceHost) return@forEach
                        }
                        book.getAsJsonArray("highlights").forEach highlightLoop@{ entry ->
                            val highlight = entry.asJsonObject
                            if (!highlight.boolean("is_deleted") && highlight.string("text") == payload.text &&
                                highlight.string("note").orEmpty() == payload.note) {
                                candidates += highlight.get("id").asLong.toString()
                            }
                        }
                    }
                    cursor = page.string("nextPageCursor")?.takeIf(String::isNotBlank)
                    if (cursor == null) return when (candidates.size) {
                        1 -> ReadwiseResult.Success(candidates.single())
                        else -> ReadwiseResult.ReconciliationRequired("Readwise accepted or de-duplicated this highlight, but its remote ID could not be identified unambiguously. Review it in Readwise before retrying.")
                    }
                    if (!seenCursors.add(cursor)) return ReadwiseResult.ReconciliationRequired("Readwise returned repeated reconciliation pages. Review this item before retrying.")
                } catch (_: RuntimeException) {
                    return ReadwiseResult.Retryable
                }
                is ReadwiseResult.RateLimited -> return if (cursor == null) result.failure()
                    else ReadwiseResult.ReconciliationThrottled(result.delayMillis)
                ReadwiseResult.Retryable, ReadwiseResult.NotSentRetryable -> return if (cursor == null) result.failure()
                    else ReadwiseResult.ReconciliationRequired("The reconciliation scan was interrupted. Review this annotation in Readwise; InkRead will not repeatedly scan your library or resend it.")
                else -> return result.failure()
            }
        }
        return ReadwiseResult.ReconciliationRequired("The Readwise library is too large for automatic reconciliation. Review this item before retrying.")
    }

    private data class HttpReply(val code: Int, val body: String)

    private fun request(
        method: String,
        path: String,
        token: String,
        epoch: Long?,
        body: JsonObject? = null,
        authentication: Boolean = false,
        deadlineNanos: Long? = null,
    ): ReadwiseResult<HttpReply> {
        if (token.isBlank() || token.length > 4096 || token.any { it.code !in 33..126 }) return ReadwiseResult.Invalid("Enter a valid Readwise access token.")
        val trace = ReadwiseDeliveryTrace()
        val request = Request.Builder().tag(ReadwiseDeliveryTrace::class.java, trace).url("https://readwise.io/api/v2/$path")
            .header("Authorization", "Token $token")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val call = synchronized(lock) {
            if (!authentication && (epoch == null || epoch != activeEpoch)) return ReadwiseResult.Cancelled
            if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) return ReadwiseResult.Retryable
            calls.newCall(request).also {
                if (deadlineNanos != null) it.timeout().deadlineNanoTime(deadlineNanos)
                activeCalls += it
            }
        }
        try {
            call.execute().use { response ->
                if (call.isCanceled()) return ReadwiseResult.Cancelled
                return when (response.code) {
                    200, 201, 204 -> {
                        val source = response.body.source()
                        if (response.body.contentLength() > MAX_RESPONSE_BYTES || source.request(MAX_RESPONSE_BYTES + 1)) {
                            ReadwiseResult.ReconciliationRequired("Readwise returned a response larger than the safe processing limit. Review this annotation in Readwise before any further send.")
                        } else ReadwiseResult.Success(HttpReply(response.code, source.readUtf8()))
                    }
                    401 -> ReadwiseResult.Unauthorized
                    429 -> ReadwiseResult.RateLimited(retryAfterMillis(response.header("Retry-After")))
                    in 500..599 -> ReadwiseResult.Retryable
                    400 -> ReadwiseResult.Invalid("Readwise rejected this annotation (HTTP 400). Check its text and source fields; nothing was truncated.")
                    403 -> ReadwiseResult.Invalid("Readwise denied this request (HTTP 403). Check your account access.")
                    404 -> ReadwiseResult.Invalid("The Readwise highlight no longer exists (HTTP 404). Local annotations are unchanged.")
                    else -> ReadwiseResult.Invalid("Readwise returned HTTP ${response.code}. The annotation remains saved locally.")
                }
            }
        } catch (_: IOException) {
            return when {
                call.isCanceled() -> ReadwiseResult.Cancelled
                trace.listenerObserved && !trace.requestStarted -> ReadwiseResult.NotSentRetryable
                else -> ReadwiseResult.Retryable
            }
        } finally {
            synchronized(lock) { activeCalls -= call }
        }
    }

    companion object { private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024 }
}

internal fun retryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long {
    val seconds = value?.trim()?.toLongOrNull()
    if (seconds != null && seconds >= 0) return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
    return runCatching {
        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0)
    }.getOrDefault(60_000)
}

private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.boolean(name: String): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
@Suppress("UNCHECKED_CAST")
private fun <T> ReadwiseResult<*>.failure(): ReadwiseResult<T> = this as ReadwiseResult<T>
