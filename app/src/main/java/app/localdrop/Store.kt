package app.localdrop

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Status { QUEUED, RUNNING, SAVING, PAUSED, COMPLETE, FAILED, CANCELED }
val Status.arabic: String get() = when(this) {
    Status.QUEUED -> "في الانتظار"
    Status.RUNNING -> "جارٍ التنزيل"
    Status.SAVING -> "جارٍ الحفظ"
    Status.PAUSED -> "متوقف مؤقتًا"
    Status.COMPLETE -> "مكتمل"
    Status.FAILED -> "تعذر التنزيل"
    Status.CANCELED -> "ملغى"
}
data class Choice(val selector: String, val label: String, val mp3: Boolean = false)
data class Preview(val url: String, val title: String, val thumbnail: String, val duration: Long,
                   val choices: List<Choice>, val fallback: Boolean)
data class Download(val id: String = UUID.randomUUID().toString(), val url: String,
    val title: String, val selector: String, val mp3: Boolean, val fallback: Boolean,
    val folder: String, val status: Status = Status.QUEUED, val progress: Int = 0,
    val message: String = "", val uri: String = "", val created: Long = System.currentTimeMillis())

class LocalDropApp : Application() {
    lateinit var store: Store; private set
    lateinit var engine: Extractor; private set
    override fun onCreate() { super.onCreate(); store = Store(this); engine = Extractor(this) }
}
val Context.app: LocalDropApp get() = applicationContext as LocalDropApp

class Store(context: Context) {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(context.filesDir, "downloads.json"))
    private val mutable = MutableStateFlow(read())
    val items = mutable.asStateFlow()
    var quality: Int
        get() = prefs.getInt("quality", 1080)
        set(v) { prefs.edit().putInt("quality", v).apply() }
    var folder: String
        get() = prefs.getString("folder", "Movies")!!
        set(v) { prefs.edit().putString("folder", v).apply() }
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi", false)
        set(v) { prefs.edit().putBoolean("wifi", v).apply() }
    @Synchronized fun add(p: Preview, c: Choice) {
        commit(mutable.value + Download(url=p.url, title=p.title, selector=c.selector,
            mp3=c.mp3, fallback=p.fallback, folder=folder))
    }
    @Synchronized fun change(id: String, save: Boolean = true, fn: (Download) -> Download) {
        val list = mutable.value.map { if (it.id == id) fn(it) else it }
        if (save) commit(list) else mutable.value = list
    }
    fun get(id: String) = items.value.firstOrNull { it.id == id }
    private fun commit(list: List<Download>) {
        val bytes = JSONArray().apply { list.forEach { d -> put(JSONObject().apply {
            put("id",d.id); put("url",d.url); put("title",d.title); put("selector",d.selector)
            put("mp3",d.mp3); put("fallback",d.fallback); put("folder",d.folder)
            put("status",d.status.name); put("progress",d.progress); put("message",d.message)
            put("uri",d.uri); put("created",d.created)
        }) } }.toString().toByteArray()
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch(e: Exception) { file.failWrite(stream); throw e }
        mutable.value = list
    }
    private fun read(): List<Download> = try {
        val array = JSONArray(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        List(array.length()) { i -> array.getJSONObject(i).let { j ->
            val old = Status.valueOf(j.getString("status"))
            Download(j.getString("id"),j.getString("url"),j.getString("title"),j.getString("selector"),
                j.getBoolean("mp3"),j.getBoolean("fallback"),j.getString("folder"),
                if(old in listOf(Status.RUNNING,Status.SAVING,Status.QUEUED)) Status.PAUSED else old,
                j.optInt("progress"), if(old in listOf(Status.RUNNING,Status.SAVING,Status.QUEUED))
                    "توقف التنزيل. اضغط استئناف للمتابعة." else j.optString("message"),j.optString("uri"),j.getLong("created"))
        } }
    } catch (_: java.io.FileNotFoundException) { emptyList() }
}
