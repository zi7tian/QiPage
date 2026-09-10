package local.readapp.data

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import java.io.*
import java.security.MessageDigest

/** Accept user-selected readable content URIs, including OEM FileProviders, never HTTP URLs. */
internal class LocalDocumentSource(private val resolver: ContentResolver) {
    data class Copied(val name:String, val bytes:Long, val hash:String,val format:String)
    suspend fun copy(uri:Uri, destination:File): Copied = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "请选择手机中的本地 TXT 文件，不支持网络链接" }
        val signal = CancellationSignal()
        val cancellation = launch(Dispatchers.Unconfined, start=CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try {
            var name = "未命名.txt"; var flags = 0L; var knownSize = -1L
            // Null projection tolerates OEM providers which do not implement DocumentsContract columns.
            resolver.query(uri,null,null,null,null,signal)?.use { cursor ->
                if(cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it>=0 }?.let { name=cursor.getString(it) ?: name }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it>=0 && !cursor.isNull(it) }?.let { knownSize=cursor.getLong(it) }
                    cursor.getColumnIndex(Document.COLUMN_FLAGS).takeIf { it>=0 }?.let { flags=cursor.getLong(it) }
                }
            }
            require(flags and (Document.FLAG_VIRTUAL_DOCUMENT or Document.FLAG_PARTIAL).toLong() == 0L) { "文件尚未完整保存在本机，请先在文件管理器中下载，再选择文件" }
            val mime = resolver.getType(uri)
            val format=if(name.endsWith(".epub",true) || mime=="application/epub+zip")"epub" else "txt"
            require(format=="epub" || name.endsWith(".txt",true) || mime == "text/plain") { "请选择 TXT 或无 DRM 的可重排 EPUB 文件" }
            require(knownSize <= MAX_BYTES) { "文件超过 512 MiB 上限" }
            val digest=MessageDigest.getInstance("SHA-256"); var total=0L
            resolver.openFileDescriptor(uri,"r",signal)?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    destination.outputStream().buffered().use { out ->
                        val buffer=ByteArray(64*1024)
                        while(true) {
                            ensureActive(); val n=input.read(buffer); if(n<0)break
                            total+=n; require(total<=MAX_BYTES) { "文件超过 512 MiB 上限" }
                            digest.update(buffer,0,n); out.write(buffer,0,n)
                        }
                    }
                }
            } ?: error("无法读取文件，请重新选择")
            // Reject common binary archives and NUL-bearing non-UTF16 files before text decoding.
            val prefix=destination.inputStream().use { input ->
                val buffer=ByteArray(8192); var count=0
                while(count<buffer.size) { val n=input.read(buffer,count,buffer.size-count); if(n<0)break; count+=n }
                buffer.copyOf(count)
            }
            val utf16=prefix.size>=2 && ((prefix[0]==0xFF.toByte() && prefix[1]==0xFE.toByte()) || (prefix[0]==0xFE.toByte() && prefix[1]==0xFF.toByte()))
            val archive=prefix.size>=4 && prefix[0]==80.toByte() && prefix[1]==75.toByte() &&
                ((prefix[2]==3.toByte() && prefix[3]==4.toByte()) || (prefix[2]==5.toByte() && prefix[3]==6.toByte()) || (prefix[2]==7.toByte() && prefix[3]==8.toByte()))
            if(format=="txt")require(utf16 || (prefix.none { it==0.toByte() } && !archive)) { "文件内容不是可读取的 TXT 文本" }
            else require(archive) { "文件内容不是有效的 EPUB 压缩包" }
            Copied(name.replace(Regex("[\\r\\n\\t]")," ").take(240),total,digest.digest().joinToString("") { "%02x".format(it) },format)
        } finally { cancellation.cancel() }
    }
    companion object { const val MAX_BYTES=512L*1024*1024 }
}

