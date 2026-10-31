package app.localdrop

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class MediaPublisher(private val context: Context) {
    suspend fun publish(d: Download, source: File): String {
        val mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase())
            ?: if(d.mp3) "audio/mpeg" else "video/x-matroska"
        val name=d.title.replace(Regex("[^\\p{L}\\p{N} ._-]"),"_").take(80).ifBlank { "Video" } +
            "-${d.id.take(8)}.${source.extension}"
        val folder=if(d.folder == "Downloads" || mime.startsWith("audio/")) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_MOVIES
        if(Build.VERSION.SDK_INT >= 29) {
            val collection=if(folder == Environment.DIRECTORY_DOWNLOADS) MediaStore.Downloads.EXTERNAL_CONTENT_URI
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val resolver=context.contentResolver
            // Journal the pending URI, so a process death during copy can be rolled back on retry.
            val journal=File(context.filesDir,"pending-${d.id}")
            if(journal.exists()) {
                runCatching { resolver.delete(Uri.parse(journal.readText()),null,null) }
                journal.delete()
            }
            val values=ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name); put(MediaStore.MediaColumns.MIME_TYPE,mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,"$folder/LocalDrop")
                put(MediaStore.MediaColumns.IS_PENDING,1)
            }
            val uri=resolver.insert(collection,values) ?: error("Cannot create MediaStore entry")
            try {
                journal.writeText(uri.toString())
                resolver.openOutputStream(uri,"w")?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: error("Cannot open destination")
                resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
                return uri.toString()
            } catch(e: Exception) { resolver.delete(uri,null,null); journal.delete(); throw e }
        }
        @Suppress("DEPRECATION")
        val target=File(Environment.getExternalStoragePublicDirectory(folder),"LocalDrop/$name")
        target.parentFile!!.mkdirs()
        val temp=File(target.parentFile,"$name.partial")
        try {
            source.copyTo(temp,overwrite=true)
            check(temp.renameTo(target)) { "Cannot save file" }
            return suspendCancellableCoroutine { continuation ->
                MediaScannerConnection.scanFile(context,arrayOf(target.absolutePath),arrayOf(mime)) { _, uri ->
                    if(continuation.isActive) continuation.resume((uri ?: Uri.fromFile(target)).toString())
                }
            }
        } catch(e: Exception) { temp.delete(); throw e }
    }
    fun committed(id: String) { File(context.filesDir,"pending-$id").delete() }
}
