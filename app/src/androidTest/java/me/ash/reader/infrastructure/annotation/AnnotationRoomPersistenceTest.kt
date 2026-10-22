package me.ash.reader.infrastructure.annotation

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.annotation.AnnotationDraft
import me.ash.reader.domain.annotation.AnnotationExportState
import me.ash.reader.domain.annotation.AnnotationSource
import me.ash.reader.domain.annotation.AnnotationSourceKind
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.library.SavedLibraryDatabase
import me.ash.reader.infrastructure.library.SavedLibraryEntity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Test-owned databases only. Must run through Android CI, not a standalone Room-annotation stub. */
class AnnotationRoomPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var database: AnnotationsDatabase
    private var time = 1_000L

    @Before fun create() {
        name = "annotations-test-${UUID.randomUUID()}.db"
        database = open()
    }

    @After fun close() {
        if (::database.isInitialized) database.close()
        if (::name.isInitialized) context.deleteDatabase(name)
    }

    @Test fun annotationsSurviveRssDeletionSavedDeletionAndDatabaseReopen() = runBlocking {
        withTimeout(20_000) {
            val rss = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).build()
            val saved = Room.inMemoryDatabaseBuilder(context, SavedLibraryDatabase::class.java).build()
            try {
                val account = Account(id = 71, name = "Annotation test account", type = AccountType.Local)
                rss.accountDao().insertList(listOf(account))
                rss.groupDao().insert(Group("annotation-group", "Group", 71))
                val feed = Feed("annotation-feed", "Paper", url = "https://example.test/feed", groupId = "annotation-group", accountId = 71)
                rss.feedDao().insert(feed)
                val article = Article("annotation-source", Date(1_000), "Article", "Ada", "<p>Persistent quote</p>",
                    "Description", link = URL, feedId = feed.id, accountId = 71)
                rss.articleDao().insert(article)
                saved.savedLibraryDao().insert(SavedLibraryEntity("saved-source", URL, URL, "Saved article", savedAt = 1_000))
                val repository = repository()
                val source = AnnotationSource(AnnotationSourceKind.RSS, article.id, 71, URL, "Article", "Ada", "Paper")
                val id = repository.create(AnnotationDraft(source, "Persistent quote", ANCHOR, "My note"))
                val shared = source.copy(kind = AnnotationSourceKind.SAVED, sourceId = "saved-source", accountId = null)
                assertEquals(id, repository.observeDocument(shared).first().single().id)
                rss.feedDao().delete(feed)
                rss.accountDao().delete(account)
                saved.savedLibraryDao().delete("saved-source")
                assertNull(rss.articleDao().queryById(article.id))
                assertNull(saved.savedLibraryDao().get("saved-source"))
                assertEquals(7, rss.openHelper.readableDatabase.version)
                assertEquals(1, saved.openHelper.readableDatabase.version)
                assertEquals(1, database.openHelper.readableDatabase.version)
                database.close()
                database = open()
                val persisted = repository().observeDocument(shared).first().single()
                assertEquals("Persistent quote", persisted.quote)
                assertEquals("My note", persisted.note)
                assertEquals(ANCHOR, persisted.anchorJson)
                assertEquals(source, persisted.source)
            } finally { rss.close(); saved.close() }
        }
    }

    @Test fun processDeathPersistsFrozenPayloadAndRequiresReconciliationBeforeCreateReplay() = runBlocking {
        withTimeout(20_000) {
            val first = repository()
            val epoch = first.setConnection("opaque-test-connection")
            first.setExportEnabled(true)
            val id = first.create(draft("first note"))
            val claimed = first.claim(epoch, time, 100)!!
            first.updateNote(id, "newer note")
            database.close()
            database = open()
            assertNull(repository().claim(epoch, time, 100))
            time += 101
            val recovered = repository().claim(epoch, time, 100)!!
            assertTrue(recovered.needsReconciliation)
            assertEquals(claimed.payload, recovered.payload)
            assertFalse(repository().acknowledge(claimed, "stale-lease"))
            assertTrue(repository().acknowledge(recovered, "reconciled"))
            val update = repository().claim(epoch, time, 100)!!
            assertEquals("newer note", update.payload.note)
            repository().remove(id)
            assertFalse(repository().acknowledge(update, "reconciled"))
            assertTrue(repository().observeAll().first().isEmpty())
            database.close()
            database = open()
            assertTrue(repository().observeAll().first().isEmpty())
        }
    }

    @Test fun separateRoomInstancesSerializeClaimsAndRejectRemoteIdentityCollisions() = runBlocking {
        withTimeout(20_000) {
            val first = repository()
            val epoch = first.setConnection("connection")
            first.setExportEnabled(true)
            first.create(draft())
            time++
            val secondId = first.create(draft())
            val other = open()
            try {
                val second = DefaultAnnotationRepository(other.annotations(), RoomAnnotationTransactions(other), { time })
                val claims = (0..15).map { index -> async {
                    (if (index % 2 == 0) first else second).claim(epoch, time, 1_000)
                } }.awaitAll().filterNotNull()
                assertEquals(1, claims.size)
                assertTrue(first.acknowledge(claims.single(), "remote-id"))
                val next = second.claim(epoch, time, 1_000)!!
                assertFalse(second.acknowledge(next, "remote-id"))
                assertEquals(AnnotationExportState.REVIEW_REQUIRED,
                    first.observeAll().first().first { it.id == secondId }.exportState)
            } finally { other.close() }
        }
    }

    @Test fun roomTransactionRollsBackDocumentAndAnnotationWhenOutboxInsertFails() = runBlocking {
        withTimeout(20_000) {
            val repository = repository()
            repository.setConnection("connection")
            repository.setExportEnabled(true)
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_outbox BEFORE INSERT ON annotation_outbox BEGIN SELECT RAISE(ABORT, 'test rejection'); END")
            try { repository.create(draft()); fail("Expected SQLite trigger rejection") } catch (_: android.database.sqlite.SQLiteException) { }
            assertTrue(repository.observeAll().first().isEmpty())
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM annotation_document").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_outbox")
            assertNotNull(repository.create(draft()))
        }
    }

    private fun open() = Room.databaseBuilder(context, AnnotationsDatabase::class.java, name).build()
    private fun repository() = DefaultAnnotationRepository(database.annotations(), RoomAnnotationTransactions(database), { time })
    private fun draft(note: String = "") = AnnotationDraft(
        AnnotationSource(AnnotationSourceKind.SAVED, "saved", url = URL, title = "Article"), "Persistent quote", ANCHOR, note)

    companion object {
        private const val URL = "https://example.test/article"
        private const val ANCHOR = "{\"version\":1,\"quote\":\"Persistent quote\",\"start\":0,\"end\":16,\"prefix\":\"\",\"suffix\":\"\"}"
    }
}
