package com.github.libretube.repo

import android.util.Log
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.DownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.IOException
import okio.source
import java.time.Duration
import kotlin.io.path.fileSize
import kotlin.math.min

/**
 * Download from RAW HTTP stream.
 */
class RawByteStreamDownloadProvider(val url: HttpUrl) : DownloadProvider {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
            .readTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong() * 2))
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun downloadNextChunk(
        item: DownloadItem,
        sink: BufferedSink,
        isActive: () -> Boolean,
    ): DownloadProgressResult {
        val startByteOffset = item.path.fileSize()
        Log.w(TAG(), "downloadNextChunk: item=${item.fileName}, startByte=$startByteOffset, downloadSize=${item.downloadSize}")
        val source =
            startConnection(url, startByteOffset, item.downloadSize) ?: run {
                Log.w(TAG(), "downloadNextChunk: connection failed for ${item.fileName}")
                return DownloadProgressResult.Failed
            }

        val sourceByte = source.byteStream().source()

        var totalRead = 0L
        var lastRead = 0L
        try {
            // Check if downloading is still active and read next bytes.
            while (isActive() && sourceByte
                    .read(sink.buffer, DownloadHelper.DOWNLOAD_CHUNK_SIZE)
                    .also { lastRead = it } != -1L
            ) {
                sink.emit()
                totalRead += lastRead
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG(), "downloadNextChunk: read timeout for ${item.fileName}, read so far=${totalRead} bytes, will retry")
            withContext(Dispatchers.IO) {
                sourceByte.close()
                source.close()
            }
            return if (totalRead > 0) {
                Log.w(TAG(), "downloadNextChunk: partial progress ${totalRead} bytes for ${item.fileName}")
                DownloadProgressResult.Progressed(totalRead)
            } else {
                DownloadProgressResult.Failed
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG(), "downloadNextChunk: IOException for ${item.fileName}: ${e.message}, read so far=${totalRead} bytes, will retry")
            withContext(Dispatchers.IO) {
                sourceByte.close()
                source.close()
            }
            return if (totalRead > 0) {
                Log.w(TAG(), "downloadNextChunk: partial progress ${totalRead} bytes for ${item.fileName}")
                DownloadProgressResult.Progressed(totalRead)
            } else {
                DownloadProgressResult.Failed
            }
        }

        withContext(Dispatchers.IO) {
            sourceByte.close()
            source.close()
        }

        if (!isActive()) {
            Log.w(TAG(), "downloadNextChunk: paused by user for ${item.fileName}, read ${totalRead} bytes")
            return if (totalRead > 0) DownloadProgressResult.Progressed(totalRead) else DownloadProgressResult.Failed
        }

        return if (startByteOffset + totalRead < item.downloadSize) {
            Log.w(TAG(), "downloadNextChunk: progressed ${totalRead} bytes, total=${startByteOffset + totalRead}/${item.downloadSize}")
            DownloadProgressResult.Progressed(totalRead)
        } else {
            Log.w(TAG(), "downloadNextChunk: DOWNLOAD COMPLETE for ${item.fileName}, total=${startByteOffset + totalRead}")
            DownloadProgressResult.DownloadComplete
        }
    }

    private suspend fun startConnection(
        url: HttpUrl,
        alreadyRead: Long,
        readLimit: Long?
    ): ResponseBody? {
        val limit = readLimit?.let {
            min(readLimit, alreadyRead + BYTES_PER_REQUEST)
        }?.toString().orEmpty()

        val request = Request.Builder()
            .url(url)
            .method("GET", null)
            .header("Range", "bytes=$alreadyRead-$limit")
            .build()

        return withContext(Dispatchers.IO) {
            // Retry connecting to server for n times.
            try {
                val call = httpClient.newCall(request)
                val response = call.execute()

                if (response.code == 403) {
                    response.close()
                    Log.w(TAG(), "Got HTTP 403 while downloading: ${response.body.string()}")
                    return@withContext null
                } else if (response.code !in 200..299) {
                    response.close()
                    Log.w(TAG(), "HTTP ${response.code} while downloading ${url}")
                    return@withContext null
                }

                return@withContext response.body
            } catch (e: IOException) {
                Log.w(TAG(), "IOException while downloading: ${e.message}")

                return@withContext null
            }
        }
    }

    companion object {
        // maximum working tested chunk size is 3MB, the 512MB value here is from NewPipe
        private const val BYTES_PER_REQUEST = 512 * 1024L
    }
}