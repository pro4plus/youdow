package app.localdrop

import android.Manifest
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) {
        val arabic=java.util.Locale("ar")
        val config=Configuration(base.resources.configuration).apply {
            setLocale(arabic); setLayoutDirection(arabic)
        }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    private var incoming by mutableStateOf("")
    private var clipboardUrl by mutableStateOf<String?>(null)
    private var lastClip: String?=null
    private var pendingDownload: (() -> Unit)?=null
    private var permissionError by mutableStateOf("")
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(Build.VERSION.SDK_INT > 28 || ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED)
            pendingDownload?.invoke()
        else permissionError="يلزم إذن التخزين لحفظ الملفات على أندرويد 8 و9."
        pendingDownload=null
    }
    private val clipListener=ClipboardManager.OnPrimaryClipChangedListener { if(hasWindowFocus()) readClipboard() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=android.graphics.Color.rgb(17,20,26)
        window.navigationBarColor=android.graphics.Color.rgb(17,20,26)
        androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=false; isAppearanceLightNavigationBars=false
        }
        consume(intent)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            LocalDropTheme {
                AppScreen(incoming,clipboardUrl,permissionError,
                    onClip={ incoming=it; clipboardUrl=null },onDismissClip={clipboardUrl=null},
                    enqueue={ p,c -> withPermissions { app.store.add(p,c); DownloadService.command(this) } })
            }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consume(intent) }
    private fun consume(i: Intent?) {
        if(i?.action == Intent.ACTION_SEND && i.type == "text/plain")
            incoming=LinkRules.extract(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()).orEmpty()
    }
    override fun onResume() { super.onResume(); getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener) }
    override fun onPause() { getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if(hasFocus) readClipboard() }
    private fun readClipboard() {
        val clip=getSystemService(ClipboardManager::class.java).primaryClip ?: return
        if(clip.itemCount == 0) return
        val url=LinkRules.extract(clip.getItemAt(0).text?.toString().orEmpty()) ?: return
        if(url != lastClip && url != incoming) { lastClip=url; clipboardUrl=url }
    }
    private fun withPermissions(action: () -> Unit) {
        val needed=buildList {
            if(Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if(Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(this,it) != PackageManager.PERMISSION_GRANTED }
        if(needed.isEmpty()) action() else { pendingDownload=action; permissions.launch(needed.toTypedArray()) }
    }
}

@Composable
internal fun Settings() {
    val context=LocalContext.current
    val store=context.app.store
    val scope=rememberCoroutineScope()
    var quality by remember { mutableIntStateOf(store.quality) }
    var folder by remember { mutableStateOf(store.folder) }
    var wifi by remember { mutableStateOf(store.wifiOnly) }
    var updating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val extractorStatus by context.app.engine.updateStatus.collectAsStateWithLifecycle()
    Text("الإعدادات",style=MaterialTheme.typography.headlineLarge)
    Text("حمّل بالطريقة التي تناسبك.",color=Palette.Muted)
    Panel {
        Text("الجودة الافتراضية",style=MaterialTheme.typography.titleMedium)
        listOf(360,720,1080,2160).chunked(2).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach { q -> Pill(q.toString()+"p",quality==q) {quality=q;store.quality=q} }
            }
        }
    }
    Panel {
        Text("مكان الحفظ",style=MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Movies","Downloads").forEach { f ->
                Pill(if(f=="Movies") "الفيديوهات" else "التنزيلات",folder==f) {folder=f;store.folder=f}
            }
        }
        Text("داخل مجلد LocalDrop. تُحفظ الملفات الصوتية في التنزيلات.",color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
    }
    Panel {
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("واي فاي فقط",style=MaterialTheme.typography.titleMedium)
                Text("اضغط استئناف بعد عودة الاتصال.",color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
            }
            Switch(wifi,{wifi=it;store.wifiOnly=it},modifier=Modifier.semantics { contentDescription="التنزيل عبر واي فاي فقط" })
        }
    }
    Panel {
        Text("أداة التنزيل",style=MaterialTheme.typography.titleMedium)
        Text("تحديث تلقائي كل 24 ساعة عند الاستخدام، عبر القناة التجريبية.",color=Palette.Muted)
        Text(extractorStatus,style=MaterialTheme.typography.bodySmall)
        PrimaryButton(if(updating) "جارٍ فحص التحديث…" else "فحص التحديث الآن",onClick={
            updating=true;result=""
            scope.launch {
                try {result=context.app.engine.update()}
                catch(e: Exception) {if(e is kotlinx.coroutines.CancellationException) throw e;result=LinkRules.report(e)}
                finally {updating=false}
            }
        },enabled=!updating,icon=R.drawable.ui_refresh)
        if(result.isNotBlank()) {
            if(result.contains("التفاصيل الفنية:")) FailurePanel(result) else Text(result)
        }
        Text("إذا تعذر التحديث نستمر بالإصدار الموجود ونحاول لاحقًا. قد تحتوي الإصدارات التجريبية على أخطاء جديدة.",color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
    }
    Text("بلا حسابات، بلا إعلانات، بلا تتبع. لا ندعم الفيديوهات الخاصة أو المحمية أو التي تتطلب تسجيل دخول.",color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
}

@Composable
internal fun FailurePanel(report: String) {
    val context=LocalContext.current
    var expanded by remember(report) { mutableStateOf(false) }
    var copied by remember(report) { mutableStateOf(false) }
    Text(report.substringBefore("\n\nالتفاصيل الفنية:"),color=MaterialTheme.colorScheme.error)
    Row {
        TextButton(onClick={expanded=!expanded},modifier=Modifier.heightIn(min=48.dp)) { Text(if(expanded) "إخفاء التفاصيل" else "عرض التفاصيل") }
        TextButton(onClick={
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("خطأ التنزيل",report))
            copied=true
        },modifier=Modifier.heightIn(min=48.dp)) { Text(if(copied) "تم النسخ" else "نسخ الخطأ") }
    }
    if(expanded) androidx.compose.foundation.text.selection.SelectionContainer {
        Text(report.substringAfter("\n\nالتفاصيل الفنية:",report),
            modifier=Modifier.heightIn(max=140.dp).verticalScroll(rememberScrollState()),
            style=MaterialTheme.typography.bodySmall)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PlayerDialog(uri: String,onClose: ()->Unit) {
    val context=LocalContext.current
    var error by remember { mutableStateOf("") }
    val player=remember(uri) { ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener { override fun onPlayerError(e: PlaybackException) {
            error="تعذر تشغيل الملف. ربما نُقل أو حُذف أو أن ترميزه غير مدعوم على جهازك."
        } })
        setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady=true
    } }
    DisposableEffect(player) { onDispose { player.release() } }
    val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner,player) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AlertDialog(onDismissRequest=onClose,confirmButton={TextButton(onClick=onClose){Text("إغلاق")}},
        text={ Column { AndroidView(factory={PlayerView(it).apply { this.player=player }},modifier=Modifier.fillMaxWidth().height(260.dp)); if(error.isNotBlank()) Text(error) } })
}
