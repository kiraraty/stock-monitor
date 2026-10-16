package app.pnale.ticker

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

class ConfigActivity : PanelActivity() {
    private val app get() = application as PnaleApp
    private lateinit var status: TextView
    private val update = object : Runnable { override fun run() { render(); status.postDelayed(this, 1000) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = SettingsPage(this, "配置与备份", "自动保存 · 重开恢复 · 可导出换机")
        val auto = page.content.section("自动保存已开启", "每次有效修改都会保存，无需另按保存")
        status = text("", 13f, Colors.green); auto.addView(status)
        auto.note("自选、字体、颜色、位置、显示开关、网络方式和警报规则统一备份。尚未填完的自选或警报会保留草稿。")
        auto.action("恢复上一个自动备份") {
            runCatching { app.config.readPrevious() }.onSuccess(::confirmRestore).onFailure { message("暂时没有上一个备份") }
        }
        val files = page.content.section("备份文件", "JSON 文件，可存到下载目录或你选择的位置")
        files.actions("导出配置" to {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "Panel-config-${java.time.LocalDate.now()}.json"), 81)
        }, "导入配置" to {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 82)
        })
        files.note("本机自动备份会随卸载或清除数据一起删除；换手机前请导出。系统通知、悬浮权限和系统音量需在新手机单独设置。")
        render()
    }
    private fun render() {
        status.text = app.config.error ?: if (app.config.lastSaved > 0) "已自动备份  " + java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(app.config.lastSaved)) else "正在建立自动备份…"
        status.setTextColor(if (app.config.error == null) Colors.green else Colors.orange)
    }
    private fun message(value: String) { AlertDialog.Builder(this).setMessage(value).setPositiveButton("知道了", null).show() }
    private fun confirmRestore(raw: String) {
        runCatching { ConfigCodec.decode(raw) }.onSuccess { config ->
            val count = org.json.JSONArray(config.second["rules"] as? String ?: "[]").length()
            AlertDialog.Builder(this).setTitle("恢复这份配置？")
                .setMessage("自选：${config.first["coins"] ?: "ETH, BTC"}\n警报：$count 条\n将替换当前配置，并按保存的开关恢复盯盘。")
                .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                    runCatching {
                        // Stop the existing owner before applying the saved running state.
                        stopService(Intent(this, OverlayService::class.java))
                        app.config.restore(raw)
                        app.market.reloadPreferences()
                        window.decorView.postDelayed({ BackgroundTracking.resume(this); render() }, 250)
                    }.onSuccess { message("配置已恢复") }.onFailure { message("恢复失败：${it.message}") }
                }.show()
        }.onFailure { message("未导入，配置文件无效：${it.message}") }
    }
    @Deprecated("Framework callback")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        val uri = data?.data ?: return
        if (result != RESULT_OK) return
        if (code == 81) runCatching {
            val raw = app.config.snapshot(); ConfigCodec.decode(raw)
            checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        }.onSuccess { message("配置已导出") }.onFailure { message("导出失败：${it.message}") }
        if (code == 82) runCatching {
            val bytes = checkNotNull(contentResolver.openInputStream(uri)).use { it.readNBytes(ConfigCodec.MAX_BYTES + 1) }
            require(bytes.size <= ConfigCodec.MAX_BYTES) { "文件过大" }
            bytes.toString(Charsets.UTF_8)
        }.onSuccess(::confirmRestore).onFailure { message("读取失败：${it.message}") }
    }
    override fun onStart() { super.onStart(); status.post(update) }
    override fun onStop() { status.removeCallbacks(update); super.onStop() }
}
