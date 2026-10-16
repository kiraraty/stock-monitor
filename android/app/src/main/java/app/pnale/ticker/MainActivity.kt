package app.pnale.ticker

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*

class MainActivity : PanelActivity() {
    private val app get() = application as PnaleApp
    private val engine get() = app.market
    private lateinit var panel: MarketPanel
    private lateinit var live: TextView
    private lateinit var monitor: TextView
    private lateinit var rules: TextView
    private lateinit var watchlist: LinearLayout
    private lateinit var toggle: Button
    private var rows = emptyList<String>()
    private val quoteViews = mutableMapOf<String, Pair<TextView, TextView>>()
    private val listener: (MarketState) -> Unit = { state -> panel.render(state); render(state) }
    private val refresh = object : Runnable { override fun run() { render(engine.state); live.postDelayed(this, 1_000) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply { setBackgroundColor(Colors.background) }
        val body = column().apply { setPadding(dp(18), dp(14), dp(18), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        val nav = row().apply { setPadding(dp(12),dp(8),dp(12),dp(8)); background=rounded(Colors.card,0) }
        nav.addView(button("行情", true) { scroll.smoothScrollTo(0,0) }, LinearLayout.LayoutParams(0,dp(46),1f).apply { marginEnd=dp(6) })
        nav.addView(button("警报") { open(AlarmSettingsActivity::class.java) }, LinearLayout.LayoutParams(0,dp(46),1f).apply { marginEnd=dp(6) })
        nav.addView(button("外观") { open(StyleActivity::class.java) }, LinearLayout.LayoutParams(0,dp(46),1f).apply { marginEnd=dp(6) })
        nav.addView(button("设置") { open(MarketSettingsActivity::class.java) }, LinearLayout.LayoutParams(0,dp(46),1f))
        root.addView(nav)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _,insets ->
            val b=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            root.setPadding(b.left,b.top,b.right,b.bottom);insets
        }
        body.addView(row().apply {
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_pnale); contentDescription="哨灯" },LinearLayout.LayoutParams(dp(42),dp(42)).apply { marginEnd=dp(10) })
            addView(column().apply { addView(text("哨灯",23f,bold=true)); space(3); addView(text("PANEL  /  市场监测",10f,Colors.muted).apply { letterSpacing=.09f }) },LinearLayout.LayoutParams(0,-2,1f))
            live=text("连接中",11f,Colors.orange).apply { setPadding(dp(10),dp(7),dp(10),dp(7));background=rounded(Colors.card,20) };addView(live)
        })
        body.space(18)
        panel=MarketPanel(this,engine).apply {
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(26,37,42),Color.rgb(17,22,29))).apply { cornerRadius=dp(24).toFloat();setStroke(dp(1),Color.rgb(51,69,74)) }
        }
        body.addView(panel,LinearLayout.LayoutParams(-1,-2));body.space(16)
        val monitorCard=column().apply { background=rounded(Colors.card,20);setPadding(dp(16),dp(14),dp(16),dp(12)) }
        monitorCard.addView(row().apply {
            addView(column().apply { addView(text("持续盯盘",16f,bold=true));space(6);monitor=text("尚未开启后台盯盘",11f,Colors.muted);addView(monitor) },LinearLayout.LayoutParams(0,-2,1f))
            toggle=button("开启",true) { if(OverlayService.running || app.preferences.trackingEnabled){BackgroundTracking.stop(this@MainActivity);toggle.postDelayed({render(engine.state)},200)}else enableTracking() }
            addView(toggle,LinearLayout.LayoutParams(dp(82),dp(46)))
        })
        monitorCard.space(10)
        rules=text("0 条警报规则已启用",12f,Colors.muted);monitorCard.addView(rules)
        body.addView(monitorCard);body.space(14)
        body.addView(row().apply {
            addView(text("自选行情",18f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
            addView(button("管理自选") { open(MarketSettingsActivity::class.java) },LinearLayout.LayoutParams(dp(98),dp(40)))
        });body.space(8)
        watchlist=column().apply { background=rounded(Colors.card,20);setPadding(dp(16),dp(4),dp(16),dp(4)) };body.addView(watchlist)
        body.space(18)
        body.addView(text("${MarketSource.resolve(app.preferences.marketSource, app.preferences.lastGoodSource).label.uppercase()}  ·  USDT 永续\n报价超过 15 秒未更新时标记过期",10f,Colors.muted).apply { gravity=Gravity.CENTER;setLineSpacing(dp(5).toFloat(),1f) })
        render(engine.state)
    }
    private fun render(state:MarketState){
        if(!::live.isInitialized||!::watchlist.isInitialized)return
        val fresh=state.status=="实时推送"&&state.quote?.stale()==false
        live.text=if(fresh)"● 实时" else "○ ${state.displayStatus()}";live.setTextColor(if(fresh)Colors.green else Colors.orange)
        monitor.text=if(OverlayService.running)"${if(OverlayService.overlayVisible)"通知与浮窗" else "通知栏"}正在运行" else if(app.preferences.trackingEnabled) "已保存开启 · 服务暂未运行，检查通知权限" else "打开后在后台接收行情"
        toggle.text=if(OverlayService.running || app.preferences.trackingEnabled)"停止" else "开启"
        val presets=AlarmPreferences(this).rules;rules.text="${presets.count{it.enabled}} 条规则已启用 · ${presets.size} 条已保存"
        if(rows!=state.coins){rows=state.coins;watchlist.removeAllViews();quoteViews.clear();rows.forEachIndexed {i,coin ->
            val price=text("—",15f,bold=true).apply { gravity=Gravity.END }
            val change=text("—",11f,Colors.muted).apply { gravity=Gravity.END }
            watchlist.addView(row().apply {
                addView(column().apply { addView(text(coin,16f,bold=true));space(5);addView(text(when(coin){"XAU"->"黄金永续";"CL"->"WTI 原油永续";"BZ"->"布伦特原油永续";else->"USDT 永续"},10f,Colors.muted)) },LinearLayout.LayoutParams(0,-2,1f))
                addView(column().apply { gravity=Gravity.END; addView(price);space(5);addView(change) },LinearLayout.LayoutParams(0,-2,1f));setOnClickListener{engine.select(coin)};isClickable=true;isFocusable=true
            },LinearLayout.LayoutParams(-1,dp(72)))
            if(i<rows.lastIndex)watchlist.addView(View(this).apply {setBackgroundColor(Colors.line)},LinearLayout.LayoutParams(-1,dp(1)))
            quoteViews[coin]=price to change
        }}
        quoteViews.forEach{(coin,views)->val q=state.quotes[coin+"USDT"];views.first.text=q?.let{Prices.format(it.price)}?:"—";views.second.text=if(q?.stale()==false)Prices.change(q.change)+" 24H" else "等待新报价";views.second.setTextColor(if(q?.stale()==false)Colors.direction(q.change)else Colors.muted)}
    }
    private fun open(type:Class<out Activity>){startActivity(Intent(this,type))}
    private fun enableTracking(){
        val manager=getSystemService(NotificationManager::class.java)
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),20);return}
        if(!manager.areNotificationsEnabled()||manager.getNotificationChannel("floating_quotes")?.importance==NotificationManager.IMPORTANCE_NONE){startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName));return}
        startForegroundService(Intent(this,OverlayService::class.java).setAction(OverlayService.RESTORE).putExtra("userStart", true));toggle.postDelayed({render(engine.state)},300)
    }
    @Deprecated("Framework permission callback")
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==20&&results.firstOrNull()==PackageManager.PERMISSION_GRANTED)enableTracking()}
    override fun onStart(){super.onStart();engine.listen(listener);engine.acquire(this);live.post(refresh)}
    override fun onResume(){super.onResume();BackgroundTracking.resume(this)}
    override fun onStop(){live.removeCallbacks(refresh);engine.unlisten(listener);engine.release(this);super.onStop()}
}
