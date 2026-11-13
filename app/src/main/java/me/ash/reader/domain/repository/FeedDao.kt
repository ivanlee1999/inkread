package me.ash.reader.domain.repository

import androidx.room.*
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.feed.Feed
import timber.log.Timber

@Dao
interface FeedDao {

    // Remote operations may finish after local preferences change. Update only their own field.
    @Query("UPDATE feed SET name = :name WHERE accountId = :accountId AND id = :id")
    suspend fun updateName(accountId: Int, id: String, name: String)

    @Query("UPDATE feed SET groupId = :groupId WHERE accountId = :accountId AND id = :id")
    suspend fun updateGroup(accountId: Int, id: String, groupId: String)

    @Query("UPDATE feed SET url = :url WHERE accountId = :accountId AND id = :id")
    suspend fun updateUrl(accountId: Int, id: String, url: String)

    @Query("SELECT url FROM feed WHERE accountId = :accountId AND id = :id AND url = :expectedUrl AND isNewsletter = 0")
    suspend fun queryIconSource(accountId: Int, id: String, expectedUrl: String): String?

    /** A delayed favicon result must never replace preferences captured before the request. */
    @Query("UPDATE feed SET icon = :icon WHERE accountId = :accountId AND id = :id AND url = :expectedUrl AND isNewsletter = 0")
    suspend fun updateIconIfOrdinary(accountId: Int, id: String, expectedUrl: String, icon: String): Int

    @Query("UPDATE feed SET isFullContent = :isFullContent, isBrowser = :isBrowser, displayMode = NULL WHERE accountId = :accountId AND id = :id AND isNewsletter = 0")
    suspend fun updateReaderPreferencesIfOrdinary(accountId: Int, id: String, isFullContent: Boolean, isBrowser: Boolean)

    @Query("UPDATE feed SET displayMode = :mode, isFullContent = :local, isBrowser = 0 WHERE accountId = :accountId AND id = :id AND isNewsletter = 0")
    suspend fun updateDisplayModeIfOrdinary(accountId: Int, id: String, mode: String, local: Boolean): Int

    @Query("UPDATE feed SET isNotification = :enabled WHERE accountId = :accountId AND id = :id")
    suspend fun updateNotification(accountId: Int, id: String, enabled: Boolean)

    @Query("UPDATE feed SET isNewsletter = :enabled, isFullContent = 0, isBrowser = 0, displayMode = NULL, icon = CASE WHEN :enabled THEN NULL ELSE icon END WHERE accountId = :accountId AND id = :id")
    suspend fun updateNewsletter(accountId: Int, id: String, enabled: Boolean)

    @Query(
        """
        UPDATE feed SET groupId = :targetGroupId
        WHERE groupId = :groupId
        AND accountId = :accountId
        """
    )
    suspend fun updateTargetGroupIdByGroupId(accountId: Int, groupId: String, targetGroupId: String)

    @Query(
        """
        UPDATE feed SET isFullContent = :isFullContent, displayMode = NULL
        WHERE accountId = :accountId
        AND groupId = :groupId
        AND isNewsletter = 0
        """
    )
    suspend fun updateIsFullContentByGroupIdInternal(
        accountId: Int,
        groupId: String,
        isFullContent: Boolean,
    )

    @Transaction
    suspend fun updateIsFullContentByGroupId(
        accountId: Int,
        groupId: String,
        isFullContent: Boolean,
    ) {
        updateIsFullContentByGroupIdInternal(accountId, groupId, isFullContent)
        if (isFullContent) {
            updateIsBrowserByGroupIdInternal(
                accountId = accountId,
                groupId = groupId,
                isBrowser = false,
            )
        }
    }

    @Query(
        """
        UPDATE feed SET isBrowser = :isBrowser, displayMode = NULL
        WHERE accountId = :accountId
        AND groupId = :groupId
        AND isNewsletter = 0
        """
    )
    suspend fun updateIsBrowserByGroupIdInternal(
        accountId: Int,
        groupId: String,
        isBrowser: Boolean,
    )

    @Transaction
    suspend fun updateIsBrowserByGroupId(accountId: Int, groupId: String, isBrowser: Boolean) {
        updateIsBrowserByGroupIdInternal(accountId, groupId, isBrowser)
        if (isBrowser) {
            updateIsFullContentByGroupIdInternal(
                accountId = accountId,
                groupId = groupId,
                isFullContent = false,
            )
        }
    }

    @Query(
        """
        UPDATE feed SET isNotification = :isNotification
        WHERE accountId = :accountId
        AND groupId = :groupId
        """
    )
    suspend fun updateIsNotificationByGroupId(
        accountId: Int,
        groupId: String,
        isNotification: Boolean,
    )

    @Query(
        """
        SELECT * FROM feed
        WHERE groupId = :groupId
        AND accountId = :accountId
        """
    )
    suspend fun queryByGroupId(accountId: Int, groupId: String): List<Feed>

    @Query(
        """
        DELETE FROM feed
        WHERE groupId = :groupId
        AND accountId = :accountId
        """
    )
    suspend fun deleteByGroupId(accountId: Int, groupId: String)

    @Query(
        """
        DELETE FROM feed
        WHERE accountId = :accountId
        """
    )
    suspend fun deleteByAccountId(accountId: Int)

    @Query(
        """
        SELECT * FROM feed
        WHERE id = :id
        """
    )
    suspend fun queryById(id: String): Feed?

    @Query(
        """
        SELECT * FROM feed
        WHERE id in (:idList)
        """
    )
    suspend fun queryByIds(idList: List<String>): List<Feed>

    @Query(
        """
        SELECT * FROM feed
        WHERE accountId = :accountId
        """
    )
    suspend fun queryAll(accountId: Int): List<Feed>

    @Query(
        """
        SELECT * FROM feed
        WHERE accountId = :accountId
        AND (icon IS NUll OR icon = '')
        AND isNewsletter = 0
        """
    )
    suspend fun queryNoIcon(accountId: Int): List<Feed>

    @Query(
        """
        SELECT * FROM feed
        WHERE accountId = :accountId
        AND isNotification = 1
        """
    )
    suspend fun queryNotificationEnabled(accountId: Int): List<Feed>

    @Query(
        """
        SELECT * FROM feed
        WHERE accountId = :accountId
        AND url = :url
        """
    )
    suspend fun queryByLink(accountId: Int, url: String): List<Feed>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(vararg feed: Feed)

    @Insert suspend fun insertList(feeds: List<Feed>): List<Long>

    @Update suspend fun update(vararg feed: Feed)

    @Delete suspend fun delete(vararg feed: Feed)

    @Insert suspend fun insertAll(feeds: List<Feed>)

    @Update suspend fun updateAll(feeds: List<Feed>)

    @Transaction
    suspend fun insertOrUpdate(feeds: List<Feed>) {
        val localFeeds = queryByIds(feeds.map { it.id }).associateBy { it.id }
        val (newFeeds, feedsToUpdate) = feeds.partition { !localFeeds.contains(it.id) }

        if (newFeeds.isNotEmpty()) insertAll(newFeeds)

        feedsToUpdate
            .mapNotNull { new ->
                val existing = localFeeds[new.id] ?: return@mapNotNull null
                val updated =
                    new.copy(
                        icon = if (existing.isNewsletter || new.icon.isNullOrEmpty()) existing.icon else new.icon,
                        isNotification = existing.isNotification,
                        isFullContent = existing.isFullContent,
                        isBrowser = existing.isBrowser,
                        isNewsletter = existing.isNewsletter,
                        displayMode = existing.displayMode,
                    )
                if (updated == existing) {
                    null
                } else {
                    Timber.d("Update ${new.name}")
                    updated
                }
            }
            .let { updateAll(it) }
    }

    @Insert suspend fun insertArchivedArticles(links: List<ArchivedArticle>)

    @Query(
        """
        SELECT * FROM archived_article
        WHERE feedId = :feedId
        """
    )
    suspend fun queryArchivedArticles(feedId: String): List<ArchivedArticle>
}
