package app.pnale.ticker

import android.os.Bundle
import android.view.View
import android.widget.*

/** Edits one rule. Existing rules save on every valid change; a new rule is created by its save button. */
class AlarmRuleActivity : AlarmPage() {
    private var original: AlarmPreset? = null
    private lateinit var preset: AlarmPreset
    private lateinit var summary: TextView
    private lateinit var summaryDetail: TextView
    private lateinit var valueLabel: TextView
    private lateinit var value: EditText
    private lateinit var priceHint: TextView
    private lateinit var windowBox: LinearLayout
    private lateinit var cooldownBox: LinearLayout
    private lateinit var name: EditText
    private lateinit var saveState: TextView
    private lateinit var primary: Button
    private var ready = false
    // Only an editor the user touched may save; a stale instance must never overwrite newer edits.
    private var dirty = false
    private val autoSave = Runnable { if (original != null && dirty) save(auto = true) }
    private val listener: (MarketState) -> Unit = { refreshPriceHint() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        original = intent.getStringExtra("id")?.let { id -> prefs.rules.firstOrNull { it.id == id } }
        preset = original ?: restoreNewDraft() ?: AlarmPreset(rule = AlarmRule(app.preferences.coins.firstOrNull() ?: "ETH", "drop", 2.0, 5))
        val page = SettingsPage(this, if (original == null) "新建规则" else "编辑规则", if (original == null) "填好后点保存" else "修改会自动保存")
        val content = page.content

        val preview = content.section("这条规则会")
        summary = text("", 18f, Colors.green, true); preview.addView(summary)
        summaryDetail = text("", 12f, Colors.muted); preview.addView(summaryDetail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val what = content.section("盯哪个、什么情况")
        val coins = (app.preferences.coins + preset.rule.coin).distinct()
        what.choices("标的（来自自选）", coins.map { it to it }, preset.rule.coin, 4) { edit(preset.copy(rule = preset.rule.copy(coin = it))); refreshPriceHint() }
        what.choices("条件", AlarmPreset.kinds.map { it to AlarmText.kindLabel(it) }, preset.rule.kind, 4) { kind ->
            // Switching between percent and price keeps a sensible default instead of reinterpreting the number.
            val percent = kind in listOf("drop", "rise")
            val wasPercent = preset.rule.kind in listOf("drop", "rise")
            val nextValue = if (percent == wasPercent) preset.rule.value else if (percent) 2.0 else currentPrice() ?: preset.rule.value
            value.setText(AlarmText.number(nextValue))
            edit(preset.copy(rule = preset.rule.copy(kind = kind, value = nextValue)))
        }
        valueLabel = text("", 12f, Colors.muted); what.addView(valueLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(8) })
        value = EditText(this).apply {
            setTextColor(android.graphics.Color.WHITE); textSize = 18f; setText(AlarmText.number(preset.rule.value)); contentDescription = "阈值"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = rounded(Colors.background, 10); setPadding(dp(12), dp(10), dp(12), dp(10)); minimumHeight = dp(48)
        }
        what.addView(value, LinearLayout.LayoutParams(-1, -2))
        priceHint = text("", 11f, Colors.muted); what.addView(priceHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        windowBox = column(); what.addView(windowBox)
        windowBox.choices("观察窗口", (listOf(1, 3, 5, 15, 30, 60) + preset.rule.minutes).distinct().sorted().map { it.toString() to "$it 分" }, preset.rule.minutes.toString(), 4) {
            edit(preset.copy(rule = preset.rule.copy(minutes = it.toInt())))
        }

        val how = content.section("怎么提醒", "实际铃声仍按「提醒时段」")
        how.toggle("铃声", preset.sound) { edit(preset.copy(sound = it)) }
        how.toggle("振动", preset.vibration) { edit(preset.copy(vibration = it)) }
        how.choices("最长提醒", (listOf(15, 30, 60, 120) + preset.duration).distinct().sorted().map { it.toString() to "$it 秒" }, preset.duration.toString(), 4) { edit(preset.copy(duration = it.toInt())) }
        how.toggle("触发后继续监测", preset.repeat) { edit(preset.copy(repeat = it)); cooldownBox.visibility = if (it) View.VISIBLE else View.GONE }
        cooldownBox = column().apply { visibility = if (preset.repeat) View.VISIBLE else View.GONE }; how.addView(cooldownBox)
        cooldownBox.choices("两次提醒至少间隔", (listOf(5, 15, 30, 60, 240) + preset.cooldownMinutes).distinct().sorted().map { it.toString() to "$it 分" }, preset.cooldownMinutes.toString(), 3) {
            edit(preset.copy(cooldownMinutes = it.toInt()))
        }
        how.action("按这条的设置试听 10 秒") { testTone(preset.sound, preset.vibration) }

        val more = content.section("名称", "留空时自动命名", true, original != null && !preset.name.startsWith(preset.rule.coin))
        name = more.input("规则名称", plain = true).apply { setText(preset.name.takeUnless { it == "新警报" }.orEmpty()); hint = autoName(preset) }

        saveState = text("", 11f, Colors.muted); content.addView(saveState)
        page.footer.visibility = View.VISIBLE
        page.footer.addView(button(if (original == null) "取消" else "删除") { if (original == null) { prefs.editorDraft = ""; finish() } else confirmDelete() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        primary = button("", true) { saveAndEnable() }; page.footer.addView(primary, LinearLayout.LayoutParams(0, -2, 1.6f))

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!ready) return
                value.text.toString().toDoubleOrNull()?.let { edit(preset.copy(rule = preset.rule.copy(value = it)), validateOnly = true) } ?: render()
                preset = preset.copy(name = name.text.toString()); render(); scheduleSave()
            }
        }
        value.addTextChangedListener(watcher); name.addTextChangedListener(watcher)
        render(); refreshPriceHint()
        window.decorView.post { ready = true }
    }

    private fun autoName(p: AlarmPreset) = "${p.rule.coin} ${AlarmText.kindLabel(p.rule.kind)}"
    private fun currentPrice() = app.market.state.quotes[preset.rule.coin + "USDT"]?.price
    private fun refreshPriceHint() {
        if (!::priceHint.isInitialized) return
        val price = currentPrice()
        priceHint.text = when {
            preset.rule.kind in listOf("drop", "rise") -> "例如 2 表示窗口内涨跌 2%。需先采满窗口才会判断。"
            price == null -> "已经越过阈值时不会立即触发，要先回到另一侧再穿越。"
            else -> "现价 ${Prices.format(price)} · 已经越过阈值时不会立即触发"
        }
    }

    private fun edit(next: AlarmPreset, validateOnly: Boolean = false) {
        preset = next
        if (validateOnly) return
        render(); scheduleSave()
    }
    private fun scheduleSave() {
        dirty = true
        persistDraft()
        window.decorView.removeCallbacks(autoSave); window.decorView.postDelayed(autoSave, 600)
    }

    /** Null when the rule is invalid; also marks the offending field. */
    private fun build(): AlarmPreset? {
        val v = value.text.toString().toDoubleOrNull()
        val percent = preset.rule.kind in listOf("drop", "rise")
        if (v == null || !v.isFinite() || v <= 0 || (percent && v >= 100)) { value.error = if (percent) "请输入 0–100 之间的百分比" else "请输入正数价格"; return null }
        value.error = null
        return preset.copy(name = name.text.toString().trim().ifEmpty { autoName(preset) }.take(40), rule = preset.rule.copy(value = v))
    }

    private fun render() {
        if (!::primary.isInitialized) return
        val percent = preset.rule.kind in listOf("drop", "rise")
        valueLabel.text = if (percent) "涨跌幅 · %" else "价格 · USDT"
        windowBox.visibility = if (percent) View.VISIBLE else View.GONE
        val valid = build()
        summary.text = valid?.let { "${it.rule.coin} ${AlarmText.condition(it.rule)} 时提醒" } ?: "请先填一个有效的阈值"
        summary.setTextColor(if (valid != null) Colors.green else Colors.orange)
        summaryDetail.text = AlarmText.delivery(preset)
        val stored = original?.let { o -> prefs.rules.firstOrNull { it.id == o.id } }
        primary.text = if (stored?.enabled == true) "保存" else "保存并启用"
        if (::name.isInitialized) name.hint = autoName(preset)
        refreshPriceHint()
    }

    /** Existing rules keep their enabled state on auto-save; a changed condition clears the last-fired time. */
    private fun save(auto: Boolean, enable: Boolean? = null): Boolean {
        window.decorView.removeCallbacks(autoSave)
        val built = build() ?: run { saveState.text = "草稿已保存 · 阈值有效后自动应用"; saveState.setTextColor(Colors.orange); return false }
        val rules = prefs.rules
        val prior = rules.firstOrNull { it.id == built.id }
        if (prior == null && rules.size >= 20) { Toast.makeText(this, "最多保存 20 条规则", Toast.LENGTH_SHORT).show(); return false }
        val next = built.copy(enabled = enable ?: prior?.enabled ?: false, lastFired = if (prior?.rule == built.rule && enable == null) prior.lastFired else 0)
        if (prior == next) return true
        prefs.rules = if (prior != null) rules.map { if (it.id == next.id) next else it } else rules + next
        prefs.editorDraft = ""; original = next; preset = next; dirty = false
        saveState.text = if (auto) "已自动保存" else "已保存"; saveState.setTextColor(Colors.green)
        render(); return true
    }
    private fun saveAndEnable() {
        val built = build() ?: return
        if (built.rule.coin !in app.preferences.coins) { Toast.makeText(this, "请先把 ${built.rule.coin} 加入自选", Toast.LENGTH_LONG).show(); return }
        arm { if (save(auto = false, enable = true)) { Toast.makeText(this, "规则已启用", Toast.LENGTH_SHORT).show(); finish() } }
    }
    private fun confirmDelete() {
        android.app.AlertDialog.Builder(this).setTitle("删除这条规则？").setMessage(preset.name).setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
            window.decorView.removeCallbacks(autoSave); prefs.editorDraft = ""
            prefs.rules = prefs.rules.filter { it.id != preset.id }; finish()
        }.show()
    }

    // A new rule that was never saved survives in the draft so an accidental back does not lose it.
    private fun persistDraft() { if (original == null) prefs.editorDraft = org.json.JSONObject().put("preset", preset.copy(name = name.text.toString()).json()).toString() }
    private fun restoreNewDraft(): AlarmPreset? = runCatching {
        AlarmPreset.parse(org.json.JSONObject(prefs.editorDraft).getJSONObject("preset")).takeIf { draft -> prefs.rules.none { it.id == draft.id } }
    }.getOrNull()

    override fun onStart() { super.onStart(); app.market.listen(listener); app.market.acquire(this) }
    override fun onStop() {
        if (original != null && dirty) save(auto = true)
        app.market.unlisten(listener); app.market.release(this); super.onStop()
    }
}
