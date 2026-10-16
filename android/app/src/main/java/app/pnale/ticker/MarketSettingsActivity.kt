package app.pnale.ticker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MarketSettingsActivity : PanelActivity() {
    private val app get() = application as PnaleApp
    private val engine get() = app.market
    private lateinit var permissionStatus: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var liveStatus: TextView
    private lateinit var liveToggle: android.widget.Switch
    private lateinit var input: EditText
    private lateinit var saveStatus: TextView
    private lateinit var save: Button
    private lateinit var start: Button
    private var pendingStart: String? = null
    private var loadingInput = false
    private var draftRevision = 0
    private val autoSave = Runnable { saveInput(auto = true) }
    private lateinit var connectionStatus: TextView
    private lateinit var powerStatus: TextView
    private lateinit var barCoinBox: LinearLayout
    private var barCoinRows = emptyList<String>()
    private val listener: (MarketState) -> Unit = { refreshControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = SettingsPage(this, "显示与连接", "自选、后台盯盘和显示方式")
        val content = page.content
        val watchlist = content.section("自选标的", "${MarketSource.resolve(app.preferences.marketSource, app.preferences.lastGoodSource).label} USDT 永续 · 按输入顺序展示")
        input = watchlist.input("标的列表", "展示币种输入框", plain = true).apply {
            hint = "ETH, BTC, XAU, CL, BZ"; setText(app.preferences.watchlistDraft); setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2; maxLines = 5; gravity = android.view.Gravity.TOP
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (loadingInput) return
                draftRevision++
                app.preferences.watchlistDraft = s.toString()
                input.removeCallbacks(autoSave); input.postDelayed(autoSave, 900)
                if (::saveStatus.isInitialized) { saveStatus.text = "草稿已自动保存 · 稍后校验并应用"; saveStatus.setTextColor(Colors.muted) }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val controls = row()
        controls.addView(button("填入示例") { input.setText("ETH, BTC, XAU, CL, BZ") }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        save = button("立即应用", true) { input.removeCallbacks(autoSave); saveInput() }; controls.addView(save, LinearLayout.LayoutParams(0, -2, 1f)); watchlist.addView(controls)
        saveStatus = text("XAU 黄金 · CL WTI 原油 · BZ 布伦特原油", 11f, Colors.muted); watchlist.space(12); watchlist.addView(saveStatus)

        val tracking = content.section("后台盯盘", "在通知栏持续显示最新报价")
        permissionStatus = text("", 12f, Colors.muted); serviceStatus = text("", 12f, Colors.muted)
        tracking.addView(permissionStatus); tracking.space(7); tracking.addView(serviceStatus)
        // Starting resumes the saved display mode; only a running service offers "notification only".
        start = tracking.action("开启盯盘", true) {
            enableTracking(if (OverlayService.running) OverlayService.NOTIFICATION_ONLY else when (app.preferences.trackingMode) {
                "top" -> OverlayService.TOP_PRICE; "floating" -> OverlayService.START; else -> OverlayService.NOTIFICATION_ONLY
            })
        }
        tracking.action("停止盯盘") { BackgroundTracking.stop(this); window.decorView.postDelayed({ refreshControls() }, 200) }
        tracking.note("开启状态与显示方式会自动保存，重开应用后恢复；主动停止后不会自行开启。")
        tracking.toggle("重启手机后恢复盯盘", app.preferences.resumeOnBoot, current = { app.preferences.resumeOnBoot }) { app.preferences.resumeOnBoot = it }
        powerStatus = text("", 12f, Colors.muted); tracking.addView(powerStatus)
        tracking.actions("电池优化设置" to { openSetting(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), "请在系统电池设置中允许哨灯后台运行") },
            "应用后台权限" to { openSetting(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")), "请允许哨灯自启动和后台运行") })
        tracking.note("ColorOS 可允许后台活动 / 自启动，并在最近任务中锁定。系统强行停止后需手动打开应用。")

        val connection = content.section("行情连接", "自动重连 · Wi-Fi / 移动网络切换恢复", true, false)
        connectionStatus = text("", 12f, Colors.muted); connection.addView(connectionStatus)
        fun sourceLabel(id: String) = if (id == "auto") "自动" else MarketSource.of(id).label
        connection.choices("行情来源", listOf("auto" to "自动（推荐）", "gate" to "Gate", "binance" to "币安", "htx" to "HTX"), app.preferences.marketSource, 4, current = { app.preferences.marketSource }) { id ->
            connectionStatus.text = "正在切换到 ${sourceLabel(id)}，校验自选合约…"
            engine.switchSource(id) { missing, error ->
                if (isDestroyed) return@switchSource
                val message = error?.let { "未切换：$it" } ?: if (missing.isEmpty()) "已切换到 ${sourceLabel(id)}" else "已切换，${sourceLabel(id)} 没有：${missing.joinToString("、")}，这些将无报价"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show(); refreshControls()
            }
        }
        connection.note("自动：优先 Gate（国内通常可直连），连续失败改用币安（经新加坡中继），再不行用 HTX，并记住上次可用的来源。各交易所是各自的永续成交价，彼此略有差异。HTX 成交稀疏，按 HTTP 每 3 秒轮询，轮询期间暂停手机本地价格警报。")
        connection.choices("币安连接线路", listOf("auto" to "自动切换", "direct" to "币安直连", "relay" to "新加坡中继"), app.preferences.networkMode, 3,
            current = { app.preferences.networkMode }) { app.preferences.networkMode = it; engine.restart() }
        connection.toggle("WS 不通时使用 HTTP 报价兜底", app.preferences.restFallback, current = { app.preferences.restFallback }) { app.preferences.restFallback = it; engine.restart() }
        connection.note("线路只对币安生效。自动模式优先直连，失败切到你的新加坡服务器。中继只转发币安公开行情，手机无需设置代理。HTTP 快照约 5 秒更新，期间暂停价格警报，恢复 WS 后重新采集窗口。")
        connection.action("立即重连") { engine.restart() }
        content.action("配置与自动备份") { startActivity(Intent(this, ConfigActivity::class.java)) }

        val floating = content.section("悬浮与顶部文字", "显示方式、位置、大小与透明度", true, intent.getStringExtra("section") == "floating")
        floating.choices("显示方式", listOf("notification" to "仅通知", "floating" to "小悬浮条", "top" to "顶部纯价格"), app.preferences.trackingMode, 3, current = { app.preferences.trackingMode }) {
            enableTracking(when (it) { "top" -> OverlayService.TOP_PRICE; "floating" -> OverlayService.START; else -> OverlayService.NOTIFICATION_ONLY })
        }
        floating.addView(text("顶部 / 状态栏币种", 12f, Colors.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        barCoinBox = column(); floating.addView(barCoinBox); renderBarCoins()
        floating.toggle("多币时显示币种名", app.preferences.topSymbols, current = { app.preferences.topSymbols }) { app.preferences.topSymbols = it; refreshDisplay() }
        floating.choices("顶部每屏显示", (1..6).map { it.toString() to "$it 个" }, app.preferences.topPageSize.toString(), 6, current = { app.preferences.topPageSize.toString() }) { app.preferences.topPageSize = it.toInt(); refreshDisplay() }
        floating.choices("超出时轮播间隔", listOf(3, 4, 6, 10).map { it.toString() to "$it 秒" }, app.preferences.topRotateSeconds.toString(), 4, current = { app.preferences.topRotateSeconds.toString() }) { app.preferences.topRotateSeconds = it.toInt(); refreshDisplay() }
        floating.note("都不勾选时跟随当前币种。勾选数量不限，超过每屏数量时顶部按页轮播。状态栏图标选“每币一个”时最多显示前 ${TopPriceStyle.MAX_ICONS} 个。")
        floating.action("文字颜色与字体") { startActivity(Intent(this, StyleActivity::class.java)) }
        floating.note("顶部数字可拖动，长按关闭；系统状态栏可能遮挡浮窗，可用下方滑杆调整。")
        listOf("左右位置" to true, "距屏幕顶部" to false).forEach { (label, horizontal) ->
            val extent = if (horizontal) windowManager.currentWindowMetrics.bounds.width() else dp(160)
            floating.slider("顶部数字$label", 0, extent, (if (horizontal) app.preferences.x else app.preferences.y).coerceIn(0, extent), "", current = { if (horizontal) app.preferences.x else app.preferences.y }) {
                if (horizontal) app.preferences.x = it else app.preferences.y = it
                app.preferences.topPositionSet = true
                if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.POSITION))
            }
        }
        floating.slider("悬浮窗大小", 65, 125, app.preferences.overlayScalePercent, current = { app.preferences.overlayScalePercent }) {
            app.preferences.overlayScalePercent = it
            if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.RESIZE))
        }
        floating.slider("悬浮窗不透明度", 35, 100, app.preferences.overlayOpacityPercent, current = { app.preferences.overlayOpacityPercent }) {
            app.preferences.overlayOpacityPercent = it
            if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.OPACITY))
        }
        floating.choices("状态栏通知图标", listOf("price" to "当前币价格", "multi" to "每币一个", "off" to "仅图标"), app.preferences.statusIconMode, 3, current = { app.preferences.statusIconMode }) { app.preferences.statusIconMode = it; refreshNotification() }
        floating.note("数字通知图标只显示价格；行情过期显示 !，无报价显示 —。“每币一个”会为额外币种各发一条静默通知，开启流体云时不生效。")

        val lock = content.section("锁屏与胶囊", "锁屏报价、实时活动与流体云", true, false)
        lock.toggle("锁屏显示行情", app.preferences.lockscreenQuotes, current = { app.preferences.lockscreenQuotes }) { app.preferences.lockscreenQuotes = it; refreshNotification() }
        liveToggle = lock.toggle("尝试实时活动 / 流体云", app.preferences.liveUpdates, current = { app.preferences.liveUpdates }) { app.preferences.liveUpdates = it; refreshNotification(); refreshControls() }
        liveStatus = text("", 11f, Colors.muted); lock.addView(liveStatus)
        lock.note("开启后再启动盯盘；能否显示胶囊由系统决定。关闭开关即可退回普通通知。")
        lock.action("系统通知与锁屏设置") { openSetting(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName), "请在系统设置中打开哨灯的通知设置") }
        val desk = content.section("充电行情牌", "大字显示与充电屏保", true, false)
        desk.actions("打开大字行情" to { startActivity(Intent(this, DeskActivity::class.java)) }, "选择充电屏保" to { openSetting(Intent(Settings.ACTION_DREAM_SETTINGS), "此手机没有屏保入口，可使用大字行情") })
        desk.note("系统屏保中选择“哨灯充电行情屏保”。数字会缓慢移动；点一下退出。这是亮屏显示，尚未接入息屏 AOD。")
        val help = content.section("使用帮助", "后台连接与权限", true, false)
        help.note("开启盯盘后返回桌面，下拉通知查看行情。通知中的“切换币种”可切换首位报价。\n\n系统省电可能暂停网络；过期报价会明确标记。如需浮窗，先允许“显示在其他应用上层”。")
        refreshControls()
    }

    override fun onStart() { super.onStart(); engine.listen(listener); engine.acquire(this) }
    override fun onStop() { input.removeCallbacks(autoSave); saveInput(auto = true); engine.unlisten(listener); engine.release(this); super.onStop() }
    override fun onResume() {
        super.onResume()
        pendingStart?.takeIf { Settings.canDrawOverlays(this) && notificationsEnabled() }?.let { pendingStart = null; startTracking(it) }
        if (::input.isInitialized && input.text.toString() != app.preferences.watchlistDraft) {
            loadingInput = true; input.setText(app.preferences.watchlistDraft); loadingInput = false; draftRevision++
        }
        refreshControls()
    }

    private fun refreshControls() {
        if (!::permissionStatus.isInitialized) return
        val allowed = notificationsEnabled()
        permissionStatus.text = if (allowed) "● 通知权限已开启" else "○ 开启盯盘时需要允许通知"
        permissionStatus.setTextColor(if (allowed) Colors.green else Colors.orange)
        serviceStatus.text = if (OverlayService.running) {
            if (OverlayService.overlayVisible) "通知与小悬浮窗运行中" else "通知栏盯盘运行中 · 可返回桌面"
        } else if (app.preferences.trackingEnabled) "已保存开启 · 服务暂未运行" else "盯盘已关闭（已保存）"
        if (::connectionStatus.isInitialized) connectionStatus.text = engine.state.connectionLabel() + (engine.state.error?.let { "\n$it" } ?: "")
        if (::powerStatus.isInitialized) powerStatus.text = if (getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) "● 系统电池优化已豁免" else "○ 仍受系统电池优化限制"
        if (::liveStatus.isInitialized) {
            if (::liveToggle.isInitialized && liveToggle.isChecked != app.preferences.liveUpdates) liveToggle.isChecked = app.preferences.liveUpdates
            val manager = getSystemService(android.app.NotificationManager::class.java)
            val posted = manager.activeNotifications.firstOrNull { it.id == 7 }?.notification
            liveStatus.text = when {
                !app.preferences.liveUpdates -> "胶囊未开启"
                !OverlayService.running -> "已选择尝试胶囊，开启盯盘后生效"
                posted != null && posted.flags and android.app.Notification.FLAG_PROMOTED_ONGOING != 0 -> "系统已提升此通知；具体展示位置由系统决定"
                !runCatching { manager.canPostPromotedNotifications() }.getOrDefault(false) -> "系统暂未允许实时活动，请查看系统通知设置"
                else -> "已请求胶囊，当前仍是普通通知"
            }
        }
        start.text = if (OverlayService.running) "只在通知栏显示" else "开启盯盘 · " + when (app.preferences.trackingMode) { "top" -> "顶部纯价格"; "floating" -> "小悬浮条"; else -> "仅通知" }
        if (::barCoinBox.isInitialized && barCoinRows != app.preferences.coins) renderBarCoins()
    }

    private fun renderBarCoins() {
        barCoinRows = app.preferences.coins
        barCoinBox.removeAllViews()
        barCoinRows.forEach { coin ->
            barCoinBox.toggle(coin, coin in app.preferences.barCoins, current = { coin in app.preferences.barCoins }) { on ->
                val chosen = app.preferences.barCoins
                app.preferences.barCoins = if (on) chosen + coin else chosen - coin
                refreshDisplay()
            }
        }
    }
    private fun refreshDisplay() {
        if (!OverlayService.running) return
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.RESIZE))
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.REFRESH))
    }
    private fun refreshNotification() {
        if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.REFRESH))
    }
    private fun openSetting(intent: Intent, fallback: String) {
        runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, fallback, Toast.LENGTH_LONG).show() }
    }
    private fun notificationsEnabled(): Boolean {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        val channel = manager.getNotificationChannel("floating_quotes")
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled() && channel?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }

    private fun openOverlayPermission() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            .onFailure { Toast.makeText(this, "请在系统设置中搜索“显示在其他应用上层”，为哨灯开启。", Toast.LENGTH_LONG).show() }
    }
    private fun enableTracking(action: String) {
        if (action in listOf(OverlayService.START, OverlayService.TOP_PRICE) && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "小悬浮条需要悬浮权限；只看通知无需开启。", Toast.LENGTH_LONG).show()
            pendingStart = action; openOverlayPermission(); return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = action
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        } else if (!notificationsEnabled()) {
            Toast.makeText(this, "请开启 哨灯的行情通知。", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else startTracking(action)
    }
    @Deprecated("Framework permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 20) {
            val action = pendingStart
            pendingStart = null
            if (action != null && notificationsEnabled()) startTracking(action)
            else Toast.makeText(this, "未开启通知，盯盘尚未启动。", Toast.LENGTH_LONG).show()
            refreshControls()
        }
    }
    private fun startTracking(action: String) {
        try {
            startForegroundService(Intent(this, OverlayService::class.java).setAction(action))
            window.decorView.postDelayed({ refreshControls() }, 300)
        } catch (e: Exception) {
            Toast.makeText(this, "暂时无法开启：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun saveInput(auto: Boolean = false) {
        val draft = input.text.toString()
        val revision = draftRevision
        val parsed = runCatching { WatchlistInput.parse(draft) }
        if (parsed.getOrNull() == app.preferences.coins) {
            saveStatus.text = "已自动保存并应用"; saveStatus.setTextColor(Colors.green); return
        }
        saveStatus.text = "草稿已保存，正在校验永续合约…"; saveStatus.setTextColor(Colors.muted)
        engine.saveInput(draft, accept = { draftRevision == revision && app.preferences.watchlistDraft == draft }) { error ->
            if (isDestroyed || revision != draftRevision) return@saveInput
            if (error == null) {
                saveStatus.text = "已自动保存并应用，行情同步更新"; saveStatus.setTextColor(Colors.green)
                if (!auto) {
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                    input.clearFocus()
                }
            } else {
                saveStatus.text = "草稿已保存，暂未应用：$error"; saveStatus.setTextColor(Colors.orange)
            }
        }
    }
}
