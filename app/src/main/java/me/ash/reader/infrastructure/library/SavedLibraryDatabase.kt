package me.ash.reader.infrastructure.library

import androidx.room.Database
import androidx.room.RoomDatabase

/** Separate DB: no migration, downgrade, account FK or lifecycle coupling to the RSS Reader DB. */
@Database(entities = [SavedLibraryEntity::class], version = 1, exportSchema = true)
internal abstract class SavedLibraryDatabase : RoomDatabase() {
    abstract fun savedLibraryDao(): SavedLibraryDao
}
