package com.axelmalik.escposemuandroid

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.roundToInt

class PaperViewport @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private var logicalWidthPx = DEFAULT_PAPER_WIDTH_PX
    private var displayWidthFraction = DEFAULT_DISPLAY_WIDTH_FRACTION
    private var scale = 1f

    var isFitToScreen: Boolean = true
        private set

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun setLogicalWidth(widthPx: Int) {
        require(widthPx > 0) { "Paper width must be positive" }
        if (logicalWidthPx == widthPx) return
        logicalWidthPx = widthPx
        requestLayout()
    }

    fun setFitToScreen(fit: Boolean) {
        if (isFitToScreen == fit) return
        isFitToScreen = fit
        requestLayout()
    }

    fun setDisplayWidthFraction(fraction: Float) {
        require(fraction > 0f) { "Paper display width must be positive" }
        if (displayWidthFraction == fraction) return
        displayWidthFraction = fraction
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        val measuredParentWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> resources.displayMetrics.widthPixels
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }
        val availableWidth = (measuredParentWidth - paddingLeft - paddingRight).coerceAtLeast(1)
        if (child == null) {
            setMeasuredDimension(resolveSize(availableWidth, widthMeasureSpec), resolveSize(paddingTop + paddingBottom, heightMeasureSpec))
            return
        }

        child.measure(
            MeasureSpec.makeMeasureSpec(logicalWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        scale = if (isFitToScreen) {
            availableWidth.toFloat() * displayWidthFraction / logicalWidthPx
        } else {
            1f
        }
        val scaledHeight = (child.measuredHeight * scale).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(availableWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(scaledHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        child.translationX = ((measuredWidth - paddingLeft - paddingRight) - child.measuredWidth * scale) / 2f + paddingLeft
        child.translationY = paddingTop.toFloat()
    }

    private companion object {
        const val DEFAULT_PAPER_WIDTH_PX = 384
        const val DEFAULT_DISPLAY_WIDTH_FRACTION = 58f / 80f
    }
}
