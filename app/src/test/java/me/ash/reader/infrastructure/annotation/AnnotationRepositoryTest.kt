package me.ash.reader.infrastructure.annotation

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ash.reader.domain.annotation.AnnotationDraft
import me.ash.reader.domain.annotation.AnnotationExportOperation
import me.ash.reader.domain.annotation.AnnotationExportState
import me.ash.reader.domain.annotation.AnnotationSource
import me.ash.reader.domain.annotation.AnnotationSourceKind
import me.ash.reader.domain.annotation.AnnotationSizeException
import me.ash.reader.domain.annotation.AnnotationExportPayload
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Production repository/state machine with a transactional in-memory DAO, not a Room substitute. */
class AnnotationRepositoryTest {
    @Test fun normalizedRssAndSavedUrlsShareDocumentButSourceSnapshotsStayDistinct() = runBlocking {
        val f = Fixture()
        val first = f.repository.create(draft(RSS))
        val second = f.repository.create(draft(SAVED))
        val annotations = f.repository.observeDocument(RSS).first()
        assertEquals(setOf(first, second), annotations.map { it.id }.toSet())
        assertEquals(1, annotations.map { it.documentId }.toSet().size)
        assertEquals(setOf(AnnotationSourceKind.RSS, AnnotationSourceKind.SAVED), annotations.map { it.source.kind }.toSet())
        assertEquals(1, f.dao.documents.size)
        assertNotEquals(annotationDocumentId(RSS.copy(url = null)), annotationDocumentId(RSS.copy(url = null, accountId = 8)))
        assertNotEquals(annotationDocumentId(RSS.copy(url = "https://example.test/a?b=1&c=2")),
            annotationDocumentId(RSS.copy(url = "https://example.test/a?c=2&b=1")))
    }

    @Test fun localCreateEnableAndReplacementEditNeverSilentlyConsentOldAnnotations() = runBlocking {
        val f = Fixture()
        val local = f.repository.create(draft())
        val epoch = f.connect()
        assertNull(f.repository.claim(epoch, f.time, 500))
        f.repository.updateNote(local, "still private")
        assertNull(f.repository.claim(epoch, f.time, 500))
        val bound = f.repository.create(draft())
        val oldClaim = f.repository.claim(epoch, f.time, 500)!!
        val replacement = f.connect("replacement")
        f.repository.updateNote(bound, "must not go to the replacement")
        assertNull(f.repository.claim(replacement, f.time, 500))
        assertFalse(f.repository.acknowledge(oldClaim, "old-remote"))
        assertFalse(f.repository.pauseIfCurrent(epoch))
        assertTrue(f.repository.currentConnection().exportEnabled)
        assertEquals(AnnotationExportState.PAUSED, f.repository.observeAll().first().first { it.id == bound }.exportState)
    }

    @Test fun frozenCreateThenLatestNoteUpdatePreservesQuoteContextAndRevision() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val id = f.repository.create(draft(note = "original"))
        val create = f.repository.claim(epoch, f.time, 500)!!
        f.repository.updateNote(id, "second")
        f.repository.updateNote(id, "third")
        assertEquals("original", create.payload.note)
        assertTrue(f.repository.fail(create, "rate limit", f.time + 100))
        assertNull(f.repository.claim(epoch, f.time, 500))
        assertEquals(f.time + 100, f.repository.nextWakeAt(epoch))
        f.time += 100
        val retry = f.repository.claim(epoch, f.time, 500)!!
        assertEquals(create.payload, retry.payload)
        assertEquals(2, retry.attemptCount)
        assertTrue(f.repository.acknowledge(retry, "remote"))
        val update = f.repository.claim(epoch, f.time, 500)!!
        assertEquals(AnnotationExportOperation.UPDATE, update.operation)
        assertEquals("remote", update.remoteId)
        assertEquals("third", update.payload.note)
        assertEquals(3L, update.payload.revision)
        assertTrue(f.repository.acknowledge(update, "remote"))
        val result = f.repository.observeAll().first().single()
        assertEquals(QUOTE, result.quote)
        assertEquals(ANCHOR, result.anchorJson)
        assertEquals(3L, result.localRevision)
        assertEquals(3L, result.exportedRevision)
        assertEquals(AnnotationExportState.EXPORTED, result.exportState)
    }

    @Test fun pendingNoteEditsCoalesceButInFlightAcknowledgmentNeverOverwritesNewerNote() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val id = f.repository.create(draft())
        f.repository.acknowledge(f.repository.claim(epoch, f.time, 500)!!, "remote")
        f.repository.updateNote(id, "revision two")
        val second = f.repository.claim(epoch, f.time, 500)!!
        f.repository.updateNote(id, "revision three")
        assertTrue(f.repository.acknowledge(second, "remote"))
        val observed = f.repository.observeAll().first().single()
        assertEquals("revision three", observed.note)
        assertEquals(2L, observed.exportedRevision)
        assertEquals(3L, f.repository.claim(epoch, f.time, 500)!!.payload.revision)
    }

    @Test fun oneLeaseAcrossConcurrentInstancesAndCrashRequiresReadOnlyReconciliationNotCreateReplay() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val first = f.repository.create(draft())
        val second = f.repository.create(draft())
        val contenders = (0..20).map { async { f.reopen().claim(epoch, f.time, 100) } }.awaitAll().filterNotNull()
        assertEquals(1, contenders.size)
        assertEquals(first, contenders.single().payload.annotationId)
        f.time += 101
        val next = f.reopen().claim(epoch, f.time, 100)!!
        assertEquals(first, next.payload.annotationId)
        assertTrue(next.needsReconciliation)
        assertEquals(contenders.single().payload, next.payload)
        assertTrue(f.repository.requireReview(next, "No unique Readwise match"))
        assertEquals(second, f.repository.claim(epoch, f.time, 100)!!.payload.annotationId)
        assertFalse(f.repository.acknowledge(contenders.single(), "stale"))
        val held = f.repository.observeAll().first().first { it.id == first }
        assertEquals(AnnotationExportState.REVIEW_REQUIRED, held.exportState)
        assertFalse(held.canRetry)
        f.repository.retryFailed(listOf(first), epoch)
        f.repository.enqueueExisting(listOf(first))
        assertEquals("REVIEW_REQUIRED", f.dao.rows.values.first { it.annotationId == first }.state)
    }

    @Test fun deletingAnInFlightAnnotationCannotResurrectLocalContentOrIssueRemoteDelete() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val id = f.repository.create(draft())
        val claim = f.repository.claim(epoch, f.time, 500)!!
        f.repository.remove(id)
        assertFalse(f.repository.acknowledge(claim, "retained-in-readwise"))
        f.repository.updateNote(id, "must not reappear")
        f.repository.enqueueExisting(listOf(id))
        assertTrue(f.repository.observeAll().first().isEmpty())
        assertNull(f.repository.claim(epoch, f.time, 500))
        assertEquals("", f.dao.annotations[id]!!.quote)
        assertTrue(f.dao.rows.values.all { it.payloadJson == "{}" })
    }

    @Test fun definiteFailureRetriesOnlyInSameEpochAmbiguousNeverRetries() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val id = f.repository.create(draft())
        val claim = f.repository.claim(epoch, f.time, 500)!!
        assertTrue(f.repository.fail(claim, "definite rejection", null))
        assertTrue(f.repository.observeAll().first().single().canRetry)
        assertNull(f.repository.claim(epoch, f.time, 500))
        f.repository.retryFailed(listOf(id), epoch + 1)
        assertNull(f.repository.claim(epoch, f.time, 500))
        f.repository.retryFailed(listOf(id), epoch)
        val retry = f.repository.claim(epoch, f.time, 500)!!
        assertTrue(f.repository.requireReview(retry, "Review in Readwise"))
        assertFalse(f.repository.observeAll().first().single().canRetry)
        f.repository.retryFailed(listOf(id), epoch)
        assertNull(f.repository.claim(epoch, f.time, 500))
        assertNull(f.repository.nextWakeAt(epoch))
    }

    @Test fun pauseResumeDoesNotEnqueueLocalNotesWrittenWhilePaused() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val id = f.repository.create(draft())
        f.repository.setExportEnabled(false)
        f.repository.updateNote(id, "private paused edit")
        val local = f.repository.create(draft())
        assertNull(f.repository.claim(epoch, f.time, 500))
        f.repository.setExportEnabled(true)
        val create = f.repository.claim(epoch, f.time, 500)!!
        assertEquals("", create.payload.note)
        assertTrue(f.repository.acknowledge(create, "remote"))
        assertNull(f.repository.claim(epoch, f.time, 500))
        assertEquals(2L, f.repository.observeAll().first().first { it.id == id }.localRevision)
        assertNull(f.repository.observeAll().first().first { it.id == local }.exportEpoch)
    }

    @Test fun failedQueueInsertionRollsBackAnnotationAndDocumentAtomically() = runBlocking {
        val f = Fixture()
        f.connect()
        f.dao.failInsertion = true
        try { f.repository.create(draft()); fail("Expected injected transaction failure") } catch (_: IllegalStateException) { }
        assertTrue(f.dao.annotations.isEmpty())
        assertTrue(f.dao.documents.isEmpty())
        assertTrue(f.dao.rows.isEmpty())
    }

    @Test fun quoteNoteAndAnchorAreNeverSilentlyTruncatedOrRenormalized() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val quote = "🛰e\u0301\n".repeat(4000)
        val note = "日\"本\\語".repeat(5000)
        f.repository.create(draft().copy(quote = quote, note = note))
        val claim = f.repository.claim(epoch, f.time, 500)!!
        assertEquals(quote, claim.payload.quote)
        assertEquals(note, claim.payload.note)
        val observed = f.repository.observeAll().first().single()
        assertEquals(quote, observed.quote)
        assertEquals(ANCHOR, observed.anchorJson)
    }

    @Test fun connectionWideRetryAfterBlocksOtherReadyAnnotationsAndSurvivesRepositoryReopen() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        f.repository.create(draft())
        f.repository.create(draft())
        val claim = f.repository.claim(epoch, f.time, 500)!!
        assertTrue(f.repository.deferUntil(epoch, f.time + 5_000))
        assertTrue(f.repository.fail(claim, "rate limited", f.time + 5_000))
        assertNull(f.reopen().claim(epoch, f.time + 4_999, 500))
        assertEquals(f.time + 5_000, f.reopen().nextWakeAt(epoch))
        assertFalse(f.repository.deferUntil(epoch + 1, Long.MAX_VALUE))
        f.time += 5_000
        assertNotNull(f.reopen().claim(epoch, f.time, 500))
    }

    @Test fun uncertainCreateIsReadOnlyOnNextClaimAndPreservesOriginalPayload() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val id = f.repository.create(draft(note = "original"))
        val claim = f.repository.claim(epoch, f.time, 500)!!
        assertTrue(f.repository.markUncertain(claim, "lost response", f.time + 10))
        f.repository.updateNote(id, "later edit")
        f.time += 10
        val reconcile = f.reopen().claim(epoch, f.time, 500)!!
        assertTrue(reconcile.needsReconciliation)
        assertEquals(claim.payload, reconcile.payload)
        assertTrue(f.repository.acknowledge(reconcile, "reconciled-id"))
        val patch = f.repository.claim(epoch, f.time, 500)!!
        assertEquals(AnnotationExportOperation.UPDATE, patch.operation)
        assertEquals("later edit", patch.payload.note)
    }

    @Test fun deduplicatedRemoteIdentityCannotBindAnotherLocalAnnotationOrOverwriteItsNote() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val first = f.repository.create(draft())
        val second = f.repository.create(draft())
        assertTrue(f.repository.acknowledge(f.repository.claim(epoch, f.time, 500)!!, "same-remote"))
        val secondClaim = f.repository.claim(epoch, f.time, 500)!!
        f.repository.updateNote(second, "must not overwrite first")
        assertFalse(f.repository.acknowledge(secondClaim, "same-remote"))
        assertNull(f.repository.claim(epoch, f.time, 500))
        val annotation = f.repository.observeAll().first().first { it.id == second }
        assertEquals(AnnotationExportState.REVIEW_REQUIRED, annotation.exportState)
        assertNull(annotation.remoteId)
        assertFalse(annotation.canRetry)
        f.repository.remove(first)
        assertEquals("same-remote", f.dao.annotations[first]!!.remoteId)
    }

    @Test fun failedOldUpdateDoesNotOverwriteNewerQueuedNoteWhenRetried() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val id = f.repository.create(draft())
        f.repository.acknowledge(f.repository.claim(epoch, f.time, 500)!!, "remote")
        f.repository.updateNote(id, "older update")
        val older = f.repository.claim(epoch, f.time, 500)!!
        f.repository.updateNote(id, "latest update")
        assertTrue(f.repository.fail(older, "temporary error", f.time + 1000))
        val latest = f.repository.claim(epoch, f.time, 500)!!
        assertEquals("latest update", latest.payload.note)
        assertTrue(f.repository.acknowledge(latest, "remote"))
        f.time += 1000
        assertNull(f.repository.claim(epoch, f.time, 500))
    }

    @Test fun utf8AggregateBudgetRejectsLargeCjkEmojiAndSourceMetadataBeforeAnyMutation() = runBlocking {
        val f = Fixture()
        f.connect()
        val rejected = listOf(
            draft().copy(quote = "界".repeat(90_000)),
            draft().copy(note = "🛰".repeat(70_000)),
            draft(RSS.copy(title = "文".repeat(90_000))),
            draft().copy(anchorJson = buildJsonObject { put("quote", "文".repeat(90_000)) }.toString()),
            draft().copy(quote = "q".repeat(80_000), note = "n".repeat(80_000),
                anchorJson = buildJsonObject { put("rawQuote", "a".repeat(100_000)) }.toString()),
        )
        rejected.forEach { value ->
            try { f.repository.create(value); fail("Expected explicit size rejection") }
            catch (error: AnnotationSizeException) { assertTrue(error.message!!.contains("Nothing was changed")) }
        }
        assertTrue(f.dao.annotations.isEmpty())
        assertTrue(f.dao.documents.isEmpty())
        assertTrue(f.dao.rows.isEmpty())
        f.repository.create(draft().copy(quote = "界".repeat(10_000), note = "🛰".repeat(10_000)))
        assertEquals("界".repeat(10_000), f.repository.observeAll().first().single().quote)
    }

    @Test fun jsonControlCharacterExpansionIsRejectedWithoutChangingExistingNoteOrOutbox() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val id = f.repository.create(draft(note = "keep this note"))
        val before = f.repository.observeAll().first().single()
        val beforeOutbox = f.dao.rows.toMap()
        val controlCharacters = "\u0001".repeat(50_000)
        assertTrue(controlCharacters.toByteArray(Charsets.UTF_8).size < AnnotationStorageLimits.MAX_ROW_BYTES)
        try { f.repository.updateNote(id, controlCharacters); fail("Expected encoded JSON size rejection") }
        catch (_: AnnotationSizeException) { }
        assertEquals(before, f.repository.observeAll().first().single())
        assertEquals(beforeOutbox, f.dao.rows)
        assertEquals("keep this note", f.repository.claim(epoch, f.time, 500)!!.payload.note)
        val largeEscapedAnchor = buildJsonObject { put("context", "\u0002".repeat(50_000)) }.toString()
        try { f.repository.create(draft().copy(anchorJson = largeEscapedAnchor)); fail("Expected serialized anchor size rejection") }
        catch (_: AnnotationSizeException) { }
        assertEquals(1, f.dao.annotations.size)
    }

    @Test fun optionalOrderingLocationUsesOnlyValidatedV1Utf16OffsetsAndOlderPayloadsStillDecode() = runBlocking {
        val f = Fixture()
        val epoch = f.connect()
        val quote = "A🛰B"
        fun anchor(start: Int, end: Int) = buildJsonObject {
            put("version", 1); put("textMapVersion", 1); put("documentFingerprint", "test-fingerprint")
            put("quote", quote); put("rangeQuote", quote); put("start", start); put("end", end)
        }.toString()
        f.repository.create(draft().copy(quote = quote, anchorJson = anchor(12, 16)))
        val claim = f.repository.claim(epoch, f.time, 500)!!
        assertEquals(12, claim.payload.location)
        assertEquals(null, AnnotationPayloadJson.location(anchor(-1, 3), quote))
        assertEquals(null, AnnotationPayloadJson.location(anchor(12, 15), quote))
        assertEquals(null, AnnotationPayloadJson.location(anchor(12, 16), "other quote"))
        assertEquals(null, AnnotationPayloadJson.location(ANCHOR, QUOTE))
        val original = AnnotationExportPayload("id", 1, null, "Title", null, null, "Quote", "Note", 1)
        val encoded = AnnotationPayloadJson.encode(original)
        assertFalse(encoded.contains("location"))
        assertEquals(original, AnnotationPayloadJson.decode(encoded))
        assertEquals(claim.payload, AnnotationPayloadJson.decode(AnnotationPayloadJson.encode(claim.payload)))
    }

    @Test fun invalidAnchorAndUnqualifiedRssIdentityFailBeforeMutation() = runBlocking {
        val f = Fixture()
        listOf(draft().copy(anchorJson = "[]"), draft().copy(anchorJson = "not json"),
            draft(RSS.copy(accountId = null))).forEach {
            try { f.repository.create(it); fail("Expected invalid selection rejection") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(f.dao.annotations.isEmpty())
    }

    private class Fixture {
        val dao = MemoryDao()
        var time = 1_000L
        val sequence = AtomicLong()
        val transaction = object : AnnotationTransactions {
            private val mutex = Mutex()
            override suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
                val before = Triple(dao.documents.toMap(), dao.annotations.toMap(), dao.rows.toMap())
                val connection = dao.savedConnection
                try { block().also { dao.publish() } }
                catch (t: Throwable) {
                    dao.documents.clear(); dao.documents.putAll(before.first)
                    dao.annotations.clear(); dao.annotations.putAll(before.second)
                    dao.rows.clear(); dao.rows.putAll(before.third)
                    dao.savedConnection = connection
                    dao.publish()
                    throw t
                }
            }
        }
        val repository = reopen()
        fun reopen() = DefaultAnnotationRepository(dao, transaction, { time }, { "id-${sequence.incrementAndGet().toString().padStart(5, '0')}" })
        suspend fun connect(id: String = "connection") = repository.setConnection(id).also { repository.setExportEnabled(true) }
    }

    private class MemoryDao : AnnotationDao {
        val documents = linkedMapOf<String, AnnotationDocumentEntity>()
        val annotations = linkedMapOf<String, AnnotationEntity>()
        val rows = linkedMapOf<String, AnnotationOutboxEntity>()
        var savedConnection: AnnotationConnectionEntity? = null
        var failInsertion = false
        private val updates = MutableStateFlow(0)
        fun publish() { updates.value++ }
        override fun observeAll() = updates.map { annotations.values.filter { it.deletedAt == null } }
        override fun observeDocument(documentId: String) = observeAll().map { it.filter { it.documentId == documentId } }
        override fun observeOutboxStatus() = updates.map { rows.values.filter { it.state !in setOf("SENT", "CANCELED") }
            .map { AnnotationOutboxStatus(it.annotationId, it.epoch, it.revision, it.state, it.lastError) } }
        override fun observeConnection(): Flow<AnnotationConnectionEntity?> = updates.map { savedConnection }
        override fun observePendingCount() = updates.map { rows.values.count { it.epoch == savedConnection?.epoch &&
            savedConnection?.exportEnabled == true && it.state in setOf("PENDING", "IN_FLIGHT") } }
        override suspend fun connection() = savedConnection
        override suspend fun putConnection(connection: AnnotationConnectionEntity) { savedConnection = connection }
        override suspend fun insertDocument(document: AnnotationDocumentEntity): Long {
            if (documents.containsKey(document.id)) return -1
            documents[document.id] = document; return 1
        }
        override suspend fun insert(annotation: AnnotationEntity) { check(!annotations.containsKey(annotation.id)); annotations[annotation.id] = annotation }
        override suspend fun update(annotation: AnnotationEntity): Int {
            if (!annotations.containsKey(annotation.id)) return 0
            annotations[annotation.id] = annotation; return 1
        }
        override suspend fun get(id: String) = annotations[id]
        override suspend fun findRemoteBinding(epoch: Long, remoteId: String, excludeId: String) = annotations.values.firstOrNull {
            it.exportEpoch == epoch && it.remoteId == remoteId && it.id != excludeId
        }
        override suspend fun insertOutbox(outbox: AnnotationOutboxEntity): Long {
            check(!failInsertion)
            if (rows.values.any { it.annotationId == outbox.annotationId && it.epoch == outbox.epoch &&
                    it.revision == outbox.revision && it.operation == outbox.operation }) return -1
            rows[outbox.id] = outbox; return 1
        }
        override suspend fun updateOutbox(outbox: AnnotationOutboxEntity): Int {
            if (!rows.containsKey(outbox.id)) return 0
            rows[outbox.id] = outbox; return 1
        }
        override suspend fun outbox(id: String) = rows[id]
        override suspend fun outboxFor(annotationId: String, epoch: Long) = rows.values.filter { it.annotationId == annotationId && it.epoch == epoch }
            .sortedWith(compareBy({ it.revision }, { it.createdAt }, { it.id }))
        override suspend fun runnable(epoch: Long) = rows.values.filter { it.epoch == epoch && it.state in setOf("PENDING", "IN_FLIGHT") }
            .sortedWith(compareBy({ it.createdAt }, { it.revision }, { it.id }))
        override suspend fun pauseOldConnections() { rows.replaceAll { _, row -> if (row.state in setOf("PENDING", "IN_FLIGHT", "FAILED"))
            row.copy(state = "PAUSED", leaseToken = null, leaseUntil = null) else row } }
        override suspend fun cancelOutbox(annotationId: String) { rows.replaceAll { _, row -> if (row.annotationId == annotationId)
            row.copy(state = if (row.state == "SENT") "SENT" else "CANCELED", payloadJson = "{}", leaseToken = null, leaseUntil = null, lastError = null) else row } }
    }

    companion object {
        private const val QUOTE = "An exact 🛰 quote"
        private const val ANCHOR = "{ \"version\": 1, \"quote\":\"An exact 🛰 quote\", \"start\":3, \"end\":20, \"prefix\":\"before\", \"suffix\":\"after\" }"
        private val RSS = AnnotationSource(AnnotationSourceKind.RSS, "article-id", 7, "HTTPS://EXAMPLE.TEST:443/a#section", "Article", "Ada", "Paper")
        private val SAVED = AnnotationSource(AnnotationSourceKind.SAVED, "saved-id", url = "https://example.test/a", title = "Saved title")
        private fun draft(source: AnnotationSource = RSS, note: String = "") = AnnotationDraft(source, QUOTE, ANCHOR, note)
    }
}
