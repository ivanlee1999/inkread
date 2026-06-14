package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.rss.ReaderCacheHelper

@HiltWorker
class ReaderWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rssService: RssService,
    private val cacheHelper: ReaderCacheHelper,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val rssService = rssService.get()
            val articleList = rssService.queryUnreadFullContentArticles(MAX_PREFETCH_ARTICLES)

            articleList
                .chunked(MAX_CONCURRENT_PREFETCHES)
                .forEach { batch ->
                    batch
                        .map { article ->
                            async {
                                cacheHelper.checkOrFetchFullContent(article)
                            }
                        }
                        .awaitAll()
                }

            Result.success()
        }
    }

    companion object {
        private const val MAX_CONCURRENT_PREFETCHES = 2
        private const val MAX_PREFETCH_ARTICLES = 50
    }
}
