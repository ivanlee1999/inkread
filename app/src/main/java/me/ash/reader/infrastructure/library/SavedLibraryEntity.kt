package me.ash.reader.infrastructure.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.ash.reader.domain.library.SavedContentState
import me.ash.reader.domain.library.SavedImageState
import me.ash.reader.domain.library.SavedItem
import me.ash.reader.domain.library.SavedLocation

/** Metadata only. Bodies and images live under filesDir/saved-library, not in Room or cacheDir. */
@Entity(tableName = "saved_item", indices = [Index(value = ["normalizedUrl"], unique = true), Index(value = ["location", "savedAt"])])
internal data class SavedLibraryEntity(
    @PrimaryKey val id: String,
    val normalizedUrl: String,
    val url: String,
    val title: String,
    val author: String? = null,
    val sourceName: String? = null,
    val excerpt: String? = null,
    val savedAt: Long,
    val location: String = SavedLocation.INBOX.name,
    val isRead: Boolean = false,
    val contentState: String = SavedContentState.QUEUED.name,
    val imageState: String = SavedImageState.PENDING.name,
    val thumbnailPath: String? = null,
    val lastError: String? = null,
    val contentRevision: String? = null,
    val fileRevision: String? = null,
    val readingAnchor: String? = null,
    val downloadToken: String? = null,
) {
    fun asItem() = SavedItem(
        id, url, title, author, sourceName, excerpt, savedAt,
        SavedLocation.valueOf(location), isRead, SavedContentState.valueOf(contentState),
        SavedImageState.valueOf(imageState), thumbnailPath, lastError,
    )
}
