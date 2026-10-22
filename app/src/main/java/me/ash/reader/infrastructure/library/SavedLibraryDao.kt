package me.ash.reader.infrastructure.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SavedLibraryDao {
    @Query("SELECT * FROM saved_item WHERE (:location IS NULL OR location = :location) ORDER BY savedAt DESC, id DESC")
    fun observeItems(location: String?): Flow<List<SavedLibraryEntity>>
    @Query("SELECT * FROM saved_item WHERE id = :id")
    fun observeItem(id: String): Flow<SavedLibraryEntity?>
    @Query("SELECT * FROM saved_item WHERE id = :id")
    suspend fun get(id: String): SavedLibraryEntity?
    @Query("SELECT * FROM saved_item WHERE normalizedUrl = :url LIMIT 1")
    suspend fun findUrl(url: String): SavedLibraryEntity?
    @Query("SELECT * FROM saved_item")
    suspend fun all(): List<SavedLibraryEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: SavedLibraryEntity): Long
    /** Update-only intentionally cannot resurrect a removed row. Repository also compares tokens. */
    @Update
    suspend fun update(item: SavedLibraryEntity): Int
    @Query("UPDATE saved_item SET location = :location WHERE id = :id")
    suspend fun move(id: String, location: String)
    @Query("UPDATE saved_item SET isRead = :value WHERE id = :id")
    suspend fun setRead(id: String, value: Boolean)
    @Query("UPDATE saved_item SET readingAnchor = :anchor WHERE id = :id AND contentRevision = :revision")
    suspend fun saveAnchor(id: String, revision: String, anchor: String)
    @Query("DELETE FROM saved_item WHERE id = :id")
    suspend fun delete(id: String)
}
