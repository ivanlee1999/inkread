package me.ash.reader.infrastructure.report

import java.net.URLDecoder
import java.util.Collections
import java.util.EnumSet
import me.ash.reader.infrastructure.rss.isPrivateOriginalFeedUrl
import me.ash.reader.infrastructure.rss.publicArticleUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

const val MAX_EXTRACTION_REPORT_NOTES_CHARS = 2_000

data class SanitizedExtractionArticleUrl(
    val url: String?,
    val redacted: Boolean,
    val omissions: Set<ExtractionPrivacyOmission>,
)

/** Pure, bounded redaction. This does not resolve DNS, fetch URLs, or inspect account settings. */
object ExtractionProblemPrivacy {
    private const val MAX_URL_CHARS = 8_192
    private const val MAX_EPOCH_MILLIS = 253_402_300_799_999L
    private const val MAX_ELAPSED_MILLIS = 86_400_000L
    private const val MAX_RESPONSE_BYTES = 32L * 1024 * 1024
    private val privateQueryName = Regex("(?i)token|secret|password|passwd|authorization|auth|signature|session|api.?key|(^|[_-])(key|sig|sid|code)($|[_-])")
    private val opaqueValue = Regex("[A-Za-z0-9_.=-]{24,}")
    private val email = Regex("(?i)[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9.-]+\\.[A-Z]{2,}")
    private val feedSegments = setOf("feed", "feeds", "rss", "rss2", "atom", "greader", "greader.php", "article-img-proxy.php",
        "account", "accounts", "inbox", "mail", "messages", "message", "login", "signin", "settings")
    private val credentialLine = Regex("(?im)\\b(?:authorization|proxy-authorization|cookie|set-cookie|x[-_]?api[-_]?key|api[-_]?key|access[-_]?token|refresh[-_]?token|token|password|passwd|secret|session|account[-_]?id|article[-_]?id|feed[-_]?id)\\s*[:=][^\\r\\n]*")
    private val bearer = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val jwt = Regex("\\b[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    private val knownToken = Regex("\\b(?:gh[pousr]_[A-Za-z0-9_]{8,}|github_pat_[A-Za-z0-9_]{8,}|xox[baprs]-[A-Za-z0-9-]{8,}|AKIA[A-Z0-9]{16})\\b")
    private val noteUrl = Regex("(?i)(?:\\b[a-z][a-z0-9+.-]{0,15}:(?://|%2f%2f)|(?<!:)//|\\bwww\\.)[^\\s<>]+|\\bhttps?%3a(?:%2f){2}[^\\s<>]+")
    private val nakedUrl = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,63}(?::[0-9]{1,5})?/[^\\s<>]*")
    private val privateIdentifier = Regex("\\b(?:[0-9]{12,}|[a-fA-F0-9]{24,}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\b")

    fun articleUrl(raw: String, newsletter: Boolean): SanitizedExtractionArticleUrl {
        val omissions = EnumSet.of(ExtractionPrivacyOmission.TITLE)
        if (newsletter) {
            omissions.add(ExtractionPrivacyOmission.NEWSLETTER)
            return urlResult(null, raw.isNotBlank(), omissions)
        }
        if (raw.isBlank() || raw.length > MAX_URL_CHARS || raw.any { it <= '\u001f' || it == '\u007f' }) {
            omissions.add(ExtractionPrivacyOmission.INVALID_URL)
            return urlResult(null, raw.isNotBlank(), omissions)
        }
        val parsed = raw.toHttpUrlOrNull()
        if (parsed == null) {
            omissions.add(ExtractionPrivacyOmission.INVALID_URL)
            return urlResult(null, true, omissions)
        }
        if (parsed.query != null) omissions.add(ExtractionPrivacyOmission.URL_QUERY)
        if (parsed.fragment != null) omissions.add(ExtractionPrivacyOmission.URL_FRAGMENT)
        val credentials = parsed.username.isNotEmpty() || parsed.password.isNotEmpty()
        if (credentials) omissions.add(ExtractionPrivacyOmission.URL_USERINFO)
        val privateQuery = parsed.queryParameterNames.any { name ->
            privateQueryName.containsMatchIn(name) || parsed.queryParameterValues(name).any { value ->
                value != null && opaqueValue.matches(value) &&
                    (value.any(Char::isDigit) || value.count { it == '.' } >= 2)
            }
        }
        val clean = parsed.newBuilder().username("").password("").query(null).fragment(null).build()
        if (clean.toString().length > MAX_URL_CHARS) {
            omissions.add(ExtractionPrivacyOmission.INVALID_URL)
            return urlResult(null, true, omissions)
        }
        var decodedPath = clean.encodedPath
        repeat(2) {
            decodedPath = runCatching { URLDecoder.decode(decodedPath.replace("+", "%2B"), "UTF-8") }.getOrElse {
                omissions.add(ExtractionPrivacyOmission.PRIVATE_URL)
                return urlResult(null, true, omissions)
            }
        }
        val segments = decodedPath.split('/').filter(String::isNotEmpty)
        val feedOrAccountPath = segments.any { segment ->
            segment.lowercase() in feedSegments || segment.substringAfterLast('.').lowercase() in setOf("xml", "rss", "atom")
        } || segments.take(2).joinToString("/").lowercase() in setOf("p/i", "api/greader.php") ||
            clean.encodedPath.trim('/') == "i"
        if (credentials || privateQuery || publicArticleUrl(clean.toString()) == null ||
            isPrivateOriginalFeedUrl(clean.toString()) || feedOrAccountPath || email.containsMatchIn(decodedPath)) {
            omissions.add(ExtractionPrivacyOmission.PRIVATE_URL)
            return urlResult(null, true, omissions)
        }
        return urlResult(clean.toString(), clean.toString() != raw, omissions)
    }

    fun sanitizeNotes(notes: String): String {
        // Limit input work before parsing. Notes are deliberate user text, never captured prose.
        var text = notes.take(MAX_EXTRACTION_REPORT_NOTES_CHARS * 16)
            .replace("\r\n", "\n").replace('\r', '\n').replace('\u2028', '\n').replace('\u2029', '\n')
            .let(::removeInvisibleControls)
        text = credentialLine.replace(text, "[Sensitive field redacted]")
        val document = Jsoup.parseBodyFragment(text)
        document.select("script,style,noscript,template").remove()
        text = document.body().wholeText()
        text = noteUrl.replace(text, "[URL redacted]")
        text = nakedUrl.replace(text, "[URL redacted]")
        text = credentialLine.replace(text, "[Sensitive field redacted]")
        text = bearer.replace(text, "[Credential redacted]")
        text = jwt.replace(text, "[Credential redacted]")
        text = knownToken.replace(text, "[Credential redacted]")
        text = email.replace(text, "[Email redacted]")
        text = privateIdentifier.replace(text, "[Identifier redacted]")
        return boundedNotes(text.trim())
    }

    fun sanitizeSnapshot(snapshot: ExtractionProblemSnapshot): ExtractionProblemSnapshot {
        require(snapshot.schemaVersion == 1) { "Unsupported extraction report schema" }
        val requested = requireNotNull(snapshot.requestedSourceMode as ExtractionSourceMode?) { "Invalid extraction report" }
        val displayed = requireNotNull(snapshot.displayedSourceMode as ExtractionSourceMode?) { "Invalid extraction report" }
        val originalOmissions = (snapshot.privacyOmissions as Set<ExtractionPrivacyOmission>?).orEmpty().filterNotNull().toSet()
        val newsletter = ExtractionPrivacyOmission.NEWSLETTER in originalOmissions ||
            requested == ExtractionSourceMode.NEWSLETTER || displayed == ExtractionSourceMode.NEWSLETTER
        val rawUrl = snapshot.publicArticleUrl
        val suppressed = newsletter || ExtractionPrivacyOmission.PRIVATE_URL in originalOmissions ||
            ExtractionPrivacyOmission.INVALID_URL in originalOmissions
        val url = when {
            suppressed -> SanitizedExtractionArticleUrl(null, rawUrl != null || snapshot.urlRedacted,
                originalOmissions + if (newsletter) setOf(ExtractionPrivacyOmission.NEWSLETTER) else emptySet())
            rawUrl == null -> SanitizedExtractionArticleUrl(null, snapshot.urlRedacted, originalOmissions)
            else -> articleUrl(rawUrl, false)
        }
        return snapshot.copy(
            capturedAtEpochMillis = snapshot.capturedAtEpochMillis.takeIf { it in 1..MAX_EPOCH_MILLIS } ?: 0,
            appVersion = scalar(snapshot.appVersion, 64),
            appVersionCode = snapshot.appVersionCode.takeIf { it > 0 } ?: 0,
            sourceRevision = snapshot.sourceRevision?.takeIf { it.matches(Regex("[0-9a-fA-F]{7,40}")) }?.lowercase(),
            deviceModel = scalar(snapshot.deviceModel, 80),
            androidApi = snapshot.androidApi.takeIf { it in 1..1_000 } ?: 0,
            publicArticleUrl = url.url,
            urlRedacted = snapshot.urlRedacted || url.redacted,
            privacyOmissions = immutableOmissions(originalOmissions + url.omissions + ExtractionPrivacyOmission.TITLE),
            requestedSourceMode = requested,
            displayedSourceMode = displayed,
            feedMetrics = metrics(snapshot.feedMetrics),
            displayedMetrics = metrics(snapshot.displayedMetrics),
            lastLocalAttempt = snapshot.lastLocalAttempt?.let(::attempt),
        )
    }

    private fun scalar(value: String?, limit: Int): String = value?.takeIf {
        it.length in 1..limit && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9 ._()+-]*")) &&
            !Regex("(?i)token|password|authorization|cookie|secret").containsMatchIn(it)
    } ?: "unavailable"

    private fun metrics(value: ExtractionContentMetrics?): ExtractionContentMetrics? = value?.takeIf {
        it.textCharacters in 0..128 * 1024 && it.paragraphs in 0..256 && it.images in 0..256
    }?.copy()

    private fun comparison(value: ExtractionSelectionSnapshot?): ExtractionSelectionSnapshot? = value?.let {
        it.copy(
            source = requireNotNull(it.source as ExtractionSelectedSource?) { "Invalid extraction report" },
            reason = requireNotNull(it.reason as ExtractionSelectionReason?) { "Invalid extraction report" },
            before = metrics(it.before), candidate = metrics(it.candidate), selected = metrics(it.selected),
        )
    }

    private fun attempt(value: ExtractionAttemptSnapshot): ExtractionAttemptSnapshot = value.copy(
        startedAtEpochMillis = value.startedAtEpochMillis.takeIf { it in 1..MAX_EPOCH_MILLIS } ?: 0,
        elapsedMillis = knownElapsed(value.elapsedMillis),
        origin = requireNotNull(value.origin as ExtractionAttemptOrigin?) { "Invalid extraction report" },
        cacheState = requireNotNull(value.cacheState as ExtractionCacheState?) { "Invalid extraction report" },
        outcome = requireNotNull(value.outcome as ExtractionAttemptOutcome?) { "Invalid extraction report" },
        failureStage = requireNotNull(value.failureStage as ExtractionFailureStage?) { "Invalid extraction report" },
        httpStatus = value.httpStatus?.takeIf { it in 100..999 },
        responseBytesRead = value.responseBytesRead?.takeIf { it in 0..MAX_RESPONSE_BYTES },
        fetchElapsedMillis = knownElapsed(value.fetchElapsedMillis), parserElapsedMillis = knownElapsed(value.parserElapsedMillis),
        previousCache = metrics(value.previousCache), fetchedCandidate = metrics(value.fetchedCandidate),
        cacheComparison = comparison(value.cacheComparison), readerComparison = comparison(value.readerComparison),
    )

    private fun knownElapsed(value: Long?): Long? = value?.takeIf { it in 0..MAX_ELAPSED_MILLIS }

    private fun removeInvisibleControls(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val point = Character.codePointAt(value, index)
            index += Character.charCount(point)
            if (point == '\n'.code || point == '\t'.code ||
                !Character.isISOControl(point) && Character.getType(point) != Character.FORMAT.toInt()) appendCodePoint(point)
        }
    }

    private fun boundedNotes(value: String): String = buildString {
        var index = 0
        while (index < value.length && length < MAX_EXTRACTION_REPORT_NOTES_CHARS) {
            val char = value[index++]
            if (Character.isHighSurrogate(char)) {
                if (index < value.length && Character.isLowSurrogate(value[index])) {
                    if (length + 2 > MAX_EXTRACTION_REPORT_NOTES_CHARS) break
                    append(char).append(value[index++])
                }
            } else if (!Character.isLowSurrogate(char)) append(char)
        }
    }

    private fun urlResult(url: String?, redacted: Boolean, omissions: Set<ExtractionPrivacyOmission>) =
        SanitizedExtractionArticleUrl(url, redacted, immutableOmissions(omissions))

    private fun immutableOmissions(values: Set<ExtractionPrivacyOmission>): Set<ExtractionPrivacyOmission> =
        Collections.unmodifiableSet(EnumSet.noneOf(ExtractionPrivacyOmission::class.java).apply { addAll(values) })
}
