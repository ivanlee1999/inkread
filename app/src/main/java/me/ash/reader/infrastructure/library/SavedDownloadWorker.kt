package me.ash.reader.infrastructure.library

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

internal class WorkManagerSavedDownloadScheduler(private val workManager: WorkManager) : SavedDownloadScheduler {
    override fun enqueue(id: String, token: String, replace: Boolean) {
        // The generation is part of the unique name. Recovery must not let an older running
        // generation's KEEP policy suppress a newer token committed before a process death.
        if (replace) workManager.cancelAllWorkByTag(itemTag(id))
        val request = OneTimeWorkRequestBuilder<SavedDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SAVED_ID to id, SAVED_TOKEN to token))
            .addTag("saved-library-download")
            .addTag(itemTag(id))
            .build()
        workManager.enqueueUniqueWork("saved-library-$id-$token", ExistingWorkPolicy.KEEP, request)
    }
    override fun cancel(id: String) { workManager.cancelAllWorkByTag(itemTag(id)) }
    private fun itemTag(id: String) = "saved-library-item-$id"
}

@HiltWorker
internal class SavedDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: DefaultSavedLibraryRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(SAVED_ID) ?: return Result.failure()
        val token = inputData.getString(SAVED_TOKEN) ?: return Result.failure()
        repository.download(id, token)
        // Failures are durable and user-retryable, not an unbounded background retry loop.
        return Result.success()
    }
}

private const val SAVED_ID = "saved-item-id"
private const val SAVED_TOKEN = "saved-download-token"
