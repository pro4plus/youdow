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
        consume(intent)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF286B5A),background=Color(0xFFF6F8F5),surface=Color(0xFFF6F8F5))) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(incoming: String,clip: String?,permissionError: String,onClip: (String)->Unit,
                      onDismissClip: ()->Unit,enqueue: (Preview,Choice)->Unit) {
    val context=LocalContext.current
    val store=context.app.store
    val downloads by store.items.collectAsStateWithLifecycle()
    val extractorStatus by context.app.engine.updateStatus.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }
    fun inspect(text: String) {
        val url=LinkRules.extract(text)
        if(url == null) { error="ألصق رابط فيديو صالحًا يبدأ بـ http أو https."; return }
        if(busy) return
        input=url; preview=null; error=""; busy=true; sheet=true
        scope.launch {
            try { preview=context.app.engine.inspect(url) }
            catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                error=LinkRules.report(e)
            }
            finally { busy=false }
        }
    }
    LaunchedEffect(incoming) { if(incoming.isNotBlank()) inspect(incoming) }
    Scaffold(topBar={ TopAppBar(title={ Column { Text("LocalDrop"); Text("فيديوهاتك على جهازك.",style=MaterialTheme.typography.labelMedium) } }) },
        bottomBar={ NavigationBar {
            listOf("تنزيل","القائمة","السجل","الإعدادات").forEachIndexed { index,label ->
                NavigationBarItem(selected=tab == index,onClick={tab=index},icon={ Text(listOf("↓","≡","↺","⚙")[index]) },label={Text(label)})
            }
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(permissionError.isNotBlank()) Text(permissionError,color=MaterialTheme.colorScheme.error)
            when(tab) {
                0 -> {
                    Spacer(Modifier.height(20.dp))
                    Text("احتفظ بما تحب مشاهدته.",style=MaterialTheme.typography.headlineLarge)
                    Text("شارك رابط الفيديو مع التطبيق أو ألصقه هنا. تتم المعالجة على جهازك.")
                    OutlinedTextField(input,{input=it},label={Text("رابط الفيديو")},modifier=Modifier.fillMaxWidth(),minLines=2)
                    Button(onClick={inspect(input)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("عرض الجودات") }
                    if(clip != null) Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("يوجد رابط في الحافظة",style=MaterialTheme.typography.titleMedium)
                            Text(clip,maxLines=2)
                            Row { TextButton(onClick={onClip(clip)}) { Text("فحص الرابط") }; TextButton(onClick=onDismissClip) {Text("تجاهل")} }
                        }
                    }
                    if(error.isNotBlank() && !sheet) Text(error,color=MaterialTheme.colorScheme.error)
                    Text("بلا حسابات • بلا إعلانات • بلا تتبع",style=MaterialTheme.typography.labelLarge)
                }
                1,2 -> {
                    Text(if(tab == 1) "قائمة التنزيلات" else "سجل التنزيلات",style=MaterialTheme.typography.headlineMedium)
                    val rows=downloads.filter { (it.status in listOf(Status.COMPLETE,Status.CANCELED)) == (tab == 2) }.reversed()
                    if(rows.isEmpty()) Text(if(tab == 1) "لا توجد تنزيلات. شارك رابطًا للبدء." else "ستظهر تنزيلاتك هنا.")
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        items(rows,key={it.id}) { d -> Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(d.title,style=MaterialTheme.typography.titleMedium,maxLines=2)
                                Text("${d.status.arabic} · ${d.progress}%")
                                if(d.message.isNotBlank()) {
                                    if(d.status == Status.FAILED) FailurePanel(d.message) else Text(d.message)
                                }
                                if(d.status == Status.RUNNING) LinearProgressIndicator(progress={d.progress/100f},modifier=Modifier.fillMaxWidth())
                                Row {
                                    if(d.status in listOf(Status.QUEUED,Status.RUNNING)) TextButton(onClick={DownloadService.command(context,"pause",d.id)}) {Text("إيقاف مؤقت")}
                                    if(d.status in listOf(Status.PAUSED,Status.FAILED)) TextButton(onClick={DownloadService.command(context,"resume",d.id)}) {Text("استئناف")}
                                    if(d.status !in listOf(Status.SAVING,Status.COMPLETE,Status.CANCELED)) TextButton(onClick={DownloadService.command(context,"cancel",d.id)}) {Text("إلغاء")}
                                    if(d.status == Status.COMPLETE) TextButton(onClick={player=d.uri}) {Text("تشغيل")}
                                }
                            }
                        } }
                    }
                }
                3 -> Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) { Settings() }
            }
        }
    }
    if(sheet) ModalBottomSheet(onDismissRequest={sheet=false}) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(busy) {
                CircularProgressIndicator()
                Text("جارٍ تجهيز الأداة والبحث عن الجودات… إذا كان هناك تنزيل نشط فسننتظر اكتماله.")
                Text(extractorStatus,style=MaterialTheme.typography.bodySmall)
            }
            if(error.isNotBlank()) {
                FailurePanel(error)
                TextButton(enabled=!busy,onClick={inspect(input)}) { Text("إعادة المحاولة") }
                TextButton(onClick={sheet=false;tab=3}) { Text("فتح الإعدادات لتحديث أداة التنزيل") }
            }
            preview?.let { p ->
                AsyncImage(p.thumbnail,"صورة الفيديو",modifier=Modifier.fillMaxWidth().height(160.dp))
                Text(p.title,style=MaterialTheme.typography.titleLarge)
                Text("${p.duration/60}:${(p.duration%60).toString().padStart(2,'0')}" + if(p.fallback) " · مستخرج يوتيوب البديل" else "")
                val preferred=p.choices.firstOrNull {
                    (it.label.substringBefore('p').toIntOrNull() ?: Int.MAX_VALUE) <= store.quality
                } ?: p.choices.firstOrNull()
                preferred?.let { c -> Button(onClick={enqueue(p,c); sheet=false; tab=1}) { Text("تنزيل الجودة الافتراضية · ${c.label}") } }
                LazyColumn(Modifier.heightIn(max=300.dp)) {
                    items(p.choices) { c -> TextButton(onClick={enqueue(p,c); sheet=false; tab=1},modifier=Modifier.fillMaxWidth()) {
                        Text(c.label + if(c == preferred) " · افتراضي" else "")
                    } }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    player?.let { uri -> PlayerDialog(uri) { player=null } }
}

@Composable
private fun Settings() {
    val context=LocalContext.current; val store=context.app.store; val scope=rememberCoroutineScope()
    var quality by remember { mutableIntStateOf(store.quality) }
    var folder by remember { mutableStateOf(store.folder) }
    var wifi by remember { mutableStateOf(store.wifiOnly) }
    var updating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val extractorStatus by context.app.engine.updateStatus.collectAsStateWithLifecycle()
    Text("الإعدادات",style=MaterialTheme.typography.headlineMedium)
    Text("الجودة الافتراضية")
    Column {
        listOf(360,720,1080,2160).chunked(2).forEach { qualities ->
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                qualities.forEach { q -> FilterChip(selected=quality==q,onClick={quality=q;store.quality=q},label={Text("${q}p")}) }
            }
        }
    }
    Text("مجلد التنزيل")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("Movies","Downloads").forEach { f -> FilterChip(selected=folder==f,onClick={folder=f;store.folder=f},label={Text(if(f == "Movies") "الفيديوهات" else "التنزيلات")}) }
    }
    Text("تُحفظ الملفات داخل مجلد LocalDrop. يُحفظ الصوت في مجلد التنزيلات.")
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("واي فاي فقط"); Switch(wifi,{wifi=it;store.wifiOnly=it})
    }
    Text("اضغط استئناف لمتابعة التنزيلات المتوقفة.")
    Button(enabled=!updating,onClick={
        updating=true; result="ننتظر اكتمال العمليات النشطة ثم نبحث عن تحديث…"
        scope.launch { try { result=context.app.engine.update() }
            catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                result=LinkRules.report(e)
            }
            finally { updating=false } }
    }) {Text(if(updating) "جارٍ التحديث…" else "فحص التحديث الآن")}
    Text("تحديث تلقائي عند الاستخدام: مرة كل 24 ساعة عبر القناة التجريبية التي نجحت مع يوتيوب لديك. لا تحتاج للضغط على زر التحديث قبل التنزيل.")
    Text("إذا فشل الاتصال نستمر بالإصدار الموجود، ونحاول التحديث بعد 15 دقيقة عند الاستخدام التالي. قد تحتوي الإصدارات التجريبية على أخطاء جديدة.")
    Text(extractorStatus)
    Text(result)
    Text("لا ندعم الفيديوهات الخاصة أو المحمية أو التي تتطلب تسجيل الدخول. دعم المواقع يعتمد على أداة التنزيل.")
}

@Composable
private fun FailurePanel(report: String) {
    val context=LocalContext.current
    var expanded by remember(report) { mutableStateOf(false) }
    var copied by remember(report) { mutableStateOf(false) }
    Text(report.substringBefore("\n\nالتفاصيل الفنية:"),color=MaterialTheme.colorScheme.error)
    Row {
        TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "إخفاء التفاصيل" else "عرض التفاصيل") }
        TextButton(onClick={
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("خطأ التنزيل",report))
            copied=true
        }) { Text(if(copied) "تم النسخ" else "نسخ الخطأ") }
    }
    if(expanded) androidx.compose.foundation.text.selection.SelectionContainer {
        Text(report.substringAfter("\n\nالتفاصيل الفنية:",report),
            modifier=Modifier.heightIn(max=140.dp).verticalScroll(rememberScrollState()),
            style=MaterialTheme.typography.bodySmall)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerDialog(uri: String,onClose: ()->Unit) {
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
