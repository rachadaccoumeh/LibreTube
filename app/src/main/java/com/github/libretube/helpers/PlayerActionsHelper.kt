package com.github.libretube.helpers

import android.content.Context
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys

object PlayerActionsHelper {

    private const val SEPARATOR = ","

    enum class PlayerAction(val id: Int, val titleRes: Int, val iconRes: Int) {
        SHARE(0, R.string.share, R.drawable.ic_share),
        DOWNLOAD(1, R.string.download, R.drawable.ic_download),
        AI(2, R.string.ai, R.drawable.ic_ai),
        SAVE(3, R.string.save, R.drawable.ic_save),
        BACKGROUND(4, R.string.audio, R.drawable.ic_headphones),
        PIP(5, R.string.pip, R.drawable.ic_open),
        SCREENSHOT(6, R.string.screenshot, R.drawable.ic_screenshot);

        companion object {
            fun fromId(id: Int) = entries.firstOrNull { it.id == id }
        }
    }

    data class PlayerActionItem(
        val action: PlayerAction,
        var isVisible: Boolean
    )

    fun getPlayerActions(context: Context): List<PlayerActionItem> {
        val pref = PreferenceHelper.getString(PreferenceKeys.PLAYER_ACTIONS_ITEMS, "")
        if (pref.isBlank()) return getDefaultActions()
        return try {
            val parts = pref.split(SEPARATOR)
            if (parts.size != PlayerAction.entries.size) return getDefaultActions()
            parts.map { part ->
                val isVisible = !part.contains("-")
                val id = part.replace("-", "").toInt()
                PlayerActionItem(PlayerAction.fromId(id) ?: return getDefaultActions(), isVisible)
            }
        } catch (e: Exception) {
            getDefaultActions()
        }
    }

    private fun getDefaultActions(): List<PlayerActionItem> {
        return PlayerAction.entries.map { PlayerActionItem(it, true) }
    }

    fun setPlayerActions(items: List<PlayerActionItem>) {
        val prefString = items.joinToString(SEPARATOR) { item ->
            if (item.isVisible) item.action.id.toString() else "-${item.action.id}"
        }
        PreferenceHelper.putString(PreferenceKeys.PLAYER_ACTIONS_ITEMS, prefString)
    }
}
