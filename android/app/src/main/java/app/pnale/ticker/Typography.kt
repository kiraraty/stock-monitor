package app.pnale.ticker

import android.app.Activity
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

object Typography {
    fun face(font: String, bold: Boolean = false): Typeface = Typeface.create(when (font) {
        "light" -> "sans-serif-light"
        "mono" -> "monospace"
        "serif" -> "serif"
        else -> "sans-serif"
    }, if (bold || font == "bold") Typeface.BOLD else Typeface.NORMAL)

    fun apply(view: View, prefs: Preferences = Preferences(view.context)) {
        if (view is TextView && view.getTag(R.id.ui_custom_font) != true) {
            val base = (view.getTag(R.id.ui_base_text_size) as? Float)
                ?: (view.textSize / view.resources.displayMetrics.scaledDensity).also { view.setTag(R.id.ui_base_text_size, it) }
            val bold = (view.getTag(R.id.ui_font_bold) as? Boolean)
                ?: (view.typeface?.isBold == true).also { view.setTag(R.id.ui_font_bold, it) }
            view.typeface = face(prefs.uiFont, bold)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, base * prefs.uiTextScale / 100f)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) apply(view.getChildAt(i), prefs)
    }
}

open class PanelActivity : Activity() {
    private val configChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        SettingsBindings.refresh(window.decorView)
    }
    override fun onStart() {
        super.onStart()
        listOf("watchlist", "price_alarm").forEach { getSharedPreferences(it, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(configChanged) }
    }
    override fun onResume() { super.onResume(); SettingsBindings.refresh(window.decorView); Typography.apply(window.decorView) }
    override fun onStop() {
        listOf("watchlist", "price_alarm").forEach { getSharedPreferences(it, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(configChanged) }
        (application as PnaleApp).config.saveNow()
        super.onStop()
    }
}
