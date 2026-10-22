package me.ash.reader.infrastructure.annotation

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Intentionally no foreign keys to Reader, SavedLibrary, accounts, feeds, or articles.
@Entity(tableName = "annotation_document", indices = [Index(value = ["identityKey"], unique = true)])
internal data class AnnotationDocumentEntity(
    @PrimaryKey val id: String,
    val identityKey: String,
    val normalizedUrl: String?,
    val createdAt: Long,
)

@Entity(tableName = "annotation", indices = [Index("documentId"), Index(value = ["exportEpoch", "remoteId"], unique = true)])
internal data class AnnotationEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val sourceKind: String,
    val sourceId: String,
    val sourceAccountId: Int?,
    val sourceUrl: String?,
    val sourceTitle: String,
    val sourceAuthor: String?,
    val sourceName: String?,
    val quote: String,
    val anchorJson: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long = 1,
    val exportEpoch: Long? = null,
    val exportedRevision: Long = 0,
    val remoteId: String? = null,
    val deletedAt: Long? = null,
)

@Entity(tableName = "annotation_outbox", indices = [
    Index(value = ["annotationId", "epoch", "revision", "operation"], unique = true),
    Index(value = ["epoch", "state", "nextAttemptAt"]),
])
internal data class AnnotationOutboxEntity(
    @PrimaryKey val id: String,
    val annotationId: String,
    val epoch: Long,
    val revision: Long,
    val operation: String,
    /** Frozen at enqueue, including across process death, edits and retries. */
    val payloadJson: String,
    val state: String = PENDING,
    val createdAt: Long,
    val nextAttemptAt: Long,
    val leaseToken: String? = null,
    val leaseUntil: Long? = null,
    val lastError: String? = null,
    val attemptCount: Int = 0,
    val needsReconciliation: Boolean = false,
    val acknowledgedRemoteId: String? = null,
) {
    companion object {
        const val PENDING = "PENDING"
        const val IN_FLIGHT = "IN_FLIGHT"
        const val SENT = "SENT"
        const val FAILED = "FAILED"
        const val REVIEW_REQUIRED = "REVIEW_REQUIRED"
        const val PAUSED = "PAUSED"
        const val CANCELED = "CANCELED"
    }
}

@Entity(tableName = "annotation_connection")
internal data class AnnotationConnectionEntity(
    @PrimaryKey val singleton: Int = 1,
    val epoch: Long = 0,
    val connectionId: String? = null,
    val exportEnabled: Boolean = false,
    val retryAt: Long? = null,
)

internal data class AnnotationOutboxStatus(
    val annotationId: String,
    val epoch: Long,
    val revision: Long,
    val state: String,
    val lastError: String?,
)
