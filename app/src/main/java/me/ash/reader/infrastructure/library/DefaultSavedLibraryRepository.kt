package me.ash.reader.infrastructure.library

import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.ash.reader.domain.library.SavedContentState
import me.ash.reader.domain.library.SavedImageState
import me.ash.reader.domain.library.SavedItem
import me.ash.reader.domain.library.SavedLibraryRepository
import me.ash.reader.domain.library.SavedLocation
import me.ash.reader.domain.library.SavedReadingDocument
import me.ash.reader.infrastructure.rss.publicArticleUrl
import org.jsoup.Jsoup

internal interface SavedDownloadScheduler {
    fun enqueue(id: String, token: String, replace: Boolean = false)
    fun cancel(id: String)
}

/** A single device library. The lock serializes DB/file commits, never HTTP downloads. */
internal class DefaultSavedLibraryRepository(
    private val dao: SavedLibraryDao,
    private val files: SavedLibraryFiles,
    private val downloader: SavedArticleDownloader,
    private val scheduler: SavedDownloadScheduler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : SavedLibraryRepository {
    private val mutations = Mutex()
    private val downloads = Semaphore(2)
    private var recovered = false

    override fun observeItems(location: SavedLocation?): Flow<List<SavedItem>> =
        dao.observeItems(location?.name).map { rows -> rows.map { it.asItem() } }.distinctUntilChanged().onStart { recoverPending() }
    override fun observeItem(id: String): Flow<SavedItem?> =
        dao.observeItem(id).map { it?.asItem() }.distinctUntilChanged().onStart { recoverPending() }

    private suspend fun recoverPending() = withContext(ioDispatcher) { mutations.withLock { recoverLocked() } }
    private suspend fun recoverLocked() {
        if (recovered) return
        val rows = dao.all() // Metadata only; observing the library never loads article bodies.
        files.recoverDirectories(rows.map { it.id }.toSet())
        rows.filter { it.downloadToken != null }.forEach { scheduleLocked(it) }
        recovered = true
    }
    private suspend fun scheduleLocked(item: SavedLibraryEntity, replace: Boolean = false) {
        val token = item.downloadToken ?: return
        try { scheduler.enqueue(item.id, token, replace) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            dao.update(item.copy(
                contentState = if (item.contentRevision != null) SavedContentState.READY.name else SavedContentState.FAILED.name,
                imageState = if (item.contentRevision != null) SavedImageState.FAILED.name else item.imageState,
                downloadToken = null, lastError = "Download could not be queued. Retry to save offline.",
            ))
        }
    }

    override suspend fun saveUrl(url: String, titleHint: String?): String = withContext(ioDispatcher) {
        val normalized = normalizedSavedUrl(url)
        require(publicArticleUrl(normalized) != null) { "Use a public HTTP or HTTPS article URL" }
        mutations.withLock {
            recoverLocked()
            dao.findUrl(normalized)?.let { return@withLock it.id }
            val item = SavedLibraryEntity(
                id = newId(), normalizedUrl = normalized, url = normalized,
                title = titleHint?.trim()?.takeIf { it.isNotBlank() }?.take(512) ?: URI(normalized).host,
                sourceName = URI(normalized).host, savedAt = now(), downloadToken = newId(),
            )
            if (dao.insert(item) == -1L) return@withLock requireNotNull(dao.findUrl(normalized)).id
            scheduleLocked(item)
            item.id
        }
    }

    override suspend fun saveRssSnapshot(url: String, title: String, author: String?, sourceName: String?, html: String): String {
        if (html.isBlank()) return saveUrl(url, title)
        return withContext(ioDispatcher) {
            val normalized = normalizedSavedUrl(url)
            require(html.toByteArray(Charsets.UTF_8).size.toLong() <= files.limits.maxHtmlBytes) { "Article text exceeds the saved-content limit" }
            mutations.withLock {
                recoverLocked()
                dao.findUrl(normalized)?.let { return@withLock it.id }
                val id = newId()
                val prepared = SavedDocumentImages.rewrite(html, normalized, emptyMap())
                val hasImages = SavedDocumentImages.sources(prepared, normalized).isNotEmpty()
                val revision = files.commit(id) { prepared }
                val item = SavedLibraryEntity(
                    id = id, normalizedUrl = normalized, url = normalized,
                    title = title.trim().ifBlank { URI(normalized).host }.take(512),
                    author = author?.take(256), sourceName = sourceName?.take(256) ?: URI(normalized).host,
                    excerpt = Jsoup.parseBodyFragment(html).text().take(280), savedAt = now(),
                    contentRevision = revision.revision, fileRevision = revision.revision, contentState = SavedContentState.READY.name,
                    imageState = if (hasImages) SavedImageState.PENDING.name else SavedImageState.NONE.name,
                    downloadToken = if (hasImages) newId() else null,
                )
                try {
                    if (dao.insert(item) == -1L) {
                        files.remove(id)
                        return@withLock requireNotNull(dao.findUrl(normalized)).id
                    }
                } catch (error: Exception) { files.remove(id); throw error }
                scheduleLocked(item)
                id
            }
        }
    }

    override suspend fun loadDocument(id: String): SavedReadingDocument? = withContext(ioDispatcher) {
        mutations.withLock {
            val item = dao.get(id) ?: return@withLock null
            val revision = item.fileRevision ?: return@withLock null
            val html = runCatching { files.read(id, revision) }.getOrNull()
            if (html == null) {
                dao.update(item.copy(contentRevision = null, fileRevision = null, thumbnailPath = null, readingAnchor = null,
                    downloadToken = null, contentState = SavedContentState.FAILED.name, imageState = SavedImageState.PENDING.name,
                    lastError = "Saved text is missing. Retry to download this article again."))
                try { scheduler.cancel(id) } catch (_: Exception) { /* Token is already invalidated. */ }
                return@withLock null
            }
            var readable = item
            if (item.imageState == SavedImageState.READY.name) {
                val sources = SavedDocumentImages.sources(html, item.url)
                val retained = SavedDocumentImages.retained(html, item.url) { files.isRetainedAsset(id, it) }
                if (retained.size < sources.size) {
                    readable = item.copy(imageState = imageState(sources.size, retained.size).name,
                        thumbnailPath = retained.values.firstOrNull(), lastError = "Some saved images are missing. Text is still available; retry to restore images.")
                    dao.update(readable)
                }
            }
            SavedReadingDocument(readable.asItem(), html, requireNotNull(readable.contentRevision), readable.readingAnchor)
        }
    }
    override suspend fun move(id: String, location: SavedLocation) = withContext(ioDispatcher) {
        mutations.withLock { dao.move(id, location.name) }
    }
    override suspend fun setRead(id: String, value: Boolean) = withContext(ioDispatcher) {
        mutations.withLock { dao.setRead(id, value) }
    }
    override suspend fun saveReadingAnchor(id: String, contentRevision: String, anchorJson: String) = withContext(ioDispatcher) {
        require(anchorJson.length <= 16 * 1024) { "Reading position is too large" }
        mutations.withLock { dao.saveAnchor(id, contentRevision, anchorJson) }
    }
    override suspend fun retry(id: String) = withContext(ioDispatcher) {
        mutations.withLock {
            recoverLocked()
            val current = dao.get(id) ?: return@withLock
            // A successful complete download needs no new request or revision.
            if (current.contentState == SavedContentState.READY.name &&
                current.imageState in setOf(SavedImageState.NONE.name, SavedImageState.READY.name)) return@withLock
            val next = current.copy(downloadToken = newId(), lastError = null,
                contentState = if (current.contentRevision == null) SavedContentState.QUEUED.name else SavedContentState.READY.name,
                imageState = SavedImageState.PENDING.name)
            dao.update(next)
            scheduleLocked(next, replace = true)
        }
    }
    override suspend fun remove(id: String) = withContext(ioDispatcher) {
        mutations.withLock {
            if (dao.get(id) == null) return@withLock
            // Invalidate first. A late worker can only update an existing matching token, never insert.
            dao.delete(id)
            try { scheduler.cancel(id) } catch (_: Exception) { /* Token is already invalidated. */ }
            files.remove(id)
        }
    }

    /** WorkManager entry. Each request and image set is bounded; no account or RSS services involved. */
    internal suspend fun download(id: String, token: String) = withContext(ioDispatcher) {
        downloads.withPermit {
            try {
                val initial = mutations.withLock {
                    recoverLocked()
                    val item = matching(id, token) ?: return@withLock null
                    dao.update(item.copy(
                        contentState = if (item.contentRevision == null) SavedContentState.DOWNLOADING.name else SavedContentState.READY.name,
                        imageState = SavedImageState.PENDING.name, lastError = null,
                    ))
                    item
                } ?: return@withPermit
                var html = initial.fileRevision?.let { mutations.withLock { runCatching { files.read(id, it) }.getOrNull() } }
                if (html == null) {
                    val stillCurrent = mutations.withLock {
                        val current = matching(id, token) ?: return@withLock false
                        if (current.contentRevision != null) {
                            dao.update(current.copy(contentRevision = null, fileRevision = null, readingAnchor = null,
                                thumbnailPath = null, contentState = SavedContentState.DOWNLOADING.name))
                        }
                        true
                    }
                    if (!stillCurrent) return@withPermit
                    val extracted = downloader.extract(initial.url, initial.title)
                    html = mutations.withLock {
                        val current = matching(id, token) ?: return@withLock null
                        val prepared = SavedDocumentImages.rewrite(extracted.html, current.url, emptyMap())
                        val revision = files.commit(id) { prepared }
                        try {
                            val updated = current.copy(title = extracted.title.take(512), author = extracted.author?.take(256),
                                sourceName = extracted.sourceName?.take(256), excerpt = extracted.excerpt?.take(280),
                                contentRevision = revision.revision, fileRevision = revision.revision, readingAnchor = null,
                                contentState = SavedContentState.READY.name, imageState = SavedImageState.PENDING.name,
                                thumbnailPath = null, lastError = null)
                            if (dao.update(updated) == 0) { files.discard(id, revision.revision); return@withLock null }
                        } catch (error: Exception) { files.discard(id, revision.revision); throw error }
                        files.pruneOldHtml(id, revision.revision)
                        prepared
                    }
                }
                val sourceHtml = html ?: return@withPermit
                val sources = SavedDocumentImages.sources(sourceHtml, initial.url)
                val retained = mutations.withLock {
                    val current = matching(id, token) ?: return@withLock null
                    val result = SavedDocumentImages.retained(sourceHtml, initial.url) { files.isRetainedAsset(id, it) }
                    current.fileRevision?.let { files.reconcileItem(id, it, result.values.toSet()) }
                    result
                } ?: return@withPermit
                val fetched = linkedMapOf<String, ByteArray>()
                var used = retained.values.sumOf { File(it).length() }
                for (url in sources.take(files.limits.maxImagesPerArticle)) {
                    currentCoroutineContext().ensureActive()
                    if (mutations.withLock { matching(id, token) == null }) return@withPermit
                    if (url in retained || publicArticleUrl(url) == null || used >= files.limits.maxArticleImageBytes) continue
                    try {
                        val bytes = downloader.fetchImage(url)
                        if (bytes.isNotEmpty() && bytes.size.toLong() <= files.limits.maxImageBytes && used + bytes.size <= files.limits.maxArticleImageBytes) {
                            fetched[url] = bytes
                            used += bytes.size
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Durable text survives missing images. */ }
                }
                mutations.withLock {
                    val current = matching(id, token) ?: return@withLock
                    var paths = retained
                    var revision = current.fileRevision
                    var storageError: String? = null
                    if (fetched.isNotEmpty()) {
                        // Reserve room for the rewritten HTML before accepting any new images.
                        val estimatedPaths = fetched.keys.associateWith { files.estimatedAssetPath(id) }
                        val estimatedHtmlBytes = SavedDocumentImages.rewrite(sourceHtml, current.url, retained + estimatedPaths).toByteArray(Charsets.UTF_8).size
                        var available = (files.remainingBytes() - estimatedHtmlBytes).coerceAtLeast(0)
                        val fitting = fetched.filterValues { bytes ->
                            (bytes.size <= available).also { fits -> if (fits) available -= bytes.size }
                        }
                        if (fitting.size < fetched.size) storageError = "Text is saved. The saved-library storage limit prevented keeping every image."
                        try {
                            if (fitting.isEmpty()) throw SavedStorageFullException()
                            val next = files.commit(id, fitting) { newPaths -> SavedDocumentImages.rewrite(sourceHtml, current.url, retained + newPaths) }
                            paths = retained + next.assetPaths
                            revision = next.revision
                        } catch (error: Exception) {
                            storageError = if (error is SavedStorageFullException) "Text is saved, but there was not enough storage for these images."
                                else "Text is saved, but image files could not be written. Retry to try again."
                        }
                    }
                    val state = imageState(sources.size, paths.size)
                    val next = current.copy(fileRevision = revision, contentState = SavedContentState.READY.name,
                        imageState = state.name, thumbnailPath = paths.values.firstOrNull(), downloadToken = null,
                        lastError = storageError ?: if (state == SavedImageState.PARTIAL || state == SavedImageState.FAILED)
                            "Text is saved. ${paths.size} of ${sources.size} images available offline." else null)
                    try {
                        if (dao.update(next) == 0 && revision != current.fileRevision) revision?.let { files.discard(id, it) }
                    } catch (error: Exception) {
                        if (revision != current.fileRevision) revision?.let { files.discard(id, it) }
                        throw error
                    }
                    revision?.let { files.pruneOldHtml(id, it) }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    mutations.withLock {
                        matching(id, token)?.let { item -> dao.update(item.copy(
                            contentState = if (item.contentRevision == null) SavedContentState.QUEUED.name else SavedContentState.READY.name,
                            imageState = SavedImageState.PENDING.name,
                        )) }
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                mutations.withLock {
                    matching(id, token)?.let { item -> dao.update(item.copy(
                        contentState = if (item.contentRevision == null) SavedContentState.FAILED.name else SavedContentState.READY.name,
                        imageState = if (item.contentRevision == null) SavedImageState.PENDING.name else SavedImageState.FAILED.name,
                        downloadToken = null,
                        lastError = if (error is SavedStorageFullException) error.message else
                            if (item.contentRevision == null) "Article text could not be saved. Retry to download it again."
                            else "Text is saved, but images could not be kept offline. Retry to try again.",
                    )) }
                }
            }
        }
    }
    private suspend fun matching(id: String, token: String) = dao.get(id)?.takeIf { it.downloadToken == token }
    private fun newId() = UUID.randomUUID().toString()
}

internal fun imageState(requested: Int, retained: Int): SavedImageState = when {
    requested == 0 -> SavedImageState.NONE
    retained >= requested -> SavedImageState.READY
    retained == 0 -> SavedImageState.FAILED
    else -> SavedImageState.PARTIAL
}
