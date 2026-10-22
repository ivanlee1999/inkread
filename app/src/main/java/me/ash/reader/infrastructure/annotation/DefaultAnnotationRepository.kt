package me.ash.reader.infrastructure.annotation

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.ash.reader.domain.annotation.Annotation
import me.ash.reader.domain.annotation.AnnotationConnection
import me.ash.reader.domain.annotation.AnnotationDraft
import me.ash.reader.domain.annotation.AnnotationExportClaim
import me.ash.reader.domain.annotation.AnnotationExportOperation
import me.ash.reader.domain.annotation.AnnotationExportPayload
import me.ash.reader.domain.annotation.AnnotationExportState
import me.ash.reader.domain.annotation.AnnotationOutbox
import me.ash.reader.domain.annotation.AnnotationRepository
import me.ash.reader.domain.annotation.AnnotationSource
import me.ash.reader.domain.annotation.AnnotationSourceKind
import me.ash.reader.infrastructure.annotation.AnnotationOutboxEntity.Companion.CANCELED
import me.ash.reader.infrastructure.annotation.AnnotationOutboxEntity.Companion.FAILED
import me.ash.reader.infrastructure.annotation.AnnotationOutboxEntity.Companion.IN_FLIGHT
import me.ash.reader.infrastructure.annotation.AnnotationOutboxEntity.Companion.PAUSED
import me.ash.reader.infrastructure.annotation.AnnotationOutboxEntity.Companion.PENDING
import me.ash.reader.infrastructure.annotation.AnnotationOutboxEntity.Companion.SENT
import me.ash.reader.infrastructure.annotation.AnnotationOutboxEntity.Companion.REVIEW_REQUIRED

/** Every mutation + outbox transition shares one transaction, including cross-instance claims. */
internal class DefaultAnnotationRepository(
    private val dao: AnnotationDao,
    private val transactions: AnnotationTransactions,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AnnotationRepository, AnnotationOutbox {
    override fun observeAll(): Flow<List<Annotation>> = observe(dao.observeAll())
    override fun observeDocument(source: AnnotationSource): Flow<List<Annotation>> =
        observe(dao.observeDocument(annotationDocumentId(source)))
    override fun observeConnection(): Flow<AnnotationConnection> =
        dao.observeConnection().map { (it ?: AnnotationConnectionEntity()).model() }.distinctUntilChanged()
    override fun observePendingCount(): Flow<Int> = dao.observePendingCount()
    override suspend fun currentConnection(): AnnotationConnection = connection().model()

    override suspend fun create(draft: AnnotationDraft): String {
        require(draft.quote.isNotBlank()) { "Select some article text first" }
        AnnotationStorageLimits.validate(draft)
        AnnotationPayloadJson.validateAnchor(draft.anchorJson)
        val identity = annotationIdentity(draft.source)
        val documentId = annotationDocumentId(draft.source)
        return transactions.run {
            val time = now()
            val connection = connection()
            val canExport = connection.connectionId != null && connection.exportEnabled
            dao.insertDocument(AnnotationDocumentEntity(documentId, identity, annotationUrl(draft.source.url), time))
            val annotation = AnnotationEntity(
                id = newId(), documentId = documentId, sourceKind = draft.source.kind.name,
                sourceId = draft.source.sourceId, sourceAccountId = draft.source.accountId,
                sourceUrl = draft.source.url, sourceTitle = draft.source.title,
                sourceAuthor = draft.source.author, sourceName = draft.source.sourceName,
                quote = draft.quote, anchorJson = draft.anchorJson, note = draft.note,
                createdAt = time, updatedAt = time,
                exportEpoch = connection.epoch.takeIf { canExport },
            )
            dao.insert(annotation)
            if (canExport) enqueueLatest(annotation, time, explicitRetry = false)
            annotation.id
        }
    }

    override suspend fun updateNote(id: String, note: String) = transactions.run {
        val previous = dao.get(id)?.takeIf { it.deletedAt == null } ?: return@run
        if (previous.note == note) return@run
        AnnotationStorageLimits.validate(AnnotationDraft(
            AnnotationSource(AnnotationSourceKind.valueOf(previous.sourceKind), previous.sourceId,
                previous.sourceAccountId, previous.sourceUrl, previous.sourceTitle, previous.sourceAuthor, previous.sourceName),
            previous.quote, previous.anchorJson, note,
        ))
        val updated = previous.copy(note = note, localRevision = Math.addExact(previous.localRevision, 1), updatedAt = now())
        dao.update(updated)
        val connection = connection()
        // Editing is not consent to export old/local-only annotations to a replacement connection.
        if (connection.connectionId != null && connection.exportEnabled && updated.exportEpoch == connection.epoch) {
            enqueueLatest(updated, updated.updatedAt, explicitRetry = false)
        }
    }

    override suspend fun remove(id: String) = transactions.run {
        val previous = dao.get(id)?.takeIf { it.deletedAt == null } ?: return@run
        // Retain only an update-only tombstone/receipt. A late network response cannot reinsert it.
        dao.update(previous.copy(quote = "", anchorJson = "{}", note = "", deletedAt = now(), updatedAt = now()))
        dao.cancelOutbox(id)
    }

    override suspend fun setConnection(connectionId: String?): Long = transactions.run {
        require(connectionId == null || connectionId.isNotBlank()) { "Missing connection identifier" }
        val epoch = Math.addExact(connection().epoch, 1)
        dao.pauseOldConnections()
        dao.putConnection(AnnotationConnectionEntity(epoch = epoch, connectionId = connectionId, exportEnabled = false))
        epoch
    }

    override suspend fun setExportEnabled(enabled: Boolean) = transactions.run {
        val connection = connection()
        require(!enabled || connection.connectionId != null) { "Connect before enabling annotation export" }
        dao.putConnection(connection.copy(exportEnabled = enabled))
    }

    override suspend fun pauseIfCurrent(epoch: Long): Boolean = transactions.run {
        val connection = connection()
        if (connection.epoch != epoch || connection.connectionId == null) return@run false
        dao.putConnection(connection.copy(exportEnabled = false))
        true
    }

    override suspend fun deferUntil(epoch: Long, retryAt: Long): Boolean = transactions.run {
        val connection = connection()
        if (connection.epoch != epoch || connection.connectionId == null) return@run false
        dao.putConnection(connection.copy(retryAt = maxOf(connection.retryAt ?: 0, retryAt)))
        true
    }

    override suspend fun retryFailed(ids: List<String>, epoch: Long) = transactions.run {
        val connection = connection()
        if (connection.epoch != epoch || connection.connectionId == null) return@run
        ids.distinct().forEach { id ->
            val annotation = dao.get(id)?.takeIf { it.deletedAt == null && it.exportEpoch == epoch } ?: return@forEach
            val rows = dao.outboxFor(annotation.id, epoch)
            if (rows.any { it.state == REVIEW_REQUIRED }) return@forEach
            rows.filter { it.state == FAILED }.forEach {
                dao.updateOutbox(it.copy(state = PENDING, nextAttemptAt = now(), lastError = null))
            }
        }
    }

    override suspend fun enqueueExisting(ids: List<String>) = transactions.run {
        val connection = connection()
        check(connection.connectionId != null) { "Connect before exporting existing annotations" }
        ids.distinct().forEach { id ->
            val previous = dao.get(id)?.takeIf { it.deletedAt == null } ?: return@forEach
            val bound = if (previous.exportEpoch == connection.epoch) previous else previous.copy(
                exportEpoch = connection.epoch, remoteId = null, exportedRevision = 0,
            ).also { dao.update(it) }
            enqueueLatest(bound, now(), explicitRetry = true)
        }
    }

    override suspend fun claim(epoch: Long, now: Long, leaseMillis: Long): AnnotationExportClaim? = transactions.run {
        require(leaseMillis > 0) { "Lease duration must be positive" }
        if (!isActive(epoch)) return@run null
        if ((connection().retryAt ?: 0) > now) return@run null
        val runnable = dao.runnable(epoch)
        // One network write at a time across foreground, WorkManager, and repository instances.
        if (runnable.any { it.state == IN_FLIGHT && (it.leaseUntil ?: 0) > now }) return@run null
        runnable.filter { it.state == IN_FLIGHT }.forEach {
            dao.updateOutbox(it.copy(state = PENDING, leaseToken = null, leaseUntil = null,
                needsReconciliation = it.needsReconciliation || it.operation == AnnotationExportOperation.CREATE.name,
                lastError = "The previous export was interrupted; checking its remote result before continuing."))
        }
        for (row in dao.runnable(epoch)) {
            if (row.state != PENDING || row.nextAttemptAt > now) continue
            val annotation = dao.get(row.annotationId)
            if (annotation == null || annotation.deletedAt != null || annotation.exportEpoch != epoch) {
                dao.updateOutbox(row.copy(state = CANCELED, leaseToken = null, leaseUntil = null))
                continue
            }
            val predecessors = dao.outboxFor(row.annotationId, epoch).filter { it.revision < row.revision }
            if (predecessors.any { it.state !in setOf(SENT, CANCELED, PAUSED) }) continue
            if (row.operation == AnnotationExportOperation.UPDATE.name && annotation.remoteId == null) continue
            val claimed = row.copy(state = IN_FLIGHT, leaseToken = newId(), leaseUntil = Math.addExact(now, leaseMillis),
                attemptCount = Math.addExact(row.attemptCount, 1), lastError = null)
            dao.updateOutbox(claimed)
            return@run claimed.claim(annotation.remoteId)
        }
        null
    }

    override suspend fun acknowledge(claim: AnnotationExportClaim, remoteId: String): Boolean = transactions.run {
        require(remoteId.isNotBlank()) { "Missing exported annotation identifier" }
        val row = validClaim(claim) ?: return@run false
        val annotation = dao.get(row.annotationId)?.takeIf { it.deletedAt == null && it.exportEpoch == row.epoch }
            ?: return@run false
        if (row.operation == AnnotationExportOperation.UPDATE.name && annotation.remoteId != remoteId) return@run false
        if (annotation.remoteId != null && annotation.remoteId != remoteId) return@run false
        if (dao.findRemoteBinding(row.epoch, remoteId, annotation.id) != null) {
            dao.updateOutbox(row.copy(state = REVIEW_REQUIRED, leaseToken = null, leaseUntil = null,
                lastError = "Readwise returned a highlight already linked to another local annotation. Review the duplicate in Readwise; its note was not overwritten."))
            return@run false
        }
        // Only acknowledgment metadata changes. A newer note, quote, or deleted row is never overwritten.
        dao.update(annotation.copy(remoteId = remoteId, exportedRevision = maxOf(annotation.exportedRevision, row.revision)))
        dao.updateOutbox(row.copy(state = SENT, leaseToken = null, leaseUntil = null,
            lastError = null, acknowledgedRemoteId = remoteId))
        true
    }

    override suspend fun fail(claim: AnnotationExportClaim, message: String, retryAt: Long?): Boolean = transactions.run {
        val row = validClaim(claim) ?: return@run false
        val annotation = dao.get(row.annotationId)?.takeIf { it.deletedAt == null && it.exportEpoch == row.epoch }
            ?: return@run false
        // An obsolete UPDATE must not later overwrite a newer queued note on the remote service.
        val superseded = row.operation == AnnotationExportOperation.UPDATE.name &&
            dao.outboxFor(annotation.id, row.epoch).any { it.revision > row.revision && it.state !in setOf(CANCELED, PAUSED) }
        dao.updateOutbox(row.copy(state = when { superseded -> CANCELED; retryAt == null -> FAILED; else -> PENDING },
            leaseToken = null, leaseUntil = null, lastError = message, nextAttemptAt = retryAt ?: row.nextAttemptAt))
        true
    }

    override suspend fun markUncertain(claim: AnnotationExportClaim, message: String, retryAt: Long): Boolean = transactions.run {
        val row = validClaim(claim) ?: return@run false
        if (row.operation != AnnotationExportOperation.CREATE.name) return@run false
        val annotation = dao.get(row.annotationId)?.takeIf { it.deletedAt == null && it.exportEpoch == row.epoch }
            ?: return@run false
        dao.updateOutbox(row.copy(state = PENDING, leaseToken = null, leaseUntil = null,
            needsReconciliation = true, lastError = message, nextAttemptAt = retryAt))
        true
    }

    override suspend fun requireReview(claim: AnnotationExportClaim, message: String): Boolean = transactions.run {
        val row = validClaim(claim) ?: return@run false
        val annotation = dao.get(row.annotationId)?.takeIf { it.deletedAt == null && it.exportEpoch == row.epoch }
            ?: return@run false
        dao.updateOutbox(row.copy(state = REVIEW_REQUIRED, leaseToken = null, leaseUntil = null, lastError = message))
        true
    }

    override suspend fun nextWakeAt(epoch: Long): Long? = transactions.run {
        if (!isActive(epoch)) return@run null
        val runnable = dao.runnable(epoch)
        val leases = runnable.filter { it.state == IN_FLIGHT }
        if (leases.isNotEmpty()) return@run maxOf(leases.minOf { it.leaseUntil ?: 0L }, connection().retryAt ?: 0L)
        val eligible = mutableListOf<Long>()
        for (row in runnable) {
            val annotation = dao.get(row.annotationId)?.takeIf { it.deletedAt == null && it.exportEpoch == epoch } ?: continue
            if (row.operation == AnnotationExportOperation.UPDATE.name && annotation.remoteId == null) continue
            if (dao.outboxFor(row.annotationId, epoch).any {
                    it.revision < row.revision && it.state !in setOf(SENT, CANCELED, PAUSED)
                }) continue
            eligible += row.nextAttemptAt
        }
        eligible.minOrNull()?.let { maxOf(it, connection().retryAt ?: 0L) }
    }

    private suspend fun enqueueLatest(annotation: AnnotationEntity, time: Long, explicitRetry: Boolean) {
        val epoch = requireNotNull(annotation.exportEpoch)
        val existing = dao.outboxFor(annotation.id, epoch)
        if (existing.any { it.state == REVIEW_REQUIRED }) return
        val create = existing.firstOrNull { it.operation == AnnotationExportOperation.CREATE.name && it.state != CANCELED }
        if (annotation.remoteId == null && create == null) {
            insertOutbox(annotation, AnnotationExportOperation.CREATE, time)
            return
        }
        if (explicitRetry && create?.state == FAILED) {
            dao.updateOutbox(create.copy(state = PENDING, nextAttemptAt = time, lastError = null))
        }
        if (annotation.remoteId == null && create?.revision == annotation.localRevision) return
        if (annotation.remoteId != null && annotation.exportedRevision >= annotation.localRevision) return
        // Preserve the in-flight payload; pending note updates can safely be superseded, never CREATE.
        existing.filter { it.operation == AnnotationExportOperation.UPDATE.name && it.revision < annotation.localRevision &&
            it.state in setOf(PENDING, FAILED) }.forEach { dao.updateOutbox(it.copy(state = CANCELED)) }
        val same = existing.firstOrNull { it.operation == AnnotationExportOperation.UPDATE.name && it.revision == annotation.localRevision }
        if (same == null) insertOutbox(annotation, AnnotationExportOperation.UPDATE, time)
        else if (explicitRetry && same.state == FAILED) dao.updateOutbox(same.copy(state = PENDING, nextAttemptAt = time, lastError = null))
    }

    private suspend fun insertOutbox(annotation: AnnotationEntity, operation: AnnotationExportOperation, time: Long) {
        val payload = AnnotationExportPayload(annotation.id, annotation.localRevision, annotationUrl(annotation.sourceUrl),
            annotation.sourceTitle, annotation.sourceAuthor, annotation.sourceName, annotation.quote, annotation.note, annotation.createdAt,
            AnnotationPayloadJson.location(annotation.anchorJson, annotation.quote))
        dao.insertOutbox(AnnotationOutboxEntity(id = newId(), annotationId = annotation.id,
            epoch = requireNotNull(annotation.exportEpoch), revision = annotation.localRevision, operation = operation.name,
            payloadJson = AnnotationPayloadJson.encode(payload), createdAt = time, nextAttemptAt = time))
    }

    private suspend fun validClaim(claim: AnnotationExportClaim): AnnotationOutboxEntity? {
        if (!isActive(claim.epoch)) return null
        return dao.outbox(claim.id)?.takeIf {
            it.epoch == claim.epoch && it.state == IN_FLIGHT && it.leaseToken == claim.leaseToken &&
                (it.leaseUntil ?: 0L) > now() &&
                it.revision == claim.payload.revision && it.annotationId == claim.payload.annotationId
        }
    }

    private suspend fun connection(): AnnotationConnectionEntity = dao.connection() ?: AnnotationConnectionEntity()
    private suspend fun isActive(epoch: Long): Boolean = connection().let {
        it.epoch == epoch && it.connectionId != null && it.exportEnabled
    }

    private fun observe(rows: Flow<List<AnnotationEntity>>): Flow<List<Annotation>> =
        combine(rows, dao.observeOutboxStatus(), dao.observeConnection()) { annotations, statuses, connection ->
            val current = connection ?: AnnotationConnectionEntity()
            annotations.map { row ->
                val pending = statuses.filter { it.annotationId == row.id && it.epoch == row.exportEpoch }
                val review = pending.firstOrNull { it.state == REVIEW_REQUIRED }
                val failure = pending.firstOrNull { it.state == FAILED }
                val active = current.connectionId != null && current.exportEnabled && current.epoch == row.exportEpoch
                val state = when {
                    row.exportEpoch != null && !active -> AnnotationExportState.PAUSED
                    review != null -> AnnotationExportState.REVIEW_REQUIRED
                    pending.any { it.state == IN_FLIGHT } -> AnnotationExportState.SENDING
                    failure != null -> AnnotationExportState.FAILED
                    pending.any { it.state == PENDING } -> AnnotationExportState.QUEUED
                    row.remoteId != null && row.exportedRevision == row.localRevision -> AnnotationExportState.EXPORTED
                    else -> AnnotationExportState.LOCAL_ONLY
                }
                Annotation(row.id, row.documentId, AnnotationSource(AnnotationSourceKind.valueOf(row.sourceKind),
                    row.sourceId, row.sourceAccountId, row.sourceUrl, row.sourceTitle, row.sourceAuthor, row.sourceName),
                    row.quote, row.anchorJson, row.note, row.createdAt, row.updatedAt, row.localRevision,
                    row.exportedRevision, row.remoteId, state, (review ?: failure)?.lastError, row.exportEpoch,
                    failure != null && review == null && current.connectionId != null && current.epoch == row.exportEpoch)
            }
        }.distinctUntilChanged()

    private fun AnnotationConnectionEntity.model() = AnnotationConnection(epoch, connectionId, exportEnabled, retryAt)
    private fun AnnotationOutboxEntity.claim(remoteId: String?) = AnnotationExportClaim(id, epoch,
        requireNotNull(leaseToken), requireNotNull(leaseUntil), attemptCount, needsReconciliation,
        AnnotationExportOperation.valueOf(operation), remoteId, AnnotationPayloadJson.decode(payloadJson))
}
