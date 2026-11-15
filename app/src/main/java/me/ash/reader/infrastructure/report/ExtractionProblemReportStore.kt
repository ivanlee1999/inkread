package me.ash.reader.infrastructure.report

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.FeedSourceDirectorySync

/**
 * Explicitly marked reports, separate from disposable extraction caches and article snapshots.
 * Local identities never belong in an export; use ExtractionProblemFormatter for sharing.
 */
@Singleton
class ExtractionProblemReportStore @Inject constructor(
    @ApplicationContext context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val directorySync: FeedSourceDirectorySync,
) {
    private val filesDirectory = context.filesDir
    private val root = filesDirectory.resolve("reader-extraction-problems-v1")
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    private val gson = Gson()

    suspend fun list(accountId: Int): List<SavedExtractionProblem> = withContext(ioDispatcher) {
        mutex.withLock {
            readReports(accountId).map { it.second }
                .sortedWith(compareByDescending<SavedExtractionProblem> { it.updatedAtEpochMillis }.thenBy { it.id })
        }
    }

    suspend fun find(accountId: Int, articleId: String): SavedExtractionProblem? = withContext(ioDispatcher) {
        mutex.withLock {
            validateIdentity(articleId)
            val file = reportFile(accountId, articleId)
            if (!file.exists()) null else readReport(file, accountId)
        }
    }

    /** One stable marker per account/article. A full collection refuses new markers, never evicts. */
    suspend fun save(
        accountId: Int,
        articleId: String,
        snapshot: ExtractionProblemSnapshot,
        notes: String,
    ): SavedExtractionProblem = mutate {
        validateIdentity(articleId)
        val file = reportFile(accountId, articleId)
        val existing = if (file.exists()) readReport(file, accountId) else null
        val directory = accountDirectory(accountId)
        if (existing == null && reportFiles(directory).size >= MAX_REPORTS_PER_ACCOUNT) {
            throw ExtractionReportLimitException()
        }
        val now = System.currentTimeMillis()
        val report = SavedExtractionProblem(
            id = existing?.id ?: UUID.randomUUID().toString(),
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = maxOf(now, existing?.updatedAtEpochMillis ?: now),
            snapshot = ExtractionProblemPrivacy.sanitizeSnapshot(snapshot),
            notes = ExtractionProblemPrivacy.sanitizeNotes(notes),
            accountId = accountId,
            articleId = articleId,
        )
        writeReport(file, report)
        report
    }

    suspend fun updateNotes(accountId: Int, reportId: String, notes: String): SavedExtractionProblem = mutate {
        val (file, existing) = findByReportId(accountId, reportId) ?: throw FileNotFoundException("Saved extraction report is unavailable")
        val report = existing.copy(
            updatedAtEpochMillis = maxOf(System.currentTimeMillis(), existing.updatedAtEpochMillis),
            notes = ExtractionProblemPrivacy.sanitizeNotes(notes),
            snapshot = ExtractionProblemPrivacy.sanitizeSnapshot(existing.snapshot),
        )
        writeReport(file, report)
        report
    }

    suspend fun remove(accountId: Int, reportId: String): Boolean = mutate {
        val (file, _) = findByReportId(accountId, reportId) ?: return@mutate false
        currentCoroutineContext().ensureActive()
        if (!file.delete()) throw IOException("Could not remove saved extraction report")
        syncParents(file.parentFile!!)
        true
    }

    /** The app uses this singleton; account switching can flatMapLatest this account-scoped flow. */
    fun observe(accountId: Int): Flow<List<SavedExtractionProblem>> =
        revision.map { list(accountId) }.distinctUntilChanged()

    private suspend fun <T> mutate(action: suspend () -> T): T = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                action()
            } finally {
                // A post-rename fsync failure can leave a complete new record. Observers must
                // rescan even when the caller receives an error, rather than showing stale data.
                revision.update { it + 1 }
            }
        }
    }

    private fun accountDirectory(accountId: Int): File = root.resolve(hash("account:$accountId"))
    private fun reportFile(accountId: Int, articleId: String): File = accountDirectory(accountId).resolve(hash("article:$articleId") + ".json")

    private suspend fun readReports(accountId: Int): List<Pair<File, SavedExtractionProblem>> {
        val result = ArrayList<Pair<File, SavedExtractionProblem>>()
        for (file in reportFiles(accountDirectory(accountId))) {
            currentCoroutineContext().ensureActive()
            try {
                result += file to readReport(file, accountId)
            } catch (failure: IOException) {
                // Keep corrupt/unknown records on disk. Their names still count against quota;
                // no automatic cleanup may silently remove a manually marked report.
            }
        }
        return result
    }

    private suspend fun findByReportId(accountId: Int, reportId: String): Pair<File, SavedExtractionProblem>? {
        if (!isReportId(reportId)) return null
        return readReports(accountId).firstOrNull { it.second.id == reportId }
    }

    private fun reportFiles(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        verifyDirectory(directory)
        val result = ArrayList<File>()
        var entries = 0
        Files.newDirectoryStream(directory.toPath()).use { paths ->
            for (path in paths) {
                if (++entries > MAX_DIRECTORY_ENTRIES) throw IOException("Saved extraction report directory exceeds its limit")
                val file = path.toFile()
                if (file.name.endsWith(".json")) result += file
            }
        }
        if (result.size > MAX_REPORTS_PER_ACCOUNT) throw IOException("Saved extraction report directory exceeds its limit")
        return result
    }

    private fun readReport(file: File, expectedAccountId: Int): SavedExtractionProblem {
        try {
            verifyDirectory(file.parentFile!!)
            if (file.canonicalFile.parentFile != file.parentFile!!.canonicalFile || !file.isFile ||
                file.length() !in 1..MAX_REPORT_BYTES.toLong()) throw IOException("Invalid saved extraction report")
            // Read at most one excess byte even if a file changes after its length check.
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(MAX_REPORT_BYTES + 1)
                var count = 0
                while (count < buffer.size) {
                    val n = input.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                if (count > MAX_REPORT_BYTES) throw IOException("Saved extraction report exceeds its limit")
                buffer.copyOf(count)
            }
            val text = String(bytes, Charsets.UTF_8)
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) throw IOException("Invalid saved extraction report encoding")
            validateStoredShape(JsonParser.parseString(text))
            val stored = gson.fromJson(text, StoredReport::class.java) ?: throw IOException("Invalid saved extraction report")
            val report = stored.report
            if (stored.storageVersion != STORAGE_VERSION || report.accountId != expectedAccountId ||
                !isReportId(report.id) || report.createdAtEpochMillis <= 0 || report.updatedAtEpochMillis < report.createdAtEpochMillis ||
                report.snapshot.schemaVersion != 1 || reportFile(expectedAccountId, report.articleId).name != file.name) {
                throw IOException("Saved extraction report identity could not be verified")
            }
            validateIdentity(report.articleId)
            return report.copy(
                snapshot = ExtractionProblemPrivacy.sanitizeSnapshot(report.snapshot),
                notes = ExtractionProblemPrivacy.sanitizeNotes(report.notes),
            )
        } catch (failure: IOException) {
            throw failure
        } catch (failure: StackOverflowError) {
            throw IOException("Saved extraction report nesting exceeds its limit")
        } catch (failure: Exception) {
            throw IOException("Saved extraction report could not be read", failure)
        }
    }

    /** Gson may default an absent primitive to zero. Presence checks preserve unknown vs known. */
    private fun validateStoredShape(value: JsonElement) {
        fun objectValue(element: JsonElement?): JsonObject {
            if (element == null || !element.isJsonObject) throw IOException("Invalid saved extraction report structure")
            return element.asJsonObject
        }
        fun number(obj: JsonObject, key: String, int: Boolean = false) {
            val raw = obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
            if (raw == null || (if (int) raw.toIntOrNull() == null else raw.toLongOrNull() == null)) {
                throw IOException("Invalid saved extraction report number")
            }
        }
        fun string(obj: JsonObject, key: String) {
            val element = obj.get(key)
            if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                throw IOException("Invalid saved extraction report string")
            }
        }
        fun boolean(obj: JsonObject, key: String) {
            val element = obj.get(key)
            if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
                throw IOException("Invalid saved extraction report boolean")
            }
        }
        fun optionalNumber(obj: JsonObject, key: String, int: Boolean = false) {
            obj.get(key)?.takeUnless { it.isJsonNull }?.let { number(obj, key, int) }
        }
        fun optionalString(obj: JsonObject, key: String) {
            obj.get(key)?.takeUnless { it.isJsonNull }?.let { string(obj, key) }
        }
        fun metrics(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            val obj = objectValue(element)
            listOf("textCharacters", "paragraphs", "images").forEach { number(obj, it, true) }
        }
        fun comparison(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            val obj = objectValue(element)
            string(obj, "source"); string(obj, "reason")
            listOf("before", "candidate", "selected").forEach { metrics(obj.get(it)) }
        }
        val stored = objectValue(value)
        number(stored, "storageVersion", true)
        val report = objectValue(stored.get("report"))
        listOf("id", "articleId", "notes").forEach { string(report, it) }
        number(report, "accountId", true)
        listOf("createdAtEpochMillis", "updatedAtEpochMillis").forEach { number(report, it) }
        val snapshot = objectValue(report.get("snapshot"))
        number(snapshot, "schemaVersion", true); number(snapshot, "capturedAtEpochMillis")
        number(snapshot, "appVersionCode", true); number(snapshot, "androidApi", true)
        listOf("appVersion", "deviceModel", "requestedSourceMode", "displayedSourceMode").forEach { string(snapshot, it) }
        boolean(snapshot, "urlRedacted"); boolean(snapshot, "presentationUpdatePending")
        optionalString(snapshot, "sourceRevision"); optionalString(snapshot, "publicArticleUrl")
        val omissions = snapshot.get("privacyOmissions")
        if (omissions == null || !omissions.isJsonArray || omissions.asJsonArray.any {
                !it.isJsonPrimitive || !it.asJsonPrimitive.isString ||
                    it.asString !in ExtractionPrivacyOmission.entries.map { omission -> omission.name }
            }) throw IOException("Invalid saved extraction report privacy metadata")
        listOf("feedMetrics", "displayedMetrics").forEach { metrics(snapshot.get(it)) }
        snapshot.get("lastLocalAttempt")?.takeUnless { it.isJsonNull }?.let { element ->
            val attempt = objectValue(element)
            number(attempt, "startedAtEpochMillis")
            listOf("origin", "cacheState", "outcome", "failureStage").forEach { string(attempt, it) }
            boolean(attempt, "requestedRefresh")
            listOf("elapsedMillis", "responseBytesRead", "fetchElapsedMillis", "parserElapsedMillis").forEach { optionalNumber(attempt, it) }
            optionalNumber(attempt, "httpStatus", true)
            attempt.get("responseBytesComplete")?.takeUnless { it.isJsonNull }?.let { boolean(attempt, "responseBytesComplete") }
            listOf("previousCache", "fetchedCandidate").forEach { metrics(attempt.get(it)) }
            listOf("cacheComparison", "readerComparison").forEach { comparison(attempt.get(it)) }
        }
    }

    private suspend fun writeReport(file: File, report: SavedExtractionProblem) {
        val text = gson.toJson(StoredReport(STORAGE_VERSION, report))
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_REPORT_BYTES || String(bytes, Charsets.UTF_8) != text) {
            throw IOException("Saved extraction report exceeds its limit")
        }
        val directory = file.parentFile!!
        if (!(directory.isDirectory || directory.mkdirs())) throw IOException("Could not create saved extraction report storage")
        verifyDirectory(directory)
        // Ensure newly-created parent directories exist durably before record replacement.
        syncParents(directory)
        val temporary = File.createTempFile(".report-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            currentCoroutineContext().ensureActive()
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncParents(directory)
        } finally {
            temporary.delete()
        }
    }

    private fun verifyDirectory(directory: File) {
        if (!directory.isDirectory || root.canonicalFile.parentFile != filesDirectory.canonicalFile ||
            directory.canonicalFile.parentFile != root.canonicalFile) {
            throw IOException("Saved extraction report storage could not be verified")
        }
    }

    private fun syncParents(directory: File) {
        generateSequence(directory) { if (it == filesDirectory) null else it.parentFile }.forEach(directorySync::sync)
    }

    private fun validateIdentity(articleId: String) {
        require(articleId.isNotBlank() && articleId.length <= MAX_IDENTITY_CHARACTERS &&
            String(articleId.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == articleId) { "Invalid saved extraction report identity" }
    }

    private fun isReportId(value: String?): Boolean = value != null && UUID_PATTERN.matches(value)
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Keep
    private data class StoredReport(val storageVersion: Int, val report: SavedExtractionProblem)

    companion object {
        const val MAX_REPORTS_PER_ACCOUNT = 200
        const val MAX_REPORT_BYTES = 32 * 1024
        const val MAX_NOTES_CHARACTERS = MAX_EXTRACTION_REPORT_NOTES_CHARS
        private const val STORAGE_VERSION = 1
        private const val MAX_IDENTITY_CHARACTERS = 4_096
        private const val MAX_DIRECTORY_ENTRIES = MAX_REPORTS_PER_ACCOUNT + 32
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

class ExtractionReportLimitException : IOException("Saved extraction reports are full. Remove a report before saving another.")
