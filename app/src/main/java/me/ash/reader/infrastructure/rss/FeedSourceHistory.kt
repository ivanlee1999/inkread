package me.ash.reader.infrastructure.rss

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.di.IODispatcher

/** Exact server/feed bodies, separate from disposable publisher extraction and browser imports. */
@Singleton
class FeedSourceHistory @Inject constructor(
    @ApplicationContext context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val directorySync: FeedSourceDirectorySync,
) {
    private val root = context.filesDir.resolve("reader-feed-sources")
    private val locks = Array(16) { Mutex() }

    private fun directory(articleId: String, accountId: Int) =
        root.resolve(accountId.toString()).resolve(readerCacheFileName(articleId).removeSuffix(".html"))

    /** Must complete before a database body is changed. Storage failure leaves the row unchanged. */
    suspend fun archive(articleId: String, accountId: Int, html: String) = withContext(ioDispatcher) {
        val key = "$accountId:$articleId"
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val bytes = boundedFeedBodyBytes(html)
            val directory = directory(articleId, accountId)
            val archive = directory.resolve(readerCacheFileName(html))
            if (archive.isFile) {
                if (archive.length() > MAX_FEED_BODY_BYTES || !archive.readBytes().contentEquals(bytes)) {
                    throw IOException("Earlier feed source could not be verified")
                }
                syncParents(directory)
                return@withLock
            }
            check((directory.listFiles()?.count { it.isFile && it.extension == "html" } ?: 0) < 5) {
                "Five earlier feed sources are retained. The current feed source is unchanged."
            }
            check(directory.isDirectory || directory.mkdirs()) { "Could not retain earlier feed source" }
            val temporary = File.createTempFile("feed-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                check(temporary.renameTo(archive)) { "Could not commit earlier feed source" }
                // Publishing the name must also reach storage before the database can change.
                // Sync every containing directory because this may be its first saved source.
                syncParents(directory)
            } finally {
                temporary.delete()
            }
        }
    }

    private fun syncParents(directory: File) {
        generateSequence(directory) { if (it == root.parentFile) null else it.parentFile }
            .forEach(directorySync::sync)
    }

    /** Explicit Earlier feed choices cycle through retained exact bodies, newest first. */
    suspend fun readEarlier(articleId: String, accountId: Int, currentBody: String? = null): Result<String> =
        withContext(ioDispatcher) {
            readerCacheResult {
                val earlier = directory(articleId, accountId).listFiles()
                    ?.filter { it.isFile && it.extension == "html" }
                    ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name }).orEmpty()
                if (earlier.isEmpty()) throw FileNotFoundException()
                val index = currentBody?.let { body -> earlier.indexOfFirst { it.name == readerCacheFileName(body) } } ?: -1
                val file = earlier[(index + 1) % earlier.size]
                if (file.length() > MAX_FEED_BODY_BYTES) throw IOException("Earlier feed source exceeds limit")
                file.readText().also {
                    check(readerCacheFileName(it) == file.name) { "Earlier feed source could not be verified" }
                }
            }
        }
}

internal const val MAX_FEED_BODY_BYTES = 2L * 1024L * 1024L

internal fun boundedFeedBodyBytes(html: String): ByteArray {
    if (html.length > MAX_FEED_BODY_BYTES) throw IOException("Feed source exceeds limit")
    val bytes = html.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_FEED_BODY_BYTES) throw IOException("Feed source exceeds limit")
    if (String(bytes, Charsets.UTF_8) != html) throw IOException("Feed source contains invalid text")
    return bytes
}
