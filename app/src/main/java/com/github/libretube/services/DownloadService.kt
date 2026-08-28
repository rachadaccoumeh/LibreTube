package com.github.libretube.services

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent.FLAG_CANCEL_CURRENT
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.SparseBooleanArray
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.core.util.contains
import androidx.core.util.keyIterator
import androidx.core.util.set
import androidx.core.util.valueIterator
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.libretube.LibreTubeApp.Companion.DOWNLOAD_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.enums.NotificationId
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.extensions.toLocalDate
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.DownloadHelper.getNotificationId
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NetworkHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.obj.DownloadStatus
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.receivers.NotificationReceiver
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_PAUSE
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_RESUME
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_STOP
import com.github.libretube.repo.DownloadProgressResult
import com.github.libretube.repo.DownloadProvider
import com.github.libretube.repo.RawByteStreamDownloadProvider
import com.github.libretube.repo.SabrDownloadProvider
import com.github.libretube.ui.activities.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import okio.sink
import java.net.HttpURLConnection
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize

/**
 * Download service with custom implementation of downloading using [HttpURLConnection].
 */
class DownloadService : LifecycleService() {
    private val binder = LocalBinder()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val coroutineContext = dispatcher + SupervisorJob()

    private lateinit var notificationManager: NotificationManager
    private lateinit var summaryNotificationBuilder: Builder

    /**
     * Maps all currently running downloads to `true`, and all paused or stopped downloads to `false`.
     * Must only be mutated while holding [queueLock] to prevent races between threads.
     */
    private val downloadQueue = SparseBooleanArray()
    private val queueLock = Any()
    private val _downloadFlow = MutableSharedFlow<Pair<Int, DownloadStatus>>()
    val downloadFlow: SharedFlow<Pair<Int, DownloadStatus>> = _downloadFlow

    /**
     * Downloads paused by the user (not by metered network). Prevents auto-resume.
     */
    private val pausedByUser = mutableSetOf<Int>()

    /**
     * Downloads that failed and should not auto-retry.
     */
    private val failedDownloads = mutableSetOf<Int>()

    /**
     * Cache that contains all the already-loaded video info.
     */
    private val cachedStreamsInfo: MutableMap<String, Streams> = mutableMapOf()

    companion object {
        private const val DOWNLOAD_NOTIFICATION_GROUP = "download_notification_group"
        const val ACTION_SERVICE_STARTED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STOPPED"
        const val ACTION_RESUME_ALL =
            "com.github.libretube.services.DownloadService.ACTION_RESUME_ALL"
        const val ACTION_PAUSE_ALL =
            "com.github.libretube.services.DownloadService.ACTION_PAUSE_ALL"

        private const val MAX_SEGMENT_RETRIES = 10
        var IS_DOWNLOAD_RUNNING = false

        @Volatile
        private var instance: DownloadService? = null

        fun isItemIdInQueue(id: Int): Boolean {
            val svc = instance ?: return false
            var found = false
            for (key in svc.downloadQueue.keyIterator()) {
                if (key == id) {
                    found = true
                    break
                }
            }
            return found && !svc.pausedByUser.contains(id)
        }

        fun enqueueItem(id: Int) {
            val svc = instance ?: return
            svc.pausedByUser.remove(id)
            svc.failedDownloads.remove(id)
            if (!svc.downloadQueue[id]) {
                svc.resume(id)
            }
        }

        fun dequeueItem(id: Int) {
            val svc = instance ?: return
            svc.pause(id, byUser = true)
        }

        /**
         * Whether the given item is actively downloading right now (globally, without binder).
         */
        fun isDownloadActive(id: Int): Boolean {
            return instance?.downloadQueue?.get(id) == true
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        IS_DOWNLOAD_RUNNING = true
        instance = this
        notifyForeground()
        registerNetworkChangedCallback()
        sendBroadcast(Intent(ACTION_SERVICE_STARTED))
    }

    /**
     * Listen for network changes and pause the download if the network connection becomes metered.
     * Only registered once to avoid duplicate callbacks.
     */
    fun registerNetworkChangedCallback() {
        if (networkCallback != null) return
        val connectivityManager = getSystemService<ConnectivityManager>()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)

                // pause all downloads when switching to a metered connection
                if (NetworkHelper.isNetworkMetered(this@DownloadService)) {
                    for (download in downloadQueue.keyIterator()) {
                        pause(download, byUser = false)
                    }
                }
            }
        }
        connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val downloadId = intent?.getIntExtra("id", -1)
        when (intent?.action) {
            ACTION_DOWNLOAD_RESUME -> {
                resume(downloadId!!)
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD_PAUSE -> {
                pause(downloadId!!)
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD_STOP -> {
                stop(downloadId!!)
                return START_NOT_STICKY
            }
            ACTION_RESUME_ALL -> {
                resumeAll()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_ALL -> {
                pauseAll()
                return START_NOT_STICKY
            }
        }

        val downloadData = intent?.parcelableExtra<DownloadData>(IntentData.downloadData)
            ?: return START_NOT_STICKY
        val videoId = downloadData.videoId

        lifecycleScope.launch(coroutineContext) {
            val streams = loadStreamsInfo(videoId) ?: return@launch

            storeVideoMetadata(videoId, streams)

            val downloadItems = streams.toDownloadItems(downloadData)
            for (downloadItem in downloadItems) {
                start(downloadItem)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun loadStreamsInfo(videoId: String): Streams? {
        if (cachedStreamsInfo.contains(videoId))
            return cachedStreamsInfo[videoId]

        val streams = try {
            withContext(Dispatchers.IO) {
                MediaServiceRepository.instance.getStreams(videoId)
            }
        } catch (e: Exception) {
            Log.w(TAG(), e.stackTraceToString())
            toastFromMainDispatcher(e.localizedMessage.orEmpty())
            return null
        }

        cachedStreamsInfo[videoId] = streams
        return streams
    }

    private suspend fun storeVideoMetadata(videoId: String, streams: Streams) {
        val thumbnailTargetPath = getDownloadPath(DownloadHelper.THUMBNAIL_DIR, videoId)

        val download = Download(
            videoId,
            streams.title,
            streams.description,
            streams.uploader,
            streams.duration,
            streams.uploadTimestamp?.toLocalDate(),
            thumbnailTargetPath,
            streams.uploaderUrl,
            streams.views,
            streams.likes,
            streams.dislikes,
        )
        Database.downloadDao().insertDownload(download)

        for (chapter in streams.chapters) {
            val downloadChapter = DownloadChapter(
                videoId = videoId,
                name = chapter.title,
                start = chapter.start,
                thumbnailUrl = chapter.image
            )
            Database.downloadDao().insertDownloadChapter(downloadChapter)
        }

        // asynchronously load the remaining metadata
        // this allows the main thread to already start the actual download items (i.e. video/audio)
        // while the thumbnail and SponsorBlock segments are loaded in the background
        coroutineScope {
            launch(Dispatchers.IO) {
                downloadExtraVideoMetadata(videoId, streams.thumbnailUrl, thumbnailTargetPath)
            }
        }
    }

    /**
     * Download the thumbnail and SponsorBlock segments for the given [videoId].
     */
    private suspend fun downloadExtraVideoMetadata(
        videoId: String,
        thumbnailUrl: String,
        thumbnailTargetPath: Path
    ) {
        coroutineScope {
            launch {
                val segmentData = try {
                    val categories = PlayerHelper.getSponsorBlockCategories()
                    MediaServiceRepository.instance.getSegments(videoId, categories.map { it.key })
                } catch (e: Exception) {
                    Log.w(TAG(), "failed to download SponsorBlock segments for $videoId")
                    Log.w(TAG(), e.stackTraceToString())
                    return@launch
                }

                Database.downloadDao().insertSponsorBlockSegments(
                    segmentData.segments.map { it.toDownloadSegment(videoId) }
                )
            }

            launch {
                try {
                    ImageHelper.downloadImage(
                        this@DownloadService,
                        ProxyHelper.rewriteUrlUsingProxyPreference(thumbnailUrl),
                        thumbnailTargetPath
                    )
                } catch (e: Exception) {
                    Log.w(TAG(), "failed to download image $thumbnailUrl")
                    Log.w(TAG(), e.stackTraceToString())
                }
            }
        }
    }

    /**
     * Download file and emit [DownloadStatus] to the collectors of [downloadFlow]
     * and notification.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun selectFormatAndDownloadFile(item: DownloadItem) {
        // Prevent double download — if already downloading this item, skip
        if (downloadQueue[item.id] == true) {
            Log.w(TAG(), "selectFormatAndDownloadFile: already downloading ${item.fileName} (id=${item.id}), skipping to prevent double download")
            return
        }
        // Don't start if the user paused this item (e.g. stale/delayed resume call)
        if (pausedByUser.contains(item.id)) {
            Log.w(TAG(), "selectFormatAndDownloadFile: ${item.fileName} (id=${item.id}) was paused by user, not starting")
            return
        }
        Log.w(TAG(), "selectFormatAndDownloadFile: starting for ${item.fileName} (type=${item.type}, id=${item.id})")
        val streams = loadStreamsInfo(item.videoId) ?: run {
            Log.w(TAG(), "selectFormatAndDownloadFile: failed to load streams for ${item.videoId}, trying regenerateLink")
            // Try regenerating link if we have a stale item
            val regenerated = regenerateLink(item)
            if (regenerated != null) {
                // Re-fetch streams info with the regenerated link
                loadStreamsInfo(item.videoId)
            } else null
        } ?: run {
            Log.w(TAG(), "selectFormatAndDownloadFile: failed to load streams AND regenerate link for ${item.fileName}")
            return
        }
        if (item.type == FileType.SUBTITLE) {
            // subtitles are always plain files and don't use SABR
            val subtitle = streams.subtitles.firstOrNull { it.code == item.language } ?: run {
                Log.w(TAG(), "selectFormatAndDownloadFile: subtitle not found for ${item.fileName}")
                return
            }
            Log.w(TAG(), "selectFormatAndDownloadFile: using RawByteStream for subtitle ${item.fileName}")
            downloadFile(item, RawByteStreamDownloadProvider(subtitle.url!!.toHttpUrl()))
        } else {
            val selectedStream = selectMatchingStream(streams, item) ?: run {
                Log.w(TAG(), "selectFormatAndDownloadFile: no matching stream found for ${item.fileName} (quality=${item.quality}, format=${item.format})")
                return
            }
            if (selectedStream.url?.startsWith("http") == true) {
                Log.w(TAG(), "selectFormatAndDownloadFile: using RawByteStream for ${item.fileName} (url starts with http)")
                downloadFile(item, RawByteStreamDownloadProvider(selectedStream.url!!.toHttpUrl()))
            } else {
                Log.w(TAG(), "selectFormatAndDownloadFile: using SABR for ${item.fileName} (url=${selectedStream.url})")
                val sabrDownloader = SabrDownloadProvider(item, streams, selectedStream)
                downloadFile(item, sabrDownloader)
            }
        }
    }

    /**
     * Atomically claim the download slot for [id].
     * @return false if this item is already being downloaded by another coroutine.
     */
    private fun tryClaimDownload(id: Int): Boolean = synchronized(queueLock) {
        if (downloadQueue[id] || pausedByUser.contains(id)) {
            false
        } else {
            downloadQueue.put(id, true)
            true
        }
    }

    /**
     * Starts and progresses until the download is canceled or finished.
     *
     * You should probably not call this directly, call [selectFormatAndDownloadFile].
     */
    private suspend fun downloadFile(
        item: DownloadItem,
        downloadProvider: DownloadProvider,
    ) {
        // Atomic guard against double downloads: only one coroutine may claim an item.
        if (!tryClaimDownload(item.id)) {
            Log.w(TAG(), "downloadFile: already downloading ${item.fileName} (id=${item.id}), skipping duplicate")
            return
        }
        Log.w(TAG(), "downloadFile: starting for ${item.fileName} (id=${item.id}, size=${item.downloadSize})")
        val notificationBuilder = getNotificationBuilder(item)
        setResumeNotification(notificationBuilder, item)

        val sink = item.path.sink(StandardOpenOption.APPEND).buffer()
        var totalRead = item.path.fileSize()
        var numberOfTries = 0
        while (downloadQueue[item.id] && !item.isFinished) {
            try {
                when (val result = downloadProvider.downloadNextChunk(item, sink) { downloadQueue[item.id] == true }) {
                    DownloadProgressResult.DownloadComplete -> {
                        Log.w(TAG(), "downloadFile: DownloadComplete for ${item.fileName}")
                        setPauseNotification(notificationBuilder, item, true)
                        _downloadFlow.emit(item.id to DownloadStatus.Completed)
                        downloadQueue[item.id] = false
                        break
                    }
                    DownloadProgressResult.Failed -> {
                        if (pausedByUser.contains(item.id)) {
                            Log.w(TAG(), "downloadFile: Failed because paused by user for ${item.fileName}")
                            break
                        }
                        Log.w(TAG(), "downloadFile: Failed result for ${item.fileName}, tries=$numberOfTries/$MAX_SEGMENT_RETRIES")
                        if (numberOfTries < MAX_SEGMENT_RETRIES) {
                            // try to download segment again after a short delay
                            delay(1000)
                            numberOfTries++
                        } else {
                            Log.w(TAG(), "downloadFile: max retries reached for ${item.fileName}, pausing")
                            setPauseNotification(notificationBuilder, item, false)
                            pause(item.id)
                            break
                        }
                    }
                    is DownloadProgressResult.Progressed -> {
                        numberOfTries = 0
                        totalRead += result.bytes
                        Log.w(TAG(), "downloadFile: Progressed for ${item.fileName}, +${result.bytes} bytes, total=$totalRead/${item.downloadSize}")
                        _downloadFlow.emit(
                            item.id to DownloadStatus.Progress(
                                result.bytes,
                                totalRead,
                                item.downloadSize
                            )
                        )
                        updateNotification(notificationBuilder, item, totalRead.toInt())
                    }
                }
            } catch (_: CancellationException) {
                Log.w(TAG(), "downloadFile: cancelled for ${item.fileName}")
                break
            } catch (e: Exception) {
                Log.w(TAG(), "downloadFile: exception for ${item.fileName}: ${e.message}")
                toastFromMainThread("${getString(R.string.download)}: ${e.message}")
                Log.w(this@DownloadService::class.java.name, e.stackTraceToString())
                _downloadFlow.emit(item.id to DownloadStatus.Error(e.message.toString(), e))
                break
            }
        }

        // NonCancellable: the service may already be stopping (scope cancelled),
        // but we must always flush and close the sink to not lose downloaded data
        withContext(Dispatchers.IO + NonCancellable) {
            sink.flush()
            sink.close()
        }

        // if the download was paused by the user (not completed), update the notification
        if (!item.isFinished && pausedByUser.contains(item.id)) {
            Log.w(TAG(), "downloadFile: was paused by user for ${item.fileName}, updating notification")
            setPauseNotification(notificationBuilder, item, false)
        }

        // start the next download if there are any remaining ones enqueued
        startNextEnqueueDownload()

        // explicitly send a pause event if the download stopped without being finished,
        // preserving whether it was the user or the system that paused it
        if (!item.isFinished) pause(item.id, byUser = pausedByUser.contains(item.id))

        // if no new download was enqueued (i.e. there's no paused/stopped download left),
        // look if any downloads are still running, and if not, stop the service
        stopServiceIfDone()
    }

    private suspend fun startNextEnqueueDownload() {
        for (id in downloadQueue.keyIterator()) {
            if (downloadQueue[id]) continue
            if (pausedByUser.contains(id)) continue

            val dbItem = Database.downloadDao().findDownloadItemById(id)
            if (dbItem != null && !dbItem.isFinished) {
                resume(id)
                return
            }
        }
    }

    private fun updateNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
        totalRead: Int
    ) {
        notificationBuilder
            .setContentText(
                totalRead.formatAsFileSize() + " / " +
                        item.downloadSize.formatAsFileSize()
            )
            .setProgress(
                item.downloadSize.toInt(),
                totalRead,
                false
            )
        notificationManager.notify(
            item.getNotificationId(),
            notificationBuilder.build()
        )
    }

    /**
     * Returns true if the current amount of downloads is still less than the maximum amount of
     * concurrent downloads.
     */
    private fun mayStartNewDownload(): Boolean {
        val downloadCount = downloadQueue.valueIterator().asSequence().count { it }
        return downloadCount < DownloadHelper.MAX_CONCURRENT_DOWNLOADS
    }

    /**
     * Initiate download [Job] using [DownloadItem] by creating file according to [FileType]
     * for the requested file.
     */
    private fun start(item: DownloadItem) {
        item.path = when (item.type) {
            FileType.AUDIO -> getDownloadPath(DownloadHelper.AUDIO_DIR, item.fileName)
            FileType.VIDEO -> getDownloadPath(DownloadHelper.VIDEO_DIR, item.fileName)
            FileType.SUBTITLE -> getDownloadPath(DownloadHelper.SUBTITLE_DIR, item.fileName)
        }.apply { deleteIfExists() }.createFile()

        lifecycleScope.launch(coroutineContext) {
            item.id = Database.downloadDao().insertDownloadItem(item).toInt()

            if (mayStartNewDownload()) {
                selectFormatAndDownloadFile(item)
            } else {
                // enqueue as waiting (NOT user-paused), so it auto-starts once a slot frees up
                pause(item.id, byUser = false)
            }
        }
    }

    /**
     * Resume download which may have been paused.
     */
    fun resume(id: Int) {
        // If file is already downloading then avoid new download job.
        if (downloadQueue[id] == true) return

        pausedByUser.remove(id)

        if (!mayStartNewDownload()) {
            // register as waiting in the queue so startNextEnqueueDownload picks it up
            // automatically once one of the active downloads finishes
            synchronized(queueLock) {
                if (!downloadQueue.contains(id)) downloadQueue.put(id, false)
            }
            toastFromMainThread(getString(R.string.concurrent_downloads_limit_reached))
            lifecycleScope.launch(coroutineContext) {
                _downloadFlow.emit(id to DownloadStatus.Paused)
            }
            return
        }

        lifecycleScope.launch(coroutineContext) {
            val file = Database.downloadDao().findDownloadItemById(id) ?: return@launch
            selectFormatAndDownloadFile(file)
        }
    }

    /**
     * Pause downloading job for given [id]. If no downloads are active, stop the service.
     * @param byUser true if paused by user action, false if paused by system (e.g. metered network)
     */
    fun pause(id: Int, byUser: Boolean = true) {
        synchronized(queueLock) {
            downloadQueue.put(id, false)
        }
        if (byUser) {
            pausedByUser.add(id)
        }

        lifecycleScope.launch(coroutineContext) {
            _downloadFlow.emit(id to DownloadStatus.Paused)
        }

        // Update the notification to the paused state right away. Must be NonCancellable
        // because stopServiceIfDone() below may destroy the service (and cancel its scope)
        // before the download loop gets a chance to update the notification itself.
        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            val item = Database.downloadDao().findDownloadItemById(id) ?: return@launch
            if (!item.isFinished) {
                setPauseNotification(getNotificationBuilder(item), item, false)
            }
        }

        stopServiceIfDone()
    }

    /**
     * Pause all active downloads. Also marks queue-waiting downloads as paused by user
     * so they don't auto-resume when a slot frees up.
     */
    fun pauseAll() {
        for (id in downloadQueue.keyIterator()) {
            if (downloadQueue[id]) {
                pause(id, byUser = true)
            } else {
                pausedByUser.add(id)
            }
        }
    }

    /**
     * Resume all incomplete downloads: find them in DB and queue them.
     */
    fun resumeAllIncomplete() {
        lifecycleScope.launch(coroutineContext) {
            val incompleteItems = withContext(Dispatchers.IO) {
                Database.downloadDao().getAll()
                    .flatMap { it.downloadItems }
                    .filter { !it.isFinished }
            }

            incompleteItems.forEach {
                pausedByUser.remove(it.id)
                failedDownloads.remove(it.id)
                if (!downloadQueue.contains(it.id)) {
                    downloadQueue.put(it.id, false)
                }
            }

            val current = downloadQueue.valueIterator().asSequence().count { it }
            val slotsToFill = DownloadHelper.MAX_CONCURRENT_DOWNLOADS - current

            if (slotsToFill > 0) {
                val candidates = incompleteItems.filter { !downloadQueue[it.id] }
                    .take(slotsToFill)

                candidates.forEach { item ->
                    launch {
                        selectFormatAndDownloadFile(item)
                    }
                }
            }
        }
    }

    /**
     * Resume all downloads: Queue them all, then fill empty slots.
     */
    private fun resumeAll() {
        lifecycleScope.launch(coroutineContext) {
            val incompleteItems = withContext(Dispatchers.IO) {
                Database.downloadDao().getAll()
                    .flatMap { it.downloadItems }
                    .filter { !it.isFinished }
            }

            incompleteItems.forEach {
                if (!downloadQueue.contains(it.id)) {
                    downloadQueue.put(it.id, false)
                }
            }

            val current = downloadQueue.valueIterator().asSequence().count { it }
            val slotsToFill = DownloadHelper.MAX_CONCURRENT_DOWNLOADS - current

            if (slotsToFill > 0) {
                val candidates = incompleteItems.filter { !downloadQueue[it.id] }
                    .take(slotsToFill)

                candidates.forEach { item ->
                    launch {
                        selectFormatAndDownloadFile(item)
                    }
                }
            }
        }
    }

    /**
     * Stop downloading job for given [id]. If no downloads are active, stop the service.
     */
    private fun stop(id: Int) = lifecycleScope.launch(coroutineContext) {
        downloadQueue[id] = false
        _downloadFlow.emit(id to DownloadStatus.Stopped)

        val item = Database.downloadDao().findDownloadItemById(id) ?: return@launch
        notificationManager.cancel(item.getNotificationId())
        Database.downloadDao().deleteDownloadItemById(id)

        // If no more DownloadItems exist for this videoId, delete the parent Download
        // to avoid an orphaned Download entry that can't be resumed or interacted with
        if (Database.downloadDao().countDownloadItemsByVideoId(item.videoId) == 0) {
            val downloadWithItems = Database.downloadDao().getDownloadById(item.videoId)
            if (downloadWithItems != null) {
                Database.downloadDao().deleteDownload(downloadWithItems.download)
            }
        }

        stopServiceIfDone()
    }

    /**
     * Stop service if no downloads are active
     */
    private fun stopServiceIfDone() {
        if (downloadQueue.valueIterator().asSequence().none { it }) {
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_DETACH)
            sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
            stopSelf()
        }
    }

    private fun selectMatchingStream(streams: Streams, item: DownloadItem): PipedStream? {
        val stream = when (item.type) {
            FileType.AUDIO -> streams.audioStreams
            FileType.VIDEO -> streams.videoStreams
            FileType.SUBTITLE -> null
        }
        return stream?.find {
            it.format == item.format && it.quality == item.quality && it.audioTrackLocale == item.language
        }
    }

    /**
     * Attempt to regenerate a download URL by re-fetching streams from the API.
     * Called when a download fails due to expired/stale stream info.
     * Returns the matching stream, or null if no match found.
     */
    private suspend fun regenerateLink(item: DownloadItem): PipedStream? {
        val streams = runCatching {
            loadStreamsInfo(item.videoId)
        }.getOrNull() ?: return null
        return selectMatchingStream(streams, item)
    }

    /**
     * Check whether the file downloading or not.
     */
    fun isDownloading(id: Int): Boolean {
        return downloadQueue[id]
    }

    private fun notifyForeground() {
        notificationManager = getSystemService()!!

        summaryNotificationBuilder = Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(getString(R.string.downloading))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setGroupSummary(true)

        ServiceCompat.startForeground(
            this, NotificationId.DOWNLOAD_IN_PROGRESS.id, summaryNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun getNotificationBuilder(item: DownloadItem): Builder {
        val intent = Intent(this@DownloadService, MainActivity::class.java)
            .putExtra(IntentData.OPEN_DOWNLOADS, true)
        val activityIntent = PendingIntentCompat
            .getActivity(this@DownloadService, 0, intent, FLAG_CANCEL_CURRENT, false)

        return Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setContentTitle("[${item.type}] ${item.fileName}")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(activityIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
    }

    private fun setResumeNotification(
        notificationBuilder: Builder,
        item: DownloadItem
    ) {
        notificationBuilder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .clearActions()
            .addAction(getPauseAction(item.id))
            .addAction(getStopAction(item.id))

        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun setPauseNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
        isCompleted: Boolean = false
    ) {
        notificationBuilder
            .setProgress(0, 0, false)
            .setOngoing(false)
            .clearActions()

        if (isCompleted) {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_done)
                .setContentText(getString(R.string.download_completed))
        } else {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_pause)
                .setContentText(getString(R.string.download_paused))
                .addAction(getResumeAction(item.id))
                .addAction(getStopAction(item.id))
        }
        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun getResumeAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java)
            .setAction(ACTION_DOWNLOAD_RESUME)
            .putExtra("id", id)

        return NotificationCompat.Action.Builder(
            R.drawable.ic_play,
            getString(R.string.resume),
            PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    private fun getPauseAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java)
            .setAction(ACTION_DOWNLOAD_PAUSE)
            .putExtra("id", id)

        return NotificationCompat.Action.Builder(
            R.drawable.ic_pause,
            getString(R.string.pause),
            PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    private fun getStopAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_DOWNLOAD_STOP
            putExtra("id", id)
        }

        // the request code must differ from the one of the pause/resume action
        val requestCode = Int.MAX_VALUE / 2 - id
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stop,
            getString(R.string.stop),
            PendingIntentCompat.getBroadcast(this, requestCode, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    /**
     * Get a [Path] from the corresponding download directory and the file name
     */
    private fun getDownloadPath(directory: String, fileName: String): Path {
        return DownloadHelper.getDownloadDir(this, directory) / fileName
    }

    override fun onDestroy() {
        downloadQueue.clear()
        pausedByUser.clear()
        failedDownloads.clear()
        networkCallback?.let {
            getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(it)
        }
        networkCallback = null
        instance = null
        IS_DOWNLOAD_RUNNING = false
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        intent.getIntArrayExtra("ids")?.forEach { resume(it) }
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
}