package com.github.libretube.ui.interfaces

interface CustomPlayerCallback {
    fun toggleFullscreen(portrait: Boolean = false)
    fun getVideoId(): String
    fun isVideoShort(): Boolean
    fun isVideoLive(): Boolean
}
