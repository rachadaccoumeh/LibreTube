package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.Playlists
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.BottomSheetAddToPlaylistBinding
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.toID
import com.github.libretube.ui.adapters.AddToPlaylistAdapter
import com.github.libretube.ui.models.AddToPlaylistViewModel
import com.github.libretube.ui.sheets.ExpandedBottomSheet
import com.github.libretube.util.PlayingQueue
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

/**
 * Bottom sheet to insert videos into playlists.
 * Shows all playlists with thumbnails; tapping a playlist saves immediately.
 * videoId: The id of the video to add. If none is provided, insert the whole playing queue
 */
class AddToPlaylistDialog : ExpandedBottomSheet(R.layout.bottom_sheet_add_to_playlist) {

    private var videoInfo: StreamItem? = null
    private val viewModel: AddToPlaylistViewModel by activityViewModels { AddToPlaylistViewModel.Factory }
    private var _binding: BottomSheetAddToPlaylistBinding? = null
    private val binding get() = _binding!!
    private var adapter: AddToPlaylistAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoInfo = arguments?.parcelable(IntentData.videoInfo)
        viewModel.savedStateHandle[IntentData.videoInfo] = videoInfo
        viewModel.fetchPlaylists()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        childFragmentManager.setFragmentResultListener(
            CreatePlaylistDialog.CREATE_PLAYLIST_DIALOG_REQUEST_KEY,
            this
        ) { _, resultBundle ->
            val addedToPlaylist = resultBundle.getBoolean(IntentData.playlistTask)
            if (addedToPlaylist) {
                viewModel.fetchPlaylists()
            }
        }

        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = BottomSheetAddToPlaylistBinding.bind(view)

        binding.btnCreatePlaylist.setOnClickListener {
            CreatePlaylistDialog().show(childFragmentManager, null)
        }

        viewModel.uiState.observe(this) { (_, playlists, msg, saved) ->
            binding.playlistsProgress.visibility =
                if (playlists.isEmpty()) View.VISIBLE else View.GONE

            if (playlists.isNotEmpty()) {
                adapter = AddToPlaylistAdapter(playlists) { playlist ->
                    addToPlaylist(playlist)
                }
                binding.playlistsRecycler.layoutManager = LinearLayoutManager(context)
                binding.playlistsRecycler.adapter = adapter
                checkExistingPlaylists(playlists)
            }

            msg?.let {
                with(binding.root.context) {
                    Toast.makeText(this, getString(it.resId, *it.formatArgs?.toTypedArray() ?: arrayOf()), Toast.LENGTH_SHORT)
                        .show()
                }
                viewModel.onMessageShown()
            }

            saved?.let {
                viewModel.onDismissed()
            }
        }
    }

    private fun checkExistingPlaylists(playlists: List<Playlists>) {
        val videoId = videoInfo?.url?.toID() ?: return
        lifecycleScope.launch {
            runCatching {
                PlaylistsHelper.getAllPlaylistsWithVideos(playlists.mapNotNull { it.id })
            }.onSuccess { fullPlaylists ->
                fullPlaylists.forEach { fullPlaylist ->
                    val contains = fullPlaylist.relatedStreams.any { it.url?.toID() == videoId }
                    if (contains) {
                        val playlistId = playlists.firstOrNull { it.id == fullPlaylist.name }?.id
                            ?: fullPlaylist.name
                        adapter?.markAsAdded(playlistId)
                        adapter?.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun addToPlaylist(playlist: Playlists) {
        val streams = videoInfo?.let { listOf(it) } ?: PlayingQueue.getStreams()
        if (streams.isEmpty()) return

        lifecycleScope.launch {
            runCatching {
                PlaylistsHelper.addToPlaylist(playlist.id!!, *streams.toTypedArray())
            }.onSuccess {
                with(binding.root.context) {
                    Toast.makeText(
                        this,
                        getString(R.string.added_to_playlist, listOf(playlist.name)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure {
                with(binding.root.context) {
                    Toast.makeText(this, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        setFragmentResult(ADD_TO_PLAYLIST_DIALOG_DISMISSED_KEY, bundleOf())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ADD_TO_PLAYLIST_DIALOG_DISMISSED_KEY = "add_to_playlist_dialog_dismissed"
    }
}
