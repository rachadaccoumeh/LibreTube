package com.github.libretube.ui.sheets

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.media3.common.util.UnstableApi
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.ShareObjectType
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.serializable
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.obj.ShareData
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.DownloadService
import com.github.libretube.ui.activities.NoInternetActivity
import com.github.libretube.ui.dialogs.AddToPlaylistDialog
import com.github.libretube.ui.dialogs.DownloadExportDialog
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.ui.fragments.DownloadTab
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.PlayingQueueMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

@UnstableApi
class DownloadOptionsBottomSheet : BaseBottomSheet() {
    private lateinit var videoId: String

    private val exportFilePicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { outputUri ->
            if (outputUri == null) return@registerForActivityResult

            DownloadExportDialog().apply {
                arguments = Bundle().apply {
                    putString(IntentData.videoId, videoId)
                    putString(IntentData.outputUri, outputUri.toString())
                }
            }.show(requireActivity().supportFragmentManager, null)

            dismiss()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val streamItem = arguments?.parcelable<StreamItem>(IntentData.streamItem)!!
        videoId = streamItem.url!!.toID()
        val downloadTab = arguments?.serializable<DownloadTab>(IntentData.downloadTab)!!
        val playlistId = arguments?.getString(IntentData.playlistId)
        val incompleteIds = arguments?.getIntArray("incompleteIds")

        val options = mutableListOf(
            R.string.playOnBackground,
            R.string.addToPlaylist,
            R.string.share,
            R.string.verify_download,
            R.string.delete,
            R.string.export,
        )

        // can't navigate to video while in offline activity
        if (ContextHelper.tryUnwrapActivity<NoInternetActivity>(requireContext()) == null) {
            options += R.string.go_to_video
        }

        val isSelectedVideoCurrentlyPlaying = PlayingQueue.getCurrent()?.url?.toID() == videoId
        if (!isSelectedVideoCurrentlyPlaying && PlayingQueue.isNotEmpty() && PlayingQueue.queueMode == PlayingQueueMode.OFFLINE) {
            options += R.string.play_next
            options += R.string.add_to_queue
        }

        // Add enqueue/dequeue option if there are incomplete downloads
        if (incompleteIds != null && incompleteIds.isNotEmpty()) {
            val isEnqueued = incompleteIds.any { id -> DownloadService.isItemIdInQueue(id) }
            if (isEnqueued) {
                options += R.string.dequeue_download
            } else {
                options += R.string.enqueue_download
            }
        }

        setSimpleItems(options.map { getString(it) }) { which ->
            val playerData = PlayerData(
                videoId,
                playlistId = playlistId,
                downloadTab = downloadTab,
                isOffline = true
            )

            when (options[which]) {
                R.string.playOnBackground -> {
                    BackgroundHelper.playOnBackground(requireContext(), playerData)
                }

                R.string.addToPlaylist -> {
                    AddToPlaylistDialog().apply {
                        arguments = bundleOf(IntentData.videoInfo to streamItem)
                    }.show(
                        parentFragmentManager,
                        AddToPlaylistDialog::class.java.name
                    )
                }

                R.string.go_to_video -> {
                    NavigationHelper.navigateVideo(requireContext(), playerData)
                }

                R.string.share -> {
                    val shareData =
                        ShareData(title = streamItem.title, previewImageUrl = streamItem.thumbnail)
                    val bundle = bundleOf(
                        IntentData.id to videoId,
                        IntentData.shareObjectType to ShareObjectType.VIDEO,
                        IntentData.shareData to shareData
                    )
                    val newShareDialog = ShareDialog()
                    newShareDialog.arguments = bundle
                    newShareDialog.show(parentFragmentManager, null)
                }

                R.string.verify_download -> {
                    val ctx = requireContext()
                    val errStr = getString(R.string.verify_download_error)
                    val okStr = getString(R.string.verify_download_success)
                    val fixedPrefix = getString(R.string.verify_download_fixed).split("%")[0]
                    dialog?.dismiss()
                    CoroutineScope(Dispatchers.Main).launch {
                        val result = withContext(Dispatchers.IO) {
                            DownloadHelper.verifyDownload(ctx, videoId)
                        }
                        val msg = when (result) {
                            -1 -> errStr
                            0 -> okStr
                            else -> "$fixedPrefix$result"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }

                R.string.delete -> {
                    setFragmentResult(DELETE_DOWNLOAD_REQUEST_KEY, bundleOf())
                }

                R.string.play_next -> {
                    PlayingQueue.addAsNext(streamItem)
                }

                R.string.add_to_queue -> {
                    PlayingQueue.add(streamItem)
                }

                R.string.export -> {
                    exportFilePicker.launch(streamItem.title)
                    // dismissing now would cause the exportFilePicker to be dropped before
                    // the file is actually chosen by the user
                    autoDismiss = false
                }

                R.string.enqueue_download -> {
                    DownloadHelper.startDownloadService(requireContext())
                    incompleteIds?.forEach { id ->
                        DownloadService.enqueueItem(id)
                    }
                }

                R.string.dequeue_download -> {
                    incompleteIds?.forEach { id ->
                        DownloadService.dequeueItem(id)
                    }
                }
            }
        }

        super.onCreate(savedInstanceState)
    }

    companion object {
        const val DELETE_DOWNLOAD_REQUEST_KEY = "delete_download_request_key"
    }
}
