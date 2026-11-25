package app.localdrop

import android.app.Activity
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun platform(url: String): String = LinkRules.platformName(url)
private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> "الحجم غير متاح"
    bytes >= 1_073_741_824 -> String.format(Locale.US,"%.1f غيغابايت",bytes/1_073_741_824.0)
    else -> String.format(Locale.US,"%.1f ميغابايت",bytes/1_048_576.0)
}
/** Compact size: "190 م.ب" / "1.2 غ.ب" / "—". */
private fun compactSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes >= 1_073_741_824 -> String.format(Locale.US,"%.1f غ.ب",bytes/1_073_741_824.0)
    bytes >= 104_857_600 -> String.format(Locale.US,"%.0f م.ب",bytes/1_048_576.0)
    else -> String.format(Locale.US,"%.1f م.ب",bytes/1_048_576.0)
}
private fun quality(d: Download)=when {
    d.mp3 -> "MP3"
    d.qualityLabel.isNotBlank() -> d.qualityLabel.substringBefore(" · ")
    d.selector == "bestaudio" -> "صوت"
    else -> "فيديو"
}
private fun formatLabel(d: Download) = if (d.mp3 || d.selector == "bestaudio") "MP3" else "MP4"
private fun downloadedBytes(d: Download) = d.progress.coerceIn(0,100) * d.estimatedBytes / 100
private fun downloadedLabel(d: Download): String {
    if (d.estimatedBytes <= 0) return quality(d)
    val done = downloadedBytes(d)
    val doneText = if (done >= 1_073_741_824) String.format(Locale.US,"%.1f",done/1_073_741_824.0)
        else String.format(Locale.US,"%.0f",done/1_048_576.0)
    val totalText = if (d.estimatedBytes >= 1_073_741_824) String.format(Locale.US,"%.1f غ.ب",d.estimatedBytes/1_073_741_824.0)
        else String.format(Locale.US,"%.0f م.ب",d.estimatedBytes/1_048_576.0)
    return "${quality(d)} · $doneText/$totalText"
}
private fun etaLabel(seconds: Long): String {
    if (seconds <= 0) return "—"
    return String.format(Locale.US,"%02d:%02d",seconds/60,seconds%60)
}
private fun speedLabel(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "—"
    return String.format(Locale.US,"%.1f م.ب/ث",bytesPerSec/1_048_576.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppScreen(incoming: String,clip: String?,permissionError: String,onClip: (String)->Unit,
                       onDismissClip: ()->Unit,enqueue: (Preview,Choice)->Unit) {
    val context=LocalContext.current
    // Download entry point: show the interstitial first, then enqueue.
    // The download always starts, even if no ad is ready.
    fun startDownload(p: Preview, c: Choice) {
        val start = { enqueue(p, c) }
        val act = context as? Activity
        if (act != null) AdsManager.showAfterDownload(act) { start() } else start()
    }
    val downloads by context.app.store.items.collectAsStateWithLifecycle()
    val extractorStatus by context.app.engine.updateStatus.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var browser by rememberSaveable { mutableStateOf(false) }
    var browserStart by rememberSaveable { mutableStateOf("https://m.youtube.com") }
    var detailId by remember { mutableStateOf<String?>(null) }
    fun inspect(text: String) {
        val urls = LinkRules.extractAll(text)
        if (urls.isEmpty()) { error = "ألصق رابط فيديو صالحًا يبدأ بـ http أو https."; return }
        if (busy) return
        AppAnalytics.linkInspected(context, urls.first(), urls.size)
        if (urls.size > 1) {
            // Batch: inspect every link and queue each with the default quality.
            input = urls.joinToString("\n"); preview = null; error = ""; busy = true; sheet = false
            scope.launch {
                var done = 0; var failed = 0
                for (u in urls) {
                    try {
                        val p = context.app.engine.inspect(u)
                        val choice = p.choices.firstOrNull {
                            (it.label.substringBefore('p').toIntOrNull() ?: Int.MAX_VALUE) <= context.app.store.quality
                        } ?: p.choices.first()
                        context.app.store.add(p, choice)
                        DownloadService.command(context)
                        done++
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failed++
                    }
                }
                busy = false
                if (failed > 0) error = "تمت إضافة $done من ${urls.size} روابط، وتعذر $failed. افحص الروابط المتعذرة واحدًا واحدًا لمعرفة السبب."
                else tab = 2
            }
            return
        }
        val url = urls.first()
        input = url; preview = null; error = ""; busy = true; sheet = true
        scope.launch {
            try { preview = context.app.engine.inspect(url) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = LinkRules.reportFor(url, e) }
            finally { busy = false }
        }
    }
    LaunchedEffect(incoming) { if(incoming.isNotBlank()) inspect(incoming) }
    val detail = detailId?.let { id -> downloads.firstOrNull { it.id == id } }
    if (detail != null) {
        val next = downloads.filter { it.status == Status.QUEUED && it.id != detail.id }
            .sortedBy { it.created }.firstOrNull()
        DownloadDetail(detail, next, onBack = { detailId = null },
            onOpenPlayer = { player = it }, onSelect = { detailId = it })
    } else Scaffold(containerColor=Palette.Background,bottomBar={ BottomTabs(if(browser) 1 else tab) {
        if(it == 1) browser = true else { browser = false; tab = it }
    } }) { inset ->
        Box(Modifier.fillMaxSize().padding(inset),contentAlignment=Alignment.TopCenter) {
            if(browser) BrowserScreen(browserStart,busy,onClose={browser=false},onDownload={inspect(it)})
            else when(tab) {
                0 -> HomeTab(input,{input=it},busy,clip,permissionError,error,downloads,
                    onPaste={ inspect(input.ifBlank {
                        context.getSystemService(ClipboardManager::class.java).primaryClip
                            ?.let { c -> if(c.itemCount>0) c.getItemAt(0).text?.toString().orEmpty() else "" }.orEmpty()
                    }) },
                    onClip={onClip(it)},onDismissClip=onDismissClip,
                    onOpenPlatform={ url -> browserStart=url;AppAnalytics.browserOpened(context,url);browser=true },
                    onShowAll={tab=2},onOpenDetail={detailId=it})
                2 -> DownloadsTab(downloads,filter,{filter=it},permissionError,
                    onOpenDetail={detailId=it},onOpenPlayer={player=it})
                else -> Column(Modifier.widthIn(max=600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement=Arrangement.spacedBy(20.dp)) { Settings() }
            }
        }
    }
    if(sheet) ModalBottomSheet(onDismissRequest={sheet=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp),containerColor=Palette.Surface,
        contentColor=Palette.Text,scrimColor=Palette.Background.copy(alpha=0.75f),
        dragHandle={ Box(Modifier.padding(top=12.dp,bottom=8.dp).size(40.dp,4.dp).clip(CircleShape).background(Palette.Outline)) }) {
        if(preview != null) QualitySheet(preview!!,context.app.store.quality,context.app.store.folder,
            onStart={c -> sheet=false;browser=false;tab=2;startDownload(preview!!,c)},onCancel={sheet=false})
        else Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(busy) {
                CircularProgressIndicator(color=Palette.Accent,strokeWidth=2.dp)
                Text("جارٍ تجهيز الفيديو…",style=MaterialTheme.typography.titleLarge)
                Text(extractorStatus,color=Palette.Muted)
            }
            if(error.isNotBlank()) {
                FailurePanel(error)
                PrimaryButton("إعادة المحاولة",{inspect(input)},enabled=!busy)
                // The preview can fail while a direct download still works
                // (the downloader resolves formats at download time).
                PrimaryButton("تحميل مباشر بأفضل جودة",{
                    val blind = Preview(input, LinkRules.platformName(input), "", 0,
                        listOf(Choice("best", "أفضل جودة · تلقائي")), false)
                    sheet = false; browser = false; tab = 2
                    startDownload(blind, blind.choices.first())
                },enabled=!busy)
                TextButton(onClick={sheet=false;browser=false;tab=3},modifier=Modifier.heightIn(min=48.dp)) { Text("فتح الإعدادات") }
            }
            TextButton(onClick={sheet=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("إلغاء",color=Palette.Muted) }
        }
    }
    player?.let { uri -> PlayerDialog(uri) { player=null } }
}

@Composable private fun HomeTab(input: String,onInput: (String)->Unit,busy: Boolean,clip: String?,
                               permissionError: String,error: String,downloads: List<Download>,
                               onPaste: ()->Unit,onClip: (String)->Unit,onDismissClip: ()->Unit,
                               onOpenPlatform: (String)->Unit,onShowAll: ()->Unit,onOpenDetail: (String)->Unit) {
    val active = downloads.filter { it.status in listOf(Status.RUNNING,Status.SAVING,Status.QUEUED,Status.PAUSED) }
        .sortedByDescending { it.created }.firstOrNull()
    LazyColumn(Modifier.widthIn(max=600.dp).fillMaxWidth(),
        contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
            verticalAlignment=Alignment.CenterVertically) {
            Text("LocalDrop",style=MaterialTheme.typography.headlineMedium)
            Surface(color=Palette.Surface,shape=CircleShape,border=BorderStroke(1.dp,Palette.Border)) {
                Text("بدون خادم · على جهازك",modifier=Modifier.padding(horizontal=12.dp,vertical=6.dp),
                    style=MaterialTheme.typography.labelMedium,color=Palette.Muted)
            }
        } }
        item { Panel {
            Text("الصق رابط فيديو، أو عدة روابط سطرًا لكل رابط",color=Palette.Muted)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(value=input,onValueChange=onInput,singleLine=true,
                    placeholder={Text("https://",color=Palette.Muted)},
                    textStyle=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.Ltr),
                    modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).semantics { contentDescription="رابط الفيديو" },
                    shape=RoundedCornerShape(14.dp),
                    colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Palette.Border,
                        focusedBorderColor=Palette.Accent,unfocusedContainerColor=Palette.Background))
            }
            PrimaryButton(if(busy) "جارٍ فحص الرابط…" else "تحليل الرابط",onClick=onPaste,enabled=!busy,icon=R.drawable.ui_clipboard)
        } }
        if(clip != null) item { Panel {
            Text("رابط في الحافظة",style=MaterialTheme.typography.titleMedium)
            Text(clip,maxLines=1,overflow=TextOverflow.Ellipsis,color=Palette.Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={onClip(clip)},shape=RoundedCornerShape(14.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=Palette.Accent,contentColor=Palette.Background),
                    contentPadding=PaddingValues(horizontal=24.dp,vertical=10.dp),
                    modifier=Modifier.heightIn(min=48.dp)) { Text("تحقق") }
                TextButton(onClick=onDismissClip,modifier=Modifier.heightIn(min=48.dp)) { Text("تجاهل",color=Palette.Muted) }
            }
        } }
        item { Text("افتح موقعًا في المتصفح",style=MaterialTheme.typography.titleMedium) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("YouTube" to "https://m.youtube.com","TikTok" to "https://www.tiktok.com",
                "Instagram" to "https://www.instagram.com","Facebook" to "https://m.facebook.com"
            ).forEach { (label,url) -> Pill(label,false) { onOpenPlatform(url) } }
        } }
        if(permissionError.isNotBlank()) item { Text(permissionError) }
        if(error.isNotBlank()) item { FailurePanel(error) }
        if(active != null) {
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=Alignment.CenterVertically) {
                Text("قيد التحميل",style=MaterialTheme.typography.titleLarge)
                TextButton(onClick=onShowAll,modifier=Modifier.heightIn(min=48.dp)) { Text("عرض الكل") }
            } }
            item { ActiveCard(active) { onOpenDetail(active.id) } }
        }
    }
}

@Composable private fun DownloadsTab(downloads: List<Download>,filter: Int,onFilter: (Int)->Unit,
                                    permissionError: String,onOpenDetail: (String)->Unit,onOpenPlayer: (String)->Unit) {
    val context=LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val rows = downloads.sortedByDescending { it.created }.filter { d ->
        when(filter) {
            1 -> d.status in listOf(Status.RUNNING,Status.SAVING,Status.QUEUED,Status.PAUSED)
            2 -> d.status == Status.COMPLETE
            3 -> d.mp3 || d.selector == "bestaudio"
            else -> true
        }
    }.filter { query.isBlank() || it.title.contains(query,ignoreCase=true) }
    val active = rows.filter { it.status in listOf(Status.RUNNING,Status.SAVING,Status.QUEUED,Status.PAUSED) }
    val completed = rows.filter { it.status == Status.COMPLETE }
    val failed = rows.filter { it.status in listOf(Status.FAILED,Status.CANCELED) }
    LazyColumn(Modifier.widthIn(max=600.dp).fillMaxWidth(),contentPadding=PaddingValues(20.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
            verticalAlignment=Alignment.CenterVertically) {
            Text("التحميلات",style=MaterialTheme.typography.headlineLarge)
            IconButton(onClick={searching=!searching;if(!searching) query=""},
                modifier=Modifier.size(48.dp).background(Palette.Surface,CircleShape)) {
                LineIcon(if(searching) R.drawable.ui_close else R.drawable.ui_search)
            }
        } }
        if(searching) item {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,
                    placeholder={Text("ابحث عن فيديو…",color=Palette.Muted)},
                    textStyle=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.Rtl),
                    modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),
                    shape=RoundedCornerShape(14.dp),
                    colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Palette.Border,
                        focusedBorderColor=Palette.Accent,unfocusedContainerColor=Palette.Background))
            }
        }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("الكل","قيد التحميل","مكتمل","صوت").forEachIndexed { index,label ->
                Pill(label,filter==index) { onFilter(index) }
            }
        } }
        if(permissionError.isNotBlank()) item { Text(permissionError) }
        if(rows.isEmpty()) item { EmptyState("لا توجد تحميلات هنا","ستظهر الملفات المطابقة لهذا الفلتر هنا.") }
        items(active,key={it.id}) { d -> ActiveCard(d) { onOpenDetail(d.id) } }
        if(completed.isNotEmpty()) {
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=Alignment.CenterVertically) {
                Text("مكتمل",style=MaterialTheme.typography.titleLarge)
                TextButton(onClick={ context.app.store.clearFinished() },modifier=Modifier.heightIn(min=48.dp)) {
                    Text("مسح المكتمل",color=Palette.Muted)
                }
            } }
            items(completed,key={it.id}) { d -> CompletedRow(d,{onOpenPlayer(d.uri)}) }
        }
        if(failed.isNotEmpty() && filter != 2) {
            item { Text("تعذر التحميل",style=MaterialTheme.typography.titleLarge) }
            items(failed,key={it.id}) { d -> FailedRow(d) }
        }
    }
}

@Composable private fun ActiveCard(d: Download,onClick: ()->Unit) {
    Surface(color=Palette.Surface,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,Palette.Border),
        modifier=Modifier.fillMaxWidth().clickable(onClick=onClick,role=Role.Button)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Thumbnail(d.thumbnail,Modifier.size(56.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(d.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                    Text(downloadedLabel(d),color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
                }
                Text("${d.progress}%",style=MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(progress={d.progress.coerceIn(0,100)/100f},
                modifier=Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color=Palette.Accent,trackColor=Palette.Elevated)
        }
    }
}

@Composable private fun CompletedRow(d: Download,onOpen: ()->Unit) {
    val context=LocalContext.current
    val actualSize by produceState(0L,d.uri) {
        if(d.uri.isNotBlank()) value=withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.query(Uri.parse(d.uri),arrayOf(OpenableColumns.SIZE),null,null,null)?.use {
                if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
            } ?: 0L }.getOrDefault(0L)
        }
    }
    Surface(color=Palette.Surface,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,Palette.Border),
        modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Surface(color=Palette.Elevated,shape=RoundedCornerShape(12.dp),
                modifier=Modifier.size(48.dp).clickable(onClick=onOpen,role=Role.Button),
                contentColor=Palette.Accent) {
                Box(contentAlignment=Alignment.Center) { LineIcon(R.drawable.ui_play,tint=Palette.Accent) }
            }
            Thumbnail(d.thumbnail,Modifier.size(56.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(d.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                Text("${quality(d)} · ${platform(d.url)} · ${compactSize(actualSize)}",color=Palette.Muted,
                    style=MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick={
                val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = if (d.mp3 || d.selector == "bestaudio") "audio/*" else "video/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, Uri.parse(d.uri))
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                AppAnalytics.fileShared(context, d)
                context.startActivity(android.content.Intent.createChooser(share, "مشاركة"))
            },modifier=Modifier.size(48.dp)) { LineIcon(R.drawable.ui_share) }
            IconButton(onClick={ context.app.store.delete(d.id) },modifier=Modifier.size(48.dp)) {
                LineIcon(R.drawable.ui_close)
            }
        }
    }
}

@Composable private fun FailedRow(d: Download) {
    val context=LocalContext.current
    Surface(color=Palette.Surface,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,Palette.Border),
        modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Thumbnail(d.thumbnail,Modifier.size(56.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(d.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                Text(d.status.arabic,color=Palette.Danger,style=MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick={ context.app.store.delete(d.id) },modifier=Modifier.size(48.dp)) {
                LineIcon(R.drawable.ui_close)
            }
        }
    }
}

@Composable private fun DownloadDetail(d: Download,next: Download?,onBack: ()->Unit,
                                      onOpenPlayer: (String)->Unit,onSelect: (String)->Unit) {
    val context=LocalContext.current
    BackHandler { onBack() }
    var speed by remember(d.id) { mutableLongStateOf(0L) }
    var etaSec by remember(d.id) { mutableLongStateOf(0L) }
    var lastBytes by remember(d.id) { mutableLongStateOf(-1L) }
    var lastTime by remember(d.id) { mutableLongStateOf(0L) }
    LaunchedEffect(d.progress,d.status) {
        if (d.status != Status.RUNNING || d.estimatedBytes <= 0) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val downloaded = downloadedBytes(d)
        if (lastBytes >= 0 && now > lastTime) {
            val instant = (downloaded - lastBytes) * 1000 / (now - lastTime)
            if (instant > 0) {
                speed = if (speed == 0L) instant else (speed * 7 + instant * 3) / 10
                etaSec = (d.estimatedBytes - downloaded) / speed
            }
        }
        lastBytes = downloaded; lastTime = now
    }
    val statusTitle = when(d.status) {
        Status.RUNNING -> "جارِ التحميل"
        Status.QUEUED -> "في الانتظار"
        Status.SAVING -> "جارٍ الحفظ"
        Status.PAUSED -> "متوقف مؤقتًا"
        Status.COMPLETE -> "اكتمل التحميل"
        Status.FAILED -> "تعذر التحميل"
        Status.CANCELED -> "ملغى"
    }
    Column(Modifier.fillMaxSize().background(Palette.Background).verticalScroll(rememberScrollState())
        .padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp),
        horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            Text(statusTitle,modifier=Modifier.align(Alignment.Center),
                style=MaterialTheme.typography.headlineMedium)
            IconButton(onClick=onBack,modifier=Modifier.align(Alignment.CenterStart)
                .size(48.dp).background(Palette.Surface,CircleShape)) {
                LineIcon(R.drawable.ui_chevron)
            }
        }
        ProgressRing(d.progress,"${quality(d)} · ${formatLabel(d)}")
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(d.title,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis,
                textAlign=TextAlign.Center)
            Text(platform(d.url),color=Palette.Muted)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            StatBox("السرعة",speedLabel(speed),Modifier.weight(1f))
            StatBox("المتبقي",etaLabel(etaSec),Modifier.weight(1f))
            StatBox("الحجم",if(d.estimatedBytes > 0) downloadedLabel(d).substringAfter(" · ") else "—",Modifier.weight(1f))
        }
        StagePills(d.status)
        if(d.status == Status.FAILED) FailurePanel(d.message)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            when(d.status) {
                Status.RUNNING, Status.QUEUED -> {
                    Button(onClick={DownloadService.command(context,"pause",d.id)},
                        shape=RoundedCornerShape(16.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=Palette.Surface,contentColor=Palette.Text),
                        modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text("إيقاف مؤقت") }
                    Button(onClick={DownloadService.command(context,"cancel",d.id)},
                        shape=RoundedCornerShape(16.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=Palette.Surface,contentColor=Palette.Danger),
                        modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text("إلغاء") }
                }
                Status.PAUSED -> {
                    PrimaryButton("استئناف",{DownloadService.command(context,"resume",d.id)},
                        modifier=Modifier.weight(1f))
                    Button(onClick={DownloadService.command(context,"cancel",d.id)},
                        shape=RoundedCornerShape(16.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=Palette.Surface,contentColor=Palette.Danger),
                        modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text("إلغاء") }
                }
                Status.FAILED -> {
                    PrimaryButton("إعادة المحاولة",{DownloadService.command(context,"resume",d.id)},
                        modifier=Modifier.weight(1f))
                    Button(onClick={DownloadService.command(context,"cancel",d.id)},
                        shape=RoundedCornerShape(16.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=Palette.Surface,contentColor=Palette.Danger),
                        modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text("إلغاء") }
                }
                Status.COMPLETE -> {
                    PrimaryButton("فتح",{onOpenPlayer(d.uri)},modifier=Modifier.weight(1f))
                    Button(onClick={
                        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = if (d.mp3 || d.selector == "bestaudio") "audio/*" else "video/*"
                            putExtra(android.content.Intent.EXTRA_STREAM, Uri.parse(d.uri))
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        AppAnalytics.fileShared(context, d)
                        context.startActivity(android.content.Intent.createChooser(share, "مشاركة"))
                    },shape=RoundedCornerShape(16.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=Palette.Surface,contentColor=Palette.Text),
                        modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text("مشاركة") }
                }
                else -> {}
            }
        }
        if(next != null) Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("التالي في القائمة",color=Palette.Muted)
            ActiveCard(next) { onSelect(next.id) }
        }
    }
}

@Composable private fun ProgressRing(progress: Int,label: String) {
    Box(Modifier.size(220.dp),contentAlignment=Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val arcSize = Size(size.width - stroke,size.height - stroke)
            val arcTopLeft = Offset(stroke/2,stroke/2)
            drawArc(Palette.Elevated,-90f,360f,false,topLeft=arcTopLeft,size=arcSize,
                style=Stroke(stroke))
            drawArc(Palette.Accent,-90f,360f*progress.coerceIn(0,100)/100f,false,topLeft=arcTopLeft,
                size=arcSize,style=Stroke(stroke,cap=StrokeCap.Round))
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("$progress%",style=MaterialTheme.typography.displayLarge)
            Text(label,color=Palette.Muted)
        }
    }
}

@Composable private fun StatBox(label: String,value: String,modifier: Modifier = Modifier) {
    Surface(color=Palette.Surface,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Palette.Border),
        modifier=modifier) {
        Column(Modifier.padding(vertical=14.dp,horizontal=4.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(label,color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
            Text(value,style=MaterialTheme.typography.titleMedium,maxLines=1)
        }
    }
}

@Composable private fun StagePills(status: Status) {
    // 0 done, 1 active, 2 upcoming
    val state = when(status) {
        Status.QUEUED -> listOf(0,2,2,2)
        Status.RUNNING -> listOf(0,1,2,2)
        Status.SAVING -> listOf(0,0,1,2)
        Status.COMPLETE -> listOf(0,0,0,0)
        else -> listOf(0,2,2,2)
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("تحليل","تحميل","دمج","حفظ").forEachIndexed { i,label ->
            Surface(color=if(state[i]==1) Palette.Accent else Palette.Surface,
                shape=CircleShape,border=BorderStroke(1.dp,if(state[i]==1) Palette.Accent else Palette.Border)) {
                Row(Modifier.padding(horizontal=14.dp,vertical=8.dp),
                    verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(state[i]==0) LineIcon(R.drawable.ui_check,tint=Palette.Accent,modifier=Modifier.size(16.dp))
                    Text(label,color=when(state[i]) {
                        1 -> Palette.Background
                        0 -> Palette.Text
                        else -> Palette.Muted
                    },style=MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable private fun BottomTabs(selected: Int,onSelect: (Int)->Unit) {
    Surface(color=Palette.Background,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color=Palette.Border)
            Row(Modifier.fillMaxWidth().height(76.dp)) {
                val icons=listOf(R.drawable.ui_home,R.drawable.ui_globe,R.drawable.ui_download,R.drawable.ui_settings)
                listOf("الرئيسية","المتصفح","التحميلات","الإعدادات").forEachIndexed { i,label ->
                    Column(Modifier.weight(1f).fillMaxHeight().selectable(selected==i,onClick={onSelect(i)},role=Role.Tab),
                        horizontalAlignment=Alignment.CenterHorizontally) {
                        Box(Modifier.width(32.dp).height(3.dp).clip(CircleShape).background(if(selected==i) Palette.Accent else Palette.Background))
                        Spacer(Modifier.height(12.dp))
                        LineIcon(icons[i],tint=if(selected==i) Palette.Accent else Palette.Muted)
                        Spacer(Modifier.height(4.dp))
                        Text(label,color=if(selected==i) Palette.Accent else Palette.Muted,style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
@Composable internal fun Pill(text: String,selected: Boolean,onClick: ()->Unit) {
    Surface(color=if(selected) Palette.Accent else Palette.Surface,shape=CircleShape,
        border=BorderStroke(1.dp,if(selected) Palette.Accent else Palette.Border),
        modifier=Modifier.heightIn(min=48.dp).selectable(selected,onClick=onClick,role=Role.RadioButton)) {
        Box(Modifier.padding(horizontal=20.dp,vertical=10.dp),contentAlignment=Alignment.Center) {
            Text(text,color=if(selected) Palette.Background else Palette.Muted,style=MaterialTheme.typography.labelLarge)
        }
    }
}
@Composable private fun Thumbnail(url: String,modifier: Modifier,play: Boolean=false) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Elevated),contentAlignment=Alignment.Center) {
        LineIcon(R.drawable.ui_video)
        if(url.isNotBlank()) AsyncImage(url,null,modifier=Modifier.matchParentSize(),contentScale=ContentScale.Crop)
        if(play) Box(Modifier.size(32.dp).clip(CircleShape).background(Palette.Background.copy(alpha=0.8f)),contentAlignment=Alignment.Center) {
            LineIcon(R.drawable.ui_play,tint=Palette.Text,modifier=Modifier.size(18.dp))
        }
    }
}
@Composable private fun EmptyState(title: String,subtitle: String) {
    Panel { LineIcon(R.drawable.ui_download);Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,color=Palette.Muted) }
}

@Composable private fun QualitySheet(p: Preview,defaultQuality: Int,folder: String,
                                    onStart: (Choice)->Unit,onCancel: ()->Unit) {
    val videoChoices = remember(p) { p.choices.filter { !it.mp3 } }
    val audioChoices = remember(p) { p.choices.filter { it.mp3 } }
    var mode by remember(p) { mutableIntStateOf(if(videoChoices.isNotEmpty()) 0 else 1) }
    val list = if(mode == 0) videoChoices else audioChoices
    val recommended = remember(p,defaultQuality) { videoChoices.firstOrNull {
        (it.label.substringBefore('p').toIntOrNull() ?: Int.MAX_VALUE) <= defaultQuality
    } ?: videoChoices.firstOrNull() }
    var selected by remember(p,mode) {
        mutableStateOf(if(mode == 0) recommended ?: list.firstOrNull() else list.firstOrNull())
    }
    val chosen = selected
    Column(Modifier.fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp * 0.86f).padding(horizontal=20.dp).navigationBarsPadding()) {
        Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Thumbnail(p.thumbnail,Modifier.size(112.dp,64.dp),play=true)
                Column(Modifier.weight(1f)) {
                    Text(p.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("${platform(p.url)} · ${p.duration/60}:${(p.duration%60).toString().padStart(2,'0')}",color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
                }
            }
            Surface(color=Palette.Elevated,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                Row(Modifier.padding(4.dp)) {
                    listOf("فيديو","صوت MP3").forEachIndexed { i,label ->
                        val on = mode == i
                        val enabled = if(i == 0) videoChoices.isNotEmpty() else audioChoices.isNotEmpty()
                        Surface(color=if(on) Palette.Accent else Palette.Elevated,shape=RoundedCornerShape(12.dp),
                            modifier=Modifier.weight(1f).heightIn(min=48.dp).selectable(on,onClick={if(enabled) mode=i},role=Role.RadioButton)) {
                            Box(contentAlignment=Alignment.Center) {
                                Text(label,color=if(on) Palette.Background else Palette.Muted,
                                    style=MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            list.forEach { choice ->
                val on = chosen == choice
                val isRecommended = mode == 0 && choice == recommended
                Surface(color=if(on) Palette.Elevated else Palette.Background,shape=RoundedCornerShape(16.dp),
                    border=BorderStroke(if(on) 2.dp else 1.dp,if(on) Palette.Accent else Palette.Border),
                    modifier=Modifier.fillMaxWidth().selectable(on,onClick={selected=choice},role=Role.RadioButton)) {
                    Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(24.dp),contentAlignment=Alignment.Center) {
                            Box(Modifier.size(24.dp).clip(CircleShape)
                                .border(2.dp,if(on) Palette.Accent else Palette.Outline,CircleShape),
                                contentAlignment=Alignment.Center) {
                                if(on) Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.Accent))
                            }
                        }
                        Text(choice.label.substringBefore(" ·"),modifier=Modifier.weight(1f),
                            style=MaterialTheme.typography.titleMedium)
                        if(isRecommended) Surface(color=Palette.Accent,shape=CircleShape) {
                            Text("موصى به",modifier=Modifier.padding(horizontal=10.dp,vertical=4.dp),
                                color=Palette.Background,style=MaterialTheme.typography.labelSmall)
                        }
                        Text(if(choice.estimatedBytes > 0) "~${compactSize(choice.estimatedBytes)}" else "—",
                            color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("الحفظ في: ${if(folder=="Movies") "الأفلام" else "التنزيلات"}",color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
                Text("الحد الافتراضي: ${defaultQuality}p",color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        if(chosen != null) PrimaryButton(
            if(chosen.mp3) "تحميل MP3" else "تحميل ${chosen.label.substringBefore(" ·")}",
            {onStart(chosen)},modifier=Modifier.heightIn(min=56.dp),icon=R.drawable.ui_download)
        TextButton(onClick=onCancel,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("إلغاء",color=Palette.Muted) }
    }
}
