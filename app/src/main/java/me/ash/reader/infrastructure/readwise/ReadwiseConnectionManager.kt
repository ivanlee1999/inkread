package me.ash.reader.infrastructure.readwise

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import me.ash.reader.domain.annotation.AnnotationOutbox
import me.ash.reader.infrastructure.di.ApplicationScope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Token entry belongs in native remember-only UI; this facade never exposes a stored token. */
@Singleton
class ReadwiseConnectionManager @Inject constructor(
    @ApplicationContext context: Context,
    outbox: AnnotationOutbox,
    @ApplicationScope scope: CoroutineScope,
    workManager: WorkManager,
) {
    private val session = ReadwiseSession(outbox, KeystoreReadwiseCredentialStore(context), ReadwiseApi(),
        WorkManagerReadwiseDrainScheduler(workManager), scope)
    val state = session.state
    suspend fun connect(token: String, exportEnabled: Boolean): ReadwiseConnectResult = session.connect(token, exportEnabled)
    suspend fun setExportEnabled(enabled: Boolean) = session.setExportEnabled(enabled)
    suspend fun disconnect() = session.disconnect()
    fun requestDrain() = session.requestDrain()
    suspend fun retryAnnotations(ids: List<String>) = session.retryAnnotations(ids)
    internal suspend fun drainForWorker() = session.drain()

    companion object {
        /** Cheap startup hint only; the session still verifies ciphertext and consent before sending. */
        fun hasStoredConnection(context: Context): Boolean = KeystoreReadwiseCredentialStore.hasStoredCredential(context)
    }
}

private class WorkManagerReadwiseDrainScheduler(private val workManager: WorkManager) : ReadwiseDrainScheduler {
    override fun enqueue(delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<ReadwiseExportWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            // No token, annotation text, URL, or note is stored in WorkManager Data.
            .build()
        if (delayMillis == 0L) {
            // Appending avoids KEEP's finish/enqueue race dropping a newly saved annotation.
            workManager.enqueueUniqueWork("readwise-export-now", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        } else {
            workManager.enqueueUniqueWork("readwise-export-wake", ExistingWorkPolicy.REPLACE, request)
        }
    }
    override fun cancel() { workManager.cancelAllWorkByTag(TAG) }
    companion object { private const val TAG = "readwise-annotation-export" }
}

@HiltWorker
class ReadwiseExportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manager: ReadwiseConnectionManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            manager.drainForWorker()
            // Provider retry deadlines and frozen request bodies live in Room, not Worker Data.
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Local storage may be temporarily unavailable. Do not abandon its persisted claim.
            Result.retry()
        }
    }
}
