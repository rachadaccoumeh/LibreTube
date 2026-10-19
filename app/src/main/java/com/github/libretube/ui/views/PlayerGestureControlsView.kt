package com.github.libretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.libretube.databinding.PlayerGestureControlsViewBinding
import com.github.libretube.extensions.normalize

class PlayerGestureControlsView(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {
    val binding = PlayerGestureControlsViewBinding.inflate(LayoutInflater.from(context), this, true)

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldHeight, oldHeight)

        // Set new max value of progress bar corresponding to the new height and
        // make progress accordingly, store oldProgress before changing it to avoid
        // inconsistency when old progress > new max
        binding.brightnessProgressBar.apply {
            val oldMax = max
            val oldProgress = progress
            max = (height * 0.7).toInt()
            progress = if (oldMax > 0) oldProgress.normalize(0, oldMax, 0, max) else 0
        }

        binding.volumeProgressBar.apply {
            val oldMax = max
            val oldProgress = progress
            max = (height * 0.7).toInt()
            progress = if (oldMax > 0) oldProgress.normalize(0, oldMax, 0, max) else 0
        }
    }
}
