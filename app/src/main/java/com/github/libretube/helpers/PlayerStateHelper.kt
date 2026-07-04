package com.github.libretube.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.libretube.ui.fragments.DownloadTab

object PlayerStateHelper {
    private const val PREF_NAME = "player_state"
    private const val KEY_VIDEO_ID = "video_id"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_IS_OFFLINE = "is_offline"
    private const val KEY_IS_AUDIO_ONLY = "is_audio_only"
    private const val KEY_DOWNLOAD_TAB = "download_tab"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun saveState(
        context: Context,
        videoId: String,
        positionMs: Long,
        isOffline: Boolean,
        isAudioOnly: Boolean,
        downloadTab: DownloadTab? = null
    ) {
        getPrefs(context).edit {
            putString(KEY_VIDEO_ID, videoId)
            putLong(KEY_POSITION_MS, positionMs)
            putBoolean(KEY_IS_OFFLINE, isOffline)
            putBoolean(KEY_IS_AUDIO_ONLY, isAudioOnly)
            putString(KEY_DOWNLOAD_TAB, downloadTab?.name)
        }
    }

    fun savePosition(context: Context, positionMs: Long) {
        getPrefs(context).edit {
            putLong(KEY_POSITION_MS, positionMs)
        }
    }

    fun clearState(context: Context) {
        getPrefs(context).edit { clear() }
    }

    fun getSavedState(context: Context): SavedPlayerState? {
        val prefs = getPrefs(context)
        val videoId = prefs.getString(KEY_VIDEO_ID, null) ?: return null
        val positionMs = prefs.getLong(KEY_POSITION_MS, 0)
        val isOffline = prefs.getBoolean(KEY_IS_OFFLINE, false)
        val isAudioOnly = prefs.getBoolean(KEY_IS_AUDIO_ONLY, false)
        val downloadTabName = prefs.getString(KEY_DOWNLOAD_TAB, null)
        val downloadTab = downloadTabName?.let { runCatching { DownloadTab.valueOf(it) }.getOrNull() }
        return SavedPlayerState(videoId, positionMs, isOffline, isAudioOnly, downloadTab)
    }
}

data class SavedPlayerState(
    val videoId: String,
    val positionMs: Long,
    val isOffline: Boolean,
    val isAudioOnly: Boolean,
    val downloadTab: DownloadTab? = null
)
