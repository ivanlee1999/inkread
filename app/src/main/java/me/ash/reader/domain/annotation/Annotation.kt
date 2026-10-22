package me.ash.reader.domain.annotation

import kotlinx.coroutines.flow.Flow

/** This describes where a local selection came from, not a foreign key into either library. */
enum class AnnotationSourceKind { RSS, SAVED }

data class AnnotationSource(
    val kind: AnnotationSourceKind,
    val sourceId: String,
    val accountId: Int? = null,
    val url: String? = null,
    val title: String,
    val author: String? = null,
    val sourceName: String? = null,
)

data class AnnotationDraft(
    val source: AnnotationSource,
    val quote: String,
    /** Immutable selection JSON, including exact quote, context and UTF-16 offsets. */
    val anchorJson: String,
    val note: String = "",
)

/** Explicit local storage rejection, never a truncated annotation or discarded previous note. */
class AnnotationSizeException : IllegalArgumentException(
    "This annotation is too large to save. Select a shorter passage or shorten the note. Nothing was changed."
)

enum class AnnotationExportState { LOCAL_ONLY, QUEUED, SENDING, EXPORTED, FAILED, REVIEW_REQUIRED, PAUSED }

data class Annotation(
    val id: String,
    val documentId: String,
    val source: AnnotationSource,
    val quote: String,
    val anchorJson: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long,
    val exportedRevision: Long,
    val remoteId: String?,
    val exportState: AnnotationExportState,
    val exportError: String?,
    val exportEpoch: Long?,
    val canRetry: Boolean,
)

/** All operations are device-local. Removing an annotation never deletes the exported copy. */
interface AnnotationRepository {
    fun observeAll(): Flow<List<Annotation>>
    fun observeDocument(source: AnnotationSource): Flow<List<Annotation>>
    suspend fun create(draft: AnnotationDraft): String
    suspend fun updateNote(id: String, note: String)
    suspend fun remove(id: String)
}

/** A connection ID is an opaque local identifier, NOT a verified Readwise account identity. */
data class AnnotationConnection(
    val epoch: Long = 0,
    val connectionId: String? = null,
    val exportEnabled: Boolean = false,
    val retryAt: Long? = null,
)

enum class AnnotationExportOperation { CREATE, UPDATE }

/** Exact, immutable request content. Export adapters must reject oversize values, never truncate. */
data class AnnotationExportPayload(
    val annotationId: String,
    val revision: Long,
    val sourceUrl: String?,
    val title: String,
    val author: String?,
    val sourceName: String?,
    val quote: String,
    val note: String,
    val highlightedAt: Long,
    /** Local body UTF-16 ordering hint only, not a Readwise Reader anchoring promise. */
    val location: Int? = null,
)

data class AnnotationExportClaim(
    val id: String,
    val epoch: Long,
    val leaseToken: String,
    val leaseUntil: Long,
    val attemptCount: Int,
    val needsReconciliation: Boolean,
    val operation: AnnotationExportOperation,
    val remoteId: String?,
    val payload: AnnotationExportPayload,
)

/** Durable one-way export queue. No tokens, HTTP, provider imports, or account identification. */
interface AnnotationOutbox {
    fun observeConnection(): Flow<AnnotationConnection>
    fun observePendingCount(): Flow<Int>
    suspend fun currentConnection(): AnnotationConnection
    /** Every explicit replacement/disconnect advances the epoch and fences old acknowledgments. */
    suspend fun setConnection(connectionId: String?): Long
    /** Resumes only work already explicitly queued in this epoch; does not opt in old local notes. */
    suspend fun setExportEnabled(enabled: Boolean)
    /** Auth failures may pause only the connection which produced the response. */
    suspend fun pauseIfCurrent(epoch: Long): Boolean
    /** Connection-wide throttle, so a second annotation cannot bypass Retry-After. */
    suspend fun deferUntil(epoch: Long, retryAt: Long): Boolean
    /** Retry definite failures only, without granting a replacement connection access. */
    suspend fun retryFailed(ids: List<String>, epoch: Long)
    /** Explicit user opt-in for these existing local annotations on the current connection. */
    suspend fun enqueueExisting(ids: List<String>)
    suspend fun claim(epoch: Long, now: Long, leaseMillis: Long): AnnotationExportClaim?
    /** Returns false for stale epochs, superseded claims, paused export, or local deletion. */
    suspend fun acknowledge(claim: AnnotationExportClaim, remoteId: String): Boolean
    /** Null retryAt is a durable, visible failure requiring explicit retry. */
    suspend fun fail(claim: AnnotationExportClaim, message: String, retryAt: Long?): Boolean
    /** A potentially committed CREATE must use read-only reconciliation, not another POST. */
    suspend fun markUncertain(claim: AnnotationExportClaim, message: String, retryAt: Long): Boolean
    /** An ambiguous remote write is held for review, never automatically retried. */
    suspend fun requireReview(claim: AnnotationExportClaim, message: String): Boolean
    /** Earliest persisted work/lease deadline; null when paused or nothing can run. */
    suspend fun nextWakeAt(epoch: Long): Long?
}
