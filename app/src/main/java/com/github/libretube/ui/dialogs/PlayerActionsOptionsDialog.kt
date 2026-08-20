package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.databinding.SimpleOptionsRecyclerBinding
import com.github.libretube.extensions.setOnDraggedListener
import com.github.libretube.helpers.PlayerActionsHelper
import com.github.libretube.ui.adapters.PlayerActionsOptionsAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PlayerActionsOptionsDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = SimpleOptionsRecyclerBinding.inflate(layoutInflater)
        val options = PlayerActionsHelper.getPlayerActions(requireContext())
        val adapter = PlayerActionsOptionsAdapter(options.toMutableList())

        binding.optionsRecycler.layoutManager = LinearLayoutManager(context)
        binding.optionsRecycler.adapter = adapter
        binding.optionsRecycler.setOnDraggedListener { from, to ->
            val itemToMove = adapter.items[from]
            adapter.items.remove(itemToMove)
            adapter.items.add(to, itemToMove)
            adapter.notifyItemMoved(from, to)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.player_actions)
            .setView(binding.root)
            .setPositiveButton(R.string.okay) { _, _ ->
                PlayerActionsHelper.setPlayerActions(adapter.items)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
