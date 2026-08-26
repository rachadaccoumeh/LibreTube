package com.github.libretube.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toAndroidUri
import com.github.libretube.ui.interfaces.TimeFrameReceiver
import java.nio.file.Path

class OfflineTimeFrameReceiver(
    private val context: Context,
    private val videoSource: Path
) : TimeFrameReceiver() {
    private val metadataRetriever = try {
        MediaMetadataRetriever().apply {
            setDataSource(context, videoSource.toAndroidUri())
        }
    } catch (e: Exception) {
        Log.e(TAG(), "OfflineTimeFrameReceiver: failed to open ${videoSource.toUri()}: ${e.message}")
        null
    }

    override suspend fun getFrameAtTime(position: Long): Bitmap? {
        return metadataRetriever?.getFrameAtTime(position * 1000)
    }
}
