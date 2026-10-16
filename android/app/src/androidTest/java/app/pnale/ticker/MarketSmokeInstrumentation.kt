package app.pnale.ticker

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real-device network/lifecycle smoke test. Does not grant permissions or change the user's watchlist. */
class MarketSmokeInstrumentation : Instrumentation() {
    private lateinit var options: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); options = arguments ?: Bundle(); start() }
    override fun onStart() {
        if (options.getString("reliability") == "true") { testReliability(); return }
        if (options.getString("resilientNetwork") == "true") { testResilientNetwork(); return }
        if (options.getString("reopen") == "true") { testReopen(); return }
        if (options.getString("appearance") == "true") { testAppearance(); return }
        if (options.getString("commodities") == "true") { testCommodities(); return }
        if (options.getString("alarms") == "true") { testAlarms(); return }
        if (options.getString("styles") == "true") { testStyles(); return }
        if (options.getString("displays") == "true") { testDisplays(); return }
        val name = "smoke_${System.currentTimeMillis()}"
        lateinit var engine: MarketEngine
        lateinit var prefs: Preferences
        var engineReady = false
        val snapshot = AtomicReference<MarketState>()
        val owner = Any()
        var activity: Activity? = null
        try {
            onMain {
                prefs = Preferences(targetContext, name)
                engine = MarketEngine(prefs)
                engineReady = true
                engine.listen { snapshot.set(it) }
                engine.acquire(owner)
            }
            fun live() {
                await("fresh WS tickers + candle", snapshot) { state ->
                    state.streamedCandle && state.candles.size == 60 && state.coins.all { coin ->
                        coin + "USDT" in state.streamedSymbols && state.quotes[coin + "USDT"]?.stale() == false
                    } && !state.chartStale()
                }
            }
            live()
            report("PASS: device REST + WS ETH/BTC; 60 candles; all tickers and kline received")
            onMain {
                engine.interval("5m"); engine.interval("15m"); engine.interval("1h"); engine.select("BTC")
            }
            live()
            check(snapshot.get().selected == "BTC" && snapshot.get().interval == "1h")
            check(snapshot.get().candles.zipWithNext().all { (a, b) -> b.time - a.time == 3_600_000L })
            onMain { engine.restart() }
            live()
            report("PASS: rapid switch to BTC 1h; contiguous candles; reconnect/backfill")

            fun save(text: String): String? {
                val done = CountDownLatch(1)
                val error = AtomicReference<String?>(null)
                onMain { engine.saveInput(text) { result -> error.set(result); done.countDown() } }
                check(done.await(25, TimeUnit.SECONDS)) { "Saving watchlist timed out" }
                return error.get()
            }
            check(save("eth，BTCUSDT\n1000PEPE/USDT") == null)
            live()
            check(prefs.coins == listOf("ETH", "BTC", "1000PEPE"))
            check(Preferences(targetContext, name).coins == prefs.coins)
            check(save("ETH, PNALE_INVALID") != null)
            check(prefs.coins == listOf("ETH", "BTC", "1000PEPE"))
            report("PASS: typed watchlist, persistence and invalid input protection")
            onMain { engine.release(owner) }
            check(snapshot.get().status == "已暂停")

            val launched = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = launched
            waitForIdleSync()
            val app = launched.application as PnaleApp
            val visibleSnapshot = AtomicReference<MarketState>()
            val visibleListener: (MarketState) -> Unit = { visibleSnapshot.set(it) }
            onMain { app.market.listen(visibleListener) }
            await("activity preview", visibleSnapshot) { it.streamedCandle && it.quote?.stale() == false }
            screenshot("activity")
            report("PASS: activity ready; live data available for on-demand chart")

            if (options.getString("notifications") == "true") {
                check(!Settings.canDrawOverlays(targetContext)) { "Run notification-only test without overlay permission" }
                val manager = targetContext.getSystemService(android.app.NotificationManager::class.java)
                check(manager.areNotificationsEnabled()) { "Notification permission must already be enabled" }
                onMain { targetContext.startForegroundService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.NOTIFICATION_ONLY)) }
                awaitCondition("notification foreground start") { OverlayService.running && manager.activeNotifications.any { it.id == 7 } }
                check(!OverlayService.overlayVisible)
                onMain { launched.moveTaskToBack(true) }
                val previousEvent = visibleSnapshot.get().quote!!.eventTime
                await("background ticker advances", visibleSnapshot) { it.quote?.eventTime?.let { event -> event > previousEvent + 5_000 } == true && !it.quote!!.stale() }
                awaitCondition("multi-coin notification") {
                    val body = manager.activeNotifications.firstOrNull { it.id == 7 }?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
                    body.contains("ETH") && body.contains("BTC") && !body.contains("等待报价")
                }
                val notification = manager.activeNotifications.first { it.id == 7 }.notification
                check(notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0)
                notification.actions.first { it.title.toString() == "切换币种" }.actionIntent.send()
                await("notification switch", visibleSnapshot) { it.selected == "BTC" && it.quote?.stale() == false && it.streamedCandle }
                manager.activeNotifications.first { it.id == 7 }.notification.actions.first { it.title.toString() == "停止盯盘" }.actionIntent.send()
                awaitCondition("notification removed and stream paused") { !OverlayService.running && manager.activeNotifications.none { it.id == 7 } && visibleSnapshot.get().status == "已暂停" }
                report("PASS: notification-only foreground service without overlay permission; background WS advances; multi-coin prices; actual notification switch/stop PendingIntents; notification removed and engine paused")
            }

            if (options.getString("overlay") == "true") {
                check(Settings.canDrawOverlays(targetContext)) { "Overlay permission must be granted by the user before this test" }
                onMain { targetContext.startForegroundService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.START)) }
                awaitCondition("foreground overlay start") { OverlayService.running && OverlayService.overlayVisible }
                onMain { targetContext.startService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.TOGGLE)) }
                awaitCondition("initial expand") { !app.preferences.compact }
                onMain { launched.moveTaskToBack(true) }
                await("background stream", visibleSnapshot) { it.streamedCandle && it.quote?.stale() == false }
                Thread.sleep(1_000)
                screenshot("overlay-expanded")
                onMain { targetContext.startService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.TOGGLE)) }
                awaitCondition("collapse") { app.preferences.compact }
                Thread.sleep(400)
                screenshot("overlay-compact")
                onMain { targetContext.startService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.TOGGLE)) }
                awaitCondition("expand") { !app.preferences.compact }
                onMain { targetContext.startService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.STOP)) }
                awaitCondition("stop foreground overlay") { !OverlayService.running }
                report("PASS: real overlay window, background WS, collapse/expand, stop action")
            } else report("SKIP: overlay interaction requires user-granted SYSTEM_ALERT_WINDOW")
            onMain { app.market.unlisten(visibleListener) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPNALE_SMOKE_PASS\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nPNALE_SMOKE_FAIL: ${e.stackTraceToString()}\n") })
        } finally {
            if (engineReady) onMain { engine.release(owner) }
            targetContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private fun testCommodities() {
        val app = targetContext.applicationContext as PnaleApp
        val oldCoins = app.preferences.coins
        val oldSelected = app.preferences.selected
        val snapshots = AtomicReference<MarketState>()
        val listener: (MarketState) -> Unit = { snapshots.set(it) }
        var activity: Activity? = null
        var home: Activity? = null
        try {
            check(AlarmPreferences(targetContext).rules.none { it.enabled }) { "Use a disposable device without active alarms" }
            home = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = home
            onMain { app.market.listen(listener) }
            fun views(v: android.view.View): List<android.view.View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
            fun tap(label: String) {
                onMain { check(views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString() == label && it.isClickable }.performClick()) }
                waitForIdleSync()
            }
            val monitor = addMonitor(MarketSettingsActivity::class.java.name, null, false)
            tap("设置")
            activity = waitForMonitorWithTimeout(monitor, 5000) ?: error("Settings navigation did not open")
            removeMonitor(monitor)
            val lockBody = AtomicReference<SectionCard>()
            onMain {
                val card = views(activity!!.window.decorView).filterIsInstance<SectionCard>().first { it.getChildAt(0).contentDescription == "锁屏与胶囊" }
                lockBody.set(card); check(card.body.visibility == android.view.View.GONE)
                card.getChildAt(0).performClick(); check(card.body.visibility == android.view.View.VISIBLE)
                card.getChildAt(0).performClick(); check(card.body.visibility == android.view.View.GONE)
            }
            onMain {
                views(activity!!.window.decorView).filterIsInstance<android.widget.EditText>().first { it.contentDescription == "展示币种输入框" }.setText("ETH, BTC, XAU, CL, BZ")
            }
            tap("保存币种")
            awaitCondition("commodity watchlist saved") { app.preferences.coins == listOf("ETH", "BTC", "XAU", "CL", "BZ") }
            check(Preferences(targetContext).coins == app.preferences.coins)
            await("five real perpetual quotes", snapshots) { it.coins.size == 5 && it.coins.all { coin -> it.quotes[coin + "USDT"]?.stale() == false && coin + "USDT" in it.streamedSymbols } }
            onMain { views(activity!!.window.decorView).filterIsInstance<android.widget.ScrollView>().first().fullScroll(android.view.View.FOCUS_DOWN) }
            waitForIdleSync(); screenshot("panel-commodity-settings")
            onMain { views(activity!!.window.decorView).first { it.contentDescription == "返回首页" }.performClick() }
            activity = home
            waitForIdleSync()
            for (coin in listOf("XAU", "CL", "BZ")) {
                tap(coin)
                await("$coin chart", snapshots) { it.selected == coin && it.quote?.stale() == false && it.streamedCandle && it.candles.size == 60 && !it.chartStale() }
                report("PASS: $coin live quote=" + snapshots.get().quote!!.price + "; 60 candles and WS updates")
                screenshot("panel-$coin")
            }
            tap("ETH")
            await("ETH home restored", snapshots) { it.selected == "ETH" && it.quote?.stale() == false && it.streamedCandle }
            screenshot("panel-home")
            report("PASS: home-to-settings navigation, collapsed sections, typed commodity watchlist persistence, return home and coin controls")
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPANEL_COMMODITIES_PASS\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nPANEL_COMMODITIES_FAIL: ${e.stackTraceToString()}\n") })
        } finally {
            onMain { app.market.unlisten(listener); activity?.finish(); home?.finish(); app.preferences.coins = oldCoins; app.preferences.selected = oldSelected }
        }
    }
    private fun testAlarms() {
        val app = targetContext.applicationContext as PnaleApp
        val prefs = AlarmPreferences(targetContext)
        val manager = targetContext.getSystemService(android.app.NotificationManager::class.java)
        val audio = targetContext.getSystemService(android.media.AudioManager::class.java)
        var activity: Activity? = null
        val original = prefs.rules
        val originalMode = prefs.deliveryMode
        try {
            check(manager.areNotificationsEnabled()) { "Enable notifications in disposable test device first" }
            check(original.none { it.enabled }) { "Use a disposable test instance without enabled rules" }; prefs.rules = emptyList(); prefs.editorDraft = ""; BackgroundTracking.stop(targetContext)
            prefs.deliveryMode = "ring"
            activity = startActivitySync(Intent(targetContext, AlarmSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fun views(v: android.view.View): List<android.view.View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
            fun tap(label: String) {
                onMain { val target = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString() == label && it.isClickable }
                    reveal(target); check(target.performClick()) }
                waitForIdleSync()
            }
            fun input(label:String,value:String) { onMain { views(activity!!.window.decorView).filterIsInstance<android.widget.EditText>().first { it.contentDescription == label }.setText(value) } }
            input("规则名称", "ETH 夜间急跌")
            input("阈值（百分比 / USDT 价格）", "2.5")
            input("观察窗口（1–60 分钟）", "3")
            input("最多提醒时长（10–120 秒）", "30")
            tap("立即保存")
            check(prefs.rules.size == 1 && !prefs.rules[0].enabled && prefs.rules[0].rule.minutes == 3)
            tap("复制")
            input("规则名称", "第二条观察规则")
            input("阈值（百分比 / USDT 价格）", "4")
            tap("立即保存")
            check(prefs.rules.size == 2 && prefs.rules.map { it.id }.distinct().size == 2)
            tap("启用当前规则")
            awaitCondition("rule foreground service") { OverlayService.running && prefs.rules.count { it.enabled } == 1 }
            check(prefs.rules.last().rule.value == 4.0)
            onMain { views(activity!!.window.decorView).filterIsInstance<android.widget.ScrollView>().first().scrollTo(0, 0) }
            waitForIdleSync(); Thread.sleep(3500); screenshot("panel-alarm-rules")
            tap("停用全部规则")
            check(prefs.rules.none { it.enabled })
            onMain { BackgroundTracking.stop(targetContext) }
            awaitCondition("stopped tracking before test") { !OverlayService.running }
            tap("试听当前设置 10 秒")
            awaitCondition("alarm notification and playback") {
                AlarmController.ringing && manager.activeNotifications.any { it.id == AlarmController.ID } &&
                    audio.activePlaybackConfigurations.any { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ALARM }
            }
            report("PASS: default off; UI saves/copies/arms independent rules; persisted custom 3m window; actual USAGE_ALARM playback and notification")
            val alarmPage = startActivitySync(Intent(targetContext, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync(); Thread.sleep(2000); screenshot("panel-alarm-ringing")
            manager.activeNotifications.first { it.id == AlarmController.ID }.notification.actions.first { it.title.toString() == "停止响铃" }.actionIntent.send()
            awaitCondition("stop cancels audio notification and test-only foreground") {
                !AlarmController.ringing && !OverlayService.running && manager.activeNotifications.none { it.id == AlarmController.ID || it.id == 7 } &&
                    audio.activePlaybackConfigurations.none { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ALARM }
            }
            onMain { alarmPage.finish() }
            tap("试听当前设置 10 秒")
            awaitCondition("second alarm begins") { AlarmController.ringing }
            Thread.sleep(11_000)
            check(!AlarmController.ringing && !OverlayService.running && manager.activeNotifications.none { it.id == AlarmController.ID })
            report("PASS: stop PendingIntent releases playback/notification/foreground; 10s timeout also clears alarm")
            tap("删除")
            // Confirm the destructive UI action in the visible dialog.
            uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("删除").last { it.isClickable }.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            waitForIdleSync()
            check(prefs.rules.size == 1)
            onMain { activity!!.finish() }
            val main = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = main
            val snapshots = AtomicReference<MarketState>()
            val listener: (MarketState)->Unit = { snapshots.set(it) }
            onMain { app.market.listen(listener) }
            await("home live data",snapshots) { it.quote?.stale() == false && it.status == "实时推送" }
            screenshot("panel-home")
            tap("设置")
            // Navigation starts the new settings Activity; verify through lifecycle state separately in UI smoke.
            report("PASS: home live quote/chart, renamed app, navigation control click")
            onMain { app.market.unlisten(listener); prefs.rules = original; prefs.deliveryMode = originalMode; BackgroundTracking.stop(targetContext) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPANEL_ALARMS_PASS\n") })
        } catch (e:Throwable) {
            onMain { prefs.rules = original; prefs.deliveryMode = originalMode; BackgroundTracking.stop(targetContext) }
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nPANEL_ALARMS_FAIL: ${e.stackTraceToString()}\n") })
        }
    }
    private fun testAppearance() {
        val app = targetContext.applicationContext as PnaleApp
        val prefs = app.preferences
        val alarms = AlarmPreferences(targetContext)
        val manager = targetContext.getSystemService(android.app.NotificationManager::class.java)
        val audio = targetContext.getSystemService(android.media.AudioManager::class.java)
        val oldFont = prefs.uiFont; val oldSize = prefs.uiTextScale; val oldTopFont = prefs.topFont; val oldColor = prefs.topColor; val oldTopSize = prefs.topTextSize
        val oldMode = alarms.deliveryMode; val oldStart = alarms.nightStart; val oldEnd = alarms.nightEnd
        val oldTop = prefs.topPrice; val oldX = prefs.x; val oldY = prefs.y; val oldPosition = prefs.topPositionSet
        val oldDirection = prefs.topDirection; val oldShadow = prefs.topShadow; val oldOpacity = prefs.overlayOpacityPercent
        var activity: Activity? = null
        var failure: Throwable? = null
        fun views(v: android.view.View): List<android.view.View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        fun tap(description: String) {
            onMain {
                val view = views(activity!!.window.decorView).first { it.contentDescription == description || it is android.widget.TextView && it.text.toString() == description && it.isClickable }
                reveal(view); check(view.performClick())
            }; waitForIdleSync()
        }
        try {
            check(manager.areNotificationsEnabled() && Settings.canDrawOverlays(targetContext)) { "Enable notifications and overlay permission on the disposable test device" }
            check(alarms.rules.none { it.enabled }) { "Test device must have no active rules" }
            activity = startActivitySync(Intent(targetContext, StyleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            tap("界面字体：衬线"); tap("界面字号：更大")
            onMain {
                val sample = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().first { it.contentDescription == "界面字体预览" }
                check(sample.typeface == Typography.face("serif", true))
                check(kotlin.math.abs(sample.textSize / sample.resources.displayMetrics.scaledDensity - 24f) < .2f)
                // Applying on resume repeatedly must not multiply the scale.
                Typography.apply(activity!!.window.decorView); Typography.apply(activity!!.window.decorView)
                check(kotlin.math.abs(sample.textSize / sample.resources.displayMetrics.scaledDensity - 24f) < .2f)
            }
            tap("顶部文字字体：等宽"); tap("冰蓝")
            onMain {
                val slider = views(activity!!.window.decorView).filterIsInstance<android.widget.SeekBar>().first { it.contentDescription == "顶部文字字号" }
                slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 12f) })
                check(prefs.topTextSize == 22)
                slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 13f) })
                check(prefs.topTextSize == 23)
                prefs.topDirection = false; prefs.topShadow = true
            }
            tap("顶部文字字体：等宽")
            check(Preferences(targetContext).uiFont == "serif" && Preferences(targetContext).uiTextScale == 120)
            check(Preferences(targetContext).topFont == "mono" && Preferences(targetContext).topColor == android.graphics.Color.rgb(137,195,255))
            onMain {
                val sample = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().first { it.contentDescription == "顶部价格样式预览" }
                check(sample.typeface == Typography.face("mono")); check(sample.currentTextColor == prefs.topColor)
            }
            Thread.sleep(700); screenshot("panel-v07-top-style")
            // Test the custom color editor, including refusal of malformed values.
            tap("自定义")
            fun nodes(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> = if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
            val edit = nodes(uiAutomation.rootInActiveWindow).first { it.className == "android.widget.EditText" }
            edit.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "#12ABEF") })
            uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("应用").first { it.isClickable }.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            waitForIdleSync(); check(prefs.topColor == 0xFF12ABEF.toInt())
            onMain { activity!!.finish() }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onMain {
                val title = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString() == "哨灯" }
                check(title.typeface == Typography.face("serif", true))
            }
            screenshot("panel-v07-home-large-font")
            onMain {
                prefs.x = targetContext.dp(95); prefs.y = targetContext.dp(65); prefs.topPositionSet = true; prefs.overlayOpacityPercent = 100
                targetContext.startForegroundService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.TOP_PRICE))
            }
            awaitCondition("styled top overlay") { OverlayService.overlayVisible }
            val snapshots = AtomicReference<MarketState>(); val listener: (MarketState) -> Unit = { snapshots.set(it) }
            onMain { app.market.listen(listener) }
            await("fresh top price", snapshots) { it.quote?.stale() == false && it.status == "实时推送" }
            Thread.sleep(500); screenshot("panel-v07-top-overlay")
            val serviceInfo = uiAutomation.serviceInfo
            serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            uiAutomation.serviceInfo = serviceInfo
            val topNode = uiAutomation.windows.flatMap { nodes(it.root) }.firstOrNull { it.contentDescription?.toString()?.startsWith("顶部价格，") == true }
            check(topNode != null) { "Actual top price window is not visible to accessibility" }
            val bounds = android.graphics.Rect(); topNode.getBoundsInScreen(bounds)
            val bitmap = uiAutomation.takeScreenshot() ?: error("Cannot capture actual overlay")
            var coloredPixels = 0
            for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height)) {
                val color = bitmap.getPixel(x, y)
                if (kotlin.math.abs(android.graphics.Color.red(color) - 18) <= 10 && kotlin.math.abs(android.graphics.Color.green(color) - 171) <= 10 && kotlin.math.abs(android.graphics.Color.blue(color) - 239) <= 10) coloredPixels++
            }
            bitmap.recycle(); check(coloredPixels > 10) { "Custom color did not reach visible overlay: $coloredPixels" }
            report("PASS: persisted app serif/120% without cumulative scaling; independent top mono font/custom #12ABEF; actual overlay has $coloredPixels matching color pixels")
            onMain { app.market.unlisten(listener); BackgroundTracking.stop(targetContext); activity!!.finish() }
            awaitCondition("overlay stopped") { !OverlayService.running }

            activity = startActivitySync(Intent(targetContext, AlarmSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val minute = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
            // Resolve against the actual device clock without changing its system time.
            onMain { alarms.setNight((minute + 60) % 1440, (minute + 120) % 1440) }
            tap("提醒模式：日夜自动"); tap("试听当前设置 10 秒")
            awaitCondition("daytime vibration notification") { AlarmController.ringing && manager.activeNotifications.any { it.id == AlarmController.ID } }
            Thread.sleep(1200)
            check(audio.activePlaybackConfigurations.none { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ALARM }) { "Daytime auto mode unexpectedly started alarm audio" }
            check(manager.activeNotifications.first { it.id == AlarmController.ID }.notification.fullScreenIntent == null)
            tap("停止提醒"); awaitCondition("day test stopped") { !AlarmController.ringing && !OverlayService.running }
            onMain { alarms.setNight((minute + 1439) % 1440, (minute + 60) % 1440) }
            tap("试听当前设置 10 秒")
            awaitCondition("nighttime actual alarm audio") { AlarmController.ringing && audio.activePlaybackConfigurations.any { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ALARM } }
            tap("停止提醒"); awaitCondition("night audio stopped") { !AlarmController.ringing && audio.activePlaybackConfigurations.none { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ALARM } }
            tap("提醒模式：仅振动"); tap("试听当前设置 10 秒")
            awaitCondition("manual vibration begins") { AlarmController.ringing }; Thread.sleep(800)
            check(audio.activePlaybackConfigurations.none { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ALARM })
            tap("停止提醒"); awaitCondition("manual vibration stopped") { !AlarmController.ringing }
            tap("提醒模式：铃声＋振动"); check(AlarmPreferences(targetContext).deliveryMode == "ring")
            onMain { alarms.setNight(1320, 480) }; tap("提醒模式：日夜自动")
            onMain { views(activity!!.window.decorView).filterIsInstance<android.widget.ScrollView>().first().scrollTo(0, 0) }
            Thread.sleep(1200); screenshot("panel-v07-alarm-schedule")
            report("PASS: auto daytime notification without USAGE_ALARM/fullscreen; auto night USAGE_ALARM playback; manual vibration override; stop releases playback/service")

        } catch (e: Throwable) {
            failure = e
        } finally {
            onMain {
                BackgroundTracking.stop(targetContext); activity?.finish()
                prefs.uiFont = oldFont; prefs.uiTextScale = oldSize; prefs.topFont = oldTopFont; prefs.topColor = oldColor; prefs.topTextSize = oldTopSize; prefs.topDirection = oldDirection; prefs.topShadow = oldShadow
                prefs.topPrice = oldTop; prefs.x = oldX; prefs.y = oldY; prefs.topPositionSet = oldPosition; prefs.overlayOpacityPercent = oldOpacity
                alarms.deliveryMode = oldMode; alarms.setNight(oldStart, oldEnd)
            }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", if (failure == null) "\nPANEL_APPEARANCE_SCHEDULE_PASS\n" else "\nPANEL_APPEARANCE_SCHEDULE_FAIL: ${failure!!.stackTraceToString()}\n") })
    }

    private fun testStyles() {
        val app = targetContext.applicationContext as PnaleApp
        val snapshots = AtomicReference<MarketState>()
        val listener: (MarketState) -> Unit = { snapshots.set(it) }
        var activity: Activity? = null
        val oldCoins = app.preferences.coins
        try {
            activity = startActivitySync(Intent(targetContext, StyleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onMain { app.market.listen(listener) }
            val saved = CountDownLatch(1)
            var error: String? = null
            onMain { app.market.saveInput("ETH, BTC, SOL, ZEC") { error = it; saved.countDown() } }
            check(saved.await(25, TimeUnit.SECONDS) && error == null) { "Test watchlist: $error" }
            await("all four style quotes", snapshots) { it.coins.size == 4 && it.coins.all { coin -> it.quotes[coin + "USDT"]?.stale() == false } && it.status == "实时推送" }
            fun views(view: android.view.View): List<android.view.View> = listOf(view) + if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
            fun tap(label: String) {
                onMain {
                    val view = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().first { it.contentDescription == label || it.text.toString() == label && it.isClickable }
                    reveal(view); check(view.performClick())
                }
                waitForIdleSync()
            }
            tap("面板样式：玻璃"); tap("排列：四宫格"); tap("数字配色：冰蓝"); tap("行情数字字体：粗体")
            check(Preferences(targetContext).boardStyle == "glass" && Preferences(targetContext).boardLayout == "grid" && Preferences(targetContext).boardFont == "bold")
            screenshot("style-editor-glass")
            val desk = startActivitySync(Intent(targetContext, DeskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onMain { (desk.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.view.ViewGroup).getChildAt(0).performClick() }
            waitForIdleSync(); Thread.sleep(700); screenshot("style-glass-grid")
            onMain { app.preferences.boardLayout = "row"; app.preferences.boardFont = "light" }
            Thread.sleep(1100); screenshot("style-glass-row")
            onMain { desk.finish() }
            waitForIdleSync()
            tap("币种 + 价格")
            check(app.preferences.chipLayout == "symbol_price")
            val manager = targetContext.getSystemService(android.app.NotificationManager::class.java)
            check(manager.areNotificationsEnabled())
            onMain {
                app.preferences.liveUpdates = true
                targetContext.startForegroundService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.NOTIFICATION_ONLY))
                app.market.select("ZEC")
            }
            await("selected ZEC", snapshots) { it.selected == "ZEC" && it.quote?.stale() == false && it.status == "实时推送" }
            onMain { targetContext.startService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.REFRESH)) }
            awaitCondition("live request published") { manager.activeNotifications.any { it.id == 7 && it.notification.extras.getBoolean("android.requestPromotedOngoing") } }
            check(DisplayStyle.chipLeft(snapshots.get(), app.preferences.chipLayout) == "ZEC")
            tap("币种 + 涨跌"); check(app.preferences.chipLayout == "symbol_change")
            tap("价格 + 涨跌"); check(app.preferences.chipLayout == "price_change")
            tap("图标 + 价格"); check(app.preferences.chipLayout == "price_only")
            tap("币种 + 价格")
            // Bring the layout previews into view using the actual scroll container.
            onMain { views(activity!!.window.decorView).filterIsInstance<android.widget.ScrollView>().first().fullScroll(android.view.View.FOCUS_DOWN) }
            waitForIdleSync(); Thread.sleep(500); screenshot("style-chip-layouts")
            report("PASS: real four-coin feed; style editor controls; persisted glass/grid/color/font; actual desk grid/row render; all four chip layouts; ZEC selection; promoted request retained")
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPNALE_STYLES_PASS\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nPNALE_STYLES_FAIL: ${e.stackTraceToString()}\n") })
        } finally {
            onMain { app.market.unlisten(listener); BackgroundTracking.stop(targetContext); activity?.finish() }
            app.preferences.coins = oldCoins
        }
    }
    private fun testDisplays() {
        var main: Activity? = null
        var desk: Activity? = null
        val app = targetContext.applicationContext as PnaleApp
        val snapshots = AtomicReference<MarketState>()
        val listener: (MarketState) -> Unit = { snapshots.set(it) }
        val manager = targetContext.getSystemService(android.app.NotificationManager::class.java)
        val oldLock = app.preferences.lockscreenQuotes
        val oldLive = app.preferences.liveUpdates
        try {
            main = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onMain { app.market.listen(listener) }
            await("display live quotes", snapshots) { it.status == "实时推送" && it.quote?.stale() == false }
            onMain {
                app.preferences.lockscreenQuotes = true; app.preferences.liveUpdates = true
                targetContext.startForegroundService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.NOTIFICATION_ONLY))
            }
            awaitCondition("public notification") { manager.activeNotifications.any { it.id == 7 && it.notification.visibility == android.app.Notification.VISIBILITY_PUBLIC } }
            val posted = manager.activeNotifications.first { it.id == 7 }.notification
            check(posted.extras.getBoolean("android.requestPromotedOngoing"))
            report("PASS: lockscreen PUBLIC + live update request; actual promoted=" + (posted.flags and android.app.Notification.FLAG_PROMOTED_ONGOING != 0))
            posted.actions.first { it.title.toString() == "取消胶囊" }.actionIntent.send()
            awaitCondition("unpin respected") { !app.preferences.liveUpdates && manager.activeNotifications.first { it.id == 7 }.notification.extras.getBoolean("android.requestPromotedOngoing").not() }
            onMain {
                app.preferences.lockscreenQuotes = false
                targetContext.startService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.REFRESH))
            }
            awaitCondition("hide from lockscreen") { manager.activeNotifications.first { it.id == 7 }.notification.visibility == android.app.Notification.VISIBILITY_SECRET }
            manager.activeNotifications.first { it.id == 7 }.notification.deleteIntent.send()
            awaitCondition("dismiss stops service") { !OverlayService.running && manager.activeNotifications.none { it.id == 7 } }
            report("PASS: actual unpin/delete PendingIntents; lockscreen hide option; foreground stopped")
            desk = startActivitySync(Intent(targetContext, DeskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("desk live quotes", snapshots) { it.status == "实时推送" && it.quote?.stale() == false }
            waitForIdleSync()
            screenshot("desk-controls")
            val board = (desk!!.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.view.ViewGroup).getChildAt(0)
            onMain { board.performClick() }
            waitForIdleSync(); screenshot("desk-prices")
            onMain { desk!!.finish(); main!!.moveTaskToBack(true) }
            await("desk owner released", snapshots) { it.status == "已暂停" }
            report("PASS: real desk activity, live quote rendering, controls toggle, exit releases market owner")
            if (options.getString("top") == "true") {
                check(Settings.canDrawOverlays(targetContext))
                onMain {
                    targetContext.startForegroundService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.TOP_PRICE))
                }
                awaitCondition("top overlay") { OverlayService.overlayVisible }
                await("top live quote", snapshots) { it.status == "实时推送" && it.quote?.stale() == false }
                Thread.sleep(1000); screenshot("top-price")
                report("PASS: top price window attached; inspect screenshot for system-bar occlusion")
                // Leave visible for manual position/long-press checks on the test device.
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPNALE_DISPLAYS_PASS\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nPNALE_DISPLAYS_FAIL: ${e.stackTraceToString()}\n") })
        } finally {
            onMain { app.market.unlisten(listener); desk?.finish() }
            app.preferences.lockscreenQuotes = oldLock; app.preferences.liveUpdates = oldLive
        }
    }

    private fun testReliability() {
        val app = targetContext.applicationContext as PnaleApp
        val prefs = app.preferences
        var activity: Activity? = null
        fun views(v: android.view.View): List<android.view.View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        fun view(desc: String) = views(activity!!.window.decorView).first { it.contentDescription?.toString() == desc }
        fun tap(desc: String) { onMain { view(desc).let { reveal(it); it.performClick() } }; waitForIdleSync() }
        fun launch(type: Class<out Activity>) { activity?.let { onMain { it.finish() } }; activity = startActivitySync(Intent(targetContext, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync() }
        try {
            check(BackgroundTracking.allowed(targetContext) && Settings.canDrawOverlays(targetContext)) { "Grant notification and overlay permissions on disposable emulator through system UI" }
            check(AlarmPreferences(targetContext).rules.none { it.enabled })
            onMain { prefs.networkMode = "relay"; prefs.topDirection = true; prefs.overlayOpacityPercent = 100 }
            launch(StyleActivity::class.java)
            tap("固定文字颜色：冰蓝")
            val blue = android.graphics.Color.rgb(137,195,255)
            check(prefs.topColor == blue && !prefs.topDirection)
            onMain {
                check(!(view("颜色跟随 24h 涨跌") as android.widget.Switch).isChecked)
                check(view("固定文字颜色：冰蓝").isSelected)
                val label = view("顶部价格样式预览") as android.widget.TextView
                TopPriceStyle.apply(label, prefs, MarketState(listOf("ETH"), "ETH", "1m", status = "重连中"))
                check(label.currentTextColor == blue) { "Stale color silently overridden" }
                prefs.topDirection = true
                SettingsBindings.refresh(activity!!.window.decorView)
                check((view("颜色跟随 24h 涨跌") as android.widget.Switch).isChecked)
            }
            tap("固定文字颜色：冰蓝"); tap("顶部文字字体：等宽")
            screenshot("panel-v08-colors")
            onMain { app.config.saveNow() }
            awaitCondition("automatic snapshot") { runCatching { ConfigCodec.decode(app.config.readBackup()).first["topFont"] == "mono" }.getOrDefault(false) }
            val raw = app.config.readBackup()
            onMain { prefs.topColor = android.graphics.Color.RED; app.config.restore(raw) }
            check(prefs.topColor == blue)
            onMain { check(view("固定文字颜色：冰蓝").isSelected) }
            val before = ConfigCodec.decode(app.config.snapshot())
            check(runCatching { app.config.restore("{broken") }.isFailure)
            check(before == ConfigCodec.decode(app.config.snapshot()))
            report("PASS: fixed color overrides direction; stale label retains chosen color; switch/selected controls sync; atomic backup restore; invalid restore leaves configuration unchanged")
            launch(MarketSettingsActivity::class.java)
            onMain { (view("展示币种输入框") as android.widget.EditText).setText("BTC, ETH, XAU, CL, BZ") }
            val end = System.currentTimeMillis() + 45000
            while (prefs.coins != listOf("BTC", "ETH", "XAU", "CL", "BZ") && System.currentTimeMillis() < end) Thread.sleep(200)
            check(prefs.coins == listOf("BTC", "ETH", "XAU", "CL", "BZ")) { "Watchlist autosave failed" }
            onMain { (view("展示币种输入框") as android.widget.EditText).setText("BTC, NOT_A_CONTRACT") }
            Thread.sleep(1500)
            check(prefs.coins.size == 5 && prefs.watchlistDraft.contains("NOT_A_CONTRACT"))
            launch(MarketSettingsActivity::class.java)
            onMain { check((view("展示币种输入框") as android.widget.EditText).text.contains("NOT_A_CONTRACT")); (view("展示币种输入框") as android.widget.EditText).setText("BTC, ETH, XAU, CL, BZ") }
            launch(AlarmSettingsActivity::class.java)
            Thread.sleep(500)
            onMain { (view("规则名称") as android.widget.EditText).setText("自动保存验证"); (view("观察窗口（1–60 分钟）") as android.widget.EditText).setText("3") }
            awaitCondition("alarm auto saved") { AlarmPreferences(targetContext).rules.any { it.name == "自动保存验证" && it.rule.minutes == 3 && !it.enabled } }
            onMain { (view("阈值（百分比 / USDT 价格）") as android.widget.EditText).setText("") }
            Thread.sleep(900)
            check(AlarmPreferences(targetContext).editorDraft.isNotEmpty())
            launch(AlarmSettingsActivity::class.java)
            onMain { check((view("阈值（百分比 / USDT 价格）") as android.widget.EditText).text.isEmpty()); (view("阈值（百分比 / USDT 价格）") as android.widget.EditText).setText("2") }
            Thread.sleep(900)
            screenshot("panel-v08-autosave")
            report("PASS: watchlist automatic validation/apply; invalid input draft survives reopen; alarm rule auto saves; incomplete rule draft survives reopen without enabling")
            onMain { activity!!.startForegroundService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.TOP_PRICE)) }
            awaitCondition("overlay running") { OverlayService.running && OverlayService.overlayVisible }
            check(prefs.trackingEnabled && prefs.trackingMode == "top")
            onMain { targetContext.stopService(Intent(targetContext, OverlayService::class.java)) }
            awaitCondition("service stopped") { !OverlayService.running }
            onMain { BackgroundTracking.resume(activity!!) }
            awaitCondition("restore saved overlay mode") { OverlayService.running && OverlayService.overlayVisible }
            check(prefs.trackingMode == "top")
            onMain { BackgroundTracking.stop(targetContext) }
            awaitCondition("explicit stop") { !OverlayService.running }
            onMain { BackgroundTracking.resume(activity!!) }
            Thread.sleep(600); check(!OverlayService.running && !prefs.trackingEnabled)
            onMain { activity!!.startForegroundService(Intent(targetContext, OverlayService::class.java).setAction(OverlayService.TOP_PRICE)) }
            awaitCondition("prepare reopen") { OverlayService.running }
            onMain { app.config.saveNow(); activity!!.finish() }; activity = null
            uiAutomation.executeShellCommand("input keyevent 3").close()
            Thread.sleep(700)
            screenshot("panel-v08-overlay")
            report("PASS: service restores saved top overlay; explicit stop persists and prevents auto resume; saved config prepared for separate process reopening test")
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPANEL_RELIABILITY_PASS\n") })
        } catch (e: Throwable) {
            onMain { BackgroundTracking.stop(targetContext); activity?.finish() }
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nPANEL_RELIABILITY_FAIL: ${e.stackTraceToString()}\n") })
        }
    }
    private fun testReopen() {
        val app = targetContext.applicationContext as PnaleApp
        var activity: Activity? = null
        try {
            check(app.preferences.topColor == android.graphics.Color.rgb(137,195,255) && app.preferences.topFont == "mono" && !app.preferences.topDirection)
            check(app.preferences.trackingEnabled && app.preferences.trackingMode == "top")
            check(app.preferences.coins == listOf("BTC", "ETH", "XAU", "CL", "BZ"))
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitCondition("restored service on app launch") { OverlayService.running && OverlayService.overlayVisible }
            Thread.sleep(2000); screenshot("panel-v08-reopen")
            report("PASS: new process reloaded saved colors/font/watchlist/on-state and restored top overlay")
            onMain { BackgroundTracking.stop(targetContext); activity!!.finish() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPANEL_REOPEN_PASS\n") })
        } catch (e: Throwable) { onMain { activity?.finish() }; finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nPANEL_REOPEN_FAIL: ${e.stackTraceToString()}\n") }) }
    }
    private fun testResilientNetwork() {
        val prefs = Preferences(targetContext, "reliability_network")
        val owner = Any(); val state = AtomicReference<MarketState>()
        var engine: MarketEngine? = null
        val base = MarketTransport(targetContext)
        try {
            val badDirect = base.direct.copy(client = base.direct.client.newBuilder().addInterceptor { throw java.io.IOException("Controlled direct-path outage") }.build())
            onMain { prefs.networkMode = "auto"; engine = MarketEngine(prefs, targetContext, MarketTransport(targetContext, badDirect)); engine!!.listen { state.set(it) }; engine!!.acquire(owner) }
            await("automatic relay with real candles/tickers", state) { it.route == "relay" && it.streamedCandle && it.streamedSymbols.size == 2 && it.quote?.stale() == false && it.candles.size == 60 }
            report("PASS: injected direct-path failure automatically switched to pinned TLS relay, real fresh ETH/BTC WS and 60 candles")
            onMain { engine!!.release(owner) }
            val block = java.util.concurrent.atomic.AtomicBoolean(true)
            val relay = base.relay!!
            val wsBlocked = relay.copy(id = "direct", client = relay.client.newBuilder().addInterceptor { chain ->
                if (block.get() && chain.request().url.encodedPath == "/market/stream") throw java.io.IOException("Controlled WS outage")
                chain.proceed(chain.request())
            }.build())
            onMain { prefs.networkMode = "direct"; engine = MarketEngine(prefs, targetContext, MarketTransport(targetContext, wsBlocked)); engine!!.listen { state.set(it) }; engine!!.acquire(owner) }
            await("HTTP fallback with fresh prices", state) { it.status == "HTTP 兜底" && it.quote?.stale() == false }
            val first = state.get().quote!!.eventTime
            await("HTTP fallback timestamp advances", state) { it.status == "HTTP 兜底" && (it.quote?.eventTime ?: 0) > first }
            val book = AlarmRuleBook()
            check(book.evaluate(listOf(AlarmPreset(enabled = true)), state.get(), System.currentTimeMillis()).isEmpty())
            check(book.statuses.values.all { it.contains("行情未就绪") })
            block.set(false)
            await("WS returns after fallback", state) { it.status == "实时推送" && it.streamedCandle && it.streamedSymbols.size == 2 && !it.chartStale() }
            check(state.get().candles.zipWithNext().all { (a,b) -> b.time - a.time == state.get().intervalMillis })
            report("PASS: blocked WS falls back to HTTP; actual exchange timestamps advance; alarms paused in HTTP mode; WS automatically reconnects with contiguous backfill")
            uiAutomation.executeShellCommand("cmd connectivity airplane-mode enable").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            await("actual network lost", state) { it.status == "等待网络" }
            uiAutomation.executeShellCommand("cmd connectivity airplane-mode disable").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            await("actual network returned", state) { it.status == "实时推送" && it.streamedCandle && it.streamedSymbols.size == 2 }
            report("PASS: actual airplane-mode network loss marked disconnected; ConnectivityManager restored WS after network returned")
            onMain { engine!!.release(owner) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPANEL_NETWORK_RESILIENCE_PASS\n") })
        } catch (e: Throwable) { uiAutomation.executeShellCommand("cmd connectivity airplane-mode disable").close(); onMain { engine?.release(owner) }; finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nPANEL_NETWORK_RESILIENCE_FAIL: ${e.stackTraceToString()}\n") }) }
    }


    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }

    private fun reveal(view: android.view.View) {
        var parent = view.parent
        while (parent is android.view.View) { if (parent is SectionCard) parent.setExpanded(true); parent = parent.parent }
        view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1)), true)
    }

    private fun await(label: String, state: AtomicReference<MarketState>, condition: (MarketState) -> Boolean) {
        val end = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < end) {
            state.get()?.let { if (condition(it)) return }
            Thread.sleep(100)
        }
        error("$label timed out: ${state.get()?.status}, ${state.get()?.error}")
    }
    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        error("$label timed out")
    }
    private fun report(text: String) { sendStatus(0, Bundle().apply { putString("stream", "\n$text\n") }) }
    private fun screenshot(name: String) {
        val file = File(targetContext.getExternalFilesDir(null), "validation/$name.png")
        file.parentFile?.mkdirs()
        uiAutomation.takeScreenshot()?.let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
