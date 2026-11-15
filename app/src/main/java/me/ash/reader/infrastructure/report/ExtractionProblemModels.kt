package me.ash.reader.infrastructure.report

import androidx.annotation.Keep

/** Versioned, content-free data only. Article/account identities live solely in local wrappers. */
@Keep
data class ExtractionContentMetrics(
    val textCharacters: Int,
    /** Visible leaf text blocks (paragraphs, list items, quotations and table cells). */
    val paragraphs: Int,
    val images: Int,
)

@Keep
enum class ExtractionAttemptOrigin { UNKNOWN, CACHE, FRESH }
@Keep
enum class ExtractionCacheState { UNKNOWN, MISS, HIT, STALE, REFRESH_REQUESTED }
@Keep
enum class ExtractionFailureStage { NONE, FETCH, ACCESS, PARSER, RESOURCE, QUALITY, CACHE, POLICY, CANCELLED }
@Keep
enum class ExtractionAttemptOutcome { RUNNING, SUCCEEDED, FEED_RETAINED, CACHE_RETAINED, FAILED, CANCELLED, NOT_FETCHED }
@Keep
enum class ExtractionDiagnosticReason {
    HTTP_ERROR, NETWORK_ERROR, NETWORK_TIMEOUT, TLS_ERROR, NON_HTML_RESPONSE,
    INPUT_LIMIT, SOURCE_LIMIT, NODE_LIMIT, DEPTH_LIMIT, TIME_LIMIT, INSTRUCTION_LIMIT, OUTPUT_LIMIT, ENGINE_ERROR,
    PUBLISHER_RESTRICTED, ACCESS_GATE, BROWSER_CHALLENGE, NO_READABLE_CONTENT,
    CACHE_REUSED, CACHE_READ_FAILED, CACHE_WRITE_FAILED, FETCH_POLICY_BLOCKED,
    FEED_ALREADY_COMPLETE, QUALITY_REJECTED, CANCELLED, UNKNOWN_ERROR,
}
@Keep
enum class ExtractionSelectedSource { FEED, EXTRACTED, UNAVAILABLE }
@Keep
enum class ExtractionSelectionReason { EXTRACTED, FEED_ONLY, EXTRACTION_FAILED, FEED_IS_BETTER, EMPTY }
@Keep
enum class ExtractionQualityVeto {
    IMAGE_LOSS, PARAGRAPH_OR_LINK_LOSS, SHORTER_TEXT, DUPLICATES, LINK_HEAVY,
    INCONCLUSIVE_LIMIT, NO_IMPROVEMENT, EMPTY_CANDIDATE, EXTRACTION_FAILED, EMPTY,
}
@Keep
enum class ExtractionSourceMode { LOCAL, SERVER, ORIGINAL, BROWSER_TEXT, EARLIER_TEXT, LEGACY_FEED, NEWSLETTER, UNKNOWN }
@Keep
enum class ExtractionPrivacyOmission { TITLE, NEWSLETTER, PRIVATE_URL, INVALID_URL, URL_QUERY, URL_USERINFO, URL_FRAGMENT }

@Keep
data class ExtractionSelectionSnapshot(
    val source: ExtractionSelectedSource,
    val reason: ExtractionSelectionReason,
    val detail: ExtractionQualityVeto? = null,
    val before: ExtractionContentMetrics? = null,
    val candidate: ExtractionContentMetrics? = null,
    val selected: ExtractionContentMetrics? = null,
)

@Keep
data class ExtractionAttemptSnapshot(
    val startedAtEpochMillis: Long,
    val elapsedMillis: Long?,
    val origin: ExtractionAttemptOrigin,
    val cacheState: ExtractionCacheState,
    val requestedRefresh: Boolean,
    val outcome: ExtractionAttemptOutcome,
    val failureStage: ExtractionFailureStage = ExtractionFailureStage.NONE,
    val reason: ExtractionDiagnosticReason? = null,
    val httpStatus: Int? = null,
    val responseBytesRead: Long? = null,
    val responseBytesComplete: Boolean? = null,
    val fetchElapsedMillis: Long? = null,
    val parserElapsedMillis: Long? = null,
    val previousCache: ExtractionContentMetrics? = null,
    val fetchedCandidate: ExtractionContentMetrics? = null,
    val cacheComparison: ExtractionSelectionSnapshot? = null,
    val readerComparison: ExtractionSelectionSnapshot? = null,
    val httpCacheHit: Boolean? = null,
    val networkResponseObserved: Boolean? = null,
)

/** No raw HTML, prose, titles, exception strings, headers, feed/server URLs or private IDs. */
@Keep
data class ExtractionProblemSnapshot(
    val schemaVersion: Int = 1,
    val capturedAtEpochMillis: Long,
    val appVersion: String,
    val appVersionCode: Int,
    val sourceRevision: String? = null,
    val deviceModel: String,
    val androidApi: Int,
    val publicArticleUrl: String? = null,
    val urlRedacted: Boolean = false,
    val privacyOmissions: Set<ExtractionPrivacyOmission> = setOf(ExtractionPrivacyOmission.TITLE),
    val requestedSourceMode: ExtractionSourceMode,
    val displayedSourceMode: ExtractionSourceMode,
    val presentationUpdatePending: Boolean = false,
    val feedMetrics: ExtractionContentMetrics? = null,
    val displayedMetrics: ExtractionContentMetrics? = null,
    /** Null means there is no observed local attempt; it is not a failure. */
    val lastLocalAttempt: ExtractionAttemptSnapshot? = null,
)

@Keep
data class SavedExtractionProblem internal constructor(
    val id: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val snapshot: ExtractionProblemSnapshot,
    val notes: String,
    internal val accountId: Int,
    internal val articleId: String,
)

@Keep
data class ExtractionProblemDraft internal constructor(
    val snapshot: ExtractionProblemSnapshot,
    val existingNotes: String = "",
    val hasExistingReport: Boolean = false,
    val localReportId: String? = null,
    internal val accountId: Int,
    internal val articleId: String,
    internal val sourceIdentity: String,
    internal val newsletter: Boolean,
)
