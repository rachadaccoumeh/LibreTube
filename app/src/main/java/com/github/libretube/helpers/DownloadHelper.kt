package com.github.libretube.helpers

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.TAG
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.services.DownloadService
import com.github.libretube.ui.dialogs.DownloadDialog
import com.github.libretube.ui.dialogs.DownloadPlaylistDialog
import com.github.libretube.ui.dialogs.ShareDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize

object DownloadHelper {
    const val VIDEO_DIR = "video"
    const val AUDIO_DIR = "audio"
    const val SUBTITLE_DIR = "subtitle"
    const val THUMBNAIL_DIR = "thumbnail"
    const val PLAYLIST_THUMBNAIL_DIR = "playlist_thumbnail"
    const val DOWNLOAD_CHUNK_SIZE = 256L * 1024
    const val DEFAULT_TIMEOUT = 15 * 1000
    const val MAX_CONCURRENT_DOWNLOADS = 6
    private const val VIDEO_MIMETYPE = "video/*"

    fun getDownloadDir(context: Context, path: String): Path {
        val storageDir =
            try {
                context.getExternalFilesDir(null)!!
            } catch (e: Exception) {
                context.filesDir
            }
        return (storageDir.toPath() / path).createDirectories()
    }

    fun startDownloadService(context: Context, downloadData: DownloadData? = null) {
        val intent = Intent(context, DownloadService::class.java)
            .putExtra(IntentData.downloadData, downloadData)

        ContextCompat.startForegroundService(context, intent)
    }

    fun DownloadItem.getNotificationId(): Int {
        return Int.MAX_VALUE - id
    }

    fun startDownloadDialog(context: Context, fragmentManager: FragmentManager, videoId: String) {
        val externalProviderPackageName =
            PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")

        if (externalProviderPackageName.isBlank()) {
            DownloadDialog().apply {
                arguments = bundleOf(IntentData.videoId to videoId)
            }.show(fragmentManager, DownloadDialog::class.java.name)
        } else {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(externalProviderPackageName)
                .setDataAndType(
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=$videoId".toUri(),
                    VIDEO_MIMETYPE
                )

            runCatching { context.startActivity(intent) }
        }
    }

    fun startDownloadPlaylistDialog(
        context: Context,
        fragmentManager: FragmentManager,
        playlistId: String,
        playlistName: String,
        playlistType: PlaylistType
    ) {
        val externalProviderPackageName =
            PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")

        if (externalProviderPackageName.isBlank()) {
            val downloadPlaylistDialog = DownloadPlaylistDialog().apply {
                arguments = bundleOf(
                    IntentData.playlistId to playlistId,
                    IntentData.playlistName to playlistName,
                    IntentData.playlistType to playlistType
                )
            }
            downloadPlaylistDialog.show(fragmentManager, null)
        } else if (playlistType == PlaylistType.PUBLIC) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(externalProviderPackageName)
                .setDataAndType(
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/playlist?list=$playlistId".toUri(),
                    VIDEO_MIMETYPE
                )

            runCatching { context.startActivity(intent) }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                val playlistVideoIds = try {
                    PlaylistsHelper.getPlaylist(playlistId)
                } catch (e: Exception) {
                    context.toastFromMainDispatcher(R.string.unknown_error)
                    return@launch
                }.relatedStreams.mapNotNull { it.url?.toID() }.joinToString(",")

                val intent = Intent(Intent.ACTION_VIEW)
                    .setPackage(externalProviderPackageName)
                    .setDataAndType(
                        "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch_videos?video_ids=${playlistVideoIds}".toUri(),
                        VIDEO_MIMETYPE
                    )

                withContext(Dispatchers.Main) {
                    runCatching { context.startActivity(intent) }
                }
            }
        }
    }

    fun extractDownloadInfoText(context: Context, download: DownloadWithItems): List<String> {
        val downloadInfo = mutableListOf<String>()
        download.downloadItems.firstOrNull { it.type == FileType.VIDEO }?.let { videoItem ->
            downloadInfo.add(context.getString(R.string.video) + ": ${videoItem.format} ${videoItem.quality}")
        }
        download.downloadItems.firstOrNull { it.type == FileType.AUDIO }?.let { audioItem ->
            var infoString = ": ${audioItem.quality} ${audioItem.format})"
            if (audioItem.language != null) infoString += " ${audioItem.language}"
            downloadInfo.add(context.getString(R.string.audio) + infoString)
        }
        download.downloadItems.firstOrNull { it.type == FileType.SUBTITLE }?.let {
            downloadInfo.add(context.getString(R.string.captions) + ": ${it.language}")
        }
        return downloadInfo
    }

    /**
     * Verify a download by fetching stream metadata from the API and comparing
     * the real contentLength with the DB downloadSize. Fixes mismatches by
     * updating downloadSize in the DB. Returns the number of items fixed.
     * If items are fixed and the file is incomplete, they are enqueued for resume.
     */
    suspend fun verifyDownload(context: Context, videoId: String): Int {
        val dao = DatabaseHolder.Database.downloadDao()
        val downloadWithItems = dao.getDownloadById(videoId) ?: return 0
        val items = downloadWithItems.downloadItems.filter { it.type != FileType.SUBTITLE }
        if (items.isEmpty()) return 0

        val streams = try {
            MediaServiceRepository.instance.getStreams(videoId)
        } catch (e: Exception) {
            android.util.Log.e(TAG(), "verifyDownload: failed to fetch streams for $videoId: ${e.message}")
            return -1
        }

        var fixed = 0
        val toEnqueue = mutableListOf<Int>()

        android.util.Log.i(TAG(), "verifyDownload: videoId=$videoId, items to check=${items.size}")

        for (item in items) {
            val streamList = if (item.type == FileType.VIDEO) streams.videoStreams else streams.audioStreams
            val match = streamList.find { it.quality == item.quality && it.format == item.format }
                ?: streamList.find { it.quality == item.quality }

            if (match == null) {
                android.util.Log.i(TAG(), "verifyDownload: ${item.fileName} — NO MATCH in API (type=${item.type}, q=${item.quality}, fmt=${item.format})")
                continue
            }

            val realSize = match.contentLength
            val fileExists = item.path.exists()
            val fileSize = if (fileExists) item.path.fileSize() else 0
            android.util.Log.i(TAG(), "verifyDownload: ${item.fileName} — DB=${item.downloadSize}, API=$realSize, fileOnDisk=$fileSize")

            if (realSize <= 0) {
                android.util.Log.i(TAG(), "verifyDownload: ${item.fileName} — API contentLength=$realSize, skipping")
                continue
            }

            if (realSize != item.downloadSize) {
                val diff = kotlin.math.abs(realSize - item.downloadSize)
                val maxTolerance = maxOf(item.downloadSize / 100, 100_000L) // 1% or 100KB, whichever is larger
                if (diff <= maxTolerance) {
                    android.util.Log.i(TAG(), "verifyDownload: ${item.fileName} — minor API diff=$diff (within tolerance $maxTolerance), skipping")
                } else {
                    android.util.Log.i("DownloadHelper", "verifyDownload: MISMATCH ${item.fileName} — DB=${item.downloadSize}, API=$realSize, fileOnDisk=$fileSize, diff=$diff → updating DB, enqueuing resume")
                    item.downloadSize = realSize
                    dao.updateDownloadItem(item)
                    fixed++
                    toEnqueue.add(item.id)
                }
            } else if (fileExists && fileSize < item.downloadSize) {
                // DB matches API, but file on disk is smaller — file is truncated
                val missing = item.downloadSize - fileSize
                android.util.Log.i("DownloadHelper", "verifyDownload: TRUNCATED ${item.fileName} — DB=${item.downloadSize}, fileOnDisk=$fileSize, missing=$missing → enqueuing resume")
                dao.updateDownloadItem(item)
                fixed++
                toEnqueue.add(item.id)
            } else {
                android.util.Log.i(TAG(), "verifyDownload: ${item.fileName} — OK, sizes match")
            }
        }

        if (toEnqueue.isNotEmpty()) {
            startDownloadService(context)
            // Give the service a moment to start
            kotlinx.coroutines.delay(500)
            toEnqueue.forEach { id ->
                DownloadService.enqueueItem(id)
            }
        }

        return fixed
    }

    suspend fun deleteDownloadIncludingFiles(downloadWithItems: DownloadWithItems) {
        val download = downloadWithItems.download
        val items = downloadWithItems.downloadItems

        items.forEach {
            it.path.deleteIfExists()
        }
        runCatching {
            download.thumbnailPath?.deleteIfExists()
        }

        withContext(Dispatchers.IO) {
            DatabaseHolder.Database.downloadDao().deleteDownload(download)
        }
    }
}
