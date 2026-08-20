package com.github.libretube.ui.controllers

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.github.libretube.extensions.dpToPx
import com.github.libretube.ui.views.CustomExoPlayerView

/**
 * A class that handles the scale animation in [CustomExoPlayerView] when entering/exiting
 * fullscreen using the swipe gesture.
 */
class FullscreenGestureAnimationController(
    private val playerView: CustomExoPlayerView,
    private val videoFrameView: View,
    private val onSwipeUpCompleted: () -> Unit,
    private val onSwipeDownCompleted: () -> Unit,
    private val onSwipeDownScrollCompleted: () -> Unit = onSwipeUpCompleted,
) {
    enum class SwipeDirection { UP, DOWN, NONE }

    private var isSwipeInProgress = false
    private var shouldHandleSwipe = false
    private var swipeDirection = SwipeDirection.NONE
    private var swipeStartY = 0f
    private var currentSwipeDistance = 0f
    // Separate state for the scroll-down-to-portrait-fullscreen gesture
    private var isScrollSwipeInProgress = false
    private var scrollSwipeDistance = 0f

    fun onSwipe(distanceY: Float, positionY: Float) {
        if (!isSwipeInProgress) {
            isSwipeInProgress = true
            currentSwipeDistance = 0f
            swipeDirection = if (distanceY.toInt() > 0) SwipeDirection.UP else SwipeDirection.DOWN
            // Allow swipe up when not in fullscreen to enter, and both up/down when in fullscreen to exit
            shouldHandleSwipe =
                if (!playerView.isFullscreen()) swipeDirection == SwipeDirection.UP
                else true

            if (!shouldHandleSwipe) {
                return
            }

            swipeStartY = positionY
            playerView.hideController()
            // Set pivot point based on swipe direction
            videoFrameView.pivotX = videoFrameView.width / 2f
            videoFrameView.pivotY = if (swipeDirection == SwipeDirection.UP) 0f else videoFrameView.height.toFloat()
        } else if (shouldHandleSwipe) {
            when (swipeDirection) {
                SwipeDirection.UP -> {
                    currentSwipeDistance = (swipeStartY - positionY).coerceAtLeast(0f)
                    val cappedDistance = currentSwipeDistance.coerceAtMost(SWIPE_DISTANCE_THRESHOLD.toFloat())

                    val scale = if (playerView.isFullscreen()) {
                        1f - (cappedDistance * SCALE_FACTOR)
                    } else {
                        1f + (cappedDistance * SCALE_FACTOR)
                    }
                    videoFrameView.scaleX = scale
                    videoFrameView.scaleY = scale
                }

                SwipeDirection.DOWN -> {
                    currentSwipeDistance = (positionY - swipeStartY).coerceAtLeast(0f)
                    val cappedDistance = currentSwipeDistance.coerceAtMost(SWIPE_DISTANCE_THRESHOLD.toFloat())

                    val scale = 1f - (cappedDistance * SCALE_FACTOR)
                    videoFrameView.scaleX = scale
                    videoFrameView.scaleY = scale
                }

                // Do nothing
                SwipeDirection.NONE -> {}
            }
        }
    }

    /**
     * Drive the animation from the scroll-down gesture (swipe down on description area
     * to enter portrait fullscreen). Scales the video frame live with the finger drag.
     */
    fun onSwipeDownScroll(dragDistance: Float) {
        if (playerView.isFullscreen()) return
        if (!isScrollSwipeInProgress) {
            isScrollSwipeInProgress = true
            playerView.hideController()
            videoFrameView.pivotX = videoFrameView.width / 2f
            videoFrameView.pivotY = videoFrameView.height.toFloat()
        }
        scrollSwipeDistance = dragDistance.coerceAtLeast(0f)
        val cappedDistance = scrollSwipeDistance.coerceAtMost(SWIPE_DISTANCE_THRESHOLD.toFloat())
        val scale = 1f + (cappedDistance * SCALE_FACTOR)
        videoFrameView.scaleX = scale
        videoFrameView.scaleY = scale
    }

    /**
     * Complete the scroll-down gesture. Animates the scale back to 1 and triggers
     * portrait fullscreen if the threshold was met.
     */
    fun onSwipeDownScrollEnd(enterFullscreen: Boolean) {
        if (!isScrollSwipeInProgress) return

        videoFrameView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setInterpolator(OvershootInterpolator(0.8f))
            .setDuration(300)
            .withEndAction {
                videoFrameView.pivotY = videoFrameView.height / 2f
                videoFrameView.pivotX = videoFrameView.width / 2f
            }
            .start()

        isScrollSwipeInProgress = false
        scrollSwipeDistance = 0f

        if (enterFullscreen) {
            onSwipeDownScrollCompleted()
        }
    }

    fun onSwipeEnd() {
        if (swipeDirection == SwipeDirection.NONE) return

        // Trigger the fullscreen toggle only on finger release. Toggling mid-drag
        // reparents the player view during an active touch, which breaks the touch
        // stream (ACTION_CANCEL + synthetic events) and causes spurious re-toggles.
        val completed = shouldHandleSwipe && currentSwipeDistance >= SWIPE_DISTANCE_THRESHOLD
        val direction = swipeDirection
        val wasEntering = direction == SwipeDirection.UP && !playerView.isFullscreen()

        // Reset scale with a smooth animation. Use OvershootInterpolator when entering
        // fullscreen (scale was > 1, settling back feels like a gentle bounce) and
        // DecelerateInterpolator when exiting (scale was < 1, smooth settle).
        if (shouldHandleSwipe) {
            val interpolator = if (wasEntering) OvershootInterpolator(0.8f) else DecelerateInterpolator()
            videoFrameView.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setInterpolator(interpolator)
                .setDuration(300)
                .withEndAction {
                    videoFrameView.pivotY = videoFrameView.height / 2f
                    videoFrameView.pivotX = videoFrameView.width / 2f
                }
                .start()
        }

        resetState()

        if (completed) {
            when (direction) {
                SwipeDirection.UP -> onSwipeUpCompleted()
                SwipeDirection.DOWN -> onSwipeDownCompleted()
                SwipeDirection.NONE -> {}
            }
        }
    }

    /**
     * Reset all state. Called when fullscreen state changes to clear any stuck gesture
     * (e.g. when the player view is reparented to the fullscreen dialog, ACTION_UP is
     * never delivered so onSwipeEnd doesn't fire).
     */
    fun reset() {
        videoFrameView.scaleX = 1f
        videoFrameView.scaleY = 1f
        videoFrameView.pivotX = videoFrameView.width / 2f
        videoFrameView.pivotY = videoFrameView.height / 2f
        resetState()
    }

    private fun resetState() {
        isSwipeInProgress = false
        shouldHandleSwipe = false
        currentSwipeDistance = 0f
        swipeDirection = SwipeDirection.NONE
        isScrollSwipeInProgress = false
        scrollSwipeDistance = 0f
    }

    companion object {
        /**
         * The amount of percentage the view will be scaled up and down.
         */
        private const val MAXIMUM_SCALE_DIFF_PERCENTAGE = 0.20f
        private val SWIPE_DISTANCE_THRESHOLD = 80f.dpToPx()
        private val SCALE_FACTOR = MAXIMUM_SCALE_DIFF_PERCENTAGE / SWIPE_DISTANCE_THRESHOLD
    }
}