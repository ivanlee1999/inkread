package me.ash.reader.infrastructure.annotation

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/** Independent v1: intentionally no migration or lifecycle coupling to Reader or SavedLibrary. */
@Database(entities = [AnnotationDocumentEntity::class, AnnotationEntity::class,
    AnnotationOutboxEntity::class, AnnotationConnectionEntity::class], version = 1, exportSchema = true)
internal abstract class AnnotationsDatabase : RoomDatabase() {
    abstract fun annotations(): AnnotationDao
}

internal interface AnnotationTransactions {
    suspend fun <T> run(block: suspend () -> T): T
}

internal class RoomAnnotationTransactions(private val database: AnnotationsDatabase) : AnnotationTransactions {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
