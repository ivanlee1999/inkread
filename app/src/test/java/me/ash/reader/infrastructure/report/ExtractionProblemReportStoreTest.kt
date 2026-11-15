package me.ash.reader.infrastructure.report

import android.content.Context
import com.google.gson.JsonParser
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.infrastructure.rss.FeedSourceDirectorySync
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*

class ExtractionProblemReportStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(sync: FeedSourceDirectorySync = mock()) = ExtractionProblemReportStore(
        mock<Context> { on { filesDir } doReturn temporary.root }, Dispatchers.IO, sync,
    )
    private fun snapshot() = ExtractionProblemSnapshot(
        capturedAtEpochMillis = 1_000, appVersion = "0.16.24", appVersionCode = 69,
        sourceRevision = "22615a658003278bdf598216b9d0b27dba3ff24e", deviceModel = "Palma Pro", androidApi = 30,
        publicArticleUrl = "https://www.newyorker.com/news/public-story?utm_source=rss#body",
        requestedSourceMode = ExtractionSourceMode.LOCAL, displayedSourceMode = ExtractionSourceMode.SERVER,
    )
    private fun files(): List<File> = temporary.root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()

    @Test fun savedReportSurvivesRestartAndRepeatedSavePreservesItsIdentityAndCreationTime() = runBlocking {
        val first = store().save(1, "private:article", snapshot(), "First note")
        val payload = JsonParser.parseString(files().single().readText()).asJsonObject
        assertEquals(setOf("storageVersion", "report"), payload.keySet())
        assertTrue(payload.getAsJsonObject("report").keySet().containsAll(
            setOf("id", "accountId", "articleId", "createdAtEpochMillis", "updatedAtEpochMillis", "snapshot", "notes")))
        val reopened = store()
        assertEquals(first, reopened.find(1, "private:article"))
        val updated = reopened.save(1, "private:article", snapshot().copy(capturedAtEpochMillis = 2_000), "Updated note")
        assertEquals(first.id, updated.id)
        assertEquals(first.createdAtEpochMillis, updated.createdAtEpochMillis)
        assertTrue(updated.updatedAtEpochMillis >= first.updatedAtEpochMillis)
        assertEquals("Updated note", store().list(1).single().notes)
        assertEquals(1, files().size)
        assertFalse(temporary.root.resolve("readability").exists())
        assertFalse(temporary.root.resolve("reader-browser-text").exists())
    }

    @Test fun articlesAndAccountsRemainSeparateAndUntrustedIdsNeverBecomePaths() = runBlocking {
        val storage = store()
        val one = storage.save(1, "../../private/article", snapshot(), "Account one")
        val two = storage.save(2, "../../private/article", snapshot(), "Account two")
        val other = storage.save(1, "different", snapshot(), "Another article")
        assertNotEquals(one.id, two.id)
        assertEquals(setOf(one.id, other.id), storage.list(1).map { it.id }.toSet())
        assertEquals(listOf(two), storage.list(2))
        assertNull(storage.find(3, "../../private/article"))
        assertTrue(files().all { it.canonicalPath.startsWith(temporary.root.canonicalPath + File.separator) })
        assertTrue(files().all { it.name.matches(Regex("[0-9a-f]{64}\\.json")) })
        assertTrue(files().all { !it.path.contains("private/article") })
    }

    @Test fun updateAndRemoveRequireTheOwningAccountAndDoNotAffectOtherReports() = runBlocking {
        val storage = store()
        val one = storage.save(1, "article", snapshot(), "One")
        val two = storage.save(2, "article", snapshot(), "Two")
        assertFalse(storage.remove(2, one.id))
        try {
            storage.updateNotes(2, one.id, "Wrong account")
            fail("A different account must not update this report")
        } catch (_: FileNotFoundException) { }
        val updated = storage.updateNotes(1, one.id, "Revised")
        assertEquals(one.id, updated.id)
        assertEquals(one.createdAtEpochMillis, updated.createdAtEpochMillis)
        assertEquals("Revised", store().find(1, "article")!!.notes)
        assertTrue(storage.remove(1, one.id))
        assertFalse(storage.remove(1, one.id))
        assertNull(store().find(1, "article"))
        assertEquals(two, store().list(2).single())
    }

    @Test fun fullCollectionRefusesNewReportsWithoutEvictionButAllowsUpdatingExistingMarker() = runBlocking {
        val storage = store()
        repeat(ExtractionProblemReportStore.MAX_REPORTS_PER_ACCOUNT) { storage.save(1, "article-$it", snapshot(), "Note $it") }
        val before = storage.list(1)
        try {
            storage.save(1, "overflow", snapshot(), "No eviction")
            fail("Full report collections must refuse new markers")
        } catch (_: ExtractionReportLimitException) { }
        assertEquals(before.map { it.id }.toSet(), store().list(1).map { it.id }.toSet())
        val existing = storage.find(1, "article-0")!!
        assertEquals(existing.id, storage.save(1, "article-0", snapshot(), "Still editable").id)
        assertNull(storage.find(1, "overflow"))
        assertEquals(ExtractionProblemReportStore.MAX_REPORTS_PER_ACCOUNT, files().size)
    }

    @Test fun concurrentSavesHaveOneStableMarkerAndUpdatesCannotResurrectExplicitRemoval() = runBlocking {
        val storage = store()
        val writes = (0..15).map { index -> async(Dispatchers.Default) { storage.save(1, "shared", snapshot(), "Note $index") } }.awaitAll()
        assertEquals(1, writes.map { it.id }.distinct().size)
        assertEquals(1, storage.list(1).size)
        val id = writes.first().id
        val operations = (0..8).map { index -> async(Dispatchers.Default) { runCatching { storage.updateNotes(1, id, "Update $index") } } }
        val removal = async(Dispatchers.Default) { storage.remove(1, id) }
        operations.awaitAll()
        assertTrue(removal.await())
        assertTrue(store().list(1).isEmpty())
        assertEquals(0, temporary.root.walkTopDown().count { it.isFile && it.extension == "tmp" })
    }

    @Test fun corruptOrOversizedExistingRecordsArePreservedAndCannotBeOverwritten() = runBlocking {
        for (corrupt in listOf("{}", "x".repeat(ExtractionProblemReportStore.MAX_REPORT_BYTES + 1))) {
            val storage = store()
            val report = storage.save(1, "corrupt", snapshot(), "Retain manually marked data")
            val file = files().single()
            file.writeText(corrupt)
            assertTrue(storage.list(1).isEmpty())
            try {
                storage.find(1, "corrupt")
                fail("Unreadable existing record cannot look like an unmarked article")
            } catch (_: IOException) { }
            try {
                storage.save(1, "corrupt", snapshot(), "Must not overwrite")
                fail("Unreadable manually marked records must remain intact")
            } catch (_: IOException) { }
            assertFalse(storage.remove(1, report.id))
            assertEquals(corrupt, file.readText())
            // Test-owned malformed fixture only; production never silently deletes it.
            assertTrue(file.delete())
        }
    }

    @Test fun missingRequiredNumbersOrMetricFieldsCannotBecomeInventedZeros() = runBlocking {
        val storage = store()
        storage.save(1, "article", snapshot().copy(feedMetrics = ExtractionContentMetrics(30, 1, 1)), "Note")
        val file = files().single()
        val valid = file.readText()
        for (key in listOf("appVersionCode", "capturedAtEpochMillis", "androidApi")) {
            val json = JsonParser.parseString(valid).asJsonObject
            json.getAsJsonObject("report").getAsJsonObject("snapshot").remove(key)
            file.writeText(json.toString())
            assertTrue(storage.list(1).isEmpty())
        }
        val json = JsonParser.parseString(valid).asJsonObject
        json.getAsJsonObject("report").getAsJsonObject("snapshot").getAsJsonObject("feedMetrics").remove("images")
        file.writeText(json.toString())
        assertTrue(storage.list(1).isEmpty())
        try {
            storage.save(1, "article", snapshot(), "No overwrite")
            fail("An incomplete metric is not a known zero-image source")
        } catch (_: IOException) { }
        assertEquals(json.toString(), file.readText())
    }

    @Test fun interruptedStagingAndFailedDirectorySyncLeaveAnEntirePreviousRecordReadable() = runBlocking {
        val report = store().save(1, "article", snapshot(), "Existing note")
        val original = files().single()
        File(original.parentFile, ".report-interrupted.tmp").writeText("Uncommitted partial bytes")
        assertEquals(report, store().list(1).single())
        val sync: FeedSourceDirectorySync = mock()
        doAnswer { throw IOException("Synthetic fsync failure") }.whenever(sync).sync(any())
        try {
            store(sync).updateNotes(1, report.id, "Uncommitted replacement")
            fail("Failed durable publication must report an error")
        } catch (_: IOException) { }
        assertEquals(report, store().find(1, "article"))
        assertEquals(1, files().size)
    }

    @Test fun storeSanitizesSnapshotAndNotesBeforePersistence() = runBlocking {
        val saved = store().save(1, "private:article", snapshot(),
            "<b>Photo missing</b> https://private.example.invalid/feed?token=synthetic-secret\nAuthorization: Bearer synthetic-secret")
        val bytes = files().single().readText()
        assertEquals("https://www.newyorker.com/news/public-story", saved.snapshot.publicArticleUrl)
        assertTrue(saved.snapshot.urlRedacted)
        assertFalse(bytes.contains("synthetic-secret"))
        assertFalse(bytes.contains("private.example.invalid"))
        assertFalse(saved.notes.contains("<b>"))
        assertTrue(saved.notes.contains("Photo missing"))
        assertEquals(saved, store().find(1, "private:article"))
    }

    @Test fun accountScopedObservationReflectsSaveEditAndRemoval() = runBlocking {
        val storage = store()
        val emitted = Channel<List<SavedExtractionProblem>>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { storage.observe(1).collect { emitted.send(it) } }
        try {
            assertTrue(withTimeout(3_000) { emitted.receive() }.isEmpty())
            val saved = storage.save(1, "one", snapshot(), "One")
            assertEquals(listOf(saved), withTimeout(3_000) { emitted.receive() })
            storage.save(2, "one", snapshot(), "Other account")
            val changed = storage.updateNotes(1, saved.id, "Changed")
            assertEquals(listOf(changed), withTimeout(3_000) { emitted.receive() })
            storage.remove(1, saved.id)
            assertTrue(withTimeout(3_000) { emitted.receive() }.isEmpty())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test fun malformedUnicodeIdentityIsRejectedWithoutWritingOrHashAliasing() = runBlocking {
        try {
            store().save(1, "bad\ud800identity", snapshot(), "Note")
            fail("Invalid identity encoding must not alias another cache key")
        } catch (_: IllegalArgumentException) { }
        assertTrue(files().isEmpty())
    }
}
