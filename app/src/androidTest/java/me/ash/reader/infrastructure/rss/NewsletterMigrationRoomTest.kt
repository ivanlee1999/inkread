package me.ash.reader.infrastructure.rss

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.annotation.AnnotationDraft
import me.ash.reader.domain.annotation.AnnotationSource
import me.ash.reader.domain.annotation.AnnotationSourceKind
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.annotation.AnnotationsDatabase
import me.ash.reader.infrastructure.annotation.DefaultAnnotationRepository
import me.ash.reader.infrastructure.annotation.RoomAnnotationTransactions
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.allMigrations
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Frozen release schema and synthetic records only; never opens the user's Reader database. */
class NewsletterMigrationRoomTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun versionSevenUpgradePreservesEveryStoredColumnAndAnnotations() = runBlocking {
        withTimeout(20_000) {
            val name = "newsletter-upgrade-${UUID.randomUUID()}.db"
            val annotationsName = "$name-annotations"
            var rss: AndroidDatabase? = null
            var annotations: AnnotationsDatabase? = null
            try {
                val schema = InstrumentationRegistry.getInstrumentation().context.assets
                    .open("me.ash.reader.infrastructure.db.AndroidDatabase/7.json")
                    .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
                assertEquals(7, schema.getInt("version"))
                val queries = linkedMapOf<String, String>()
                val before = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices")
                        if (indices != null) for (i in 0 until indices.length()) {
                            old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                        val fields = entity.getJSONArray("fields")
                        val columns = (0 until fields.length()).joinToString(",") { "`${fields.getJSONObject(it).getString("columnName")}`" }
                        queries[table] = "SELECT $columns FROM `$table` ORDER BY rowid"
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
                    old.execSQL("INSERT INTO account(id,name,type,updateAt,lastArticleId,securityKey) VALUES(71,'Existing account',4,1000,'71\$123','synthetic-key')")
                    old.execSQL("INSERT INTO `group`(id,name,accountId) VALUES('71\$group','Existing group',71)")
                    old.execSQL("INSERT INTO feed(id,name,icon,url,groupId,accountId,isNotification,isFullContent,isBrowser) VALUES('71\$feed','Existing feed','https://example.test/icon','https://example.test/feed','71\$group',71,1,1,0)")
                    old.execSQL("INSERT INTO article(id,date,title,author,rawDescription,shortDescription,fullContent,img,link,feedId,accountId,isUnread,isStarred,isReadLater,updateAt) VALUES('71\$123',1000,'Existing article','Ada',?,'Existing description','Legacy body','https://example.test/photo','https://example.test/article','71\$feed',71,0,1,1,2000)", arrayOf(BODY))
                    old.execSQL("INSERT INTO archived_article(id,feedId,link) VALUES(10,'71\$feed','https://example.test/archived')")
                    old.version = 7
                    queries.mapValues { (_, query) -> old.rawQuery(query, null).use(::rows) }
                }

                annotations = Room.databaseBuilder(context, AnnotationsDatabase::class.java, annotationsName).build()
                val source = AnnotationSource(AnnotationSourceKind.RSS, "71\$123", 71,
                    "https://example.test/article", "Existing article", "Ada", "Existing feed")
                val repository = DefaultAnnotationRepository(annotations.annotations(), RoomAnnotationTransactions(annotations), { 3_000L })
                val annotationId = repository.create(AnnotationDraft(source, "Persistent quote", ANCHOR, "Existing note"))
                val savedAnnotation = repository.observeDocument(source).first().single()

                rss = Room.databaseBuilder(context, AndroidDatabase::class.java, name).addMigrations(*allMigrations).build()
                assertEquals(8, rss.openHelper.readableDatabase.version)
                for ((table, query) in queries) {
                    assertEquals("Upgrade changed existing $table columns", before[table], rss.openHelper.readableDatabase.query(query).use(::rows))
                }
                val feed = rss.feedDao().queryById("71\$feed")!!
                assertFalse(feed.isNewsletter)
                assertTrue(feed.isFullContent)
                assertTrue(feed.isNotification)
                val article = rss.articleDao().queryByAccountAndId(71, "71\$123")!!
                assertEquals(BODY, article.rawDescription)
                assertFalse(article.isUnread)
                assertTrue(article.isStarred)
                assertTrue(article.isReadLater)

                rss.close()
                rss = Room.databaseBuilder(context, AndroidDatabase::class.java, name).addMigrations(*allMigrations).build()
                assertEquals(feed, rss.feedDao().queryById(feed.id))
                assertEquals(article, rss.articleDao().queryByAccountAndId(71, article.id))
                annotations.close()
                annotations = Room.databaseBuilder(context, AnnotationsDatabase::class.java, annotationsName).build()
                val reopened = DefaultAnnotationRepository(annotations.annotations(), RoomAnnotationTransactions(annotations), { 4_000L })
                    .observeDocument(source).first().single()
                assertEquals(annotationId, reopened.id)
                assertEquals(savedAnnotation, reopened)
            } finally {
                rss?.close()
                annotations?.close()
                context.deleteDatabase(name)
                context.deleteDatabase(annotationsName)
            }
        }
    }

    @Test fun newsletterMarkerSurvivesServerRefreshAndGroupPresetsCannotEnablePublisherAccess() = runBlocking {
        withTimeout(20_000) {
            val database = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).build()
            try {
                database.accountDao().insertList(listOf(Account(id = 71, name = "Synthetic account", type = AccountType.Local)))
                database.groupDao().insert(Group("71\$group", "Synthetic group", 71))
                val newsletter = Feed("71\$newsletter", "Newsletter", url = "https://example.test/private/feed?token=synthetic",
                    groupId = "71\$group", accountId = 71, isNewsletter = true)
                val ordinary = newsletter.copy(id = "71\$ordinary", name = "Ordinary", isNewsletter = false)
                database.feedDao().insert(newsletter, ordinary)
                database.feedDao().updateIsFullContentByGroupId(71, newsletter.groupId, true)
                assertFalse(database.feedDao().queryById(newsletter.id)!!.isFullContent)
                assertTrue(database.feedDao().queryById(ordinary.id)!!.isFullContent)
                database.feedDao().updateIsBrowserByGroupId(71, newsletter.groupId, true)
                assertFalse(database.feedDao().queryById(newsletter.id)!!.isBrowser)
                assertTrue(database.feedDao().queryById(ordinary.id)!!.isBrowser)
                database.feedDao().insertOrUpdate(listOf(newsletter.copy(name = "Renamed on server", isNewsletter = false,
                    isFullContent = true, isBrowser = true, icon = "https://example.test/tracking-icon")))
                val refreshed = database.feedDao().queryById(newsletter.id)!!
                assertTrue(refreshed.isNewsletter)
                assertFalse(refreshed.isFullContent)
                assertFalse(refreshed.isBrowser)
                assertNull(refreshed.icon)
                assertFalse(database.feedDao().queryNoIcon(71).any { it.id == newsletter.id })
            } finally { database.close() }
        }
    }

    @Test fun lateIconAndPreferenceWritesCannotRevertNewsletterMode() = runBlocking {
        withTimeout(20_000) {
            val database = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).build()
            val iconStarted = CountDownLatch(1)
            val releaseIcon = CountDownLatch(1)
            val requests = AtomicInteger()
            // Every request is intercepted; no publisher or external server is contacted.
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests.incrementAndGet()
                val icon = chain.request().url.encodedPath == "/favicon.ico"
                if (icon) {
                    iconStarted.countDown()
                    check(releaseIcon.await(10, TimeUnit.SECONDS)) { "Fixture icon response was not released" }
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", if (icon) "image/x-icon" else "text/html")
                    .body((if (icon) "synthetic-icon" else "<html><title>Fixture</title></html>")
                        .toResponseBody((if (icon) "image/x-icon" else "text/html").toMediaType())).build()
            }.build()
            try {
                database.accountDao().insertList(listOf(Account(id = 71, name = "Synthetic account", type = AccountType.Local)))
                database.groupDao().insert(Group("71\$group", "Synthetic group", 71))
                val stale = Feed("71\$icon", "Ordinary source", url = "https://icon-fixture.test/feed",
                    groupId = "71\$group", accountId = 71, isFullContent = true)
                database.feedDao().insert(stale)
                assertEquals(0, database.feedDao().updateIconIfOrdinary(72, stale.id, stale.url, "wrong-account"))
                assertEquals(0, database.feedDao().updateIconIfOrdinary(71, stale.id, "https://changed.test/feed", "wrong-url"))
                val helper = RssHelper(context, Dispatchers.IO, client)
                val fetching = async(Dispatchers.IO) { helper.fetchAndSaveRssIcon(database.feedDao(), stale) }
                assertTrue(withContext(Dispatchers.IO) { iconStarted.await(10, TimeUnit.SECONDS) })
                database.feedDao().updateNewsletter(stale.accountId, stale.id, true)
                database.groupDao().insert(Group("71\$moved", "Destination group", 71))
                database.feedDao().updateName(stale.accountId, stale.id, "Renamed source")
                database.feedDao().updateGroup(stale.accountId, stale.id, "71\$moved")
                database.feedDao().updateUrl(stale.accountId, stale.id, "https://icon-fixture.test/revised-feed")
                database.feedDao().updateReaderPreferencesIfOrdinary(stale.accountId, stale.id, true, true)
                database.feedDao().updateNotification(stale.accountId, stale.id, true)
                releaseIcon.countDown()
                fetching.await()

                val current = database.feedDao().queryById(stale.id)!!
                assertTrue(current.isNewsletter)
                assertEquals("Renamed source", current.name)
                assertEquals("71\$moved", current.groupId)
                assertEquals("https://icon-fixture.test/revised-feed", current.url)
                assertFalse(current.isBrowser)
                assertFalse(current.isFullContent)
                assertTrue(current.isNotification)
                assertNull(current.icon)
                val count = requests.get()
                withContext(Dispatchers.IO) { helper.fetchAndSaveRssIcon(database.feedDao(), stale.copy(url = current.url)) }
                assertEquals("A stale ordinary snapshot must not start another lookup", count, requests.get())
            } finally {
                releaseIcon.countDown()
                database.close()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
    }

    private companion object {
        const val BODY = "<article>\r\n<p>Persistent quote</p><p>原有正文 &amp; exact markup.</p>\n</article>"
        const val ANCHOR = "{\"version\":1,\"quote\":\"Persistent quote\",\"start\":0,\"end\":16,\"prefix\":\"\",\"suffix\":\"\"}"
    }
}
