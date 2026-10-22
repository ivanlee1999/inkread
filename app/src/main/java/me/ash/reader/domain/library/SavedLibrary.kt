package me.ash.reader.domain.library

import kotlinx.coroutines.flow.Flow

enum class SavedLocation { INBOX, LATER, ARCHIVE }
enum class SavedContentState { QUEUED, DOWNLOADING, READY, FAILED }
/** Image completeness is separate from readable text. READY covers inline images, not video. */
enum class SavedImageState { NONE, PENDING, READY, PARTIAL, FAILED }

data class SavedItem(
    val id: String,
    val url: String,
    val title: String,
    val author: String?,
    val sourceName: String?,
    val excerpt: String?,
    val savedAt: Long,
    val location: SavedLocation,
    val isRead: Boolean,
    val contentState: SavedContentState,
    val imageState: SavedImageState,
    val thumbnailPath: String?,
    val lastError: String?,
)

data class SavedReadingDocument(
    val item: SavedItem,
    val html: String,
    val contentRevision: String,
    val readingAnchor: String?,
)

/** Device-local library. No account, RSS mutation, provider login, or synchronization. */
interface SavedLibraryRepository {
    fun observeItems(location: SavedLocation? = null): Flow<List<SavedItem>>
    fun observeItem(id: String): Flow<SavedItem?>
    suspend fun saveUrl(url: String, titleHint: String? = null): String
    suspend fun saveRssSnapshot(url: String, title: String, author: String?, sourceName: String?, html: String): String
    suspend fun loadDocument(id: String): SavedReadingDocument?
    suspend fun move(id: String, location: SavedLocation)
    suspend fun setRead(id: String, value: Boolean)
    suspend fun saveReadingAnchor(id: String, contentRevision: String, anchorJson: String)
    suspend fun retry(id: String)
    suspend fun remove(id: String)
}

/** Future providers must declare supported capabilities; there is no provider implementation yet. */
interface SavedLibraryProvider {
    val providerId: String
    val capabilities: Set<SavedProviderCapability>
}

enum class SavedProviderCapability { IMPORT, EXPORT, SYNC_LOCATIONS, SYNC_READ_STATE }
