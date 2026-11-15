package me.ash.reader.infrastructure.report

import com.google.gson.GsonBuilder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Explicit export schema: local marker IDs, account IDs and article IDs never enter either format. */
object ExtractionProblemFormatter {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    fun markdown(report: SavedExtractionProblem): String = markdown(report.snapshot, report.notes)

    fun json(report: SavedExtractionProblem): String = json(report.snapshot, report.notes)

    fun json(snapshot: ExtractionProblemSnapshot, notes: String): String = gson.toJson(export(snapshot, notes)) + "\n"

    fun markdown(snapshot: ExtractionProblemSnapshot, notes: String): String {
        val s = ExtractionProblemPrivacy.sanitizeSnapshot(snapshot)
        val safeNotes = ExtractionProblemPrivacy.sanitizeNotes(notes)
        return buildString {
            appendLine("# InkRead extraction problem report")
            appendLine()
            appendLine("- Schema: ${s.schemaVersion}")
            appendLine("- Captured: ${time(s.capturedAtEpochMillis)}")
            appendLine("- App: ${s.appVersion}; version code: ${known(s.appVersionCode)}")
            s.sourceRevision?.let { appendLine("- Source revision: `$it`") }
            appendLine("- Device model: ${s.deviceModel}; Android API: ${known(s.androidApi)}")
            appendLine("- Public article URL: ${s.publicArticleUrl?.let { "`" + it.replace("`", "%60") + "`" } ?: "Omitted or unavailable"}")
            appendLine("- URL redacted: ${s.urlRedacted}")
            appendLine("- Privacy omissions: ${s.privacyOmissions.sortedBy { it.name }.joinToString(", ") { word(it) }}")
            appendLine("- Requested source: ${word(s.requestedSourceMode)}")
            appendLine("- Displayed source: ${word(s.displayedSourceMode)}")
            appendLine("- Displayed content update pending: ${s.presentationUpdatePending}")
            appendLine()
            appendLine("## Current content metrics")
            appendLine()
            appendLine("Counts use normalized letters/digits, visible leaf paragraph blocks, and distinct editorial images. They contain no article text or image URLs.")
            appendLine("- Feed/server body: ${metric(s.feedMetrics)}")
            appendLine("- Displayed body: ${metric(s.displayedMetrics)}")
            appendLine()
            appendLine("## Last local extraction attempt")
            appendLine()
            val attempt = s.lastLocalAttempt
            if (attempt == null) {
                appendLine("No local extraction attempt recorded. This does not identify a parsing failure.")
            } else {
                appendLine("- Started: ${time(attempt.startedAtEpochMillis)}")
                appendLine("- Origin: ${word(attempt.origin)}; cache state: ${word(attempt.cacheState)}")
                appendLine("- Forced local refresh requested: ${attempt.requestedRefresh}")
                appendLine("- Outcome: ${word(attempt.outcome)}")
                appendLine("- Stage: ${word(attempt.failureStage)}")
                appendLine("- Reason: ${attempt.reason?.let(::word) ?: "Not recorded"}")
                appendLine("- HTTP status: ${attempt.httpStatus ?: "Not recorded"}")
                appendLine("- HTTP cache hit: ${attempt.httpCacheHit ?: "Not recorded"}")
                appendLine("- Network response observed: ${attempt.networkResponseObserved ?: "Not recorded"}")
                appendLine("- Response bytes actually read: ${attempt.responseBytesRead ?: "Not recorded"}")
                appendLine("- Response fully read: ${attempt.responseBytesComplete ?: "Not recorded"}")
                appendLine("- Elapsed ms: ${attempt.elapsedMillis ?: "Not recorded"}")
                appendLine("- Fetch elapsed ms: ${attempt.fetchElapsedMillis ?: "Not recorded"}")
                appendLine("- Parser elapsed ms: ${attempt.parserElapsedMillis ?: "Not recorded"}")
                appendLine("- Previous cache: ${metric(attempt.previousCache)}")
                appendLine("- Fetched candidate: ${metric(attempt.fetchedCandidate)}")
                comparison("Cache comparison", attempt.cacheComparison)
                comparison("Reader comparison", attempt.readerComparison)
            }
            appendLine()
            appendLine("## User notes")
            appendLine()
            if (safeNotes.isBlank()) appendLine("No notes.")
            else safeNotes.lines().forEach { appendLine("> $it") }
        }
    }

    private fun export(snapshot: ExtractionProblemSnapshot, notes: String): Map<String, Any?> {
        val s = ExtractionProblemPrivacy.sanitizeSnapshot(snapshot)
        return linkedMapOf<String, Any?>(
            "schema_version" to 1,
            "captured_at_epoch_millis" to s.capturedAtEpochMillis.takeIf { it > 0 },
            "app_version" to s.appVersion,
            "app_version_code" to s.appVersionCode.takeIf { it > 0 },
            "device_model" to s.deviceModel,
            "android_api" to s.androidApi.takeIf { it > 0 },
            "public_article_url" to s.publicArticleUrl,
            "url_redacted" to s.urlRedacted,
            "privacy_omissions" to s.privacyOmissions.sortedBy { it.name }.map { code(it) },
            "requested_source_mode" to code(s.requestedSourceMode),
            "displayed_source_mode" to code(s.displayedSourceMode),
            "presentation_update_pending" to s.presentationUpdatePending,
            "feed_metrics" to metrics(s.feedMetrics),
            "displayed_metrics" to metrics(s.displayedMetrics),
            "last_local_attempt" to s.lastLocalAttempt?.let(::attempt),
            "notes" to ExtractionProblemPrivacy.sanitizeNotes(notes),
        ).apply { s.sourceRevision?.let { put("source_revision", it) } }
    }

    private fun attempt(a: ExtractionAttemptSnapshot): Map<String, Any?> = linkedMapOf(
        "started_at_epoch_millis" to a.startedAtEpochMillis.takeIf { it > 0 },
        "elapsed_millis" to a.elapsedMillis,
        "origin" to code(a.origin),
        "cache_state" to code(a.cacheState),
        "requested_refresh" to a.requestedRefresh,
        "outcome" to code(a.outcome),
        "failure_stage" to code(a.failureStage),
        "reason" to a.reason?.let(::code),
        "http_status" to a.httpStatus,
        "http_cache_hit" to a.httpCacheHit,
        "network_response_observed" to a.networkResponseObserved,
        "response_bytes_read" to a.responseBytesRead,
        "response_bytes_complete" to a.responseBytesComplete,
        "fetch_elapsed_millis" to a.fetchElapsedMillis,
        "parser_elapsed_millis" to a.parserElapsedMillis,
        "previous_cache" to metrics(a.previousCache),
        "fetched_candidate" to metrics(a.fetchedCandidate),
        "cache_comparison" to a.cacheComparison?.let(::comparison),
        "reader_comparison" to a.readerComparison?.let(::comparison),
    )

    private fun comparison(c: ExtractionSelectionSnapshot): Map<String, Any?> = linkedMapOf(
        "source" to code(c.source), "reason" to code(c.reason), "detail" to c.detail?.let(::code),
        "before" to metrics(c.before), "candidate" to metrics(c.candidate), "selected" to metrics(c.selected),
    )

    private fun metrics(m: ExtractionContentMetrics?): Map<String, Int>? = m?.let {
        linkedMapOf("normalized_text_characters" to it.textCharacters, "paragraph_blocks" to it.paragraphs, "editorial_images" to it.images)
    }

    private fun StringBuilder.comparison(label: String, c: ExtractionSelectionSnapshot?) {
        if (c == null) appendLine("- $label: Not recorded") else {
            appendLine("- $label: source=${code(c.source)}, reason=${code(c.reason)}, detail=${c.detail?.let(::code) ?: "none"}")
            appendLine("  - Before: ${metric(c.before)}")
            appendLine("  - Candidate: ${metric(c.candidate)}")
            appendLine("  - Selected: ${metric(c.selected)}")
        }
    }

    private fun metric(m: ExtractionContentMetrics?): String = m?.let {
        "${it.textCharacters} normalized text characters; ${it.paragraphs} paragraph blocks; ${it.images} editorial images"
    } ?: "Not recorded or not measurable within limits"

    private fun code(value: Enum<*>): String = value.name.lowercase(Locale.ROOT)
    private fun word(value: Enum<*>): String = code(value).replace('_', ' ')
    private fun known(value: Int): String = if (value > 0) value.toString() else "Not recorded"
    private fun time(value: Long): String = if (value > 0) DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(value)) else "Not recorded"
}
