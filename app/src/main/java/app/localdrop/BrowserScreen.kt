package app.localdrop

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import org.json.JSONTokener
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

private data class DetectedVideo(val url: String,val title: String,val thumbnail: String)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BrowserScreen(initialUrl: String,busy: Boolean,onClose: ()->Unit,onDownload: (String)->Unit) {
    val context=LocalContext.current
    val download by rememberUpdatedState(onDownload)
    var address by remember { mutableStateOf(initialUrl) }
    var current by remember { mutableStateOf(initialUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var back by remember { mutableStateOf(false) }
    var forward by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<List<DetectedVideo>>(emptyList()) }
    var choosing by remember { mutableStateOf(false) }
    var detectedPage by remember { mutableStateOf("") }
    var protectedVideo by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(true) }
    val detectionScript=remember { context.assets.open("detect-video.js").bufferedReader().use { it.readText() } }
    val web=remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(17,20,26))
            settings.apply {
                javaScriptEnabled=true
                domStorageEnabled=true
                allowFileAccess=false
                allowContentAccess=false
                mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically=false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture=true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
            webChromeClient=object: WebChromeClient() {
                override fun onProgressChanged(view: WebView,newProgress: Int) { progress=newProgress }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
            webViewClient=object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                    val blocked=BrowserRules.address(request.url.toString())==null
                    if(blocked && request.isForMainFrame) error="هذا الرابط لا يُفتح داخل المتصفح. افتح صفحة الفيديو الآمنة عبر https."
                    return blocked
                }
                override fun onPageStarted(view: WebView,url: String,favicon: Bitmap?) {
                    generation++;detected=emptyList();choosing=false;detectedPage="";protectedVideo=false
                    current=url;address=url;error=""
                }
                override fun doUpdateVisitedHistory(view: WebView,url: String,isReload: Boolean) {
                    if(current!=url) { generation++;detected=emptyList();choosing=false;detectedPage="";protectedVideo=false }
                    current=url;address=url;back=view.canGoBack();forward=view.canGoForward()
                }
                override fun onPageFinished(view: WebView,url: String) {
                    current=url;address=url;back=view.canGoBack();forward=view.canGoForward()
                }
                override fun onReceivedError(view: WebView,request: WebResourceRequest,e: WebResourceError) {
                    if(request.isForMainFrame) error="تعذر فتح الصفحة. تحقق من اتصالك ثم اضغط تحديث."
                }
                override fun onReceivedHttpError(view: WebView,request: WebResourceRequest,response: WebResourceResponse) {
                    if(request.isForMainFrame) error="تعذر فتح الصفحة ("+response.statusCode+"). جرّب رابط الفيديو مباشرة."
                }
                override fun onReceivedSslError(view: WebView,handler: android.webkit.SslErrorHandler,e: android.net.http.SslError) {
                    handler.cancel();error="تعذر التحقق من أمان اتصال الموقع. لم تُفتح الصفحة."
                }
            }
            setDownloadListener { _,_,_,_,_ ->
                // Page download events must not auto-start extraction or expose arbitrary files.
                error="شغّل الفيديو داخل الصفحة ليظهر زر التنزيل عند اكتشافه."
            }
            loadUrl(initialUrl)
        }
    }
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(web,active) {
        if(!active) return@LaunchedEffect
        while(isActive) {
            val page=web.url.orEmpty()
            val token=generation
            web.evaluateJavascript(detectionScript) { raw ->
                if(active && token==generation && page==web.url) {
                    val result=runCatching {
                        val decoded=JSONTokener(raw).nextValue() as? String ?: return@runCatching null
                        if(decoded.length>32_768) return@runCatching null
                        JSONObject(decoded).takeIf { it.optString("page")==page }
                    }.getOrNull()
                    val videos=result?.optJSONArray("videos")
                    if(!choosing) detected=if(videos==null) emptyList() else (0 until minOf(videos.length(),20)).mapNotNull { i ->
                        val item=videos.optJSONObject(i) ?: return@mapNotNull null
                        val target=item.optString("target").takeIf { it.startsWith("https://") }
                            ?.let { BrowserRules.address(it) } ?: return@mapNotNull null
                        DetectedVideo(target,item.optString("title","فيديو").take(160),
                            item.optString("thumbnail").takeIf { it.startsWith("https://") }?.let { BrowserRules.address(it) }.orEmpty())
                    }.distinctBy { it.url }
                    protectedVideo=result?.optBoolean("drm")==true
                    detectedPage=page
                }
            }
            delay(1500)
        }
    }
    DisposableEffect(web,owner) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) { active=true;web.onResume() }
            if(event==Lifecycle.Event.ON_PAUSE) { active=false;generation++;web.onPause() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            active=false;generation++
            owner.lifecycle.removeObserver(observer)
            web.stopLoading();web.onPause();web.removeAllViews();web.destroy()
        }
    }
    BackHandler { if(web.canGoBack()) web.goBack() else onClose() }
    fun navigate() {
        val url=BrowserRules.address(address)
        if(url==null) error="أدخل عنوان موقع صالحًا مثل youtube.com أو رابط https."
        else { error="";web.loadUrl(url) }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick=onClose,modifier=Modifier.heightIn(min=48.dp)) { Text("إغلاق المتصفح") }
            TextButton(onClick={web.reload()},modifier=Modifier.heightIn(min=48.dp)) { Text("تحديث") }
        }
        Row(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(address,{address=it},modifier=Modifier.weight(1f),singleLine=true,
                    label={Text("عنوان الموقع")},textStyle=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.Ltr),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,imeAction=ImeAction.Go),
                    keyboardActions=KeyboardActions(onGo={navigate()}))
            }
            TextButton(onClick={navigate()},modifier=Modifier.heightIn(min=56.dp)) { Text("فتح") }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={web.goBack()},enabled=back,modifier=Modifier.heightIn(min=48.dp)) { Text("السابق") }
            TextButton(onClick={web.goForward()},enabled=forward,modifier=Modifier.heightIn(min=48.dp)) { Text("التالي") }
        }
        if(progress<100) LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth(),color=Palette.Accent)
        if(error.isNotBlank()) Text(error,modifier=Modifier.padding(12.dp),color=Palette.Text)
        AndroidView(factory={web},modifier=Modifier.fillMaxWidth().weight(1f))
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            if(detected.isNotEmpty() && detectedPage==current) {
                PrimaryButton(if(busy) "جارٍ فحص الفيديو…" else if(detected.size>1) "اختر فيديو للتنزيل (${detected.size})" else "تنزيل الفيديو المكتشف",onClick={
                    // Never download the previous video's URL after SPA navigation.
                    if(detectedPage==web.url) {
                        if(detected.size>1) choosing=true else detected.firstOrNull()?.let { download(it.url) }
                    }
                },enabled=!busy,icon=R.drawable.ui_download)
                Text("تم اكتشاف فيديو. اضغط لعرض الجودات المتاحة.",style=MaterialTheme.typography.bodySmall,color=Palette.Muted)
            } else Text(if(protectedVideo) "الفيديو محمي ولا يمكن تنزيله." else
                "لم يُكتشف فيديو بعد. افتح الفيديو أو شغّله داخل الصفحة.",style=MaterialTheme.typography.bodySmall,color=Palette.Muted)
        }
    }
    if(choosing) AlertDialog(onDismissRequest={choosing=false},title={Text("اختر الفيديو")},
        confirmButton={},dismissButton={TextButton(onClick={choosing=false}) { Text("إلغاء") }},
        text={ LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            itemsIndexed(detected,key={_,v -> v.url}) { index,video ->
                Row(Modifier.fillMaxWidth().heightIn(min=72.dp).clickable(enabled=!busy) {
                    if(detectedPage==web.url) { choosing=false;download(video.url) }
                }.padding(8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(video.thumbnail.isNotBlank()) AsyncImage(video.thumbnail,null,modifier=Modifier.size(64.dp,48.dp),contentScale=ContentScale.Crop)
                    else LineIcon(R.drawable.ui_video)
                    Column(Modifier.weight(1f)) {
                        Text("${index+1}. ${video.title}",maxLines=2)
                        Text("عرض الجودات",style=MaterialTheme.typography.bodySmall,color=Palette.Accent)
                    }
                }
            }
        } })
}
