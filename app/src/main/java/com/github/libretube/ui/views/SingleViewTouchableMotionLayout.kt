package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import com.github.libretube.extensions.dpToPx
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
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

    private val scrollView by lazy { findViewById<ScrollView>(R.id.player_scrollView) }

    private var startedMinimized = false
    private var isStrictlyDownSwipe = false
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
            if (isStrictlyDownSwipe && distanceY > 0) {
                isStrictlyDownSwipe = false
            }

            if (isStrictlyDownSwipe && distanceY < -15F) {
                swipeDownListener.forEach { it.invoke() }
                return true
            }

            return false
        }
    }

    /**
     * Add a listener when the view is swiped down while the current transition's state is in
     * end state (minimized state)
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
                isStrictlyDownSwipe = true
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
            // detect gesture only when the player is in minimized state
            gestureDetector.onTouchEvent(event)
        }

        return isTouchDownInsideHitArea && super.onTouchEvent(event)
    }
}
