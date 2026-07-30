package com.github.libretube.services

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
import androidx.core.util.keyIterator
import androidx.core.util.set
import androidx.core.util.valueIterator
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.libretube.LibreTubeApp.Companion.DOWNLOAD_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
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
import com.github.libretube.extensions.getContentLength
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
import com.github.libretube.ui.activities.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.math.min

/**
 * Download service with custom implementation of downloading using [HttpURLConnection].
 */
class DownloadService : LifecycleService() {
    private val binder = LocalBinder()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val coroutineContext = dispatcher + SupervisorJob()

    private lateinit var notificationManager: NotificationManager
    private lateinit var summaryNotificationBuilder: Builder

    private val downloadQueue = SparseBooleanArray()
    private val _downloadFlow = MutableSharedFlow<Pair<Int, DownloadStatus>>()
    val downloadFlow: SharedFlow<Pair<Int, DownloadStatus>> = _downloadFlow

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(30))
            .retryOnConnectionFailure(true)
            .build()
    }

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
     * Only registered once in onCreate to avoid duplicate callbacks.
     */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun registerNetworkChangedCallback() {
        if (networkCallback != null) return
        val connectivityManager = getSystemService<ConnectivityManager>()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
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
            ACTION_DOWNLOAD_RESUME -> resume(downloadId!!)
            ACTION_DOWNLOAD_PAUSE -> pause(downloadId!!)
            ACTION_DOWNLOAD_STOP -> stop(downloadId!!)
        }

        val downloadData = intent?.parcelableExtra<DownloadData>(IntentData.downloadData)
            ?: return START_NOT_STICKY
        val videoId = downloadData.videoId

        lifecycleScope.launch(coroutineContext) {
            val streams = try {
                withContext(Dispatchers.IO) {
                    MediaServiceRepository.instance.getStreams(videoId)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.stackTraceToString())
                toastFromMainDispatcher(e.localizedMessage.orEmpty())
                return@launch
            }

            storeVideoMetadata(videoId, streams)

            val downloadItems = streams.toDownloadItems(downloadData)
            for (downloadItem in downloadItems) {
                start(downloadItem)
            }
        }

        return START_NOT_STICKY
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
                    Log.e(TAG(), "failed to download SponsorBlock segments for $videoId")
                    Log.e(TAG(), e.stackTraceToString())
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
                    Log.e(TAG(), "failed to download image $thumbnailUrl")
                    Log.e(TAG(), e.stackTraceToString())
                }
            }
        }
    }

    /**
     * Download file and emit [DownloadStatus] to the collectors of [downloadFlow]
     * and notification.
     */
    private suspend fun downloadFile(item: DownloadItem) {
        Log.i(TAG(), "downloadFile() started for id=${item.id}, videoId=${item.videoId}")
        downloadQueue[item.id] = true
        val notificationBuilder = getNotificationBuilder(item)
        setResumeNotification(notificationBuilder, item)

        var totalRead = if (item.path.exists()) item.path.fileSize() else 0
        Log.i(TAG(), "downloadFile() — existing fileSize=$totalRead, downloadSize=${item.downloadSize}")

        // If URL is null, try to regenerate it before giving up
        if (item.url == null) {
            Log.i(TAG(), "downloadFile() — item.url is null, attempting to regenerate link")
            regenerateLink(item)
        }
        val url = URL(ProxyHelper.rewriteUrlUsingProxyPreference(item.url ?: run {
            Log.i(TAG(), "downloadFile() — item.url is still null after regeneration, cannot download")
            _downloadFlow.emit(item.id to DownloadStatus.Error("Could not regenerate download URL"))
            failedDownloads.add(item.id)
            downloadQueue[item.id] = false
            setPauseNotification(notificationBuilder, item, false)
            stopServiceIfDone()
            return
        }))

        // If file is larger than downloadSize, DB metadata is stale — delete and re-download
        if (totalRead > 0 && item.downloadSize > 0 && totalRead > item.downloadSize) {
            Log.i(TAG(), "downloadFile() — fileSize ($totalRead) > downloadSize (${item.downloadSize}), deleting file and re-downloading")
            item.path.deleteIfExists()
            item.path.createFile()
            totalRead = 0
            // Re-fetch the correct content length
            item.downloadSize = 0L
        }

        // only fetch the content length if it's not been returned by the API
        if (item.downloadSize <= 0L) {
            Log.i(TAG(), "downloadFile() — fetching content length from URL")
            url.getContentLength()?.let { size ->
                item.downloadSize = size
                Database.downloadDao().updateDownloadItem(item)
                Log.i(TAG(), "downloadFile() — content length=$size")
            }
        }

        // If downloadSize is still 0, we can't download — emit error and stop
        if (item.downloadSize <= 0L) {
            Log.i(TAG(), "downloadFile() — downloadSize is 0 after getContentLength, cannot download id=${item.id}")
            _downloadFlow.emit(item.id to DownloadStatus.Error("Could not determine download size"))
            failedDownloads.add(item.id)
            setPauseNotification(notificationBuilder, item, false)
            downloadQueue[item.id] = false
            stopServiceIfDone()
            return
        }

        // Ensure file exists before download loop (may have been deleted by previous crash)
        if (!item.path.exists()) {
            item.path.createFile()
        }

        Log.i(TAG(), "downloadFile() — starting download loop: totalRead=$totalRead, downloadSize=${item.downloadSize}")
        while (totalRead < item.downloadSize) {
            try {
                val previousRead = totalRead
                totalRead = progressDownload(item, url, totalRead, notificationBuilder)
                if (totalRead == previousRead) {
                    // No progress made — startConnection failed after all retries
                    Log.i(TAG(), "downloadFile() — no progress, breaking loop")
                    break
                }
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                toastFromMainThread("${getString(R.string.download)}: ${e.message}")
                Log.i(TAG(), "downloadFile() — exception: ${e.stackTraceToString()}")
                _downloadFlow.emit(item.id to DownloadStatus.Error(e.message.toString(), e))
                break
            }
        }

        val completed = item.downloadSize > 0 && totalRead >= item.downloadSize
        Log.i(TAG(), "downloadFile() finished for id=${item.id}, totalRead=$totalRead, downloadSize=${item.downloadSize}, completed=$completed")
        if (completed) {
            _downloadFlow.emit(item.id to DownloadStatus.Completed)
            failedDownloads.remove(item.id)
        } else {
            _downloadFlow.emit(item.id to DownloadStatus.Paused)
            if (totalRead == 0L) {
                failedDownloads.add(item.id)
            }
        }

        setPauseNotification(notificationBuilder, item, completed)

        downloadQueue[item.id] = false

        // start the next download if there are any remaining ones enqueued
        // (skip downloads paused by the user — only auto-resume queue-waiting ones)
        Log.i(TAG(), "downloadFile() — auto-resume loop, pausedByUser=$pausedByUser, failedDownloads=$failedDownloads, queueKeys=${downloadQueue.keyIterator().asSequence().toList()}")
        for (id in downloadQueue.keyIterator()) {
            Log.i(TAG(), "  auto-resume: checking id=$id, downloadQueue[id]=${downloadQueue[id]}, inFailed=${id in failedDownloads}, inPaused=${id in pausedByUser}")
            if (downloadQueue[id]) continue
            if (id in failedDownloads) continue
            if (id in pausedByUser) continue

            val dbItem = Database.downloadDao().findDownloadItemById(id)
            // Skip stale placeholder items (null URL + no size) and already-complete items
            if (dbItem != null && dbItem.url != null && (dbItem.downloadSize <= 0L || (if (dbItem.path.exists()) dbItem.path.fileSize() else 0) < dbItem.downloadSize)) {
                Log.i(TAG(), "  auto-resume: calling resume($id)")
                resume(id)
                return
            }
        }

        // if no new download was enqueued (i.e. there's no paused/stopped download left),
        // look if any downloads are still running, and if not, stop the service
        stopServiceIfDone()
    }

    private suspend fun progressDownload(
        item: DownloadItem,
        url: URL,
        totalReadBefore: Long,
        notificationBuilder: Builder
    ): Long {
        Log.i(TAG(), "progressDownload() id=${item.id}, totalReadBefore=$totalReadBefore, downloadSize=${item.downloadSize}")
        val source =
            startConnection(item, url, totalReadBefore, item.downloadSize)
        if (source == null) {
            Log.i(TAG(), "progressDownload() — startConnection returned null, no progress")
            return totalReadBefore
        }

        var totalRead = totalReadBefore

        val sink = item.path.sink(StandardOpenOption.APPEND).buffer()
        val sourceByte = source.byteStream().source()

        var lastTime = System.currentTimeMillis() / 1000
        var lastRead = 0L
        var bytesSinceLastFlush = 0L
        var lastEmittedTotal = totalReadBefore
        val FLUSH_THRESHOLD = 1024L * 1024 // flush every 1MB

        // Check if downloading is still active and read next bytes.
        while (downloadQueue[item.id] && totalRead < item.downloadSize && sourceByte
                .read(sink.buffer, DownloadHelper.DOWNLOAD_CHUNK_SIZE)
                .also { lastRead = it } != -1L
        ) {
            totalRead += lastRead
            bytesSinceLastFlush += lastRead

            // Only flush to disk every 1MB to reduce I/O overhead
            if (bytesSinceLastFlush >= FLUSH_THRESHOLD) {
                sink.emit()
                bytesSinceLastFlush = 0L
            }

            // Only emit progress and update notification once per second
            if (item.downloadSize != -1L &&
                System.currentTimeMillis() / 1000 > lastTime
            ) {
                sink.emit()
                _downloadFlow.emit(
                    item.id to DownloadStatus.Progress(
                        totalRead - lastEmittedTotal,
                        totalRead,
                        item.downloadSize
                    )
                )
                lastEmittedTotal = totalRead
                updateNotification(notificationBuilder, item, totalRead.toInt())
                lastTime = System.currentTimeMillis() / 1000
            }
        }

        withContext(Dispatchers.IO) {
            sink.flush()
            sink.close()
            sourceByte.close()
            source.close()
        }

        return totalRead
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

    private suspend fun startConnection(
        item: DownloadItem,
        url: URL,
        alreadyRead: Long,
        readLimit: Long?
    ): ResponseBody? {
        val limit = readLimit?.let {
            // generate a random byte distance to make it more difficult to fingerprint
            val nextBytesToReadSize = (BYTES_PER_REQUEST_MIN..BYTES_PER_REQUEST_MAX).random()
            val endByte = min(readLimit, alreadyRead + nextBytesToReadSize)
            // Range header is inclusive — if endByte equals the full content length,
            // omit the end to let the server send the remaining bytes without off-by-one issues
            if (endByte >= readLimit) "" else (endByte - 1).toString()
        }.orEmpty()

        // Append ratebypass=yes to googlevideo.com URLs to avoid YouTube throttling
        val downloadUrl = if (url.host.contains("googlevideo.com") && url.query?.contains("ratebypass") != true) {
            val separator = if (url.query.isNullOrEmpty()) "?" else "&"
            URL(url.toString() + "${separator}ratebypass=yes")
        } else {
            url
        }

        val request = Request.Builder()
            .url(downloadUrl)
            .method("GET", null)
            .header("Range", "bytes=$alreadyRead-$limit")
            .build()

        Log.i(TAG(), "startConnection() — url contains ratebypass=${downloadUrl.query?.contains("ratebypass") == true}")

        return withContext(Dispatchers.IO) {
            val maxRetries = 3
            var lastError: IOException? = null

            for (attempt in 0 until maxRetries) {
                try {
                    val call = httpClient.newCall(request)
                    val response = call.execute()
                    return@withContext handleResponse(item, response, downloadUrl, alreadyRead, limit)
                } catch (e: IOException) {
                    lastError = e
                    Log.i(TAG(), "Download attempt ${attempt + 1}/$maxRetries failed: ${e.message}")
                    if (attempt < maxRetries - 1) {
                        val backoffMs = (1000L * (1 shl attempt)) + (100..500).random()
                        delay(backoffMs)
                    }
                }
            }

            Log.i(TAG(), "All $maxRetries attempts failed, trying to regenerate link", lastError)
            regenerateLink(item)
            val newUrl = item.url
            if (newUrl != null && newUrl != url.toString()) {
                Log.i(TAG(), "Link regenerated, retrying with new URL")
                val regeneratedRequest = Request.Builder()
                    .url(URL(ProxyHelper.rewriteUrlUsingProxyPreference(newUrl)))
                    .method("GET", null)
                    .header("Range", "bytes=$alreadyRead-$limit")
                    .build()
                try {
                    val call = httpClient.newCall(regeneratedRequest)
                    val response = call.execute()
                    return@withContext handleResponse(item, response, URL(ProxyHelper.rewriteUrlUsingProxyPreference(newUrl)), alreadyRead, limit)
                } catch (e: IOException) {
                    Log.i(TAG(), "Regenerated link also failed: ${e.message}")
                }
            }

            val message = getString(R.string.downloadfailed)
            _downloadFlow.emit(item.id to DownloadStatus.Error(message))
            toastFromMainThread(message)

            null
        }
    }

    private val failedDownloads = java.util.concurrent.CopyOnWriteArraySet<Int>()
    private val pausedByUser = java.util.concurrent.CopyOnWriteArraySet<Int>()
    private var regenerateCount = 0

    private suspend fun handleResponse(item: DownloadItem, response: Response, url: URL, alreadyRead: Long, limit: String): ResponseBody? {
        Log.i(TAG(), "handleResponse() code=${response.code}, message=${response.message}, url=${response.request.url.toString().take(80)}")
        // If link is expired or unavailable, try to regenerate using available info.
        if (response.code == 403 || response.code == 503) {
            regenerateCount++
            if (regenerateCount > 3) {
                Log.i(TAG(), "handleResponse() — already regenerated $regenerateCount times, giving up")
                regenerateCount = 0
                val message = getString(R.string.downloadfailed) + ": 503 Service Unavailable"
                _downloadFlow.emit(item.id to DownloadStatus.Error(message))
                toastFromMainThread(message)
                response.close()
                pause(item.id, byUser = false)
                return null
            }
            Log.i(TAG(), "handleResponse() got ${response.code}, regenerating link (attempt $regenerateCount/3) and retrying")
            regenerateLink(item)
            response.close()
            // Retry with the new URL instead of recursively calling downloadFile()
            val newUrl = item.url
            if (newUrl != null && newUrl != url.toString()) {
                Log.i(TAG(), "handleResponse() — retrying with regenerated URL")
                val regeneratedUrl = URL(ProxyHelper.rewriteUrlUsingProxyPreference(newUrl))
                // Append ratebypass=yes to googlevideo.com URLs to avoid YouTube throttling
                val finalUrl = if (regeneratedUrl.host.contains("googlevideo.com") && regeneratedUrl.query?.contains("ratebypass") != true) {
                    val separator = if (regeneratedUrl.query.isNullOrEmpty()) "?" else "&"
                    URL(regeneratedUrl.toString() + "${separator}ratebypass=yes")
                } else {
                    regeneratedUrl
                }
                val retryRequest = Request.Builder()
                    .url(finalUrl)
                    .method("GET", null)
                    .header("Range", "bytes=$alreadyRead-$limit")
                    .build()
                try {
                    val retryResponse = httpClient.newCall(retryRequest).execute()
                    return handleResponse(item, retryResponse, finalUrl, alreadyRead, limit)
                } catch (e: IOException) {
                    Log.i(TAG(), "handleResponse() — regenerated link also failed: ${e.message}")
                    return null
                }
            }
            return null
        } else if (response.code !in 200..299) {
            val message = getString(R.string.downloadfailed) + ": " + response.message
            _downloadFlow.emit(item.id to DownloadStatus.Error(message))
            toastFromMainThread(message)
            response.close()
            pause(item.id, byUser = false)
            return null
        }

        regenerateCount = 0
        return response.body
    }

    /**
     * Returns true if the current amount of downloads is still less than the maximum amount of
     * concurrent downloads.
     */
    private fun mayStartNewDownload(): Boolean {
        val downloadCount = downloadQueue.valueIterator().asSequence().count { it }
        val max = DownloadHelper.getMaxConcurrentDownloads()
        Log.i(TAG(), "mayStartNewDownload() — active=$downloadCount, max=$max, result=${downloadCount < max}")
        return downloadCount < max
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
                // Reserve slot immediately to prevent race condition with other start() coroutines
                downloadQueue[item.id] = true
                downloadFile(item)
            } else {
                Log.i(TAG(), "start() — concurrent limit reached, queueing id=${item.id}")
                pause(item.id, byUser = false)
            }
        }
    }

    /**
     * Resume download which may have been paused.
     */
    fun resume(id: Int) {
        Log.i(TAG(), "resume() called for id=$id, downloadQueue[$id]=${downloadQueue[id]}")
        failedDownloads.remove(id)
        pausedByUser.remove(id)
        // If file is already downloading then avoid new download job.
        if (downloadQueue[id]) {
            Log.i(TAG(), "resume() skipped — already downloading")
            return
        }

        if (!mayStartNewDownload()) {
            Log.i(TAG(), "resume() — concurrent limit reached, queueing id=$id for later")
            downloadQueue[id] = false
            lifecycleScope.launch(coroutineContext) {
                _downloadFlow.emit(id to DownloadStatus.Paused)
            }
            return
        }

        // Reserve the slot immediately on the main thread to prevent race condition
        // where multiple resume() calls all pass mayStartNewDownload() before any
        // downloadQueue flag is set on the dispatcher thread.
        downloadQueue[id] = true

        lifecycleScope.launch(coroutineContext) {
            val file = Database.downloadDao().findDownloadItemById(id)
            if (file == null) {
                Log.i(TAG(), "resume() — no DownloadItem found in DB for id=$id, releasing slot")
                downloadQueue[id] = false
                return@launch
            }
            Log.i(TAG(), "resume() starting downloadFile for id=$id, videoId=${file.videoId}, fileSize=${if (file.path.exists()) file.path.fileSize() else 0}, downloadSize=${file.downloadSize}")
            downloadFile(file)
        }
    }

    /**
     * Pause downloading job for given [id]. If no downloads are active, stop the service.
     * @param byUser true when paused by user action (notification/UI), false when paused
     * due to queue waiting or error. Only user-paused downloads are excluded from auto-resume.
     */
    fun pause(id: Int, byUser: Boolean = true) {
        Log.i(TAG(), "pause() id=$id byUser=$byUser")
        downloadQueue[id] = false
        if (byUser) {
            pausedByUser.add(id)
        }

        lifecycleScope.launch(coroutineContext) {
            _downloadFlow.emit(id to DownloadStatus.Paused)
        }

        stopServiceIfDone()
    }

    /**
     * Resume all incomplete downloads, respecting the concurrency limit.
     * Removes all from pausedByUser so they can be auto-resumed as slots free up.
     */
    fun resumeAllIncomplete() {
        Log.i(TAG(), "resumeAllIncomplete() called")
        lifecycleScope.launch(coroutineContext) {
            val allItems = Database.downloadDao().getAllDownloadItems()
            val incomplete = allItems.filter { item ->
                // Skip stale placeholder items (e.g. subtitles with null URL and no size)
                if (item.url == null && item.downloadSize <= 0L) return@filter false
                val fileSize = if (item.path.exists()) item.path.fileSize() else 0
                item.downloadSize <= 0L || fileSize < item.downloadSize
            }
            Log.i(TAG(), "resumeAllIncomplete() — found ${incomplete.size} incomplete downloads")

            for (item in incomplete) {
                failedDownloads.remove(item.id)
                pausedByUser.remove(item.id)
                if (!downloadQueue[item.id] && mayStartNewDownload()) {
                    downloadQueue[item.id] = true
                    launch(coroutineContext) {
                        downloadFile(item)
                    }
                } else if (!downloadQueue[item.id]) {
                    // Mark as queue-waiting so auto-resume picks it up
                    downloadQueue[item.id] = false
                    _downloadFlow.emit(item.id to DownloadStatus.Paused)
                }
            }
        }
    }

    /**
     * Pause all active downloads as user-initiated pauses.
     */
    fun pauseAll() {
        Log.i(TAG(), "pauseAll() called")
        for (id in downloadQueue.keyIterator()) {
            if (downloadQueue[id]) {
                pause(id, byUser = true)
            }
        }
    }

    /**
     * Stop downloading job for given [id]. If no downloads are active, stop the service.
     */
    private fun stop(id: Int) = lifecycleScope.launch(coroutineContext) {
        downloadQueue[id] = false
        pausedByUser.remove(id)
        failedDownloads.remove(id)
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

    /**
     * Regenerate stream url using available info format and quality.
     */
    private suspend fun regenerateLink(item: DownloadItem) {
        val streams = runCatching {
            MediaServiceRepository.instance.getStreams(item.videoId)
        }.getOrNull() ?: return
        val stream = when (item.type) {
            FileType.AUDIO -> streams.audioStreams
            FileType.VIDEO -> streams.videoStreams
            else -> null
        }
        stream?.find {
            it.format == item.format && it.quality == item.quality && it.audioTrackLocale == item.language
        }?.let {
            item.url = it.url
        }
        Database.downloadDao().updateDownloadItem(item)
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
        networkCallback?.let { cb ->
            getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(cb)
        }
        networkCallback = null
        downloadQueue.clear()
        pausedByUser.clear()
        failedDownloads.clear()
        IS_DOWNLOAD_RUNNING = false
        instance = null
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    companion object {
        private const val DOWNLOAD_NOTIFICATION_GROUP = "download_notification_group"
        const val ACTION_SERVICE_STARTED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STOPPED"

        // Larger chunks to reduce number of HTTP requests and avoid YouTube throttling.
        // Previous values (500KB-3MB) caused 33-200 requests per video and got rate-limited.
        private const val BYTES_PER_REQUEST_MIN = 10_000_000L
        private const val BYTES_PER_REQUEST_MAX = 50_000_000L

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
    }
}
