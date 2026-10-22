package me.ash.reader.infrastructure.annotation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AnnotationDao {
    @Query("SELECT * FROM annotation WHERE deletedAt IS NULL ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<AnnotationEntity>>
    @Query("SELECT * FROM annotation WHERE documentId = :documentId AND deletedAt IS NULL ORDER BY createdAt, id")
    fun observeDocument(documentId: String): Flow<List<AnnotationEntity>>
    @Query("SELECT annotationId, epoch, revision, state, lastError FROM annotation_outbox WHERE state NOT IN ('SENT', 'CANCELED')")
    fun observeOutboxStatus(): Flow<List<AnnotationOutboxStatus>>
    @Query("SELECT COUNT(*) FROM annotation_outbox o INNER JOIN annotation_connection c ON c.singleton = 1 AND c.epoch = o.epoch WHERE c.exportEnabled = 1 AND c.connectionId IS NOT NULL AND o.state IN ('PENDING', 'IN_FLIGHT')")
    fun observePendingCount(): Flow<Int>
    @Query("SELECT * FROM annotation_connection WHERE singleton = 1")
    fun observeConnection(): Flow<AnnotationConnectionEntity?>
    @Query("SELECT * FROM annotation_connection WHERE singleton = 1")
    suspend fun connection(): AnnotationConnectionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putConnection(connection: AnnotationConnectionEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocument(document: AnnotationDocumentEntity): Long
    @Insert
    suspend fun insert(annotation: AnnotationEntity)
    @Update
    suspend fun update(annotation: AnnotationEntity): Int
    @Query("SELECT * FROM annotation WHERE id = :id")
    suspend fun get(id: String): AnnotationEntity?
    @Query("SELECT * FROM annotation WHERE exportEpoch = :epoch AND remoteId = :remoteId AND id != :excludeId LIMIT 1")
    suspend fun findRemoteBinding(epoch: Long, remoteId: String, excludeId: String): AnnotationEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutbox(outbox: AnnotationOutboxEntity): Long
    @Update
    suspend fun updateOutbox(outbox: AnnotationOutboxEntity): Int
    @Query("SELECT * FROM annotation_outbox WHERE id = :id")
    suspend fun outbox(id: String): AnnotationOutboxEntity?
    @Query("SELECT * FROM annotation_outbox WHERE annotationId = :annotationId AND epoch = :epoch ORDER BY revision, createdAt, id")
    suspend fun outboxFor(annotationId: String, epoch: Long): List<AnnotationOutboxEntity>
    @Query("SELECT * FROM annotation_outbox WHERE epoch = :epoch AND state IN ('PENDING', 'IN_FLIGHT') ORDER BY createdAt, revision, id")
    suspend fun runnable(epoch: Long): List<AnnotationOutboxEntity>
    @Query("UPDATE annotation_outbox SET state = 'PAUSED', leaseToken = NULL, leaseUntil = NULL WHERE state IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun pauseOldConnections()
    @Query("UPDATE annotation_outbox SET state = CASE WHEN state = 'SENT' THEN 'SENT' ELSE 'CANCELED' END, payloadJson = '{}', leaseToken = NULL, leaseUntil = NULL, lastError = NULL WHERE annotationId = :annotationId")
    suspend fun cancelOutbox(annotationId: String)
}
