package app.pnale.ticker

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

/** SharedPreferences saves immediately; complete snapshots are serialized and atomically replaced. */
class ConfigStore(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val files = listOf("watchlist", "price_alarm").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
    private val backup = AtomicFile(File(context.filesDir, "config/latest.json"))
    private val previous = AtomicFile(File(context.filesDir, "config/previous.json"))
    private val restoreJournal = AtomicFile(File(context.filesDir, "config/restoring.json"))
    @Volatile var lastSaved = 0L; private set
    @Volatile var error: String? = null; private set
    private var restoring = false
    private val saveTask = Runnable { saveNow() }
    private val change = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!restoring) { main.removeCallbacks(saveTask); main.postDelayed(saveTask, 450) }
    }
    fun start() {
        // A journal completes a restore interrupted between the two preference files.
        if (restoreJournal.baseFile.exists()) runCatching { apply(ConfigCodec.decode(restoreJournal.readFully().toString(Charsets.UTF_8))) }
            .onSuccess { restoreJournal.delete() }.onFailure { error = "上次恢复未完成：${it.message}" }
        if (files.all { it.all.isEmpty() } && backup.baseFile.exists()) runCatching { apply(ConfigCodec.decode(readBackup())) }
            .onFailure { error = "自动恢复失败：${it.message}" }
        if (backup.baseFile.exists()) lastSaved = backup.baseFile.lastModified()
        files.forEach { it.registerOnSharedPreferenceChangeListener(change) }
        if (!backup.baseFile.exists()) saveNow()
    }
    fun snapshot() = ConfigCodec.encode(files[0].all, files[1].all)
    private fun write(file: AtomicFile, raw: String) {
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try { stream.write(raw.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun saveNow() {
        if (restoring) return
        main.removeCallbacks(saveTask)
        val raw = snapshot()
        writer.execute {
            runCatching {
                ConfigCodec.decode(raw)
                if (backup.baseFile.exists()) {
                    val old = backup.readFully().toString(Charsets.UTF_8)
                    val decoded = runCatching { ConfigCodec.decode(old) }.getOrNull()
                    if (decoded == ConfigCodec.decode(raw)) return@execute
                    if (decoded != null) write(previous, old)
                }
                write(backup, raw); lastSaved = System.currentTimeMillis(); error = null
            }.onFailure { error = "自动备份失败：${it.message}" }
        }
    }
    fun readBackup(): String = backup.readFully().toString(Charsets.UTF_8)
    fun readPrevious(): String = previous.readFully().toString(Charsets.UTF_8)
    fun hasPrevious() = previous.baseFile.exists()
    fun restore(raw: String) {
        val parsed = ConfigCodec.decode(raw)
        write(restoreJournal, raw)
        apply(parsed)
        restoreJournal.delete()
        saveNow()
    }
    private fun apply(data: Pair<Map<String, Any>, Map<String, Any>>) {
        restoring = true
        try {
            listOf(data.first, data.second).forEachIndexed { i, values ->
                val edit = files[i].edit().clear()
                values.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                } }
                check(edit.commit()) { "配置写入失败" }
            }
        } finally { restoring = false }
    }
}
