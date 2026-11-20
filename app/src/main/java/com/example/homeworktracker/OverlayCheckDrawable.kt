package com.example.homeworktracker

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import kotlin.math.sin

class OverlayCheckDrawable(checked: Boolean) : Drawable() {
    private val uncheckedColor = Color.parseColor("#8B949E")
    private val checkedColor = Color.parseColor("#3FB950")
    private val colorEvaluator = android.animation.ArgbEvaluator()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val check = Path().apply { moveTo(11f, 16f); lineTo(14.5f, 19.5f); lineTo(21f, 12.5f) }
    private val measure = PathMeasure(check, false)
    private val segment = Path()
    private var progress = if (checked) 1f else 0f
    private var pulse = 0f
    private var target = checked
    private var animator: ValueAnimator? = null

    fun setChecked(checked: Boolean, animate: Boolean) {
        if (checked == target) return
        target = checked
        animator?.cancel()
        if (!animate) {
            progress = if (checked) 1f else 0f
            pulse = 0f
            invalidateSelf()
            return
        }
        val start = progress
        val end = if (checked) 1f else 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                progress = start + (end - start) * fraction
                pulse = sin(Math.PI * fraction).toFloat() * 0.12f
                invalidateSelf()
            }
            start()
        }
    }

    fun stop() { animator?.cancel(); animator = null }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 32f, bounds.height() / 32f)
        canvas.scale(0.64f * (1f + pulse), 0.64f * (1f + pulse), 16f, 16f)
        paint.color = colorEvaluator.evaluate(progress, uncheckedColor, checkedColor) as Int
        canvas.drawCircle(16f, 16f, 10f, paint)
        segment.reset()
        if (progress > 0f) {
            measure.getSegment(0f, measure.length * progress, segment, true)
            canvas.drawPath(segment, paint)
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
