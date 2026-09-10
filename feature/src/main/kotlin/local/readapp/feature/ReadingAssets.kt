package local.readapp.feature

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.readapp.core.ReaderPreferences
import java.io.File
import java.util.UUID

/** Only app-owned validated copies are available to the two reading engines. */
internal object ReadingAssets {
    fun prune(context:Context,keep:Set<String>){
        File(context.filesDir,"reading-assets").listFiles()?.filter { it.name !in keep && file(context,it.name)!=null }?.forEach { it.delete() }
    }
    fun file(context:Context,name:String):File? = name.takeIf { Regex("[a-f0-9-]+\\.(font|jpg)").matches(it) }
        ?.let { File(context.filesDir,"reading-assets/$it") }?.takeIf { it.isFile }

    suspend fun import(context:Context,uri:Uri,font:Boolean):Pair<String,String> = withContext(Dispatchers.IO) {
        require(uri.scheme=="content") { "请选择本机文件" }
        val dir=File(context.filesDir,"reading-assets").apply { mkdirs() }
        val temp=File.createTempFile("import-",".tmp",dir)
        val result=File(dir,UUID.randomUUID().toString()+if(font)".font" else ".jpg")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { out ->
                val buffer=ByteArray(65536);var total=0L
                while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=32L*1024*1024){"字体或背景图片不能超过 32 MiB"};out.write(buffer,0,n)}
            }} ?: error("无法读取所选文件")
            if(font){
                val header=ByteArray(4);java.io.DataInputStream(temp.inputStream()).use { it.readFully(header) }
                require(header.contentEquals(byteArrayOf(0,1,0,0)) || String(header,Charsets.US_ASCII) in setOf("OTTO","ttcf","true")){"请选择 TTF、OTF 或 TTC 字体"}
                Typeface.createFromFile(temp)
                check(temp.renameTo(result))
            } else {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(temp.path,bounds)
                require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=100_000_000){"图片无效或尺寸过大"}
                var sample=1;while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>2048)sample*=2
                val bitmap=BitmapFactory.decodeFile(temp.path,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("无法解码图片")
                try { result.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it)) } } finally { bitmap.recycle() }
            }
            val name=runCatching { context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())it.getString(0) else null } }.getOrNull()?:if(font)"本机字体" else "背景图片"
            result.name to name.take(120)
        } catch(e:Exception){result.delete();throw e} finally {temp.delete()}
    }
}

@Composable internal fun readerIsDark(prefs:ReaderPreferences)=prefs.theme=="dark" || (prefs.theme=="system" && isSystemInDarkTheme())
internal fun customColor(value:String,fallback:Color):Color = if(Regex("#[0-9a-fA-F]{6}").matches(value))Color(android.graphics.Color.parseColor(value)) else fallback

@Composable internal fun ReadingAppearance(prefs:ReaderPreferences,content:@Composable ()->Unit){
    val base=MaterialTheme.colorScheme;val dark=readerIsDark(prefs);val context=LocalContext.current
    val bg=customColor(if(dark)prefs.nightBackground else prefs.dayBackground,base.surface)
    val fg=customColor(if(dark)prefs.nightText else prefs.dayText,base.onSurface)
    val bitmap by produceState<android.graphics.Bitmap?>(null,prefs.backgroundFile){value=withContext(Dispatchers.IO){ReadingAssets.file(context,prefs.backgroundFile)?.let { BitmapFactory.decodeFile(it.path) }}}
    MaterialTheme(colorScheme=base.copy(surface=bg,onSurface=fg)) {
        Box(Modifier.fillMaxSize().background(bg)) {
            bitmap?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop);if(dark)Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=prefs.nightImageDim))) }
            content()
        }
    }
}

