package com.marcel.hyperliquid

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * A ScrollView that detects a pull-down gesture at the very top of the
 * content and reports it, so the activity can offer pull-to-refresh
 * without any third-party dependency. It never consumes gestures itself:
 * all events still go through ScrollView, so normal scrolling is
 * untouched. Callbacks:
 * - onPullProgress(pulling, beyondThreshold): while a top pull is in
 *   progress, so the activity can show/hide the indicator line.
 * - onPullRefresh(): when a pull past the threshold is released.
 */
class PullScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    var onPullProgress: ((pulling: Boolean, beyondThreshold: Boolean) -> Unit)? = null
    var onPullRefresh: (() -> Unit)? = null

    private var startY = 0f
    private var pulling = false
    private val thresholdPx = (120 * resources.displayMetrics.density).toInt()

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - startY
                if (scrollY == 0 && dy > 0) {
                    if (!pulling) pulling = true
                    onPullProgress?.invoke(true, dy >= thresholdPx)
                } else if (pulling && (scrollY > 0 || dy <= 0)) {
                    pulling = false
                    onPullProgress?.invoke(false, false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    val dy = ev.y - startY
                    if (dy >= thresholdPx) onPullRefresh?.invoke()
                    pulling = false
                    onPullProgress?.invoke(false, false)
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}
