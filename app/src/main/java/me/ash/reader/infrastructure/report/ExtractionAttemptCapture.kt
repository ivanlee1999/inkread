package me.ash.reader.infrastructure.report

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import me.ash.reader.infrastructure.html.ArticleExtractionPreparation
import me.ash.reader.infrastructure.html.ArticleRequiresBrowserException
import me.ash.reader.infrastructure.html.HtmlReadException
import me.ash.reader.infrastructure.html.HtmlReadFailure
import me.ash.reader.infrastructure.html.ModernReadability
import me.ash.reader.infrastructure.rss.ArticleContentSelection

/** One request's numeric trace. It never accepts URLs, titles, HTML storage or error messages. */
internal class ExtractionAttemptCapture(
    private val refresh: Boolean,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val nanoClock: () -> Long = System::nanoTime,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExtractionAttemptCapture>
    private val started = wallClock()
    private val startedNanos = nanoClock()
    private var finishedAt: Long? = null
    private var observed = false
    private var origin = ExtractionAttemptOrigin.UNKNOWN
    private var cacheState = if (refresh) ExtractionCacheState.REFRESH_REQUESTED else ExtractionCacheState.UNKNOWN
    private var outcome = ExtractionAttemptOutcome.RUNNING
    private var failureStage = ExtractionFailureStage.NONE
    private var reason: ExtractionDiagnosticReason? = null
    private var httpStatus: Int? = null
    private var httpCacheHit: Boolean? = null
    private var networkResponseObserved: Boolean? = null
    private var bytes: Long? = null
    private var bytesComplete: Boolean? = null
    private var fetchStarted: Long? = null
    private var fetchElapsed: Long? = null
    private var parserStarted: Long? = null
    private var parserElapsed: Long? = null
    private var previousCache: ExtractionContentMetrics? = null
    private var candidate: ExtractionContentMetrics? = null
    private var cacheComparison: ExtractionSelectionSnapshot? = null
    private var readerComparison: ExtractionSelectionSnapshot? = null

    @Synchronized fun cacheRead(hit: Boolean, content: String? = null) {
        observed = true
        if (cacheState != ExtractionCacheState.REFRESH_REQUESTED) cacheState = if (hit) ExtractionCacheState.HIT else ExtractionCacheState.MISS
        if (hit && content != null) previousCache = ArticleContentSelection.diagnosticMetrics(content)?.reportMetrics()
    }
    @Synchronized fun cacheReturned() {
        observed = true; origin = ExtractionAttemptOrigin.CACHE
        outcome = ExtractionAttemptOutcome.SUCCEEDED; reason = ExtractionDiagnosticReason.CACHE_REUSED
    }
    @Synchronized fun staleCache() { if (cacheState != ExtractionCacheState.REFRESH_REQUESTED) cacheState = ExtractionCacheState.STALE }
    @Synchronized fun cacheReadFailed() {
        observed = true; failureStage = ExtractionFailureStage.CACHE; reason = ExtractionDiagnosticReason.CACHE_READ_FAILED
    }
    @Synchronized fun cacheWriteFailed() {
        observed = true; failureStage = ExtractionFailureStage.CACHE; reason = ExtractionDiagnosticReason.CACHE_WRITE_FAILED
    }
    @Synchronized fun feedAlreadyComplete() {
        observed = true; outcome = ExtractionAttemptOutcome.NOT_FETCHED
        reason = ExtractionDiagnosticReason.FEED_ALREADY_COMPLETE
    }
    @Synchronized fun fetchStarted() {
        observed = true; origin = ExtractionAttemptOrigin.FRESH; fetchStarted = nanoClock()
        outcome = ExtractionAttemptOutcome.RUNNING; failureStage = ExtractionFailureStage.NONE; reason = null
    }
    @Synchronized fun httpResponse(status: Int, cacheHit: Boolean, networkObserved: Boolean) {
        httpStatus = status; httpCacheHit = cacheHit; networkResponseObserved = networkObserved
    }
    @Synchronized fun readBytes(count: Long) { bytes = count; bytesComplete = false }
    @Synchronized fun fetched() {
        bytesComplete = true; fetchElapsed = fetchStarted?.let { elapsed(it) }
    }
    @Synchronized fun parserStarted() { parserStarted = nanoClock() }
    @Synchronized fun parserFinished() { parserElapsed = parserStarted?.let { elapsed(it) } }
    @Synchronized fun candidateReady(content: String) {
        candidate = ArticleContentSelection.diagnosticMetrics(content)?.reportMetrics()
        outcome = ExtractionAttemptOutcome.SUCCEEDED
    }
    @Synchronized fun httpFailed(status: Int) {
        httpStatus = status; outcome = ExtractionAttemptOutcome.FAILED
        failureStage = ExtractionFailureStage.FETCH; reason = ExtractionDiagnosticReason.HTTP_ERROR
    }
    @Synchronized fun noReadableContent() {
        outcome = ExtractionAttemptOutcome.FAILED; failureStage = ExtractionFailureStage.PARSER
        reason = ExtractionDiagnosticReason.NO_READABLE_CONTENT
    }
    @Synchronized fun policyBlocked() {
        observed = true; outcome = ExtractionAttemptOutcome.NOT_FETCHED
        failureStage = ExtractionFailureStage.POLICY; reason = ExtractionDiagnosticReason.FETCH_POLICY_BLOCKED
    }
    @Synchronized fun cacheDecision(selection: ArticleContentSelection.Selection) {
        cacheComparison = selection.reportSnapshot()
        previousCache = cacheComparison?.before ?: previousCache
        if (selection.source == ArticleContentSelection.Source.FEED) {
            outcome = ExtractionAttemptOutcome.CACHE_RETAINED
            failureStage = ExtractionFailureStage.QUALITY; reason = ExtractionDiagnosticReason.QUALITY_REJECTED
        }
    }
    @Synchronized fun readerDecision(selection: ArticleContentSelection.Selection) {
        observed = true; readerComparison = selection.reportSnapshot()
        when (selection.source) {
            ArticleContentSelection.Source.EXTRACTED -> {
                if (outcome != ExtractionAttemptOutcome.CACHE_RETAINED) outcome = ExtractionAttemptOutcome.SUCCEEDED
            }
            ArticleContentSelection.Source.FEED -> {
                if (selection.reason != ArticleContentSelection.Reason.EXTRACTION_FAILED) {
                    failureStage = ExtractionFailureStage.QUALITY; reason = ExtractionDiagnosticReason.QUALITY_REJECTED
                }
                outcome = ExtractionAttemptOutcome.FEED_RETAINED
            }
            ArticleContentSelection.Source.UNAVAILABLE -> outcome = ExtractionAttemptOutcome.FAILED
        }
    }
    @Synchronized fun retainedDisplayedContent() { outcome = ExtractionAttemptOutcome.CACHE_RETAINED }

    @Synchronized fun failed(error: Throwable, cache: Boolean = false) {
        observed = true
        if (error is CancellationException) {
            outcome = ExtractionAttemptOutcome.CANCELLED; failureStage = ExtractionFailureStage.CANCELLED
            reason = ExtractionDiagnosticReason.CANCELLED
            return
        }
        if (outcome == ExtractionAttemptOutcome.NOT_FETCHED && failureStage == ExtractionFailureStage.POLICY) return
        if (error is ArticleRequiresBrowserException) {
            failureStage = if (error.reason == ArticleExtractionPreparation.AccessReason.SOURCE_LIMIT)
                ExtractionFailureStage.RESOURCE else ExtractionFailureStage.ACCESS
            reason = when (error.reason) {
                ArticleExtractionPreparation.AccessReason.PUBLISHER_RESTRICTED -> ExtractionDiagnosticReason.PUBLISHER_RESTRICTED
                ArticleExtractionPreparation.AccessReason.ACCESS_GATE -> ExtractionDiagnosticReason.ACCESS_GATE
                ArticleExtractionPreparation.AccessReason.BROWSER_CHALLENGE -> ExtractionDiagnosticReason.BROWSER_CHALLENGE
                ArticleExtractionPreparation.AccessReason.SOURCE_LIMIT -> ExtractionDiagnosticReason.SOURCE_LIMIT
            }
        } else if (error is ModernReadability.ExtractionException) {
            failureStage = if (error.reason == ModernReadability.Failure.ENGINE) ExtractionFailureStage.PARSER else ExtractionFailureStage.RESOURCE
            reason = ExtractionDiagnosticReason.valueOf(if (error.reason == ModernReadability.Failure.ENGINE) "ENGINE_ERROR" else error.reason.name)
        } else if (error is HtmlReadException) {
            failureStage = if (error.reason == HtmlReadFailure.INPUT_LIMIT) ExtractionFailureStage.RESOURCE else ExtractionFailureStage.FETCH
            reason = if (error.reason == HtmlReadFailure.INPUT_LIMIT) ExtractionDiagnosticReason.INPUT_LIMIT else ExtractionDiagnosticReason.NON_HTML_RESPONSE
        } else if (cache) {
            failureStage = ExtractionFailureStage.CACHE; reason = ExtractionDiagnosticReason.CACHE_WRITE_FAILED
        } else if (reason == null || failureStage == ExtractionFailureStage.NONE) {
            failureStage = ExtractionFailureStage.FETCH
            reason = when (error) {
                is SocketTimeoutException -> ExtractionDiagnosticReason.NETWORK_TIMEOUT
                is SSLException -> ExtractionDiagnosticReason.TLS_ERROR
                is IOException -> ExtractionDiagnosticReason.NETWORK_ERROR
                else -> ExtractionDiagnosticReason.UNKNOWN_ERROR
            }
        }
        outcome = ExtractionAttemptOutcome.FAILED
        if (fetchElapsed == null) fetchElapsed = fetchStarted?.let { elapsed(it) }
        if (parserElapsed == null) parserElapsed = parserStarted?.let { elapsed(it) }
    }

    @Synchronized fun finish() {
        if (finishedAt == null) finishedAt = nanoClock()
        if (outcome == ExtractionAttemptOutcome.RUNNING) outcome = ExtractionAttemptOutcome.NOT_FETCHED
    }
    @Synchronized fun snapshot(): ExtractionAttemptSnapshot? {
        if (!observed) return null
        val active = finishedAt == null
        return ExtractionAttemptSnapshot(started, elapsed(startedNanos, finishedAt ?: nanoClock()), origin, cacheState, refresh,
            if (active && outcome == ExtractionAttemptOutcome.RUNNING) ExtractionAttemptOutcome.RUNNING else outcome,
            failureStage, reason, httpStatus, bytes, bytesComplete, fetchElapsed, parserElapsed,
            previousCache, candidate, cacheComparison, readerComparison, httpCacheHit, networkResponseObserved)
    }

    private fun elapsed(start: Long, end: Long = nanoClock()) = ((end - start).coerceAtLeast(0) / 1_000_000)
}

internal fun ArticleContentSelection.ContentMetrics.reportMetrics() = ExtractionContentMetrics(textCharacters, paragraphs, images)
internal fun ArticleContentSelection.Selection.reportSnapshot() = ExtractionSelectionSnapshot(
    ExtractionSelectedSource.valueOf(source.name), ExtractionSelectionReason.valueOf(reason.name),
    detail?.let { ExtractionQualityVeto.valueOf(it.name) }, statistics?.before?.reportMetrics(),
    statistics?.candidate?.reportMetrics(), statistics?.selected?.reportMetrics(),
)
