package com.github.libretube.ui.models

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.AiHelper.ChatMessage

class CommonPlayerViewModel : ViewModel() {
    val isMiniPlayerVisible = MutableLiveData(false)
    val isFullscreen = MutableLiveData(false)
    var maxSheetHeightPx = 0

    val sheetExpand = MutableLiveData<Boolean?>()

    fun setSheetExpand(state: Boolean?) {
        sheetExpand.updateIfChanged(state)
    }

    // AI chat state - persists across bottom sheet open/close cycles
    var aiVideoId: String? = null
    var aiMessages: MutableList<ChatMessage> = mutableListOf()
    var aiMessageViews: MutableList<Boolean> = mutableListOf() // true = user, false = assistant
    var aiTranscriptText: String? = null
    var aiTranscriptLoaded: Boolean = false

    fun clearAiState() {
        aiVideoId = null
        aiMessages.clear()
        aiMessageViews.clear()
        aiTranscriptText = null
        aiTranscriptLoaded = false
    }
}
