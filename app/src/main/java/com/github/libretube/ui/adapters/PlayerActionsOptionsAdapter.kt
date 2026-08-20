package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.databinding.PlayerActionsItemBinding
import com.github.libretube.helpers.PlayerActionsHelper.PlayerActionItem
import com.github.libretube.ui.viewholders.PlayerActionsOptionsViewHolder

class PlayerActionsOptionsAdapter(
    val items: MutableList<PlayerActionItem>
) : RecyclerView.Adapter<PlayerActionsOptionsViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerActionsOptionsViewHolder {
        val binding = PlayerActionsItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlayerActionsOptionsViewHolder(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: PlayerActionsOptionsViewHolder, position: Int) {
        val item = items[position]
        holder.binding.apply {
            title.text = root.context.getString(item.action.titleRes)
            actionIcon.setImageResource(item.action.iconRes)
            checkbox.isChecked = item.isVisible
            checkbox.setOnClickListener {
                item.isVisible = checkbox.isChecked
            }
        }
    }
}
