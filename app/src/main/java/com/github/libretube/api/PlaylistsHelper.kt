package com.github.libretube.api

import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.Playlists
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.PipedImportPlaylist
import com.github.libretube.repo.UserDataRepository
import com.github.libretube.repo.UserDataRepositoryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

object PlaylistsHelper {
    const val MAX_CONCURRENT_IMPORT_CALLS = 5

    @Suppress("DEPRECATION")
    private val userDataRepository: UserDataRepository get() = UserDataRepositoryHelper.userDataRepository

    suspend fun getPlaylists(): List<Playlists> = withContext(Dispatchers.IO) {
        val playlists = userDataRepository.getPlaylists()
        sortPlaylists(playlists)
    }

    private fun sortPlaylists(playlists: List<Playlists>): List<Playlists> {
        return when (
            PreferenceHelper.getString(PreferenceKeys.PLAYLISTS_ORDER, "creation_date")
        ) {
            "creation_date" -> playlists
            "creation_date_reversed" -> playlists.reversed()
            "alphabetic" -> playlists.sortedBy { it.name?.lowercase() }
            "alphabetic_reversed" -> playlists.sortedBy { it.name?.lowercase() }
                .reversed()

            else -> playlists
        }
    }

    suspend fun getPlaylist(playlistId: String, playlistType: PlaylistType): Playlist {
        // load locally stored playlists with the auth api
        return when (playlistType) {
            PlaylistType.PUBLIC -> MediaServiceRepository.instance.getPlaylist(playlistId)
            else -> userDataRepository.getPlaylist(playlistId)
        }
    }

    suspend fun getAllPlaylistsWithVideos(playlistIds: List<String>? = null): List<Playlist> {
        return withContext(Dispatchers.IO) {
            (playlistIds ?: getPlaylists().map { it.id!! })
                .map { async { getPlaylist(it, getPlaylistType(it)) } }
                .awaitAll()
        }
    }

    suspend fun createPlaylist(playlistName: String) =
        userDataRepository.createPlaylist(playlistName)

    suspend fun addToPlaylist(playlistId: String, vararg videos: StreamItem) =
        withContext(Dispatchers.IO) {
            userDataRepository.addToPlaylist(playlistId, *videos)
        }

    suspend fun renamePlaylist(playlistId: String, newName: String) =
        userDataRepository.renamePlaylist(playlistId, newName)

    suspend fun changePlaylistDescription(playlistId: String, newDescription: String) =
        userDataRepository.changePlaylistDescription(playlistId, newDescription)

    suspend fun removeFromPlaylist(playlistId: String, videoId: String, index: Int) =
        userDataRepository.removeFromPlaylist(playlistId, videoId, index)

    /**
     * The playlist chosen in the settings for "watch later", or a new one named "Watch later"
     * if none is chosen or the chosen one was deleted.
     */
    suspend fun getWatchLaterPlaylistId(): String {
        val savedId = PreferenceHelper.getString(PreferenceKeys.WATCH_LATER_PLAYLIST_ID, "")
        if (getPlaylists().any { it.id == savedId }) return savedId

        val id = createPlaylist(LibreTubeApp.instance.getString(R.string.watch_later))
            ?: throw IllegalStateException("could not create the watch later playlist")
        PreferenceHelper.putString(PreferenceKeys.WATCH_LATER_PLAYLIST_ID, id)
        return id
    }

    /** @return the index of the video in the watch later playlist, or -1 */
    suspend fun indexInWatchLater(videoId: String): Int {
        val playlistId = getWatchLaterPlaylistId()
        return getPlaylist(playlistId, PlaylistType.PRIVATE).relatedStreams
            .indexOfFirst { it.url?.toID() == videoId }
    }

    /**
     * Adds the video to the watch later playlist, or removes it if it is already there.
     *
     * @return true if the video was added, false if it was removed
     */
    suspend fun toggleWatchLater(video: StreamItem): Boolean {
        val videoId = video.url!!.toID()
        val index = indexInWatchLater(videoId)
        if (index >= 0) {
            removeFromPlaylist(getWatchLaterPlaylistId(), videoId, index)
            return false
        }
        addToPlaylist(getWatchLaterPlaylistId(), video)
        return true
    }

    suspend fun importPlaylists(playlists: List<PipedImportPlaylist>) =
        userDataRepository.importPlaylists(playlists)

    suspend fun clonePlaylist(playlistId: String) = userDataRepository.clonePlaylist(playlistId)
    suspend fun deletePlaylist(playlistId: String) = userDataRepository.deletePlaylist(playlistId)

    // TODO: remove this and pass the type information down instead
    private fun isYouTubePlaylist(playlistId: String) = playlistId.startsWith("PL") && playlistId.length == 34
    fun getPlaylistType(playlistId: String) = if (isYouTubePlaylist(playlistId)) PlaylistType.PUBLIC else PlaylistType.PRIVATE
}
