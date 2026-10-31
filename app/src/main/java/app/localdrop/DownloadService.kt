package app.localdrop

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File

class DownloadService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var runner: Job?=null
    @Volatile private var active: String?=null
    private var wake: PowerManager.WakeLock?=null
    private val store get()=app.store
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads","التنزيلات",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        ServiceCompat.startForeground(this,1,notification(null),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val id=intent?.getStringExtra("id")
        if(id != null) when(intent.action) {
            "pause" -> pause(id,"متوقف مؤقتًا")
            "cancel" -> {
                if(store.get(id)?.status !in listOf(Status.SAVING,Status.COMPLETE)) {
                    store.change(id) { it.copy(status=Status.CANCELED,message="ملغى") }
                    scope.launch(Dispatchers.IO) { app.engine.stop(id) }
                }
            }
            "resume" -> if(store.get(id)?.status in listOf(Status.PAUSED,Status.FAILED))
                store.change(id) { it.copy(status=Status.QUEUED,message="") }
        }
        if(runner?.isActive != true) runner=scope.launch { drain() }
        return START_NOT_STICKY
    }
    private fun pause(id: String,reason: String) {
        if(store.get(id)?.status in listOf(Status.RUNNING,Status.QUEUED)) {
            store.change(id) { it.copy(status=Status.PAUSED,message=reason) }
            scope.launch(Dispatchers.IO) { app.engine.stop(id) }
        }
    }
    private suspend fun drain() {
        try {
            while(true) {
                withContext(Dispatchers.IO) {
                    store.items.value.filter { it.status == Status.CANCELED && it.id != active }
                        .forEach { directory(it.id).deleteRecursively() }
                }
                val next=store.items.value.firstOrNull { it.status == Status.QUEUED } ?: break
                if(store.wifiOnly && !hasWifi()) {
                    store.change(next.id) { it.copy(status=Status.PAUSED,message="يلزم اتصال واي فاي. اتصل ثم اضغط استئناف.") }
                    continue
                }
                active=next.id
                store.change(next.id) { it.copy(status=Status.RUNNING,message="جارٍ التنزيل") }
                wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LocalDrop:download")
                    .apply { acquire(6*60*60*1000L) }
                val monitor=scope.launch {
                    while(isActive) {
                        delay(400)
                        if(store.wifiOnly && !hasWifi()) pause(next.id,"انقطع الواي فاي. اضغط استئناف بعد الاتصال.")
                        // Repeated stop also covers pause during initialization, before the process exists.
                        if(store.get(next.id)?.status in listOf(Status.PAUSED,Status.CANCELED))
                            withContext(Dispatchers.IO) { app.engine.stop(next.id) }
                    }
                }
                var lastNotification=0L
                try {
                    val source=app.engine.download(next,directory(next.id)) { percent ->
                        store.change(next.id,save=false) { if(it.status == Status.RUNNING) it.copy(progress=percent) else it }
                        if(System.currentTimeMillis()-lastNotification>900) {
                            lastNotification=System.currentTimeMillis()
                            getSystemService(NotificationManager::class.java).notify(1,notification(store.get(next.id)))
                        }
                    }
                    monitor.cancelAndJoin()
                    if(store.get(next.id)?.status == Status.RUNNING) {
                        store.change(next.id) { it.copy(status=Status.SAVING,message="جارٍ الحفظ على الجهاز") }
                        getSystemService(NotificationManager::class.java).notify(1,notification(store.get(next.id)))
                        val publisher=MediaPublisher(this)
                        val uri=withContext(Dispatchers.IO) { publisher.publish(next,source) }
                        store.change(next.id) { it.copy(status=Status.COMPLETE,progress=100,uri=uri,message="تم الحفظ") }
                        publisher.committed(next.id)
                        withContext(Dispatchers.IO) { directory(next.id).deleteRecursively() }
                    }
                } catch(e: Exception) {
                    if(e is CancellationException) throw e
                    if(store.get(next.id)?.status in listOf(Status.RUNNING,Status.SAVING))
                        store.change(next.id) { it.copy(status=Status.FAILED,message=LinkRules.report(e)) }
                } finally {
                    monitor.cancelAndJoin()
                    if(wake?.isHeld == true) wake?.release()
                    wake=null
                    if(store.get(next.id)?.status == Status.CANCELED)
                        withContext(Dispatchers.IO) { directory(next.id).deleteRecursively() }
                    active=null
                }
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            store.items.value.lastOrNull { it.status == Status.PAUSED }?.let {
                getSystemService(NotificationManager::class.java).notify(1,notification(it))
            }
            stopSelf()
        }
    }
    private fun directory(id: String)=File(filesDir,"transfers/$id")
    private fun hasWifi(): Boolean {
        val cm=getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
    }
    private fun notification(d: Download?): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b=NotificationCompat.Builder(this,"downloads").setSmallIcon(R.drawable.ic_download)
            .setContentTitle(d?.title ?: "LocalDrop").setContentText(d?.message ?: "جارٍ تجهيز التنزيلات")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(d?.status != Status.PAUSED)
        if(d != null) {
            b.setProgress(100,d.progress,d.status == Status.SAVING)
            if(d.status != Status.SAVING) {
                val action=if(d.status == Status.PAUSED) "resume" else "pause"
                b.addAction(0,if(action == "resume") "استئناف" else "إيقاف مؤقت",pending(action,d.id))
                b.addAction(0,"إلغاء",pending("cancel",d.id))
            }
        }
        return b.build()
    }
    private fun pending(action: String,id: String)=PendingIntent.getForegroundService(this,(id+action).hashCode(),
        Intent(this,DownloadService::class.java).setAction(action).putExtra("id",id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun onTimeout(startId: Int,fgsType: Int) {
        active?.let { pause(it,"انتهت مدة الخدمة المسموحة. افتح التطبيق واضغط استئناف.") }
        stopSelf()
    }
    override fun onDestroy() {
        active?.let { id ->
            if(store.get(id)?.status in listOf(Status.RUNNING,Status.SAVING))
                store.change(id) { it.copy(status=Status.PAUSED,message="توقف التنزيل. اضغط استئناف.") }
            Thread { app.engine.stop(id) }.start()
        }
        if(wake?.isHeld == true) wake?.release()
        scope.cancel(); super.onDestroy()
    }
    companion object {
        fun command(context: Context,action: String="start",id: String?=null) {
            ContextCompat.startForegroundService(context,Intent(context,DownloadService::class.java)
                .setAction(action).putExtra("id",id))
        }
    }
}
