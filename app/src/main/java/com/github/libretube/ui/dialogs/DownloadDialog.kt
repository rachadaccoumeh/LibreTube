package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputFilter
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.Subtitle
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.DialogDownloadBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.getWhileDigit
import com.github.libretube.extensions.sha256Sum
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.parcelable.DownloadData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadDialog : DialogFragment() {
    private lateinit var videoId: String
    private var onDownloadConfirm = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoId = arguments?.getString(IntentData.videoId)!!
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogDownloadBinding.inflate(layoutInflater)

        fetchAvailableSources(binding)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.download)
            .setView(binding.root)
            .setPositiveButton(R.string.download, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
            .apply {
                getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    onDownloadConfirm.invoke()
                }
            }
    }

    private fun fetchAvailableSources(binding: DialogDownloadBinding) {
        lifecycleScope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    MediaServiceRepository.instance.getStreams(videoId)
                }
            }  catch (e: Exception) {
                Log.e(TAG(), e.stackTraceToString())
                context?.toastFromMainDispatcher(e.localizedMessage.orEmpty())
                return@launch
            }
            val existingItems = withContext(Dispatchers.IO) {
                DatabaseHolder.Database.downloadDao().getDownloadById(videoId)?.downloadItems.orEmpty()
            }
            initDownloadOptions(binding, response, existingItems)
        }
    }

    private fun initDownloadOptions(binding: DialogDownloadBinding, streams: Streams, existingItems: List<DownloadItem>) {
        binding.videoTitle.text = streams.title

        Log.i(TAG(), "initDownloadOptions() — videoId=$videoId, existingItems count=${existingItems.size}")
        existingItems.forEach { item ->
            Log.i(TAG(), "  existing item: id=${item.id}, type=${item.type}, format=${item.format}, quality=${item.quality}, language=${item.language}, downloadSize=${item.downloadSize}")
        }

        val videoStreams = streams.videoStreams.filter {
            !it.url.isNullOrEmpty()
        }.filter { !it.format.orEmpty().contains("HLS") }.sortedByDescending {
            it.quality.getWhileDigit()
        }

        val audioStreams = streams.audioStreams.filter {
            !it.url.isNullOrEmpty()
        }
            .sortedBy {
                // prioritize main audio track types (lower role flag) over secondary/subbed ones
                PlayerHelper.getFullAudioRoleFlags(0, it.audioTrackType.orEmpty())
            }
            .sortedByDescending {
                it.quality.getWhileDigit()
            }

        val subtitles = streams.subtitles
            .filter { !it.url.isNullOrEmpty() && !it.name.isNullOrEmpty() }
            .sortedBy { it.name }

        if (subtitles.isEmpty()) binding.subtitleSpinner.isGone = true

        binding.videoSpinner.items = videoStreams.map {
            val fileSize = Formatter.formatShortFileSize(context, it.contentLength)
            val downloaded = existingItems.any { item ->
                item.type == FileType.VIDEO && item.format == it.format && item.quality == it.quality
            }
            if (downloaded) {
                val matchedItem = existingItems.first { item ->
                    item.type == FileType.VIDEO && item.format == it.format && item.quality == it.quality
                }
                Log.i(TAG(), "VIDEO stream matched as downloaded: stream quality=${it.quality}, format=${it.format}, codec=${it.codec} -> DB item id=${matchedItem.id}, format=${matchedItem.format}, quality=${matchedItem.quality}")
            }
            val suffix = if (downloaded) " — ✓ ${getString(R.string.already_downloaded)}" else ""
            "${it.quality} ${it.codec} ($fileSize)$suffix"
        }.toMutableList().also {
            it.add(0, getString(R.string.no_video))
        }

        binding.audioSpinner.items = audioStreams.map {
            val fileSize = it.contentLength
                .takeIf { l -> l > 0 }
                ?.let { cl -> Formatter.formatShortFileSize(context, cl) }
            val infoStr = listOfNotNull(it.audioTrackLocale, fileSize)
                .joinToString(", ")
            val downloaded = existingItems.any { item ->
                item.type == FileType.AUDIO && item.format == it.format && item.quality == it.quality
            }
            if (downloaded) {
                val matchedItem = existingItems.first { item ->
                    item.type == FileType.AUDIO && item.format == it.format && item.quality == it.quality
                }
                Log.i(TAG(), "AUDIO stream matched as downloaded: stream quality=${it.quality}, format=${it.format}, locale=${it.audioTrackLocale} -> DB item id=${matchedItem.id}, format=${matchedItem.format}, quality=${matchedItem.quality}, language=${matchedItem.language}")
            }
            val suffix = if (downloaded) " — ✓ ${getString(R.string.already_downloaded)}" else ""
            "${it.quality} ${it.format} ($infoStr)$suffix"
        }.toMutableList().also {
            it.add(0, getString(R.string.no_audio))
        }

        binding.subtitleSpinner.items = subtitles.map { it.name.orEmpty() }.toMutableList().also {
            it.add(0, getString(R.string.no_subtitle))
        }

        restorePreviousSelections(binding, videoStreams, audioStreams, subtitles)

        onDownloadConfirm = onDownloadConfirm@{
            val videoPosition = binding.videoSpinner.selectedItemPosition - 1
            val audioPosition = binding.audioSpinner.selectedItemPosition - 1
            val subtitlePosition = binding.subtitleSpinner.selectedItemPosition - 1

            if (listOf(videoPosition, audioPosition, subtitlePosition).all { it == -1 }) {
                Toast.makeText(context, R.string.nothing_selected, Toast.LENGTH_SHORT).show()
                return@onDownloadConfirm
            }

            val videoStream = videoStreams.getOrNull(videoPosition)
            val audioStream = audioStreams.getOrNull(audioPosition)
            val subtitle = subtitles.getOrNull(subtitlePosition)

            val videoAlreadyDownloaded = videoStream != null && existingItems.any { item ->
                item.type == FileType.VIDEO && item.format == videoStream.format && item.quality == videoStream.quality
            }
            val audioAlreadyDownloaded = audioStream != null && existingItems.any { item ->
                item.type == FileType.AUDIO && item.format == audioStream.format && item.quality == audioStream.quality
            }

            if (videoAlreadyDownloaded || audioAlreadyDownloaded) {
                val message = buildString {
                    if (videoAlreadyDownloaded) append(getString(R.string.already_downloaded_video))
                    if (videoAlreadyDownloaded && audioAlreadyDownloaded) append("\n\n")
                    if (audioAlreadyDownloaded) append(getString(R.string.already_downloaded_audio))
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.download)
                    .setMessage(message)
                    .setPositiveButton(R.string.download) { _, _ ->
                        saveSelections(videoStream, audioStream, subtitle)
                        startDownload(videoStream, audioStream, subtitle)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@onDownloadConfirm
            }

            saveSelections(videoStream, audioStream, subtitle)
            startDownload(videoStream, audioStream, subtitle)
        }
    }

    private fun startDownload(
        videoStream: PipedStream?,
        audioStream: PipedStream?,
        subtitle: Subtitle?
    ) {
        val downloadData = DownloadData(
            videoId = videoId,
            videoFormat = videoStream?.format,
            videoQuality = videoStream?.quality,
            audioFormat = audioStream?.format,
            audioQuality = audioStream?.quality,
            audioLanguage = audioStream?.audioTrackLocale,
            subtitleCode = subtitle?.code
        )
        DownloadHelper.startDownloadService(requireContext(), downloadData)
        dismiss()
    }

    /**
     * Save the download selection to the preferences
     */
    private fun saveSelections(
        videoStream: PipedStream?,
        audioStream: PipedStream?,
        subtitle: Subtitle?
    ) {
        PreferenceHelper.putString(SUBTITLE_LANGUAGE, subtitle?.code.orEmpty())
        PreferenceHelper.putString(VIDEO_DOWNLOAD_FORMAT, videoStream?.format.orEmpty())
        PreferenceHelper.putString(VIDEO_DOWNLOAD_QUALITY, videoStream?.quality.orEmpty())
        PreferenceHelper.putString(AUDIO_DOWNLOAD_FORMAT, audioStream?.format.orEmpty())
        PreferenceHelper.putString(AUDIO_DOWNLOAD_QUALITY, audioStream?.quality.orEmpty())
    }

    private fun getSel(key: String) = PreferenceHelper.getString(key, "")

    /**
     * Restore the download selections from a previous session
     */
    private fun restorePreviousSelections(
        binding: DialogDownloadBinding,
        videoStreams: List<PipedStream>,
        audioStreams: List<PipedStream>,
        subtitles: List<Subtitle>
    ) {
        getStreamSelection(
            videoStreams,
            getSel(VIDEO_DOWNLOAD_QUALITY),
            getSel(VIDEO_DOWNLOAD_FORMAT)
        )?.let {
            binding.videoSpinner.selectedItemPosition = it + 1
        }
        getStreamSelection(
            audioStreams,
            getSel(AUDIO_DOWNLOAD_QUALITY),
            getSel(AUDIO_DOWNLOAD_FORMAT)
        )?.let {
            binding.audioSpinner.selectedItemPosition = it + 1
        }

        subtitles.indexOfFirst { it.code == getSel(SUBTITLE_LANGUAGE) }.takeIf { it != -1 }?.let {
            binding.subtitleSpinner.selectedItemPosition = it + 1
        }
    }

    private fun getStreamSelection(
        streams: List<PipedStream>,
        quality: String,
        format: String
    ): Int? {
        if (quality.isBlank()) return null

        streams.forEachIndexed { index, pipedStream ->
            if (quality == pipedStream.quality && format == pipedStream.format) return index
        }

        streams.forEachIndexed { index, pipedStream ->
            if (quality == pipedStream.quality) return index
        }

        val qualityInt = quality.getWhileDigit() ?: return null

        streams.forEachIndexed { index, pipedStream ->
            if ((pipedStream.quality.getWhileDigit() ?: Int.MAX_VALUE) < qualityInt) return index
        }

        return null
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        setFragmentResult(DOWNLOAD_DIALOG_DISMISSED_KEY, bundleOf())
    }

    companion object {
        private const val VIDEO_DOWNLOAD_QUALITY = "video_download_quality"
        private const val VIDEO_DOWNLOAD_FORMAT = "video_download_format"
        private const val AUDIO_DOWNLOAD_QUALITY = "audio_download_quality"
        private const val AUDIO_DOWNLOAD_FORMAT = "audio_download_format"
        private const val SUBTITLE_LANGUAGE = "subtitle_download_language"

        const val DOWNLOAD_DIALOG_DISMISSED_KEY = "download_dialog_dismissed_key"
    }
}
