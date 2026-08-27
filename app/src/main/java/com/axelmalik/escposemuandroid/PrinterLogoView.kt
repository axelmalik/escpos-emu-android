package com.axelmalik.escposemuandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class PrinterLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = RectF(4f, 8f, 28f, 24f)
    private val paper = RectF(9f, 2f, 23f, 11f)
    private val output = RectF(9f, 19f, 23f, 28f)
    private val detail = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width, height) / 32f
        canvas.save()
        canvas.scale(scale, scale)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(paper, paint)
        canvas.drawRoundRect(body, 3f, 3f, paint)
        canvas.drawRect(output, paint)

        paint.color = Color.rgb(38, 55, 70)
        canvas.drawCircle(23f, 13f, 1.5f, paint)
        detail.reset()
        detail.moveTo(11f, 21f)
        detail.lineTo(21f, 21f)
        detail.lineTo(21f, 22f)
        detail.lineTo(11f, 22f)
        detail.close()
        canvas.drawPath(detail, paint)

        canvas.restore()
    }
}
