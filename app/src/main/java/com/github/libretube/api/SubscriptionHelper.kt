package com.github.libretube.api

import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.SubscriptionsFeedItem
import com.github.libretube.repo.FeedProgress
import com.github.libretube.repo.FeedRefresh
import com.github.libretube.repo.FeedRepository
import com.github.libretube.repo.LocalFeedRepository
import com.github.libretube.repo.UserDataRepository
import com.github.libretube.repo.UserDataRepositoryHelper

object SubscriptionHelper {
    @Suppress("DEPRECATION")
    private val userDataRepository: UserDataRepository
        get() = UserDataRepositoryHelper.userDataRepository
    @Suppress("DEPRECATION")
    private val feedRepository: FeedRepository
        get() = UserDataRepositoryHelper.feedRepository

    suspend fun subscribe(
        channelId: String, name: String, uploaderAvatar: String?, verified: Boolean
    ) = userDataRepository.subscribe(channelId, name, uploaderAvatar, verified)

    suspend fun unsubscribe(channelId: String) {
        userDataRepository.unsubscribe(channelId)
        // remove videos from (local) feed
        feedRepository.removeChannel(channelId)
    }
    suspend fun isSubscribed(channelId: String) = userDataRepository.isSubscribed(channelId)
    suspend fun importSubscriptions(newChannels: List<String>) =
        userDataRepository.importSubscriptions(newChannels)

    suspend fun getSubscriptions() =
        userDataRepository.getSubscriptions().sortedBy { it.name.lowercase() }

    suspend fun getSubscriptionChannelIds() = userDataRepository.getSubscriptionChannelIds()
    suspend fun getFeed(refresh: FeedRefresh, onProgressUpdate: (FeedProgress) -> Unit = {}): List<StreamItem> {
        val repository = feedRepository
        val feed = repository.getFeed(refresh, onProgressUpdate)

        // for non-local feeds, track when each video was first fetched (insertedAt),
        // so the UI can show a NEW badge for videos fetched after the user last caught up.
        // LocalFeedRepository already maintains this itself.
        if (repository !is LocalFeedRepository) {
            val trackedItems = feed.filter { it.url != null }.map { it.toFeedItem() }
            DatabaseHolder.Database.feedDao().insertAllIgnoreExisting(trackedItems)
        }

        return feed
    }

    suspend fun submitFeedItemChange(feedItem: SubscriptionsFeedItem) =
        feedRepository.submitFeedItemChange(feedItem)

    suspend fun submitSubscriptionChannelInfosChanged(subscriptions: List<Subscription>) =
        userDataRepository.submitSubscriptionChannelInfosChanged(subscriptions)
}
