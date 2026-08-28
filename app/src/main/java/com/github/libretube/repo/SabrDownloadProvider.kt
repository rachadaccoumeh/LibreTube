package com.github.libretube.repo

import android.annotation.SuppressLint
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.player.manifest.Representation
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.player.parser.PlaybackRequest
import com.github.libretube.player.parser.SabrClient
import com.github.libretube.player.parser.Segment
import okio.BufferedSink

private const val TAG = "SabrDownload"

data class SabrDownloaderHandle(
    val sabrClient: SabrClient,
    @SuppressLint("UnsafeOptInUsageError")
    val streamRepresentation: Representation,
    var initSegment: Segment? = null,
    var nextSegmentNumber: Long = 0L
)

@OptIn(UnstableApi::class)
class SabrDownloadProvider(
    downloadItem: DownloadItem,
    streams: Streams,
    stream: PipedStream,
) : DownloadProvider {
    private val downloadHandle: SabrDownloaderHandle

    init {
        val sabrManifest = SabrManifest(downloadItem.videoId, streams)
        val sabrClient = SabrClient(sabrManifest)

        val streamRepresentation = Representation(stream)
        sabrClient.selectFormat(streamRepresentation)

        downloadHandle = SabrDownloaderHandle(sabrClient, streamRepresentation)
    }

    override suspend fun downloadNextChunk(
        item: DownloadItem,
        sink: BufferedSink,
        isActive: () -> Boolean,
    ): DownloadProgressResult {
        var currentPositionMillis = item.currentDownloadPositionMillis ?: 0L
        Log.w(TAG, "downloadNextChunk: item=${item.fileName}, position=${currentPositionMillis}ms, initSegment=${downloadHandle.initSegment != null}, nextSegment=${downloadHandle.nextSegmentNumber}")

        if (downloadHandle.initSegment == null) {
            val initRequest = PlaybackRequest.initRequest(
                format = downloadHandle.streamRepresentation.formatId(),
                playerPosition = currentPositionMillis,
                playbackSpeed = 1f
            )
            val initSegment = downloadHandle.sabrClient
                .getNextSegment(initRequest)
            if (initSegment == null) {
                Log.w(TAG, "downloadNextChunk: init segment was null for ${item.fileName}")
                return DownloadProgressResult.Failed
            }
            for (chunk in initSegment.data) {
                sink.write(chunk)
            }
            downloadHandle.initSegment = initSegment

            downloadHandle.nextSegmentNumber = initSegment.sequenceNumber + 1
            Log.w(TAG, "downloadNextChunk: init segment done, seq=${initSegment.sequenceNumber}, nextSegment=${downloadHandle.nextSegmentNumber}")
        }

        val request = PlaybackRequest(
            format = downloadHandle.streamRepresentation.formatId(),
            playerPosition = currentPositionMillis,
            segment = downloadHandle.nextSegmentNumber,
            segmentStartTimeMs = currentPositionMillis,
            playbackSpeed = 1f,
            bufferedSegments = emptyList()
        )
        val segment = downloadHandle.sabrClient.getNextSegment(request)
        if (segment == null) {
            Log.w(TAG, "downloadNextChunk: segment was null for ${item.fileName}, segment=${downloadHandle.nextSegmentNumber}")
            return DownloadProgressResult.Failed
        }

        for (chunk in segment.data) {
            sink.write(chunk)
        }

        downloadHandle.nextSegmentNumber = segment.sequenceNumber + 1
        currentPositionMillis += segment.duration

        // persist current download position in millis in the database
        // this is used to restore the download position when pausing and resuming the download
        item.currentDownloadPositionMillis = currentPositionMillis
        DatabaseHolder.Database.downloadDao().updateDownloadItem(item)

        val endSegmentNumber = downloadHandle.sabrClient.getEndSegmentNumber(
            downloadHandle.streamRepresentation.formatId()
        )
        Log.w(TAG, "downloadNextChunk: segment done, seq=${segment.sequenceNumber}, nextSegment=${downloadHandle.nextSegmentNumber}, endSegment=$endSegmentNumber, position=${currentPositionMillis}ms")
        return if (endSegmentNumber != null && downloadHandle.nextSegmentNumber < endSegmentNumber) {
            val downloadedBytesLength = segment.data.sumOf { it.size }
            DownloadProgressResult.Progressed(downloadedBytesLength.toLong())
        } else {
            Log.w(TAG, "downloadNextChunk: DOWNLOAD COMPLETE for ${item.fileName}")
            DownloadProgressResult.DownloadComplete
        }
    }
}