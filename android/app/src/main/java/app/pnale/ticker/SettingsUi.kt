package app.pnale.ticker

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*

class SettingsPage(activity: Activity, title: String, subtitle: String) {
    val root = activity.column().apply { setBackgroundColor(Colors.background) }
    val content = activity.column().apply { setPadding(activity.dp(18), activity.dp(8), activity.dp(18), activity.dp(24)) }
    val footer = activity.row().apply { visibility = View.GONE; setPadding(activity.dp(18), activity.dp(10), activity.dp(18), activity.dp(12)); setBackgroundColor(Colors.background) }
    val scroll = ScrollView(activity).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }
    init {
        root.addView(activity.row().apply {
            setPadding(activity.dp(10), activity.dp(8), activity.dp(18), activity.dp(12))
            addView(activity.button("‹") { activity.finish() }.apply { contentDescription = "返回首页"; textSize = 24f; setTag(R.id.ui_base_text_size, 24f) }, LinearLayout.LayoutParams(activity.dp(44), activity.dp(48)).apply { marginEnd = activity.dp(12) })
            addView(activity.column().apply { addView(activity.text(title, 22f, bold = true)); space(5); addView(activity.text(subtitle, 11f, Colors.muted)) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(footer)
        activity.setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            root.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
    }
}

class SectionCard(context: Context, title: String, subtitle: String = "", collapsible: Boolean = false, expanded: Boolean = true) : LinearLayout(context) {
    val body = context.column().apply { setPadding(context.dp(16), context.dp(2), context.dp(16), context.dp(16)) }
    private val arrow = context.text(if (expanded) "−" else "+", 20f, Colors.muted)
    init {
        orientation = VERTICAL; background = context.rounded(Colors.card, 18)
        val header = context.row().apply {
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(14)); minimumHeight = context.dp(58)
            addView(context.column().apply {
                addView(context.text(title, 16f, bold = true))
                if (subtitle.isNotEmpty()) { space(6); addView(context.text(subtitle, 11f, Colors.muted)) }
            }, LayoutParams(0, -2, 1f))
            if (collapsible) { addView(arrow, LayoutParams(context.dp(24), -2)); isClickable = true; isFocusable = true; contentDescription = title
                setOnClickListener { setExpanded(body.visibility != VISIBLE) }
            }
        }
        addView(header); addView(body)
        body.visibility = if (!collapsible || expanded) VISIBLE else GONE
        if (collapsible) header.stateDescription = if (expanded) "已展开" else "已折叠"
    }
    fun setExpanded(expanded: Boolean) { body.visibility = if (expanded) VISIBLE else GONE; arrow.text = if (expanded) "−" else "+"; getChildAt(0).stateDescription = if (expanded) "已展开" else "已折叠" }
}

fun LinearLayout.section(title: String, subtitle: String = "", collapsible: Boolean = false, expanded: Boolean = true): LinearLayout {
    val card = SectionCard(context, title, subtitle, collapsible, expanded)
    addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(14) })
    return card.body
}
fun LinearLayout.note(value: String) { addView(context.text(value, 12f, Colors.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6); bottomMargin = context.dp(8) }) }
fun LinearLayout.action(label: String, primary: Boolean = false, fn: () -> Unit): Button = context.button(label, primary, fn).also {
    addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
}
fun LinearLayout.actions(vararg items: Pair<String, () -> Unit>) {
    addView(context.row().apply { items.forEachIndexed { i, (label, fn) ->
        addView(context.button(label, action = fn), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = context.dp(8) })
    } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
}
fun LinearLayout.input(label: String, description: String = label, decimal: Boolean = false, plain: Boolean = false): EditText {
    addView(context.text(label, 12f, Colors.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10); bottomMargin = context.dp(8) })
    return EditText(context).apply {
        setTextColor(Color.WHITE); setHintTextColor(Colors.muted); textSize = 15f; contentDescription = description
        inputType = if (plain) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
        setSingleLine(true); background = context.rounded(Colors.background, 10); setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        minimumHeight = context.dp(48); Typography.apply(this)
        this@input.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(6) })
    }
}
fun Context.labels(values: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).also { Typography.apply(it) }
    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = super.getDropDownView(position, convertView, parent).also { Typography.apply(it); it.minimumHeight = dp(48) }
}
fun LinearLayout.spinner(label: String, values: List<String> = emptyList(), description: String = label): Spinner {
    addView(context.text(label, 12f, Colors.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10); bottomMargin = context.dp(7) })
    return Spinner(context).apply { contentDescription = description; adapter = context.labels(values); background = context.rounded(Colors.background, 10); this@spinner.addView(this, LinearLayout.LayoutParams(-1, context.dp(50)).apply { bottomMargin = context.dp(6) }) }
}
fun LinearLayout.toggle(label: String, checked: Boolean = false, current: (() -> Boolean)? = null, action: (Boolean) -> Unit = {}): Switch = Switch(context).apply {
    text = label; textSize = 13f; contentDescription = label; setTextColor(Color.WHITE); setPadding(0, context.dp(8), 0, context.dp(8)); minimumHeight = context.dp(50)
    var updating = false
    isChecked = checked; Typography.apply(this); setOnCheckedChangeListener { _, enabled -> if (!updating) action(enabled) }
    if (current != null) setTag(R.id.settings_refresh, Runnable { updating = true; isChecked = current(); updating = false })
    this@toggle.addView(this, LinearLayout.LayoutParams(-1, -2))
}
fun LinearLayout.choices(title: String, values: List<Pair<String, String>>, selected: String, columns: Int = 2, current: (() -> String)? = null, action: (String) -> Unit) {
    if (title.isNotEmpty()) addView(context.text(title, 12f, Colors.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12); bottomMargin = context.dp(6) })
    var chosen = selected
    val buttons = mutableMapOf<String, Button>()
    fun render() { buttons.forEach { (key, button) -> button.isSelected = key == chosen; button.setTextColor(if (key == chosen) Colors.green else Colors.muted); button.background = context.rounded(if (key == chosen) Color.rgb(28, 51, 49) else Colors.background, 10).apply { setStroke(context.dp(1), if (key == chosen) Colors.green else Colors.line) } } }
    values.chunked(columns).forEach { chunk -> addView(context.row().apply {
        chunk.forEachIndexed { i, (key, label) ->
            val button = context.button(label) { chosen = key; render(); action(key); if (current != null) { chosen = current(); render() } }; button.contentDescription = if (title.isEmpty()) label else "$title：$label"; buttons[key] = button
            addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = context.dp(8) })
        }
        repeat(columns - chunk.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = context.dp(8) }) }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) }) }
    render()
    if (current != null) buttons.values.firstOrNull()?.setTag(R.id.settings_refresh, Runnable { chosen = current(); render() })
}
fun LinearLayout.slider(title: String, min: Int, max: Int, initial: Int, suffix: String = "%", current: (() -> Int)? = null, action: (Int) -> Unit) {
    val label = context.text("$title  $initial$suffix", 12f, Colors.muted)
    addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(14) })
    addView(SeekBar(context).apply {
        this.max = max - min; progress = initial - min; contentDescription = title
        progressTintList = ColorStateList.valueOf(Colors.green); thumbTintList = ColorStateList.valueOf(Colors.green)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { label.text = "$title  ${value + min}$suffix"; if (user) action(value + min) }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        if (current != null) setTag(R.id.settings_refresh, Runnable { if (!isPressed) progress = current().coerceIn(min, max) - min })
    }, LinearLayout.LayoutParams(-1, context.dp(44)))
}

object SettingsBindings {
    fun refresh(view: View) {
        (view.getTag(R.id.settings_refresh) as? Runnable)?.run()
        if (view is ViewGroup) repeat(view.childCount) { refresh(view.getChildAt(it)) }
    }
}
