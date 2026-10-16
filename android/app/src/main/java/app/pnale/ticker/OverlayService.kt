package app.pnale.ticker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {
    companion object {
        const val TEST_ALARM = "app.pnale.ticker.TEST_ALARM"
        const val RESTORE = "app.pnale.ticker.RESTORE"
        const val STOP_ALARM = "app.pnale.ticker.STOP_ALARM"
        const val ARM_ALARM = "app.pnale.ticker.ARM_ALARM"
        var alarmRuleStatuses: Map<String, String> = emptyMap()
            private set
        var alarmStatus = "警报未启用"
            private set
        const val TOP_PRICE = "app.pnale.ticker.TOP_PRICE"
        const val POSITION = "app.pnale.ticker.POSITION"
        const val UNPIN = "app.pnale.ticker.UNPIN"
        const val START = "app.pnale.ticker.START"
        const val NOTIFICATION_ONLY = "app.pnale.ticker.NOTIFICATION_ONLY"
        const val NEXT = "app.pnale.ticker.NEXT"
        const val RESIZE = "app.pnale.ticker.RESIZE"
        const val REFRESH = "app.pnale.ticker.REFRESH"
        const val OPACITY = "app.pnale.ticker.OPACITY"
        const val STOP = "app.pnale.ticker.STOP"
        const val TOGGLE = "app.pnale.ticker.TOGGLE"
        private const val CHANNEL = "floating_quotes"
        private const val NOTIFICATION = 7
        var running = false
            private set
        var overlayVisible = false
            private set
    }
    private val app get() = application as PnaleApp
    private val engine get() = app.market
    private val preferences get() = app.preferences
    private lateinit var wm: WindowManager
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var panel: MarketPanel? = null
    private var compactPrice: TextView? = null
    private var compactTitle: TextView? = null
    private var lastNotification = 0L
    private var topCoinsBuilt = emptyList<List<String>>()
    private var topPage = 0
    private val rotate = object : Runnable {
        override fun run() {
            val pages = TopPriceStyle.pages(preferences, engine.state).size
            if (compactPrice == null || !preferences.topPrice || pages < 2) return
            topPage = (topPage + 1) % pages
            compactPrice?.text = TopPriceStyle.label(preferences, engine.state, page = topPage)
            compactPrice?.postDelayed(this, preferences.topRotateSeconds * 1000L)
        }
    }
    private var coinNotificationIds = emptySet<Int>()
    private var lastServerPoll = 0L
    private var attached = false
    private var testOnly = false
    private val alarmPrefs by lazy { AlarmPreferences(this) }
    private val alarmBook = AlarmRuleBook()
    private lateinit var alarm: AlarmController
    private val listener: (MarketState) -> Unit = { state ->
        if (root != null && !Settings.canDrawOverlays(this)) removeOverlay()
        run {
            panel?.render(state)
            compactTitle?.text = "${state.selected} 永续 · ${state.displayStatus()}"
            // Adding or removing a bar coin changes the window width.
            if (preferences.topPrice && preferences.compact && root != null && TopPriceStyle.pages(preferences, state) != topCoinsBuilt) buildOverlay()
            compactPrice?.text = if (preferences.topPrice) TopPriceStyle.label(preferences, state, page = topPage) else state.quote?.let { Prices.format(it.price) } ?: "—"
            compactPrice?.let { if (preferences.topPrice) TopPriceStyle.apply(it, preferences, state) else it.setTextColor(if (state.quote?.stale() == false) Color.WHITE else Colors.orange) }
            val rules = alarmPrefs.rules
            val now = System.currentTimeMillis()
            // In server mode the server evaluates; evaluating here too would ring twice.
            val serverMode = app.serverAlarms.prefs.enabled
            val hits = if (serverMode) emptyList() else alarmBook.evaluate(rules, state, now)
            if (serverMode) {
                if (SystemClock.elapsedRealtime() - lastServerPoll > 5_000) { lastServerPoll = SystemClock.elapsedRealtime(); app.serverAlarms.poll(::serverFired) }
                alarmRuleStatuses = emptyMap()
                alarmStatus = "${rules.count { it.enabled }} 条规则由服务器监测" + app.serverAlarms.status.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
            } else {
                alarmRuleStatuses = alarmBook.statuses
                alarmStatus = if (rules.any { it.enabled }) "${rules.count { it.enabled }} 条规则监测中" else "警报未启用"
            }
            if (hits.isNotEmpty()) {
                val ids = hits.map { it.first.id }.toSet()
                // Persist one-shot disarm/cooldown before any sound is started.
                alarmPrefs.rules = rules.map { if (it.id in ids) it.afterTrigger(now) else it }
                val event = PriceAlarmEvent(hits.joinToString(" · ") { it.second.title }, hits.joinToString("\n") { "${it.first.name}：${it.second.detail}" })
                alarmPrefs.lastEvent = "${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} ${event.title} · ${event.detail}"
                val delivery = alarmPrefs.delivery(hits.any { it.first.sound }, hits.any { it.first.vibration })
                alarm.start(event, hits.maxOf { it.first.duration }, delivery.sound, delivery.vibration)
                alarmStatus = "已触发 ${hits.size} 条警报"
            }
            if (SystemClock.elapsedRealtime() - lastNotification > 5_000) {
                lastNotification = SystemClock.elapsedRealtime()
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                    getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
                    getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
                    postNotifications()
                } else stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        alarm = AlarmController(this) { if (testOnly) stopSelf() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "实时合约行情", NotificationManager.IMPORTANCE_LOW).apply { description = "通知栏报价、涨跌和停止入口" })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if ((intent == null || intent.action == RESTORE) && !preferences.trackingEnabled && intent?.getBooleanExtra("userStart", false) != true) {
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == STOP_ALARM) {
            alarm.stop()
            if (!running || testOnly) stopSelf()
            return restartMode()
        }
        if (intent?.action == TEST_ALARM) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || !getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                if (!running) stopSelf()
                return START_NOT_STICKY
            }
            if (!running) {
                testOnly = true
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                running = true
            }
            val delivery = alarmPrefs.delivery(intent.getBooleanExtra("sound", true), intent.getBooleanExtra("vibration", true))
            alarm.start(PriceAlarmEvent("警报试听", "当前${delivery.label} · 10 秒后自动结束"), 10, delivery.sound, delivery.vibration)
            if (!AlarmController.ringing && testOnly) stopSelf()
            return restartMode()
        }
        if (intent?.action == ARM_ALARM) {
            testOnly = false
            preferences.trackingEnabled = true
            alarm.stop(false)
            alarmBook.reset()
            alarmStatus = "等待实时数据"
            if (running && attached) { listener(engine.state); return restartMode() }
        }
        if (intent?.action == POSITION) {
            if (preferences.topPrice && root != null) {
                params?.let { it.x = savedX; it.y = savedY }
                clampAndSave()
            }
            if (!running) stopSelf()
            return restartMode()
        }
        if (intent?.action == UNPIN) {
            preferences.liveUpdates = false
            if (running) postNotifications() else stopSelf()
            return restartMode()
        }
        if (intent?.action == OPACITY) {
            params?.alpha = preferences.overlayOpacityPercent / 100f
            root?.let { view -> params?.let { wm.updateViewLayout(view, it) } }
            if (!running) stopSelf()
            return restartMode()
        }
        // Display-only actions must never start tracking that the user has stopped.
        if (intent?.action in listOf(REFRESH, NEXT, TOGGLE) && !running) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == REFRESH) {
            compactPrice?.let { if (preferences.topPrice) TopPriceStyle.apply(it, preferences, engine.state) }
            postNotifications()
            return restartMode()
        }
        if (intent?.action == RESIZE) {
            if (root != null) buildOverlay()
            if (!running) stopSelf()
            return restartMode()
        }
        if (intent?.action == NEXT) {
            val coins = engine.state.coins
            engine.select(coins[(coins.indexOf(engine.state.selected) + 1) % coins.size])
            return restartMode()
        }
        if (intent?.action == STOP) { preferences.trackingEnabled = false; stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == TOGGLE) { if (root != null) toggleCompact(); return restartMode() }
        try {
            testOnly = false
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            val mode = when (intent?.action) { START -> "floating"; TOP_PRICE -> "top"; NOTIFICATION_ONLY -> "notification"; else -> preferences.trackingMode }
            preferences.trackingMode = mode
            preferences.trackingEnabled = true
            preferences.topPrice = mode == "top"
            if (mode != "notification" && Settings.canDrawOverlays(this)) {
                if (preferences.topPrice && !preferences.topPositionSet) {
                    val metrics = wm.currentWindowMetrics
                    val statusHeight = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
                    preferences.x = metrics.bounds.width() / 2
                    preferences.y = ((statusHeight - dp(28) * preferences.overlayScalePercent / 100) / 2).coerceAtLeast(0)
                    preferences.topPositionSet = true
                }
                if (intent?.action in listOf(START, TOP_PRICE)) preferences.compact = true
                buildOverlay()
            } else removeOverlay()
            if (!attached) {
                attached = true
                engine.listen(listener)
                engine.acquire(this)
            }
            running = true
        } catch (e: Exception) {
            Toast.makeText(this, "盯盘开启失败：${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return restartMode()
    }

    private fun restartMode() = if (preferences.trackingEnabled && !testOnly) START_STICKY else START_NOT_STICKY
    private fun hideOverlay() { preferences.trackingMode = "notification"; removeOverlay() }

    /** Rings for rules the server fired, with each rule's own sound, vibration and duration settings. */
    private fun serverFired(events: List<ServerAlarmEvent>) {
        if (!running) return
        val now = System.currentTimeMillis()
        val rules = alarmPrefs.rules
        val hits = events.mapNotNull { e -> rules.firstOrNull { it.id == e.ruleId }?.let { it to e } }
        if (hits.isEmpty()) return
        // Mirror the server's one-shot disarm locally so the next sync keeps it off.
        val ids = hits.map { it.first.id }.toSet()
        alarmPrefs.rules = rules.map { if (it.id in ids) it.afterTrigger(hits.first { h -> h.first.id == it.id }.second.observed) else it }
        val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        val fresh = hits.filter { now - it.second.observed < ServerAlarms.RING_WINDOW_MS }
        alarmPrefs.lastEvent = hits.joinToString("\n") { (_, e) -> "${time.format(java.util.Date(e.observed))} ${e.title} · ${e.detail}" + if (e in fresh.map { it.second }) "" else "（手机离线，未响铃）" }
        if (fresh.isEmpty()) return
        val event = PriceAlarmEvent(fresh.joinToString(" · ") { it.second.title }, fresh.joinToString("\n") { "${it.first.name}：${it.second.detail}" })
        val delivery = alarmPrefs.delivery(fresh.any { it.first.sound }, fresh.any { it.first.vibration })
        alarm.start(event, fresh.maxOf { it.first.duration }, delivery.sound, delivery.vibration)
        alarmStatus = "服务器触发 ${fresh.size} 条警报"
    }

    private val topWindow get() = preferences.topPrice && preferences.compact
    private var savedX: Int
        get() = if (topWindow) preferences.x else preferences.floatX
        set(value) { if (topWindow) preferences.x = value else preferences.floatX = value }
    private var savedY: Int
        get() = if (topWindow) preferences.y else preferences.floatY
        set(value) { if (topWindow) preferences.y = value else preferences.floatY = value }

    private fun postNotifications() {
        val manager = getSystemService(NotificationManager::class.java)
        val state = engine.state
        manager.notify(NOTIFICATION, notification())
        // The status bar shows one small icon per notification, so each extra coin gets its own quiet notification.
        val extra = if (preferences.statusIconMode == "multi" && !preferences.liveUpdates) TopPriceStyle.coins(preferences, state).take(TopPriceStyle.MAX_ICONS).drop(1) else emptyList()
        val ids = extra.mapIndexed { i, coin -> (NOTIFICATION + 100 + i).also { manager.notify(it, coinNotification(state, coin, i)) } }.toSet()
        (coinNotificationIds - ids).forEach(manager::cancel)
        coinNotificationIds = ids
    }

    private fun coinNotification(state: MarketState, coin: String, index: Int): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val quote = state.quotes[coin + "USDT"]
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(StatusIcon.coin(state, coin, preferences.topFont))
            .setContentTitle(quote?.let { "$coin ${Prices.format(it.price)}" } ?: "$coin 等待报价")
            .setContentText(quote?.let { Prices.change(it.change) + " 24H" + if (it.stale()) " · 已过期" else "" } ?: state.displayStatus())
            .setSubText("状态栏 · $coin")
            .setVisibility(if (preferences.lockscreenQuotes) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_STATUS).setSortKey("coin$index")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build()
    }

    private fun notification(): Notification {
        val state = engine.state
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, OverlayService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = PendingIntent.getService(this, 3, Intent(this, OverlayService::class.java).setAction(NEXT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hide = PendingIntent.getService(this, 4, Intent(this, OverlayService::class.java).setAction(NOTIFICATION_ONLY), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val unpin = PendingIntent.getService(this, 5, Intent(this, OverlayService::class.java).setAction(UNPIN), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val repost = PendingIntent.getService(this, 6, Intent(this, OverlayService::class.java).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ordered = listOf(state.selected) + state.coins.filter { it != state.selected }
        fun row(coin: String): String {
            val quote = state.quotes[coin + "USDT"] ?: return "$coin  等待报价"
            return "$coin  ${Prices.format(quote.price)}" + (if (preferences.notificationChanges) "  ${Prices.change(quote.change)}" else "") + if (quote.stale()) " · 已过期" else ""
        }
        val title = ordered.take(if (preferences.notificationDetail == "single") 1 else 2).joinToString("  ·  ") { coin ->
            state.quotes[coin + "USDT"]?.let { "$coin ${Prices.format(it.price)}${if (it.stale()) " 过期" else ""}" } ?: "$coin —"
        }
        val count = when (preferences.notificationDetail) { "single" -> 1; "pair" -> 2; else -> 6 }
        val body = ordered.take(count).joinToString("\n", transform = ::row) +
            (if (ordered.size > count) "\n另 ${ordered.size - count} 个币种，点击查看" else "") + "\n${state.displayStatus()} · USDT 永续"
        return Notification.Builder(this, CHANNEL).apply {
            if (preferences.liveUpdates) {
                when (preferences.chipLayout) {
                    "symbol_price", "symbol_change" -> setSmallIcon(StatusIcon.create(state, "symbol"))
                    "price_change" -> setSmallIcon(StatusIcon.create(state, "price"))
                    else -> setSmallIcon(R.drawable.ic_notification)
                }
            } else if (preferences.statusIconMode == "off") setSmallIcon(R.drawable.ic_notification)
            else if (preferences.statusIconMode == "multi") setSmallIcon(StatusIcon.coin(state, TopPriceStyle.coins(preferences, state).first(), preferences.topFont))
            else setSmallIcon(StatusIcon.create(state, preferences.statusIconMode, preferences.topFont))
        }
            .setContentTitle(title).setContentText("${row(state.selected)} · ${state.displayStatus()}")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setVisibility(if (preferences.lockscreenQuotes) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_SECRET)
            // Android 14+ lets users swipe away this notification; re-post it instead of silently turning tracking off.
            .setDeleteIntent(repost)
            .apply {
                if (preferences.liveUpdates) {
                    // Documented 36.1 extra; older Android 16 builds may ignore it.
                    addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
                    val price = DisplayStyle.chipRight(state, preferences.chipLayout, preferences.chipDecimals)
                    if (price.length <= 7) setShortCriticalText(price)
                    // Do not truncate a numeric price into a different value.
                }
            }
            .setSubText("合约盯盘").setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "切换币种", next).build())
            .addAction(Notification.Action.Builder(null, if (preferences.liveUpdates) "取消胶囊" else "只留通知", if (preferences.liveUpdates) unpin else hide).build())
            .addAction(Notification.Action.Builder(null, "停止盯盘", stop).build()).build()
    }

    private fun removeOverlay() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null; panel = null; compactPrice = null; compactTitle = null
        overlayVisible = false
    }

    private fun icon(label: String, description: String, action: () -> Unit) = text(label, 20f, Colors.muted).apply {
        gravity = Gravity.CENTER; contentDescription = description
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }

    private fun buildOverlay() {
        root?.let { runCatching { wm.removeView(it) } }
        panel = null; compactPrice = null; compactTitle = null
        val compact = preferences.compact
        val topPrice = preferences.topPrice && compact
        val container = column().apply {
            background = if (topPrice) null else if (preferences.boardStyle == "glass") android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xC54B6677.toInt(), 0xB51E2C42.toInt(), 0xC338354F.toInt())
            ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0x90FFFFFF.toInt()) } else rounded(Colors.background, 16)
            elevation = if (topPrice) 0f else dp(8).toFloat()
            clipToOutline = true
        }
        val drag: View
        if (topPrice) {
            compactPrice = text("—", 15f, bold = true).apply {
                gravity = Gravity.CENTER
                setTag(R.id.ui_custom_font, true)
                maxLines = 1
                TopPriceStyle.apply(this, preferences, engine.state)
                text = TopPriceStyle.label(preferences, engine.state, page = topPage)
                contentDescription = "顶部价格，拖动调整位置，长按关闭"
                setOnLongClickListener { hideOverlay(); true }
            }
            container.addView(compactPrice, LinearLayout.LayoutParams(-1, dp(preferences.topTextSize + 14)))
            drag = compactPrice!!
        } else if (compact) {
            val line = row().apply { setPadding(dp(13), dp(6), dp(5), dp(6)) }
            val priceArea = column()
            compactTitle = text("", 9f, Colors.green)
            compactPrice = text("—", 20f, bold = true)
            priceArea.addView(compactTitle); priceArea.space(3); priceArea.addView(compactPrice)
            line.addView(priceArea, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(icon("↗", "展开悬浮窗") { toggleCompact() }, LinearLayout.LayoutParams(dp(38), dp(44)))
            line.addView(icon("×", "收起到通知栏") { hideOverlay() }, LinearLayout.LayoutParams(dp(34), dp(44)))
            container.addView(line)
            drag = priceArea
        } else {
            val header = row().apply { setPadding(dp(15), dp(2), dp(4), 0) }
            val title = text("哨灯  PANEL", 12f, Colors.green, true).apply { gravity = Gravity.CENTER_VERTICAL; contentDescription = "拖动悬浮窗" }
            header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
            header.addView(icon("⋯", "编辑币种") {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }, LinearLayout.LayoutParams(dp(40), dp(44)))
            header.addView(icon("−", "收起悬浮窗") { toggleCompact() }, LinearLayout.LayoutParams(dp(40), dp(44)))
            header.addView(icon("×", "收起到通知栏") { hideOverlay() }, LinearLayout.LayoutParams(dp(36), dp(44)))
            container.addView(header)
            container.addView(View(this).apply { setBackgroundColor(Colors.line) }, LinearLayout.LayoutParams(-1, dp(1)))
            panel = MarketPanel(this, engine)
            container.addView(panel)
            val sizeControls = row()
            sizeControls.addView(icon("−", "缩小悬浮窗") { resizeBy(-5) }, LinearLayout.LayoutParams(dp(48), dp(36)))
            sizeControls.addView(text("大小 ${preferences.overlayScalePercent}%", 11f, Colors.muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(36), 1f))
            sizeControls.addView(icon("+", "放大悬浮窗") { resizeBy(5) }, LinearLayout.LayoutParams(dp(48), dp(36)))
            container.addView(sizeControls)
            drag = title
        }
        val bounds = screenBounds()
        topCoinsBuilt = if (topPrice) TopPriceStyle.pages(preferences, engine.state) else emptyList()
        if (topPage >= topCoinsBuilt.size) topPage = 0
        compactPrice?.let { it.removeCallbacks(rotate); if (topCoinsBuilt.size > 1) it.postDelayed(rotate, preferences.topRotateSeconds * 1000L) }
        val frame = OverlayFrame(this, if (topPrice) TopPriceStyle.width(compactPrice!!, preferences, engine.state) else dp(if (compact) 230 else 340), preferences.overlayScalePercent / 100f,
            bounds.first - dp(16), bounds.second - dp(16)).apply { addView(container) }
        frame.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = frame.measuredWidth
        val layout = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceIn(0, (bounds.first - width).coerceAtLeast(0))
            y = savedY.coerceIn(0, (bounds.second - dp(if (compact) 70 else 440)).coerceAtLeast(0))
            title = "哨灯 Panel 悬浮行情"
            if (topPrice) {
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            alpha = preferences.overlayOpacityPercent / 100f
        }
        root = frame; params = layout
        enableDrag(drag)
        wm.addView(frame, layout)
        overlayVisible = true
        frame.post { clampAndSave() }
        listener(engine.state)
    }

    private fun resizeBy(delta: Int) {
        preferences.overlayScalePercent += delta
        buildOverlay()
    }

    private fun toggleCompact() {
        preferences.compact = !preferences.compact
        buildOverlay()
    }

    private fun enableDrag(view: View) {
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            val layout = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; initialX = layout.x; initialY = layout.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                    if (moved) { layout.x = initialX + dx.toInt(); layout.y = initialY + dy.toInt(); clampAndSave(save = false) }
                    true
                }
                MotionEvent.ACTION_UP -> { clampAndSave(); if (!moved) { if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) view.performLongClick() else view.performClick() }; true }
                MotionEvent.ACTION_CANCEL -> { clampAndSave(); true }
                else -> false
            }
        }
    }
    private fun screenBounds(): Pair<Int, Int> {
        val metrics = wm.currentWindowMetrics
        if (preferences.topPrice && preferences.compact) return Pair(metrics.bounds.width(), metrics.bounds.height())
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        return Pair(metrics.bounds.width() - insets.left - insets.right, metrics.bounds.height() - insets.top - insets.bottom)
    }

    private fun clampAndSave(save: Boolean = true) {
        val view = root ?: return
        val layout = params ?: return
        val (width, height) = screenBounds()
        layout.x = layout.x.coerceIn(0, (width - view.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(0, (height - view.height.coerceAtLeast(dp(60))).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(view, layout) }
        if (save) { savedX = layout.x; savedY = layout.y }
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); if (root != null) buildOverlay() }
    override fun onDestroy() {
        running = false
        if (::alarm.isInitialized) alarm.stop(false)
        alarmBook.reset(); alarmStatus = "监测已停止"
        if (attached) { engine.unlisten(listener); engine.release(this); attached = false }
        removeOverlay()
        coinNotificationIds.forEach(getSystemService(NotificationManager::class.java)::cancel); coinNotificationIds = emptySet()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
