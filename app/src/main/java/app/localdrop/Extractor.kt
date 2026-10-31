package app.localdrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.util.concurrent.TimeUnit

class Extractor(private val context: Context) {
    // Keep updates from replacing the extractor while it is executing.
    private val lock = Mutex()
    private var initialized = false
    private val updatePrefs = context.getSharedPreferences("extractor_updates",Context.MODE_PRIVATE)
    private val mutableUpdateStatus = MutableStateFlow(updatePrefs.getString("status","سيُفحص تحديث الأداة تلقائيًا عند أول استخدام.")!!)
    val updateStatus = mutableUpdateStatus.asStateFlow()
    private fun status(text: String) {
        mutableUpdateStatus.value=text
        updatePrefs.edit().putString("status",text).apply()
    }
    private fun init() {
        if (initialized) return
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
        NewPipe.init(object : Downloader() {
            val client = OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS)
                .readTimeout(45,TimeUnit.SECONDS).build()
            override fun execute(request: Request): Response {
                val b = okhttp3.Request.Builder().url(request.url())
                request.headers().forEach { (k,v) -> v.forEach { b.addHeader(k,it) } }
                val body = request.dataToSend()?.toRequestBody()
                    ?: if(request.httpMethod() in listOf("POST","PUT","PATCH")) ByteArray(0).toRequestBody() else null
                b.method(request.httpMethod(),body)
                return client.newCall(b.build()).execute().use {
                    Response(it.code,it.message,it.headers.toMultimap(),it.body?.string(),it.request.url.toString())
                }
            }
        })
        initialized = true
    }
    suspend fun update(): String = withContext(Dispatchers.IO) { lock.withLock {
        init()
        try { updateNightly() } catch(e: Exception) {
            status("تعذر تحديث الأداة: ${LinkRules.error(e)}")
            throw e
        }
    } }
    // Caller holds lock: never replace the executable during extraction or merging.
    private fun updateNightly(): String {
        updatePrefs.edit().putLong("attempt",System.currentTimeMillis()).apply()
        status("جارٍ فحص أحدث إصدار لأداة التنزيل…")
        val result=YoutubeDL.getInstance().updateYoutubeDL(context,YoutubeDL.UpdateChannel.NIGHTLY)
            ?: error("Extractor update returned no result")
        // Read the installed executable, rather than trusting a cached version preference.
        val request=YoutubeDLRequest(emptyList<String>()).apply { addOption("--version") }
        val version=YoutubeDL.getInstance().execute(request,"version-check").out.trim()
        require(version.isNotBlank()) { "Cannot verify extractor version" }
        val text=if(result == YoutubeDL.UpdateStatus.DONE) "تم تحديث أداة التنزيل: $version"
            else "أداة التنزيل محدثة: $version"
        updatePrefs.edit().putLong("success",System.currentTimeMillis()).apply()
        status(text)
        return text
    }
    private fun ensureUpdated() {
        if(!UpdatePolicy.due(System.currentTimeMillis(),updatePrefs.getLong("success",0),updatePrefs.getLong("attempt",0))) return
        try { updateNightly() } catch(e: Exception) {
            // Network outages must not stop use of a previously installed extractor.
            if(e is kotlinx.coroutines.CancellationException) throw e
            status("تعذر التحديث التلقائي؛ سنستخدم الإصدار الموجود ونحاول لاحقًا. ${LinkRules.error(e)}")
        }
    }
    suspend fun inspect(url: String): Preview = withContext(Dispatchers.IO) { lock.withLock {
        init()
        ensureUpdated()
        try {
            val r = base(url).apply { addOption("--dump-single-json"); addOption("--skip-download") }
            preview(url,JSONObject(YoutubeDL.getInstance().execute(r, "inspect").out),false)
        } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            val msg = e.message.orEmpty().lowercase()
            if (!LinkRules.youtube(url) || listOf("drm","private","login","sign in").any { it in msg }) throw e
            try { preview(url,fallbackInfo(url),true) }
            catch(fallbackError: Exception) {
                if(fallbackError is kotlinx.coroutines.CancellationException) throw fallbackError
                // Preserve both failures; the previous implementation lost the yt-dlp reason.
                throw IllegalStateException("yt-dlp: ${e.message}\nNewPipe: ${fallbackError.message}",e)
            }
        }
    } }
    private fun preview(url: String, j: JSONObject, fallback: Boolean): Preview {
        require(!j.optBoolean("has_drm")) { "DRM protected" }
        require(!j.optBoolean("is_live")) { "Unsupported live stream; try after the broadcast ends" }
        val formats=j.optJSONArray("formats") ?: JSONArray()
        val fs=(0 until formats.length()).map { formats.getJSONObject(it) }
            .filter { !it.optBoolean("has_drm") && it.optString("url").startsWith("https://") }
        val audio=fs.filter { it.optString("vcodec","none") == "none" && it.optString("acodec","none") != "none" }
        val choices=fs.filter { it.optString("vcodec","none") != "none" &&
            (it.optString("acodec","none") != "none" || audio.isNotEmpty()) }
            .sortedByDescending { it.optInt("height") }.map { f ->
                val separate=f.optString("acodec","none") == "none"
                Choice(f.getString("format_id") + if(separate) "+bestaudio" else "",
                    "${f.optInt("height")}p · ${f.optString("ext")} · فيديو وصوت" +
                        if(separate) " (دمج)" else "")
            }.toMutableList()
        if(audio.isNotEmpty()) { choices += Choice("bestaudio","صوت · الصيغة الأصلية"); choices += Choice("bestaudio","صوت · MP3",true) }
        require(choices.isNotEmpty()) { "No downloadable formats available; update extractor" }
        return Preview(url,j.optString("title","Video"),j.optString("thumbnail"),j.optLong("duration"),choices,fallback)
    }
    private fun base(url: String) = YoutubeDLRequest(url).apply {
        addOption("--no-playlist"); addOption("--socket-timeout",30)
        addOption("--retries",3); addOption("--fragment-retries",3)
        // Official yt-dlp EJS scripts run locally using the library's bundled QuickJS.
        if(LinkRules.youtube(url)) addOption("--remote-components","ejs:github")
    }
    suspend fun download(d: Download, dir: File, onProgress: (Int)->Unit): File = withContext(Dispatchers.IO) { lock.withLock {
        init(); dir.mkdirs()
        ensureUpdated()
        val sourceAddress = if(context.app.store.wifiOnly) {
            val cm=context.getSystemService(ConnectivityManager::class.java)
            val network=cm.activeNetwork ?: error("Network unavailable")
            check(cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) { "Wi-Fi network required" }
            cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }
                ?.address?.hostAddress ?: error("Wi-Fi IPv4 network required")
        } else null
        val r = if(d.fallback) {
            // Refresh expiring URLs on each attempt; stable format IDs retain partial files.
            val info=File(dir,"source.json").apply { writeText(fallbackInfo(d.url).toString()) }
            YoutubeDLRequest(emptyList<String>()).apply { addOption("--load-info-json",info.absolutePath) }
        } else base(d.url)
        // Binding the transfer socket to the Wi-Fi address prevents cellular fallback.
        if(sourceAddress != null) r.addOption("--source-address",sourceAddress)
        r.addOption("-f",d.selector); r.addOption("--continue")
        // yt-dlp's default preserves complete downloads, but lets postprocessors replace
        // an incomplete merge/conversion output on retry. --no-overwrites disabled both.
        r.addOption("--verbose") // Includes FFmpeg stderr, not only its final error line.
        r.addOption("-o",File(dir,"media.%(ext)s").absolutePath)
        r.addOption("--merge-output-format","mkv"); r.addOption("--newline"); r.addOption("--progress")
        // Format 312 arrives as MPEG-TS despite its .mp4 extension. Generate missing
        // presentation timestamps from decoding timestamps BEFORE reading each input.
        // Keep stream copying; no video re-encode or invented frame rate.
        r.addOption("--postprocessor-args","Merger+ffmpeg_i:-fflags +genpts")
        r.addCommands(listOf("--print-to-file","after_move:filepath",File(dir,"result.txt").absolutePath))
        if(d.mp3) { r.addOption("-x"); r.addOption("--audio-format","mp3"); r.addOption("--audio-quality","0") }
        try {
            YoutubeDL.getInstance().execute(r,d.id) { progress, _, _ -> onProgress(progress.toInt().coerceIn(0,100)) }
        } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException || e is YoutubeDL.CanceledException) throw e
            throw IllegalStateException(
                "الصيغة: ${d.selector}; MP3: ${d.mp3}; المستخرج: ${if(d.fallback) "NewPipe" else "yt-dlp"}\n${e.message}")
        }
        val output = File(dir,"result.txt").readLines().lastOrNull { it.isNotBlank() }
            ?: error("No output file")
        File(output).also { require(it.isFile && it.length()>0 && it.canonicalFile.parentFile == dir.canonicalFile) }
    } }
    fun stop(id: String) { YoutubeDL.getInstance().destroyProcessById(id) }
    private fun fallbackInfo(url: String): JSONObject {
        val info = StreamInfo.getInfo(ServiceList.YouTube,url)
        val formats=JSONArray()
        (info.videoStreams + info.videoOnlyStreams).filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.startsWith("https://")
        }.forEach { s -> formats.put(JSONObject().apply {
            put("format_id",s.id); put("url",s.content); put("ext",s.format?.suffix ?: "mp4")
            put("height",Regex("\\d+").find(s.resolution)?.value?.toIntOrNull() ?: 0)
            put("vcodec","unknown"); put("acodec",if(s.isVideoOnly) "none" else "unknown")
        }) }
        info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.startsWith("https://") }
            .forEach { s -> formats.put(JSONObject().apply {
                put("format_id",s.id); put("url",s.content); put("ext",s.format?.suffix ?: "m4a")
                put("vcodec","none"); put("acodec","unknown"); put("abr",s.averageBitrate)
            }) }
        return JSONObject().apply {
            put("id",info.id); put("title",info.name); put("duration",info.duration)
            put("thumbnail",info.thumbnails.firstOrNull()?.url ?: ""); put("webpage_url",url)
            put("extractor","newpipe"); put("formats",formats)
        }
    }
}
