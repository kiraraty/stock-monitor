package app.pnale.ticker

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import kotlin.math.ceil

/** Scale the whole panel, including touch coordinates, while keeping its window bounds snug. */
class OverlayFrame(context: Context, private val baseWidth: Int, private val requestedScale: Float,
    private val maxWidth: Int, private val maxHeight: Int) : FrameLayout(context) {
    init { clipChildren = false; clipToPadding = false }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        child.measure(View.MeasureSpec.makeMeasureSpec(baseWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val scale = minOf(requestedScale, maxWidth.toFloat() / baseWidth, maxHeight.toFloat() / child.measuredHeight.coerceAtLeast(1))
        child.pivotX = 0f; child.pivotY = 0f; child.scaleX = scale; child.scaleY = scale
        setMeasuredDimension(ceil(baseWidth * scale).toInt(), ceil(child.measuredHeight * scale).toInt())
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0)
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }
}
