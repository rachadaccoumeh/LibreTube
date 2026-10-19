package com.github.libretube.ui.extensions

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

fun RecyclerView.addOnBottomReachedListener(
    prefetchDistance: Int = 0,
    onBottomReached: () -> Unit
) {
    require(prefetchDistance >= 0)

    if (prefetchDistance == 0) {
        viewTreeObserver.addOnScrollChangedListener {
            if (!canScrollVertically(1)) onBottomReached()
        }
        return
    }

    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0) return

            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val itemCount = recyclerView.adapter?.itemCount ?: return
            if (layoutManager.findLastVisibleItemPosition() >= itemCount - prefetchDistance - 1) {
                onBottomReached()
            }
        }
    })
}

/**
 * Shows [button] once the list is scrolled down by more than one screen and scrolls back to the
 * top when it is tapped. [onClick] runs after the scroll, e.g. to expand a collapsed app bar.
 */
fun RecyclerView.setupBackToTopButton(button: FloatingActionButton, onClick: () -> Unit = {}) {
    button.hide()
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (recyclerView.computeVerticalScrollOffset() > recyclerView.height) button.show() else button.hide()
        }
    })
    button.setOnClickListener {
        scrollToPosition(0)
        button.hide()
        onClick()
    }
}
