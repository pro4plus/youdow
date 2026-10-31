package app.localdrop

import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

private fun platform(url: String): String {
    val host=runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
    return when {
        LinkRules.youtube(url) -> "يوتيوب"
        host.endsWith("tiktok.com") -> "تيك توك"
        host.endsWith("instagram.com") -> "إنستغرام"
        host.endsWith("facebook.com") || host == "fb.watch" -> "فيسبوك"
        host.endsWith("snapchat.com") -> "سناب شات"
        else -> host.ifBlank { "فيديو" }
    }
}
private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> "الحجم غير متاح"
    bytes >= 1_073_741_824 -> String.format(Locale.US,"%.1f غيغابايت",bytes/1_073_741_824.0)
    else -> String.format(Locale.US,"%.1f ميغابايت",bytes/1_048_576.0)
}
private fun quality(d: Download)=when {
    d.mp3 -> "MP3"
    d.qualityLabel.isNotBlank() -> d.qualityLabel.substringBefore(" · ")
    d.selector == "bestaudio" -> "صوت"
    else -> "فيديو"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppScreen(incoming: String,clip: String?,permissionError: String,onClip: (String)->Unit,
                       onDismissClip: ()->Unit,enqueue: (Preview,Choice)->Unit) {
    val context=LocalContext.current
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
    fun inspect(text: String) {
        val url=LinkRules.extract(text)
        if(url == null) { error="ألصق رابط فيديو صالحًا يبدأ بـ http أو https.";return }
        if(busy) return
        input=url;preview=null;error="";busy=true;sheet=true
        scope.launch {
            try { preview=context.app.engine.inspect(url) }
            catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; error=LinkRules.report(e) }
            finally { busy=false }
        }
    }
    LaunchedEffect(incoming) { if(incoming.isNotBlank()) inspect(incoming) }
    Scaffold(containerColor=Palette.Background,bottomBar={ BottomTabs(tab) { tab=it } }) { inset ->
        Box(Modifier.fillMaxSize().padding(inset),contentAlignment=Alignment.TopCenter) {
            when(tab) {
                0 -> LazyColumn(Modifier.widthIn(max=600.dp).fillMaxWidth(),
                    contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    item { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("لوكال دروب",color=Palette.Accent,style=MaterialTheme.typography.labelLarge)
                        Text("حمّل أي فيديو،\nبلمسة واحدة.",style=MaterialTheme.typography.headlineLarge)
                        Text("يعمل مباشرة من جهازك، دون حساب ودون سيرفر.",color=Palette.Muted)
                    } }
                    item { Panel {
                        Text("رابط الفيديو",style=MaterialTheme.typography.titleMedium)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(value=input,onValueChange={input=it},singleLine=true,
                                placeholder={Text("https://",color=Palette.Muted)},
                                textStyle=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.Ltr),
                                modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).semantics { contentDescription="رابط الفيديو" },
                                shape=RoundedCornerShape(14.dp),
                                colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Palette.Border,
                                    focusedBorderColor=Palette.Accent,unfocusedContainerColor=Palette.Background))
                        }
                        PrimaryButton(if(busy) "جارٍ فحص الرابط…" else "الصق وحمّل",onClick={
                            val pasted=context.getSystemService(ClipboardManager::class.java).primaryClip
                                ?.let { if(it.itemCount>0) it.getItemAt(0).text?.toString().orEmpty() else "" }.orEmpty()
                            inspect(input.ifBlank { pasted })
                        },enabled=!busy,icon=R.drawable.ui_clipboard)
                    } }
                    if(permissionError.isNotBlank()) item { Text(permissionError) }
                    if(error.isNotBlank() && !sheet) item { FailurePanel(error) }
                    item { Row(Modifier.fillMaxWidth().drawBehind {
                        drawRoundRect(Palette.Outline,cornerRadius=CornerRadius(16.dp.toPx()),
                            style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(),5.dp.toPx()))))
                    }.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        LineIcon(R.drawable.ui_share)
                        Text("أسرع طريقة: من أي تطبيق اضغط مشاركة ثم اختر لوكال دروب",color=Palette.Muted,
                            style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
                    } }
                    if(clip != null) item { Panel {
                        Text("رابط جاهز من الحافظة",style=MaterialTheme.typography.titleMedium)
                        Text(clip,maxLines=1,overflow=TextOverflow.Ellipsis,color=Palette.Muted)
                        Row {
                            TextButton(onClick={onClip(clip)},modifier=Modifier.heightIn(min=48.dp)) { Text("فحص الرابط") }
                            TextButton(onClick=onDismissClip,modifier=Modifier.heightIn(min=48.dp)) { Text("تجاهل",color=Palette.Muted) }
                        }
                    } }
                    item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("آخر التحميلات",style=MaterialTheme.typography.titleLarge)
                        TextButton(onClick={tab=1},modifier=Modifier.heightIn(min=48.dp)) { Text("عرض الكل") }
                    } }
                    if(downloads.isEmpty()) item { EmptyState("لا توجد تحميلات بعد","ألصق رابطك الأول، وستجد فيديوهاتك هنا.") }
                    items(downloads.sortedByDescending { it.created }.take(3),key={it.id}) { d ->
                        RecentRow(d) { if(d.status == Status.COMPLETE) player=d.uri else tab=1 }
                    }
                }
                1 -> {
                    val rows=downloads.sortedByDescending { it.created }.filter { d ->
                        when(filter) { 1 -> d.status in listOf(Status.RUNNING,Status.SAVING,Status.QUEUED)
                            2 -> d.status == Status.COMPLETE; else -> true }
                    }
                    LazyColumn(Modifier.widthIn(max=600.dp).fillMaxWidth(),contentPadding=PaddingValues(20.dp),
                        verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        item { Text("التحميلات",style=MaterialTheme.typography.headlineLarge) }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("الكل","جارٍ","مكتمل").forEachIndexed { index,label -> Pill(label,filter==index) { filter=index } }
                        } }
                        if(permissionError.isNotBlank()) item { Text(permissionError) }
                        if(rows.isEmpty()) item { EmptyState("لا توجد تحميلات هنا","ستظهر الملفات المطابقة لهذا الفلتر هنا.") }
                        items(rows,key={it.id}) { d -> DownloadCard(d,onOpen={player=d.uri}) }
                    }
                }
                else -> Column(Modifier.widthIn(max=600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement=Arrangement.spacedBy(20.dp)) { Settings() }
            }
        }
    }
    if(sheet) ModalBottomSheet(onDismissRequest={sheet=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp),containerColor=Palette.Surface,
        contentColor=Palette.Text,scrimColor=Palette.Background.copy(alpha=0.75f),
        dragHandle={ Box(Modifier.padding(top=12.dp,bottom=8.dp).size(40.dp,4.dp).clip(CircleShape).background(Palette.Outline)) }) {
        if(preview != null) QualitySheet(preview!!,context.app.store.quality,
            onStart={c -> enqueue(preview!!,c);sheet=false;tab=1},onCancel={sheet=false})
        else Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(busy) {
                CircularProgressIndicator(color=Palette.Accent,strokeWidth=2.dp)
                Text("جارٍ تجهيز الفيديو…",style=MaterialTheme.typography.titleLarge)
                Text(extractorStatus,color=Palette.Muted)
            }
            if(error.isNotBlank()) {
                FailurePanel(error)
                PrimaryButton("إعادة المحاولة",{inspect(input)},enabled=!busy)
                TextButton(onClick={sheet=false;tab=2},modifier=Modifier.heightIn(min=48.dp)) { Text("فتح إعدادات أداة التنزيل") }
            }
            TextButton(onClick={sheet=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("إلغاء",color=Palette.Muted) }
        }
    }
    player?.let { uri -> PlayerDialog(uri) { player=null } }
}

@Composable private fun BottomTabs(selected: Int,onSelect: (Int)->Unit) {
    Surface(color=Palette.Background,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color=Palette.Border)
            Row(Modifier.fillMaxWidth().height(76.dp)) {
                val icons=listOf(R.drawable.ui_home,R.drawable.ui_download,R.drawable.ui_settings)
                listOf("الرئيسية","التحميلات","الإعدادات").forEachIndexed { i,label ->
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
@Composable private fun RecentRow(d: Download,onClick: ()->Unit) {
    Surface(color=Palette.Surface,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Palette.Border),
        modifier=Modifier.fillMaxWidth().clickable(onClick=onClick,role=Role.Button)) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Thumbnail(d.thumbnail,Modifier.size(48.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(d.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                Text("${platform(d.url)} · ${quality(d)} · ${sizeLabel(d.estimatedBytes)}",color=Palette.Muted,
                    maxLines=2,style=MaterialTheme.typography.bodySmall)
            }
            LineIcon(if(d.status==Status.COMPLETE) R.drawable.ui_play else R.drawable.ui_chevron)
        }
    }
}
@Composable private fun DownloadCard(d: Download,onOpen: ()->Unit) {
    val context=LocalContext.current
    val actualSize by produceState(0L,d.uri) {
        if(d.uri.isNotBlank()) value=withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.query(Uri.parse(d.uri),arrayOf(OpenableColumns.SIZE),null,null,null)?.use {
                if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
            } ?: 0L }.getOrDefault(0L)
        }
    }
    Panel {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Thumbnail(d.thumbnail,Modifier.size(56.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(d.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                Text("${platform(d.url)} · ${quality(d)}",style=MaterialTheme.typography.bodySmall,color=Palette.Muted)
            }
            if(d.status in listOf(Status.RUNNING,Status.QUEUED,Status.PAUSED,Status.FAILED)) {
                val pause=d.status in listOf(Status.RUNNING,Status.QUEUED)
                IconButton(onClick={DownloadService.command(context,if(pause) "pause" else "resume",d.id)},
                    modifier=Modifier.size(48.dp).border(1.dp,Palette.Outline,CircleShape)) {
                    LineIcon(if(pause) R.drawable.ui_pause else R.drawable.ui_play,
                        if(pause) "إيقاف التنزيل مؤقتًا" else "استئناف التنزيل",tint=Palette.Text)
                }
            }
        }
        if(d.status in listOf(Status.RUNNING,Status.QUEUED,Status.PAUSED)) LinearProgressIndicator(
            progress={d.progress.coerceIn(0,100)/100f},modifier=Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color=Palette.Accent,trackColor=Palette.Elevated)
        if(d.status == Status.SAVING) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp),color=Palette.Accent,trackColor=Palette.Elevated)
        val status=when(d.status) {
            Status.COMPLETE -> if(d.folder=="Movies" && !d.mp3 && d.selector!="bestaudio") "مكتمل · تم الحفظ في المعرض" else "مكتمل · تم الحفظ في التنزيلات"
            Status.RUNNING -> "جارٍ التحميل · ${d.progress}%"
            else -> d.status.arabic
        }
        Text(status,style=MaterialTheme.typography.bodyMedium,color=if(d.status==Status.COMPLETE) Palette.Text else Palette.Muted)
        if(d.status == Status.FAILED) FailurePanel(d.message)
        else if(d.status !in listOf(Status.COMPLETE,Status.CANCELED) && d.message.isNotBlank()) Text(d.message,color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
        if(d.status==Status.COMPLETE) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text(sizeLabel(actualSize),color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=onOpen,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,Palette.Outline),
                modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Palette.Text)) {
                LineIcon(R.drawable.ui_play,tint=Palette.Text);Spacer(Modifier.width(6.dp));Text("فتح")
            }
        }
        if(d.status !in listOf(Status.COMPLETE,Status.CANCELED,Status.SAVING)) TextButton(onClick={DownloadService.command(context,"cancel",d.id)},modifier=Modifier.heightIn(min=48.dp)) {
            LineIcon(R.drawable.ui_close);Spacer(Modifier.width(6.dp));Text("إلغاء التحميل",color=Palette.Muted)
        }
    }
}

@Composable private fun QualitySheet(p: Preview,defaultQuality: Int,onStart: (Choice)->Unit,onCancel: ()->Unit) {
    val preferred=remember(p,defaultQuality) { p.choices.firstOrNull {
        (it.label.substringBefore('p').toIntOrNull() ?: Int.MAX_VALUE) <= defaultQuality
    } ?: p.choices.first() }
    var selected by remember(p) { mutableStateOf(preferred) }
    var expanded by remember(p) { mutableStateOf(false) }
    val cards=remember(p) { listOf(1080,720,480).map { height ->
        "${height}p" to p.choices.firstOrNull { it.label.startsWith("${height}p ·") }
    } + listOf("صوت MP3" to p.choices.firstOrNull { it.mp3 }) }
    Column(Modifier.fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp * 0.86f).padding(horizontal=20.dp).navigationBarsPadding()) {
        Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Thumbnail(p.thumbnail,Modifier.size(112.dp,64.dp),play=true)
                Column(Modifier.weight(1f)) {
                    Text(p.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("${platform(p.url)} · ${p.duration/60}:${(p.duration%60).toString().padStart(2,'0')}",color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
                }
            }
            Text("اختر الجودة",style=MaterialTheme.typography.titleLarge)
            cards.chunked(2).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEach { (title,choice) -> QualityCard(title,choice,selected==choice,{if(choice!=null) selected=choice},Modifier.weight(1f)) }
            } }
            if(selected !in cards.map { it.second }) Text("المحدد: ${selected.label}",color=Palette.Accent,style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text(if(expanded) "إخفاء الجودات الإضافية" else "كل الجودات والصيغ (${p.choices.size})")
            }
            if(expanded) p.choices.forEach { choice ->
                QualityCard(choice.label,choice,selected==choice,{selected=choice},Modifier.fillMaxWidth())
            }
            Text("الأحجام تقديرية عندما يوفرها المصدر.",color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("ابدأ التحميل",{onStart(selected)},modifier=Modifier.heightIn(min=56.dp),icon=R.drawable.ui_download)
        TextButton(onClick=onCancel,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("إلغاء",color=Palette.Muted) }
    }
}
@Composable private fun QualityCard(title: String,choice: Choice?,selected: Boolean,onClick: ()->Unit,modifier: Modifier) {
    Surface(color=if(selected) Palette.Elevated else Palette.Background,shape=RoundedCornerShape(16.dp),
        border=BorderStroke(if(selected) 2.dp else 1.dp,if(selected) Palette.Accent else Palette.Border),
        modifier=modifier.heightIn(min=94.dp).selectable(selected,onClick=onClick,enabled=choice!=null,role=Role.RadioButton)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text(title,modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,
                    color=if(choice==null) Palette.Muted else Palette.Text)
                if(selected) LineIcon(R.drawable.ui_check,tint=Palette.Accent,modifier=Modifier.size(18.dp))
            }
            Text(if(choice==null) "غير متاحة" else sizeLabel(choice.estimatedBytes),color=Palette.Muted,style=MaterialTheme.typography.bodySmall)
        }
    }
}
