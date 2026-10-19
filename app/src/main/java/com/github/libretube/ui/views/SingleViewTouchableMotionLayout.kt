package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import com.github.libretube.extensions.dpToPx
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.github.libretube.R

class SingleViewTouchableMotionLayout(context: Context, attributeSet: AttributeSet? = null) :
    MotionLayout(context, attributeSet) {

    private val viewToDetectTouch by lazy {
        findViewById<View>(R.id.main_container) ?: findViewById(R.id.audio_player_container)
    }
    private val isAudioPlayer by lazy {
        viewToDetectTouch.id == R.id.audio_player_container
    }
    private val scaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val viewRect = Rect()
    private val transitionListenerList = mutableListOf<TransitionListener?>()
    private val swipeDownListener = mutableListOf<() -> Unit>()
    private val swipeDownOnScrollListener = mutableListOf<(Float) -> Unit>()
    private val swipeDownOnScrollEndListener = mutableListOf<(Boolean) -> Unit>()
    private val gestureDetector = GestureDetector(context, Listener())
    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())

    // all views that form the mini player card and move together while it is dragged
    private val miniPlayerViews by lazy {
        listOf(
            R.id.main_container,
            R.id.player,
            R.id.close_imageView,
            R.id.play_imageView,
            R.id.miniPlayerProgress
        ).mapNotNull { findViewById<View>(it) }
    }

    private val scrollView by lazy { findViewById<ScrollView>(R.id.player_scrollView) }

    private var startedMinimized = false
    private var touchInitialY = 0f
    private var isTouchDownInsideHitArea = false
    private var shouldInterceptTouchEvent = false

    // touch tracking for swipe-down-to-portrait-fullscreen on the scroll area
    private var isTouchOnScrollView = false
    private var scrollTouchStartY = 0f
    private var scrollAtTop = false
    private var portraitFullscreenTriggered = false

    init {
        super.setTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(p0: MotionLayout?, p1: Int, p2: Int, p3: Float) {
                transitionListenerList.filterNotNull()
                    .forEach { it.onTransitionChange(p0, p1, p2, p3) }
            }

            override fun onTransitionCompleted(p0: MotionLayout?, p1: Int) {
                transitionListenerList.filterNotNull()
                    .forEach { it.onTransitionCompleted(p0, p1) }
            }
        })
    }

    override fun setTransitionListener(listener: TransitionListener?) {
        addTransitionListener(listener)
    }

    override fun addTransitionListener(listener: TransitionListener?) {
        transitionListenerList += listener
    }

    private inner class Listener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            setTransitionDuration(200)
            transitionToStart()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (scaleGestureDetector.isInProgress) return false

            // move the mini player card with the finger, the position is committed on release
            miniPlayerViews.forEach {
                it.translationX -= distanceX
                it.translationY -= distanceY
            }
            return true
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val margin = MINI_PLAYER_MARGIN_DP.dpToPx()
            // start from the stored width: the view's width only updates after the next layout
            val currentWidth = miniPlayerWidth ?: viewToDetectTouch.width
            miniPlayerWidth = (currentWidth * detector.scaleFactor).toInt()
                .coerceIn(MINI_PLAYER_MIN_WIDTH_DP.dpToPx(), width - 2 * margin)
            applyMiniPlayerBounds()
            return true
        }
    }

    /**
     * Saves the position the mini player card was dragged to, or closes the mini player
     * if the card was dragged mostly off the bottom of the screen.
     */
    private fun commitMiniPlayerPosition() {
        val card = viewToDetectTouch
        val wasDragged = card.translationX != 0f || card.translationY != 0f
        if (!wasDragged) return

        val dismissed = card.y + card.height / 2 > height
        val x = card.x.toInt()
        val y = card.y.toInt()
        miniPlayerViews.forEach {
            it.translationX = 0f
            it.translationY = 0f
        }

        if (dismissed) {
            swipeDownListener.forEach { it.invoke() }
            return
        }

        miniPlayerX = x
        miniPlayerY = y
        applyMiniPlayerBounds()
    }

    /**
     * Applies the user's mini player size and position to the minimized constraint set,
     * clamped to the visible part of the screen.
     */
    private fun applyMiniPlayerBounds() {
        if (isAudioPlayer) return
        val set = getConstraintSet(R.id.end) ?: return
        val cardId = R.id.main_container
        val margin = MINI_PLAYER_MARGIN_DP.dpToPx()

        val cardWidth = miniPlayerWidth?.coerceAtMost(width - 2 * margin)
        if (cardWidth != null) set.constrainWidth(cardId, cardWidth)

        val x = miniPlayerX
        val y = miniPlayerY
        if (x != null && y != null) {
            val cardHeight = (cardWidth ?: viewToDetectTouch.width) * 9 / 16
            // the host activity may shift this layout up, so compute the top edge on screen
            val location = IntArray(2).also { getLocationOnScreen(it) }
            val statusBarHeight = ViewCompat.getRootWindowInsets(this)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            val minY = (statusBarHeight - location[1] + margin).coerceAtLeast(margin)
            val maxX = (width - (cardWidth ?: viewToDetectTouch.width) - margin).coerceAtLeast(margin)
            val maxY = (height - cardHeight - margin).coerceAtLeast(minY)

            set.clear(cardId, ConstraintSet.END)
            set.clear(cardId, ConstraintSet.BOTTOM)
            set.connect(cardId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, x.coerceIn(margin, maxX))
            set.connect(cardId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, y.coerceIn(minY, maxY))
        }

        updateState(R.id.end, set)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // a new player keeps the mini player size and position the user chose before
        doOnLayout { applyMiniPlayerBounds() }
    }

    /**
     * Add a listener when the mini player card is dragged off the bottom of the screen
     * while the current transition's state is in end state (minimized state)
     */
    fun addSwipeDownListener(listener: () -> Unit) = apply {
        swipeDownListener.add(listener)
    }

    /**
     * Add a listener for when the user swipes down on the scroll area while the player
     * is expanded and the scroll view is at the top. Used to enter portrait fullscreen.
     */
    fun addSwipeDownOnScrollListener(listener: (Float) -> Unit) = apply {
        swipeDownOnScrollListener.add(listener)
    }

    fun addSwipeDownOnScrollEndListener(listener: (Boolean) -> Unit) = apply {
        swipeDownOnScrollEndListener.add(listener)
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                shouldInterceptTouchEvent = false
                isTouchDownInsideHitArea = false

                // never intercept touch event if we're currently in audio player mode
                if (isAudioPlayer) return false

                viewToDetectTouch.getHitRect(viewRect)
                isTouchDownInsideHitArea = viewRect.contains(event.x.toInt(), event.y.toInt())
                touchInitialY = event.y
                startedMinimized = progress == 1F
            }

            MotionEvent.ACTION_MOVE -> {
                if (!startedMinimized && !shouldInterceptTouchEvent && isTouchDownInsideHitArea) {
                    val deltaY = event.y - touchInitialY

                    // swipe down detected
                    if (deltaY > scaledTouchSlop) {
                        // start intercepting and consume the event ourselves in onTouchEvent()
                        shouldInterceptTouchEvent = true

                        // inject down MotionEvent from current position to properly trigger
                        // motion scene's swipe action
                        MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_DOWN
                            setLocation(event.x, event.y)
                        }.also { downEvent ->
                            onTouchEvent(downEvent)
                            downEvent.recycle()
                        }
                    }
                }
            }
        }

        return shouldInterceptTouchEvent
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Track scroll-down-to-portrait-fullscreen gesture here (not in onInterceptTouchEvent)
        // because dispatchTouchEvent always receives all events, even after the ScrollView
        // calls requestDisallowInterceptTouchEvent(true) when it starts scrolling.
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // track touch on scroll view for portrait fullscreen gesture
                isTouchOnScrollView = false
                portraitFullscreenTriggered = false
                scrollView?.let { sv ->
                    sv.getHitRect(viewRect)
                    if (viewRect.contains(event.x.toInt(), event.y.toInt())) {
                        isTouchOnScrollView = true
                        scrollTouchStartY = event.y
                        scrollAtTop = !sv.canScrollVertically(-1)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTouchOnScrollView && scrollAtTop && !startedMinimized && progress != 1F) {
                    val deltaY = event.y - scrollTouchStartY
                    // Report live drag progress for animation (even past threshold, even negative)
                    swipeDownOnScrollListener.forEach { it.invoke(deltaY) }
                    if (deltaY > 80f.dpToPx()) {
                        portraitFullscreenTriggered = true
                    } else if (deltaY < 0) {
                        // Finger moved back above start — cancel the trigger
                        portraitFullscreenTriggered = false
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTouchOnScrollView && scrollAtTop && !startedMinimized && progress != 1F) {
                    swipeDownOnScrollEndListener.forEach {
                        it.invoke(portraitFullscreenTriggered)
                    }
                }
                portraitFullscreenTriggered = false
                isTouchOnScrollView = false
            }
        }
        return super.dispatchTouchEvent(event)
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isTouchDownInsideHitArea && startedMinimized) {
            // the mini player card handles drag, pinch and tap itself instead of the
            // motion scene's swipe, so it can be moved and resized freely
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                commitMiniPlayerPosition()
            }
            return true
        }

        return isTouchDownInsideHitArea && super.onTouchEvent(event)
    }

    companion object {
        private const val MINI_PLAYER_MARGIN_DP = 12f
        private const val MINI_PLAYER_MIN_WIDTH_DP = 140f

        // kept for the app's lifetime so the next video's mini player opens at the same place
        private var miniPlayerWidth: Int? = null
        private var miniPlayerX: Int? = null
        private var miniPlayerY: Int? = null
    }
}
