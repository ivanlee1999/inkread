package me.ash.reader.infrastructure.rss

import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.isValidItemId
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.ofFeedStreamIdToId
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.remoteId
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.shortId
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderDTO
import me.ash.reader.ui.ext.spacerDollar
import org.jsoup.Jsoup

enum class FeedArticleRefreshStatus { UPDATED, UNCHANGED, KEPT_CURRENT, CONCURRENT_CHANGE }

data class FeedArticleRefreshResult(val article: Article, val status: FeedArticleRefreshStatus)

/** Explicit one-item server refresh. It never fetches a publisher or modifies reader state/tags. */
class FeedArticleBodyRefresh @Inject constructor(
    private val articleDao: ArticleDao,
    private val history: FeedSourceHistory,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun refresh(
        article: Article,
        fetch: suspend (remoteId: String) -> GoogleReaderDTO.ItemsContents,
    ): FeedArticleRefreshResult = withContext(ioDispatcher) {
        val original = articleDao.queryByAccountAndId(article.accountId, article.id)
            ?: throw IOException("This feed article is no longer available")
        val remoteId = original.id.remoteId
        val normalizedRemoteId = runCatching { remoteId.takeIf { it.isValidItemId() }?.shortId }.getOrNull()
        if (normalizedRemoteId == null || original.id != original.accountId.spacerDollar(remoteId)) {
            throw IOException("This feed article has no valid server ID")
        }
        val response = fetch(remoteId)
        currentCoroutineContext().ensureActive()
        val item = response.items?.singleOrNull()
            ?: throw IOException("The server did not return the requested feed article")
        val returnedId = runCatching { item.id?.takeIf { it.isValidItemId() }?.shortId }.getOrNull()
        if (returnedId != normalizedRemoteId || item.origin?.streamId?.ofFeedStreamIdToId()
                ?.let { original.accountId.spacerDollar(it) } != original.feedId) {
            throw IOException("The server returned a different feed article")
        }
        val fetched = item.summary?.content ?: throw IOException("The server returned no feed body")
        boundedFeedBodyBytes(fetched)
        if (fetched == original.rawDescription) {
            return@withContext outcome(original, FeedArticleRefreshStatus.UNCHANGED)
        }
        // Compare inert copies only; keep the exact authenticated server bytes as this source.
        // Missing endings/images, truncated bodies and inconclusive oversized DOMs stay unchanged.
        if (ArticleContentSelection.select(original.rawDescription, fetched).source != ArticleContentSelection.Source.EXTRACTED) {
            return@withContext outcome(original, FeedArticleRefreshStatus.KEPT_CURRENT)
        }
        val document = Jsoup.parseBodyFragment(fetched, original.link)
        document.select("script,style,noscript,template,[hidden],[aria-hidden=true]").remove()
        val description = document.text().take(280)
        val image = findHtmlThumbnail(fetched, original.link) ?: original.img
        history.archive(original.id, original.accountId, original.rawDescription)
        currentCoroutineContext().ensureActive()
        val changed = articleDao.replaceFeedBodyIfUnchanged(
            original.accountId, original.id, original.feedId, original.link, original.rawDescription,
            fetched, description, image,
        )
        outcome(original, if (changed == 1) FeedArticleRefreshStatus.UPDATED
            else FeedArticleRefreshStatus.CONCURRENT_CHANGE, if (changed == 1) fetched else original.rawDescription)
    }

    private suspend fun outcome(original: Article, status: FeedArticleRefreshStatus,
        expectedBody: String = original.rawDescription): FeedArticleRefreshResult {
        val current = articleDao.queryByAccountAndId(original.accountId, original.id)
            ?: throw IOException("This feed article is no longer available")
        val conflict = current.rawDescription != expectedBody || current.link != original.link || current.feedId != original.feedId
        return FeedArticleRefreshResult(current, if (conflict) FeedArticleRefreshStatus.CONCURRENT_CHANGE else status)
    }
}
