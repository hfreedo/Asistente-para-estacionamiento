package py.soulhoshi.parkassist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class LedStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var distance = 68f
    private var rangeMax = 120f
    private var rangeMin = 15f
    private val ledCount = 15

    fun setDistance(value: Float) {
        distance = value
        invalidate()
    }

    fun setThresholds(max: Float, min: Float) {
        rangeMax = max
        rangeMin = min
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(48))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = dp(5).toFloat()
        val available = width - paddingLeft - paddingRight - gap * (ledCount - 1)
        val diameter = min(dp(16).toFloat(), available / ledCount)
        val radius = diameter / 2f
        val total = diameter * ledCount + gap * (ledCount - 1)
        val origin = (width - total) / 2f + radius

        val span = rangeMax - rangeMin
        val redLimit = rangeMin + span / 3f
        val yellowLimit = rangeMin + 2f * span / 3f
        val range = when {
            distance > rangeMax -> 1..0
            distance <= rangeMin -> 0..14
            distance <= redLimit -> 5..9
            distance <= yellowLimit -> 3..11
            else -> 0..14
        }
        val activeColor = when {
            distance <= redLimit -> Color.rgb(255, 77, 94)
            distance <= yellowLimit -> Color.rgb(255, 210, 73)
            else -> Color.rgb(62, 229, 123)
        }

        repeat(ledCount) { index ->
            paint.color = if (index in range) activeColor else Color.rgb(39, 53, 74)
            paint.setShadowLayer(if (index in range) dp(7).toFloat() else 0f, 0f, 0f, paint.color)
            setLayerType(LAYER_TYPE_SOFTWARE, paint)
            canvas.drawCircle(origin + index * (diameter + gap), height / 2f, radius, paint)
        }
        paint.clearShadowLayer()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
