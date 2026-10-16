package app.pnale.ticker

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Colors {
    val background = Color.rgb(11, 15, 20)
    val card = Color.rgb(23, 29, 37)
    val muted = Color.rgb(151, 162, 177)
    val green = Color.rgb(117, 245, 208)
    val red = Color.rgb(255, 112, 126)
    val line = Color.rgb(43, 50, 59)
    val orange = Color.rgb(255, 189, 105)
    fun direction(value: Double) = if (value >= 0) green else red
}

fun Context.dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
fun Context.text(value: String, size: Float = 13f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color)
    setTag(R.id.ui_base_text_size, size); setTag(R.id.ui_font_bold, bold)
    Typography.apply(this)
    includeFontPadding = false
    setLineSpacing(dp(3).toFloat(), 1f)
}
fun Context.button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
    text = value; textSize = 13f; isAllCaps = false
    setTag(R.id.ui_base_text_size, 13f); setTag(R.id.ui_font_bold, true); Typography.apply(this)
    setTextColor(if (primary) Colors.background else Color.WHITE)
    background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(35, 255, 255, 255)), rounded(if (primary) Colors.green else Colors.background, 12), null)
    setPadding(dp(10), dp(3), dp(10), dp(3))
    minHeight = dp(48); minimumWidth = 0; minWidth = 0
    setOnClickListener { action() }
}
fun Context.rounded(color: Int = Colors.card, radius: Int = 14) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Colors.line)
}
fun LinearLayout.space(height: Int) { addView(View(context), LinearLayout.LayoutParams(1, context.dp(height))) }
fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
