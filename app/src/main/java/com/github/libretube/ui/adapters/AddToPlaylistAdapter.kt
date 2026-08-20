package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.obj.Playlists
import com.github.libretube.databinding.AddToPlaylistItemBinding
import com.github.libretube.helpers.ImageHelper

class AddToPlaylistAdapter(
    val playlists: List<Playlists>,
    val onPlaylistClick: (Playlists) -> Unit
) : RecyclerView.Adapter<AddToPlaylistAdapter.PlaylistViewHolder>() {

    private val addedIds = mutableSetOf<String?>()

    inner class PlaylistViewHolder(val binding: AddToPlaylistItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = AddToPlaylistItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlaylistViewHolder(binding)
    }

    override fun getItemCount() = playlists.size

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val playlist = playlists[position]
        val isAdded = addedIds.contains(playlist.id)
        holder.binding.apply {
            playlistName.text = playlist.name
            playlistVideoCount.text = playlist.videos.toString()
            ImageHelper.loadImage(playlist.thumbnail, playlistThumbnail)
            playlistBookmark.setImageResource(
                if (isAdded) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined
            )

            root.setOnClickListener {
                if (isAdded) return@setOnClickListener
                onPlaylistClick(playlist)
                addedIds.add(playlist.id)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
        }
    }

    fun markAsAdded(playlistId: String?) {
        addedIds.add(playlistId)
    }
}
