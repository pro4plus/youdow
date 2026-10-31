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

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BrowserScreen(initialUrl: String,busy: Boolean,onClose: ()->Unit,onDownload: (String)->Unit) {
    val context=LocalContext.current
    val download by rememberUpdatedState(onDownload)
    val working by rememberUpdatedState(busy)
    var address by remember { mutableStateOf(initialUrl) }
    var current by remember { mutableStateOf(initialUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var back by remember { mutableStateOf(false) }
    var forward by remember { mutableStateOf(false) }
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
                    current=url;address=url;error=""
                }
                override fun doUpdateVisitedHistory(view: WebView,url: String,isReload: Boolean) {
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
            setDownloadListener { url,_,_,_,_ ->
                val valid=BrowserRules.address(url)
                if(valid!=null && !working) download(valid)
                else if(valid==null) error="افتح صفحة الفيديو ثم اضغط تنزيل فيديو الصفحة."
            }
            loadUrl(initialUrl)
        }
    }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(web,owner) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) web.onResume()
            if(event==Lifecycle.Event.ON_PAUSE) web.onPause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
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
            PrimaryButton(if(busy) "جارٍ فحص الفيديو…" else "تنزيل فيديو الصفحة",onClick={
                // Read the current URL at the tap: video sites can navigate without reloading.
                val url=BrowserRules.address(web.url.orEmpty())
                if(url!=null) download(url)
            },enabled=!busy && BrowserRules.address(current)!=null,icon=R.drawable.ui_download)
            Text("افتح صفحة فيديو ثم اضغط الزر لعرض الجودات المتاحة.",style=MaterialTheme.typography.bodySmall,color=Palette.Muted)
        }
    }
}
