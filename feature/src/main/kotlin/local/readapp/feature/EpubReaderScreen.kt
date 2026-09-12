package local.readapp.feature

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import local.readapp.core.*
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

private data class EpubFrame(val locator:Locator,val page:Int,val count:Int,val section:Int,val sectionEnd:Int,val bitmap:Bitmap,val text:String)
private data class EpubStyle(val prefs:ReaderPreferences,val bg:Int,val fg:Int,val accent:Int,val scale:Float,val width:Float,val height:Float,val dark:Boolean)
private data class LocalPage(val url:String,val html:String,val font:java.io.File?,val background:java.io.File?)
private fun cssColor(color:Int)="#"+Integer.toHexString(color and 0xffffff).padStart(6,'0')

@Composable internal fun EpubReaderScreen(open:OpenBook,prefs:ReaderPreferences,settingsOpen:Boolean,highlight:Highlight?,onClearHighlight:()->Unit,jump:JumpRequest?,locate:(String,Locator)->Unit,onBack:()->Unit,onSettings:()->Unit,onNavigation:()->Unit,onJumpHandled:()->Unit){
    val book=checkNotNull(open.epub);val scope=rememberCoroutineScope();val density=LocalDensity.current
    val latestLocate by rememberUpdatedState(locate);val latestSettings by rememberUpdatedState(onSettings)
    val colors=MaterialTheme.colorScheme;val dark=readerIsDark(prefs)
    key(book){
        var surface by remember {mutableStateOf<EpubSurface?>(null)}
        var frame by remember {mutableStateOf<EpubFrame?>(null)}
        var error by remember {mutableStateOf<String?>(null)}
        var progress by remember {mutableStateOf(false)};var target by remember {mutableFloatStateOf(1f)}
        val first=remember {open.book.locator.takeIf {l->book.chapters.any {it.path==l.href}}?:Locator(format="epub",href=book.chapters.first().path)}
        Column(Modifier.fillMaxSize()){
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
                val style=EpubStyle(prefs,colors.surface.toArgb(),colors.onSurface.toArgb(),colors.primary.toArgb(),density.fontScale,maxWidth.value,maxHeight.value,dark)
                AndroidView(modifier=Modifier.fillMaxSize(),factory={EpubSurface(it,book,scope).also {s->surface=s}},update={s->
                    s.changed={f->frame=f;error=null;latestLocate(open.book.id,f.locator)}
                    s.failed={error=it};s.cover.settings={latestSettings()};s.cover.edgeTap=prefs.tapToTurn;s.cover.settingsOpen=settingsOpen
                    s.highlight=highlight;s.onTurn=onClearHighlight
                    s.configure(style,frame?.locator?:first)
                },onRelease={it.dispose();surface=null})
                error?.let {Text(it)}
            }
            ReaderFooter(frame?.let {it.page-it.section+1}?:0,frame?.let {it.sectionEnd-it.section}?:0,(frame?.locator?.progression?:first.progression)*100,prefs,onNavigation,{target=(frame?.let {it.page-it.section+1}?:1).toFloat();progress=true})
        }
        LaunchedEffect(jump,surface){jump?.locator?.takeIf {it.format=="epub"}?.let {surface?.jump(it);onJumpHandled()}}
        if(progress)AlertDialog(onDismissRequest={progress=false},title={Text("章节进度")},text={Column{
            val count=frame?.let {it.sectionEnd-it.section}?:1
            Text("${target.roundToInt()} / $count");Slider(target,{target=it},valueRange=1f..count.coerceAtLeast(2).toFloat(),enabled=count>1)
        }},confirmButton={TextButton(onClick={surface?.seek((frame?.section?:0)+target.roundToInt()-1);progress=false}){Text("跳转")}},dismissButton={TextButton(onClick={progress=false}){Text("取消")}})
    }
}

/** The opaque cover holds the last completed frame while WebView lays out the target underneath. */
@SuppressLint("SetJavaScriptEnabled")
private class EpubSurface(context:Context,val book:EpubContent,val scope:CoroutineScope):FrameLayout(context){
    val cover=CoverPageView(context)
    private val web=WebView(context)
    var changed:((EpubFrame)->Unit)?=null;var failed:((String)->Unit)?=null
    private val local=java.util.concurrent.atomic.AtomicReference<LocalPage?>(null)
    private var style:EpubStyle?=null;private var generation=0L;private var job:Job?=null
    private var loaded="";private var finished:(()->Unit)?=null
    private var current:EpubFrame?=null;private var pending:EpubFrame?=null
    var highlight:Highlight?=null
    var onTurn:(()->Unit)?=null
    private val paths=book.chapters.map {it.path}
    init {
        addView(web,LayoutParams(-1,-1));addView(cover,LayoutParams(-1,-1))
        web.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        with(web.settings){
            javaScriptEnabled=true;useWideViewPort=true;loadWithOverviewMode=false;textZoom=100
            setSupportZoom(false);builtInZoomControls=false;displayZoomControls=false
            allowFileAccess=false;allowContentAccess=false;domStorageEnabled=false;blockNetworkLoads=true
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false);cacheMode=WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            setForceDark(WebSettings.FORCE_DARK_OFF)
        }
        web.isVerticalScrollBarEnabled=false;web.isHorizontalScrollBarEnabled=false;web.overScrollMode=View.OVER_SCROLL_NEVER
        web.setDownloadListener {_,_,_,_,_->}
        web.webViewClient=object:WebViewClient(){
            private fun blocked()=WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(byteArrayOf()))
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                val u=request.url;val s=local.get()
                if(u.scheme!="https"||u.port != -1||u.userInfo!=null||u.query!=null)return blocked()
                if(u.host=="qipage.invalid"&&s!=null){
                    if(u.toString()==s.url)return WebResourceResponse("text/html","UTF-8",ByteArrayInputStream(s.html.toByteArray()))
                    val file=when(u.path){"/font"->s.font;"/background"->s.background;else->null}
                    return file?.let {runCatching {WebResourceResponse(if(u.path=="/font")"font/ttf" else "image/jpeg",null,it.inputStream())}.getOrNull()}?:blocked()
                }
                if(u.host!="reader.invalid")return blocked()
                val path=u.path.orEmpty().removePrefix("/")
                if(path.split('/').any {it==".."||it=="."}||path.contains('\\'))return blocked()
                return runCatching {book.image(path)?.let {(mime,bytes)->WebResourceResponse(mime,null,ByteArrayInputStream(bytes))}}.getOrNull()?:blocked()
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {if(request.isForMainFrame)followLink(request.url.toString());return true}
            override fun onPageFinished(view:WebView,url:String?){if(url==local.get()?.url){val callback=finished;finished=null;callback?.invoke()}}
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(request.isForMainFrame)failed?.invoke("正文显示失败，请返回书架重新打开。")}
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {generation++;failed?.invoke("系统阅读组件已退出，请返回书架重新打开。");return true}
        }
        cover.longPress={x,y->
            val token=generation;val d=resources.displayMetrics.density
            web.evaluateJavascript("(function(){var e=document.elementFromPoint(${x/d},${y/d}),a=e&&e.closest('a');return a?a.getAttribute('href'):null;})()"){raw->
                if(token==generation){val ref=runCatching {JSONTokener(raw).nextValue() as? String}.getOrNull();if(ref==null||!followLink(ref))cover.settings?.invoke()}
            }
        }
        cover.prepare=prepare@{next,done->
            highlight=null;onTurn?.invoke()
            val f=current
            if(f==null)done(null) else {
                var path=f.locator.href;var page=f.page+if(next)1 else -1
                if(page<0||page>=f.count){val i=paths.indexOf(path)+if(next)1 else -1;if(i !in paths.indices){done(null);return@prepare};path=paths[i];page=if(next)0 else Int.MAX_VALUE}
                render(Locator(format="epub",href=path),page){pending=it;done(it.bitmap)}
            }
        }
        cover.commit={pending?.let {current=it;pending=null;cover.show(it.bitmap,it.text);changed?.invoke(it)}}
        cover.cancelled={pending=null;current?.let {render(it.locator,it.page){ /* restore hidden renderer without changing the saved position */ }}}
    }
    private fun followLink(ref:String):Boolean {
        val uri=Uri.parse(ref);val path=uri.path.orEmpty().removePrefix("/")
        if(uri.scheme!="https"||uri.host!="reader.invalid"||uri.port != -1||uri.userInfo!=null||uri.query!=null||path !in paths)return false
        jump(Locator(format="epub",href=path,anchor=uri.fragment.orEmpty()));return true
    }
    fun configure(value:EpubStyle,locator:Locator){
        cover.setBackgroundColor(value.bg)
        // UI-only preferences must not repaginate or interrupt a gesture.
        val old=style
        style=value
        fun key(s:EpubStyle)=listOf(s.bg,s.fg,s.scale,s.width,s.height,s.dark,s.prefs.fontSize,s.prefs.lineSpacing,s.prefs.paragraphSpacing,s.prefs.pageMargin,s.prefs.fontFile,s.prefs.backgroundFile,s.prefs.nightImageDim)
        if(old==null||key(old)!=key(value)){loaded="";jump(locator)}
    }
    fun jump(locator:Locator){if(locator.href !in paths)return;cover.abort();pending=null;render(locator,null){current=it;cover.show(it.bitmap,it.text);changed?.invoke(it)}}
    fun seek(page:Int){current?.let {f->cover.abort();render(f.locator,page){current=it;cover.show(it.bitmap,it.text);changed?.invoke(it)}}}
    private fun render(locator:Locator,page:Int?,done:(EpubFrame)->Unit){
        val s=style?:return;val token=++generation;job?.cancel()
        fun snapshot(){
            if(token!=generation)return
            web.evaluateJavascript(CAPTURE){raw->
                if(token!=generation)return@evaluateJavascript
                runCatching {
                    val json=JSONObject(JSONTokener(raw).nextValue() as String)
                    web.postVisualStateCallback(token,object:WebView.VisualStateCallback(){override fun onComplete(requestId:Long){
                        if(token!=generation||web.width==0||web.height==0)return
                        val bitmap=Bitmap.createBitmap(web.width,web.height,Bitmap.Config.ARGB_8888);web.draw(Canvas(bitmap))
                        val count=json.optInt("count",1).coerceAtLeast(1);val p=json.optInt("page").coerceIn(0,count-1)
                        val l=Locator(format="epub",href=locator.href,offset=json.optLong("offset"),anchor=json.optString("anchor"),progression=(paths.indexOf(locator.href)+(p+1.0)/count)/paths.size)
                        done(EpubFrame(l,p,count,json.optInt("section"),json.optInt("sectionEnd",count),bitmap,json.optString("text").ifBlank {book.chapters[paths.indexOf(locator.href)].title}))
                    }})
                }.onFailure {failed?.invoke("正文分页失败，请返回书架重试。")}
            }
        }
        fun restore(attempt:Int=0){
            if(token!=generation)return
            web.evaluateJavascript("!document.fonts || document.fonts.status==='loaded'"){ready->
                if(token!=generation)return@evaluateJavascript
                if(ready!="true"&&attempt<100)web.postDelayed({restore(attempt+1)},50)
                else web.evaluateJavascript(restoreScript(locator)){
                    if(token!=generation)return@evaluateJavascript
                    val capture={
                        if(token==generation){
                            if(page==null)snapshot() else web.evaluateJavascript("(function(){var e=document.getElementById('pages'),m=${LAST_CONTENT_PAGE_JS},p=Math.min(m,Math.max(0,$page));e.dataset.page=p;document.getElementById('viewport').scrollLeft=p*innerWidth;})()"){snapshot()}
                        }
                    }
                    // Paint the match before the page is captured, otherwise the
                    // screenshot used for the frame would not show it.
                    val hl=highlight
                    if(hl!=null&&hl.length>0)web.evaluateJavascript(highlightScript(hl.start,hl.length)){capture()}
                    else capture()
                }
            }
        }
        if(loaded==locator.href&&finished==null){restore();return}
        job=scope.launch {
            try{
                val body=withContext(Dispatchers.IO){book.chapter(locator.href)}
                if(token!=generation)return@launch
                val url="https://qipage.invalid/page/"+java.util.UUID.randomUUID()
                local.set(LocalPage(url,document(body,s.prefs,s.bg,s.fg,s.accent,s.scale,s.width,s.height,s.dark),ReadingAssets.file(context,s.prefs.fontFile),ReadingAssets.file(context,s.prefs.backgroundFile)))
                loaded=locator.href;finished={restore()};web.loadUrl(url)
            }catch(e:Exception){if(e is CancellationException)throw e;failed?.invoke("这一节暂时无法解析，请从目录选择其他章节。")}
        }
    }
    fun dispose(){generation++;job?.cancel();finished=null;cover.abort();web.stopLoading();web.destroy()}
}
internal fun document(body:String,prefs:ReaderPreferences,bg:Int,fg:Int,accent:Int,scale:Float,viewportWidth:Float,viewportHeight:Float,dark:Boolean):String="""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://reader.invalid https://qipage.invalid; font-src https://qipage.invalid; style-src 'unsafe-inline'; script-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"><style>
@font-face{font-family:QiPageLocal;src:url(https://qipage.invalid/font)}
html,body{background:${cssColor(bg)};color:${cssColor(fg)};margin:0;padding:0;overflow:hidden;height:${viewportHeight}px;width:${viewportWidth}px}body{overflow:hidden}#viewport{position:relative;overflow:hidden;width:${viewportWidth}px;height:${viewportHeight}px;background:${cssColor(bg)};${if(prefs.backgroundFile.isNotEmpty())"background-image:linear-gradient(rgba(0,0,0,${if(dark)prefs.nightImageDim else 0f}),rgba(0,0,0,${if(dark)prefs.nightImageDim else 0f})),url(https://qipage.invalid/background);background-size:cover;background-position:center;" else ""}}#extent{position:absolute;width:1px;height:1px;top:0}#pages{overflow:visible;overflow-wrap:break-word;word-break:normal;line-break:strict;text-align:${if(prefs.justify)"justify" else "start"};letter-spacing:${prefs.letterSpacing}em;font-family:${if(prefs.fontFile.isNotEmpty())"QiPageLocal,system-ui,sans-serif" else "system-ui,sans-serif"};font-size:${prefs.fontSize*scale}px;line-height:${prefs.fontSize*scale*prefs.lineSpacing}px;margin:4px ${prefs.pageMargin}px;width:${(viewportWidth-prefs.pageMargin*2).coerceAtLeast(1f)}px;height:${(viewportHeight-8).coerceAtLeast(64f)}px;column-width:${(viewportWidth-prefs.pageMargin*2).coerceAtLeast(1f)}px;column-count:1;column-gap:${prefs.pageMargin*2}px;column-fill:auto}p{margin:0 0 ${prefs.paragraphSpacing}px;text-indent:2em}p:empty{display:none}p>img:first-child{margin-left:-2em}#pages>:last-child{margin-bottom:0}h1,h2,h3,h4,h5,h6{font-weight:700;text-align:left;line-height:1.25;margin:0 0 ${prefs.paragraphSpacing+6}px;break-after:avoid}h1,h2,h3{break-before:column}h1,h2,h3,h4,h5,h6{font-size:1.18em}img{max-width:100%;max-height:${(viewportHeight-32).coerceAtLeast(32f)}px;object-fit:contain;height:auto;break-inside:avoid}a{color:${cssColor(accent)}}mark.qp-hl{background:#66FFB300;color:inherit}pre{white-space:pre-wrap}table{max-width:100%;border-collapse:collapse}td,th{border:1px solid;padding:4px}blockquote{margin:0 0 ${prefs.paragraphSpacing}px;padding-left:16px;border-left:2px solid}*{box-sizing:border-box;orphans:1;widows:1}</style></head><body><div id="viewport"><div id="pages">$body</div><div id="extent"></div></div></body></html>"""
/**
 * JS expression evaluating to the index of the last column that actually holds
 * content.
 *
 * A multi-column container over-reports its scroll width by one column when the
 * final block's bottom margin, or a forced `break-before:column`, spills past
 * the last baseline. Trusting it made the reader expose a trailing blank page -
 * most visible when stepping back into a chapter, because that path lands on
 * the reported last page on purpose. Measuring the real content extent instead
 * keeps the page count honest.
 */
/**
 * JS expression snapping a wanted page index to the nearest column that really
 * holds content.
 *
 * Whatever produces a contentless column - a forced column break landing on a
 * boundary, or a trailing margin - the reader must never display it nor land on
 * it. This walks the child fragments, then takes the target column if it has
 * content, else the closest one that does.
 */
internal fun nearestContentPageJs(target:String):String="""(function(){var pages=document.getElementById('pages'),W=innerWidth,m=parseFloat(getComputedStyle(pages).marginLeft),total=Math.max(1,Math.ceil((pages.scrollWidth+m*2)/W)),has=[],i;for(i=0;i<total;i++)has.push(false);var kids=pages.children;for(i=0;i<kids.length;i++){var rs=kids[i].getClientRects();for(var j=0;j<rs.length;j++){var r=rs[j];if(r.width<=0||r.height<=0)continue;var c=Math.floor(r.left/W);if(c>=0&&c<total)has[c]=true;}}var t=Math.round($target);if(t<0)t=0;if(t>=total)t=total-1;if(has[t])return t;for(var d=1;d<total;d++){if(t+d<total&&has[t+d])return t+d;if(t-d>=0&&has[t-d])return t-d;}return t;})()"""

/** Wraps one match in `<mark class="qp-hl">` so the reader can see it. */
internal fun highlightScript(start:Long,length:Int):String="""(function(){var t=$start,n=$length,list=document.querySelectorAll('#pages span[data-read]'),i;for(i=0;i<list.length;i++){var el=list[i],o=Number(el.getAttribute('data-read')),txt=el.firstChild;if(!txt||txt.nodeType!==3)continue;if(o+txt.length<=t||o>=t+n)continue;var a=Math.max(0,t-o),b=Math.min(txt.length,t+n-o);if(b<=a)continue;var r=document.createRange();r.setStart(txt,a);r.setEnd(txt,b);var mk=document.createElement('mark');mk.className='qp-hl';try{r.surroundContents(mk);}catch(e){}}return 1;})()"""

/** Drops any previous highlight, restoring the plain text nodes. */
private const val CLEAR_HIGHLIGHT_JS="""(function(){var ms=document.querySelectorAll('mark.qp-hl');for(var i=0;i<ms.length;i++){var m=ms[i],p=m.parentNode;while(m.firstChild)p.insertBefore(m.firstChild,m);p.removeChild(m);p.normalize();}return ms.length;})();"""

internal const val LAST_CONTENT_PAGE_JS="""(function(){var v=document.getElementById('viewport'),sl=v?v.scrollLeft:0,n=document.querySelectorAll('#pages *'),right=0;for(var i=0;i<n.length;i++){var b=n[i].getBoundingClientRect();if(b.width<=0||b.height<=0)continue;var e=b.right+sl;if(e>right)right=e;}return Math.max(0,Math.ceil((right-1)/innerWidth)-1);})()"""

private const val CAPTURE="""(function(){var container=document.getElementById('pages'),margin=parseFloat(getComputedStyle(container).marginLeft),list=document.querySelectorAll('span[data-read]'),chosen=null,offset=0;var r=document.caretRangeFromPoint?document.caretRangeFromPoint(margin+1,10+parseFloat(getComputedStyle(container).fontSize)*.5):null;if(r&&r.startContainer.nodeType===3){var p=r.startContainer.parentElement;if(p&&p.hasAttribute('data-read')){chosen=p;offset=r.startOffset;}}if(!chosen){for(var i=0;i<list.length;i++){var rects=list[i].getClientRects();for(var j=0;j<rects.length;j++){var rect=rects[j];if(rect.right>0&&rect.left<innerWidth&&rect.bottom>0&&rect.top<innerHeight){chosen=list[i];break;}}if(chosen)break;}}var max=${LAST_CONTENT_PAGE_JS},page=Number(document.getElementById('pages').dataset.page||0);var starts=[0],heads=document.querySelectorAll("[data-chapter],h1,h2,h3");for(var k=0;k<heads.length;k++){var n=Math.max(0,Math.round((heads[k].getBoundingClientRect().left+document.getElementById("viewport").scrollLeft)/innerWidth));if(starts.indexOf(n)<0)starts.push(n);}starts.sort(function(a,b){return a-b;});var section=0,sectionEnd=max+1;for(var k=0;k<starts.length;k++){if(starts[k]<=page)section=starts[k];else{sectionEnd=starts[k];break;}}var visible=[];for(var k=0;k<list.length;k++){var rs=list[k].getClientRects();for(var j=0;j<rs.length;j++){if(rs[j].left<innerWidth&&rs[j].right>0&&rs[j].bottom>0&&rs[j].top<innerHeight){visible.push(list[k].textContent);break;}}}return JSON.stringify({anchor:chosen?chosen.id:'',offset:chosen?Number(chosen.getAttribute('data-read'))+offset:0,page:page,count:max+1,section:section,sectionEnd:sectionEnd,text:visible.join(" ")});})()"""
/**
 * Quotes a string as a JavaScript literal.
 *
 * Deliberately not [org.json.JSONObject.quote]: this runs while building a page
 * script, and keeping it pure Kotlin means the script can be asserted on the JVM.
 */
internal fun jsQuote(value:String):String=buildString {
    append('"')
    for(c in value){
        when(c){
            '"'->append("\\\"")
            '\\'->append("\\\\")
            '\n'->append("\\n")
            '\r'->append("\\r")
            '\t'->append("\\t")
            '\b'->append("\\b")
            '\u000C'->append("\\f")
            else->if(c<' ')append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

internal fun restoreScript(locator:Locator):String {
    val anchor=jsQuote(locator.anchor)
    return """(function(){${CLEAR_HIGHLIGHT_JS}var container=document.getElementById('pages'),margin=parseFloat(getComputedStyle(container).marginLeft);container.style.width=(innerWidth-margin*2)+'px';container.style.columnWidth=(innerWidth-margin*2)+'px';document.getElementById('viewport').style.width=innerWidth+'px';document.getElementById('viewport').scrollLeft=0;container.dataset.page=0;var max=${LAST_CONTENT_PAGE_JS};document.getElementById('extent').style.left=((max+1)*innerWidth-1)+'px';if(${locator.offset}>=9007199254740991){var e=document.getElementById('pages'),lp=${nearestContentPageJs("max")};e.dataset.page=lp;document.getElementById('viewport').scrollLeft=lp*innerWidth;return;}var anchor=$anchor,n=null,rect=null;if(anchor.indexOf('book-')===0)n=document.getElementById(anchor);if(n)rect=n.getBoundingClientRect();else{var target=${locator.offset.coerceAtLeast(0)},list=document.querySelectorAll('span[data-read]');for(var i=0;i<list.length;i++){if(Number(list[i].getAttribute('data-read'))<=target)n=list[i];else break;}if(n&&n.firstChild){var r=document.createRange(),o=Math.max(0,Math.min(n.firstChild.length,target-Number(n.getAttribute('data-read'))));r.setStart(n.firstChild,o);r.setEnd(n.firstChild,Math.min(n.firstChild.length,o+1));rect=r.getBoundingClientRect();}}var e=document.getElementById('pages'),raw=rect?Math.max(0,Math.min(max,Math.floor(rect.left/innerWidth))):0,page=${nearestContentPageJs("raw")};e.dataset.page=page;document.getElementById('viewport').scrollLeft=page*innerWidth;})()"""
}



















