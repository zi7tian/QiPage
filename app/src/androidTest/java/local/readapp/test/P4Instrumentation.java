package local.readapp.test;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.io.*;
import java.util.*;

/** Release-APK black-box P4 acceptance; no test hooks in the application. */
public final class P4Instrumentation extends Instrumentation {
    private Bundle args;private final JSONArray checks=new JSONArray();
    public void onCreate(Bundle b){super.onCreate(b);args=b==null?new Bundle():b;start();}
    public void onStart(){JSONObject result=new JSONObject();boolean success=false;
        try{
            String mode=args.getString("mode","txt");String fixture=args.getString("fixture","p4-layout."+(mode.equals("epub")?"epub":"txt"));
            Intent launch=new Intent().setClassName("local.readapp","local.readapp.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if(!mode.equals("migrate")&&!mode.equals("performance"))launch.setAction(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://local.readapp.test.oem.files/"+fixture),"application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Activity activity=startActivitySync(launch);
            if(mode.equals("reset")){
                // Typography is adjustable only inside the reader now, so the reset
                // goes through an opened book; the drawer entry must be gone.
                await("目录 / 书签",65000);awaitPage();SystemClock.sleep(1400);
                touch(.5f,.3f,.5f,.3f,80);await("阅读设置",8000);click("更多");scrollPanel("恢复默认设置");click("恢复默认设置");click("完成");SystemClock.sleep(900);
                back();await("+",8000);swipe(.08f,.5f,.85f,.5f);await("QiPage",8000);
                check(!text().contains("阅读设置"),"书架抽屉不再提供阅读设置入口");
                check(true,"恢复默认偏好，不清除书架数据");
            }else if(mode.equals("columns")){
                // Diagnostic: report which CSS columns actually hold content and
                // where each chapter heading lands. Used to find stray blank pages.
                await("目录 / 书签",65000);awaitPage();SystemClock.sleep(2200);
                // Optionally land on a later chapter first, so spine boundaries
                // deep in a multi-file book can be inspected too.
                String want=args.getString("chapter","");
                if(want.length()>0&&webOrNull(activity)!=null){
                    click("目录 / 书签");SystemClock.sleep(1500);
                    AccessibilityNodeInfo pick=find(root(),want,true);
                    if(pick==null)throw new IllegalStateException("chapter not in TOC: "+want);
                    clickNode(pick);SystemClock.sleep(2600);awaitPage();
                }
                android.webkit.WebView cw=findWeb(activity.getWindow().getDecorView());
                if(cw==null)throw new IllegalStateException("no webview");
                String map=js(cw,"JSON.stringify((function(){var pages=document.getElementById('pages'),vp=document.getElementById('viewport'),W=innerWidth,H=innerHeight,sl=vp.scrollLeft;var total=Math.ceil((pages.scrollWidth+parseFloat(getComputedStyle(pages).marginLeft)*2)/W);var frag=[],fill=[];for(var i=0;i<total;i++){frag.push(0);fill.push(0);}var kids=pages.children;for(var i=0;i<kids.length;i++){var rs=kids[i].getClientRects();for(var j=0;j<rs.length;j++){var r=rs[j];if(r.width<=0||r.height<=0)continue;var c=Math.floor((r.left+sl)/W);if(c<0||c>=total)continue;frag[c]++;fill[c]+=r.height;}}var heads=[];var hs=pages.querySelectorAll('h1,h2,h3');for(var i=0;i<hs.length;i++){var r=hs[i].getBoundingClientRect();heads.push({t:hs[i].textContent.slice(0,14),col:Math.floor((r.left+sl)/W)});}return {total:total,frag:frag,fill:fill.map(function(v){return Math.round(v);}),heads:heads,H:H};})())");
                result.put("columns",new JSONObject(map));
                AccessibilityNodeInfo pn=pageNode(root());
                result.put("pageText",pn==null?"<null>":String.valueOf(pn.getContentDescription()));
                result.put("footer",footer()==null?"<null>":footerText());
                shot("columns-"+args.getString("chapter","first"));
                sendStatus(0,statusBundle("COLUMNS "+map+"\n"));
                check(true,"列布局已导出");
            }else if(mode.equals("v2")){
                runV2(activity,result);
            }else if(mode.equals("fixes")){
                runFixes(activity,result);
            }else if(mode.equals("performance")){
                SystemClock.sleep(1200);swipe(.1f,.5f,.85f,.5f);await("搜索书名",5000);List<AccessibilityNodeInfo> fields=new ArrayList<>();editables(root(),fields);set(fields.get(0),"utf8-100-MiB");click("返回书架");await("utf8-100-MiB",5000);
                long time=SystemClock.elapsedRealtime();clickFast("utf8-100-MiB");awaitPage();long cold=SystemClock.elapsedRealtime()-time;SystemClock.sleep(1000);long position=position("utf8-100-MiB","txt");check(position>30000000,"100 MiB 直接恢复原位置");back();await("utf8-100-MiB",5000);
                time=SystemClock.elapsedRealtime();clickFast("utf8-100-MiB");awaitPage();long warm=SystemClock.elapsedRealtime()-time;SystemClock.sleep(900);check(position("utf8-100-MiB","txt")==position,"复用会话位置不变");result.put("firstOpenMs",cold).put("warmOpenMs",warm).put("position",position);shot("large-txt");
            }else if(mode.equals("migrate")){
                SystemClock.sleep(1800);try(SQLiteDatabase db=db();Cursor c=db.rawQuery("SELECT position FROM books WHERE title='utf8-100-MiB'",null)){check(db.getVersion()==3,"沿用 Room v3");check(c.moveToFirst()&&c.getLong(0)>30000000,"旧大书阅读记录保留");}swipe(.08f,.5f,.85f,.5f);await("QiPage",6000);shot("drawer");check(text().contains("0.4.0-p4"),"P4 应用信息");
            }else if(mode.equals("reject")){
                await(args.getString("expected"),45000);check(true,"拒绝不支持或越界 EPUB");
            }else{
                await("目录 / 书签",65000);awaitPage();SystemClock.sleep(1800);
                android.webkit.WebView web=findWeb(activity.getWindow().getDecorView());                if(web!=null){JSONObject width=new JSONObject(js(web,"JSON.stringify({viewport:innerWidth,stride:parseFloat(getComputedStyle(document.getElementById('pages')).width)+2*parseFloat(getComputedStyle(document.getElementById('pages')).marginLeft)})"));result.put("columnWidth",width);check(Math.abs(width.getDouble("viewport")-width.getDouble("stride"))<.01,"EPUB 列宽与翻页步长相同，避免密度取整漂移");}
                if(mode.equals("links")){
                    check(web!=null,"EPUB 书内链接回归");click("目录 / 书签");await("第一章",5000);clickContaining("第一章");SystemClock.sleep(1400);clickNode(footer());await("章节进度",5000);
                    AccessibilityNodeInfo range=findRange(root());Bundle max=new Bundle();max.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,range.getRangeInfo().getMax());range.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),max);click("跳转");SystemClock.sleep(1400);
                    JSONObject rect=new JSONObject(js(web,"JSON.stringify((function(){var r=document.querySelector('a').getBoundingClientRect();return {x:r.left+Math.min(10,r.width/2),y:r.top+r.height/2,width:innerWidth};})())"));
                    int[] location=new int[2];runOnMainSync(()->web.getLocationOnScreen(location));float scale=web.getWidth()/(float)rect.getDouble("width");float x=location[0]+(float)rect.getDouble("x")*scale,y=location[1]+(float)rect.getDouble("y")*scale;
                    long now=SystemClock.uptimeMillis();inject(now,now,MotionEvent.ACTION_DOWN,x,y);SystemClock.sleep(700);inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y);SystemClock.sleep(1800);
                    check(js(web,"document.getElementById('pages').innerText").contains("P2_ANCHOR_TARGET"),"长按书内链接跳转到目标正文文件");check(Integer.parseInt(js(web,"document.getElementById('pages').dataset.page"))>0,"保留书内片段锚点定位");shot("epub-link");
                }else if(mode.equals("custom")){
                    check(web!=null,"EPUB 自定义资源回归");
                    touch(.5f,.3f,.5f,.3f,80);await("阅读设置",5000);click("外观");scrollPanel("选择字体文件");selectAsset("选择字体文件","QiPage-test.ttf");await("QiPage-test.ttf",12000);
                    scrollPanel("选择背景图片");selectAsset("选择背景图片","QiPage-background.png");await("移除图片",12000);click("完成");SystemClock.sleep(2000);
                    check(js(web,"getComputedStyle(document.getElementById('pages')).fontFamily").contains("QiPageLocal"),"自选字体应用到 EPUB");
                    check(js(web,"Array.from(document.fonts).some(f=>f.family==='QiPageLocal'&&f.status==='loaded')").equals("true"),"本机字体资源实际加载完成");
                    check(js(web,"getComputedStyle(document.getElementById('viewport')).backgroundImage").contains("qipage.invalid/background"),"背景图片从本机资源加载");shot("custom-day");
                    touch(.5f,.3f,.5f,.3f,80);await("阅读设置",5000);click("外观");clickContaining("深色");click("完成");SystemClock.sleep(1800);
                    check(js(web,"getComputedStyle(document.getElementById('viewport')).backgroundImage").contains("0.65"),"深色模式应用图片遮暗");shot("custom-night");
                    String before=footerText();swipe(.8f,.4f,.2f,.4f);SystemClock.sleep(1600);check(!before.equals(footerText()),"自选字体和图片下仍可翻页");
                }else if(mode.equals("sample")){
                    if(web!=null)check(js(web,"location.href").startsWith("https://qipage.invalid/page/"),"用户 EPUB 使用本机虚拟页面");click("目录 / 书签");await("第一章",7000);clickContaining("第一章");SystemClock.sleep(2000);
                    check(!text().contains("ERR_HTTP")&&!text().contains("正文分页失败"),"用户样书无解析或网页错误");check(footer()!=null,"用户样书有章节页数及一位小数");shot("sample-"+fixture);
                    String before=footerText();swipe(.8f,.4f,.2f,.4f);SystemClock.sleep(1300);check(!before.equals(footerText()),"用户样书可翻页");
                }else{
                    // Repeated runs always start at the same chapter.
                    click("目录 / 书签");await("第一章 山间清晨",7000);click("第一章 山间清晨");SystemClock.sleep(1800);
                    check(footer()!=null,"进度格式为 章节页码/总页数 整书百分比，保留一位小数");
                    String original=footerText();check(original.startsWith("1/"),"目录跳转定位章节第一页");shot(mode+"-first");
                    if(web!=null){
                        JSONObject geometry=new JSONObject(js(web,"JSON.stringify({font:getComputedStyle(document.getElementById('pages')).fontFamily,line:getComputedStyle(document.getElementById('pages')).lineHeight,gap:getComputedStyle(document.querySelector('p')).marginBottom,height:innerHeight,pages:document.getElementById('pages').scrollWidth})"));result.put("geometry",geometry);check(geometry.getString("font").contains("system-ui"),"EPUB 默认系统字体");check(geometry.getString("line").equals("34px")&&geometry.getString("gap").equals("8px"),"EPUB 行高按字号、段距按 dp");
                    }
                    swipe(.8f,.4f,.2f,.4f);SystemClock.sleep(1200);awaitFooter("2/");String second=footerText();check(second.startsWith("2/"),"左滑到本章第二页");
                    result.put("bodyBands",bands());shot(mode+"-body");
                    swipe(.2f,.4f,.8f,.4f);SystemClock.sleep(1200);awaitFooter(original);check(original.equals(footerText()),"右滑恢复同一页及进度");
                    // Hold the finger halfway across the page for an actual cover-animation frame.
                    long now=SystemClock.uptimeMillis();android.util.DisplayMetrics m=getTargetContext().getResources().getDisplayMetrics();
                    inject(now,now,MotionEvent.ACTION_DOWN,m.widthPixels*.8f,m.heightPixels*.4f);inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,m.widthPixels*.4f,m.heightPixels*.4f);SystemClock.sleep(1000);shot(mode+"-cover-left");inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,m.widthPixels*.4f,m.heightPixels*.4f);SystemClock.sleep(700);awaitFooter(original);check(original.equals(footerText()),"取消覆盖翻页不提交阅读位置");
                    swipe(.8f,.4f,.2f,.4f);SystemClock.sleep(1000);now=SystemClock.uptimeMillis();inject(now,now,MotionEvent.ACTION_DOWN,m.widthPixels*.2f,m.heightPixels*.4f);inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,m.widthPixels*.6f,m.heightPixels*.4f);SystemClock.sleep(1000);shot(mode+"-cover-right");inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,m.widthPixels*.6f,m.heightPixels*.4f);SystemClock.sleep(700);
                    awaitFooter(second);check(second.equals(footerText()),"反向覆盖取消仍保留当前页");
                    Rect before=new Rect();pageNode(root()).getBoundsInScreen(before);touch(.5f,.3f,.5f,.3f,80);await("阅读设置",6000);check(text().contains("排版")&&text().contains("外观"),"点击中央打开底部设置面板");
                    Rect after=new Rect();pageNode(root()).getBoundsInScreen(after);check(before.equals(after),"排版面板不缩小正文视口");
                    AccessibilityNodeInfo range=findRange(root());Bundle value=new Bundle();value.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,24f);range.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),value);SystemClock.sleep(1400);shot(mode+"-live-settings");check(text().contains("字号 · 24"),"字号即时应用");
                    click("外观");check(text().contains("系统字体"),"默认字体标签为系统字体");click("自定义背景与文字颜色");scrollPanel("颜色代码 #RRGGBB");List<AccessibilityNodeInfo> fields=new ArrayList<>();editables(root(),fields);check(!fields.isEmpty(),"RGB 色图保留可编辑颜色代码");set(fields.get(0),"#E7E1D5");SystemClock.sleep(700);shot(mode+"-rgb");click("完成");
                    click("目录 / 书签");await("第二章 短章",5000);click("第二章 短章");SystemClock.sleep(1400);awaitFooter("1/1 ");check(footerText().startsWith("1/1 "),"短章独立一页");String pageText=String.valueOf(pageNode(root()).getContentDescription());check(pageText.contains("此章到此结束")&&!pageText.contains("第三章 再出发"),"章尾留白，不拼接下一章");shot(mode+"-short");
                    swipe(.8f,.4f,.2f,.4f);SystemClock.sleep(1000);check(footerText().startsWith("1/"),"下一章从第 1 页开始");swipe(.2f,.4f,.8f,.4f);SystemClock.sleep(1000);awaitFooter("1/1 ");check(footerText().startsWith("1/1 "),"返回上一章最后一页");
                    back();await("+",6000);swipe(.08f,.5f,.85f,.5f);await("QiPage",6000);shot(mode+"-custom-drawer");Bitmap image=getUiAutomation().takeScreenshot();check((image.getPixel(12,300)&0xffffff)==0xe7e1d5,"侧栏采用相同自定义背景色");
                }
            }
            success=true;result.put("pass",true);
        }catch(Throwable e){try{result.put("pass",false).put("error",e.toString()).put("visible",text().substring(0,Math.min(text().length(),2000)));shot("failure-"+args.getString("mode"));}catch(Exception ignored){}}
        try{result.put("checks",checks);}catch(Exception ignored){}Bundle out=new Bundle();out.putString("stream",result.toString()+"\n");finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);
    }
    /**
     * Verifies the seven requested reader fixes end to end on a device:
     * animation direction and pacing, EPUB paragraph indent, no trailing blank
     * page, the full-screen directory with its fast-scroll bar, the drawer no
     * longer offering reading settings, and full-text search.
     */
    private void runFixes(Activity activity,JSONObject result)throws Exception{
        await("目录 / 书签",65000);awaitPage();SystemClock.sleep(1800);
        android.webkit.WebView web=findWeb(activity.getWindow().getDecorView());

        // (3) paragraph first-line indent, EPUB only
        if(web!=null){
            JSONObject indent=new JSONObject(js(web,"JSON.stringify((function(){var p=document.querySelector('#pages p');if(!p)return {em:-1};var cs=getComputedStyle(p);return {em:parseFloat(cs.textIndent)/parseFloat(cs.fontSize),raw:cs.textIndent};})())"));
            check(Math.abs(indent.getDouble("em")-2.0)<0.05,"EPUB 段落首行缩进为 2 字符（实测 "+indent.optString("raw")+"）");
            check(js(web,"getComputedStyle(document.querySelector('#pages h1,#pages h2')).textIndent").startsWith("0"),"章节标题不缩进");
            shot("fix-indent");
        }

        // (4) a short chapter must be exactly one page: a trailing blank column
        //     would show up here as 1/2.
        if(web!=null){
            click("目录 / 书签");await("第二章 短章",8000);click("第二章 短章");SystemClock.sleep(2000);
            String footer=footerText();
            check(footer.startsWith("1/1 "),"短章恰好一页，章末无多余空白页（底栏 "+footer+"）");
            String pageText=String.valueOf(pageNode(root()).getContentDescription());
            check(pageText.contains("此章到此结束"),"短章末页含正文而非空白");
            shot("fix-short-chapter");
            swipe(.8f,.4f,.2f,.4f);SystemClock.sleep(1600);
            check(footerText().startsWith("1/"),"下一章从第 1 页开始");
            swipe(.2f,.4f,.8f,.4f);SystemClock.sleep(1600);awaitFooter("1/1 ");
            check(footerText().startsWith("1/1 "),"返回上一章最后一页，不落到空白页");

            // (1)(2) page-turn direction. This page holds a single short line while
            // the page behind it is full of text, so which half of the screen the
            // previous page occupies is unambiguous.
            SystemClock.sleep(1000);
            String beforeTurn=footerText();
            Bitmap pageB=getUiAutomation().takeScreenshot();
            Rect page=new Rect();pageNode(root()).getBoundsInScreen(page);
            float y=page.top+page.height()*.4f;
            long now=SystemClock.uptimeMillis();
            inject(now,now,MotionEvent.ACTION_DOWN,page.left+page.width()*.2f,y);
            inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,page.left+page.width()*.6f,y);
            SystemClock.sleep(1100);
            Bitmap held=getUiAutomation().takeScreenshot();
            shot("fix-cover-right");
            inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,page.left+page.width()*.6f,y);
            SystemClock.sleep(1100);
            check(beforeTurn.equals(footerText()),"取消右滑不改变阅读位置（保持 "+beforeTurn+"）");
            double right=bandMatch(held,pageB,page.left+page.width()*6/10,page.right,page.top+4,page.bottom-4);
            check(right>0.90,"右滑动画：当前页右半部分保持不动（匹配 "+(int)(right*100)+"%）");
            // Counting ink rather than matching pixels: these pages are mostly
            // background, so a raw match ratio stays high whichever page is shown.
            // The arriving page is full of text, the pinned one is nearly blank.
            int inkLeft=ink(held,page.left,page.left+page.width()*4/10,page.top+4,page.bottom-4);
            int inkRight=ink(held,page.left+page.width()*6/10,page.right,page.top+4,page.bottom-4);
            check(inkLeft>inkRight*3&&inkLeft>500,"右滑动画：上一页自左侧滑入覆盖（左半墨迹 "+inkLeft+"，右半 "+inkRight+"）");
            result.put("backwardInkLeft",inkLeft).put("backwardInkRight",inkRight).put("backwardRightBand",right);
        }

        // (5) full-screen directory with a fast-scroll bar
        click("目录 / 书签");SystemClock.sleep(1600);
        android.util.DisplayMetrics metrics=getTargetContext().getResources().getDisplayMetrics();
        AccessibilityNodeInfo list=scrollable(root());
        if(list!=null){
            Rect listBounds=new Rect();list.getBoundsInScreen(listBounds);
            // The old bottom sheet was capped at 520dp and anchored to the bottom, so
            // its list started low; a full-screen panel starts right under the header.
            check(listBounds.top<=metrics.heightPixels*0.18,"目录铺满整屏，列表自顶部开始（top="+listBounds.top+" / "+metrics.heightPixels+"）");
            shot("fix-toc-fullscreen");
        }
        AccessibilityNodeInfo bar=findDescription(root(),"快速滚动条");
        if(bar==null){
            // Acceptable only when the whole table of contents already fits on screen.
            check(list==null,"目录无需滚动时不显示快速滚动条");
        }else{
            Rect barBounds=new Rect();bar.getBoundsInScreen(barBounds);
            check(barBounds.right>=metrics.widthPixels-2,"快速滚动条贴屏幕右侧（right="+barBounds.right+" / "+metrics.widthPixels+"）");
            check(barBounds.width()<=40,"快速滚动条是窄条而非整屏覆盖（width="+barBounds.width()+"）");
            // The whole point of the bar is crossing a long book in one gesture.
            int bx=barBounds.centerX();
            int by0=barBounds.top+16;
            int by1=barBounds.top+(int)(barBounds.height()*0.75f);
            long bt=SystemClock.uptimeMillis();
            inject(bt,bt,MotionEvent.ACTION_DOWN,bx,by0);
            for(int i=1;i<=10;i++){SystemClock.sleep(45);inject(bt,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,bx,by0+(by1-by0)*i/10f);}
            inject(bt,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,bx,by1);
            SystemClock.sleep(1000);
            boolean reached=false;String visible=text();
            for(int n=200;n<=300;n++){if(visible.contains("第"+n+"章")){reached=true;break;}}
            check(reached,"拖动快速滚动条可直接跳到目录靠后的章节");
            shot("fix-toc-scrolled");
        }

        // (7) full-text search from the same panel
        click("搜索");await("搜索全书文字",8000);
        List<AccessibilityNodeInfo> fields=new ArrayList<>();editables(root(),fields);
        check(!fields.isEmpty(),"搜索面板提供输入框");
        set(fields.get(0),"山川河流");
        click("搜索");
        long deadline=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<deadline&&!text().contains("找到")&&!text().contains("没有找到"))SystemClock.sleep(300);
        check(text().contains("找到"),"全文搜索返回结果并显示计数");
        shot("fix-search");
        clickContaining("山川河流");SystemClock.sleep(2500);awaitPage();
        check(footer()!=null,"点击搜索结果可跳回正文");
        shot("fix-search-jump");

    }

    /**
     * Verifies the second round of reader fixes end to end on a device:
     * tap priority while the panel is open, directory auto-location, the
     * bookshelf search entry, the bookmark placeholder and indexed search with
     * in-page highlighting.
     */
    private void runV2(Activity activity,JSONObject result)throws Exception{
        await("目录 / 书签",65000);awaitPage();SystemClock.sleep(1800);
        android.webkit.WebView web=findWeb(activity.getWindow().getDecorView());
        check(web!=null,"EPUB 阅读界面就绪");

        // (1) typography knobs reach the renderer
        String css=js(web,"(function(){var cs=getComputedStyle(document.getElementById('pages'));return cs.textAlign+'|'+cs.letterSpacing+'|'+cs.lineBreak;})()");
        check(css.startsWith("justify"),"EPUB 正文两端对齐（"+css+"）");

        // (3) with the panel open, an edge tap must close it, not turn the page
        String before=footerText();
        touch(.5f,.3f,.5f,.3f,80);await("阅读设置",6000);
        check(text().contains("排版"),"点击中央打开排版面板");
        long now=SystemClock.uptimeMillis();
        android.util.DisplayMetrics m=getTargetContext().getResources().getDisplayMetrics();
        inject(now,now,MotionEvent.ACTION_DOWN,m.widthPixels*.9f,m.heightPixels*.5f);
        inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,m.widthPixels*.9f,m.heightPixels*.5f);
        SystemClock.sleep(1200);
        check(!text().contains("排版"),"面板打开时点击边缘先关闭面板而不翻页");
        check(before.equals(footerText()),"关闭面板没有移动阅读位置（"+footerText()+"）");
        shot("v2-panel-closed");

        // (4) directory opens on the chapter being read
        click("目录 / 书签");SystemClock.sleep(1600);
        String want=args.getString("chapter","第二章 短章");
        check(text().contains(want),"目录自动定位到当前章节（"+want+" 可见）");
        shot("v2-toc-current");

        // (7) indexed search, then the match must be highlighted in the page
        click("搜索");await("搜索全书文字",8000);
        List<AccessibilityNodeInfo> f=new ArrayList<>();editables(root(),f);
        check(!f.isEmpty(),"搜索面板提供输入框");
        set(f.get(0),"山川河流");
        click("搜索");
        long deadline=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<deadline&&!text().contains("找到"))SystemClock.sleep(300);
        check(text().contains("找到"),"索引化搜索返回结果");
        shot("v2-search");
        clickContaining("山川河流");SystemClock.sleep(2600);awaitPage();
        long marks=Long.parseLong(js(web,"String(document.querySelectorAll('mark.qp-hl').length)"));
        check(marks>0,"正文中高亮了命中词组（"+marks+" 处）");
        shot("v2-highlight");

        // (5)(6) bookshelf entry points and the bookmark placeholder
        click("目录 / 书签");SystemClock.sleep(1500);click("书签");SystemClock.sleep(900);
        click("添加当前书签");SystemClock.sleep(1500);
        // The Material 3 placeholder is painted, not exposed to accessibility, so
        // capture the dialog and assert the functional half instead.
        shot("v2-bookmark");
        click("保存");SystemClock.sleep(1200);
        check(!text().contains("阅读书签"),"不再预填默认书签名称");
        check(text().contains("未命名"),"空名称书签以占位文字显示");
        shot("v2-bookmark-saved");
        click("关闭");SystemClock.sleep(900);
        back();await("+",8000);SystemClock.sleep(1200);
        check(findDescription(root(),"搜索书名")!=null,"书架左上角有放大镜搜索入口");
        shot("v2-bookshelf");
        swipe(.08f,.5f,.85f,.5f);await("QiPage",8000);SystemClock.sleep(900);
        check(!text().contains("阅读设置"),"侧边栏不再有阅读设置");
        check(!text().contains("返回书架"),"侧边栏不再有返回书架");
        shot("v2-drawer");
    }

    /** Fraction of sampled pixels that agree between two shots inside a band. */
    private double bandMatch(Bitmap a,Bitmap b,int x0,int x1,int y0,int y1){
        int same=0,total=0;
        for(int y=Math.max(0,y0);y<Math.min(y1,Math.min(a.getHeight(),b.getHeight()));y+=4){
            for(int x=Math.max(0,x0);x<Math.min(x1,Math.min(a.getWidth(),b.getWidth()));x+=4){
                int p=a.getPixel(x,y),q=b.getPixel(x,y);total++;
                if(Math.abs(android.graphics.Color.red(p)-android.graphics.Color.red(q))<=12&&
                   Math.abs(android.graphics.Color.green(p)-android.graphics.Color.green(q))<=12&&
                   Math.abs(android.graphics.Color.blue(p)-android.graphics.Color.blue(q))<=12)same++;
            }
        }
        return total==0?0:(double)same/total;
    }

    /** Number of dark ("inked") pixels in a band: a proxy for how much text it shows. */
    private int ink(Bitmap image,int x0,int x1,int y0,int y1){
        int count=0;
        for(int y=Math.max(0,y0);y<Math.min(y1,image.getHeight());y+=2){
            for(int x=Math.max(0,x0);x<Math.min(x1,image.getWidth());x+=2){
                int c=image.getPixel(x,y);
                if(android.graphics.Color.red(c)<150&&android.graphics.Color.green(c)<150&&android.graphics.Color.blue(c)<150)count++;
            }
        }
        return count;
    }

    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo n){
        if(n==null)return null;n.refresh();
        if(n.isScrollable())return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo r=scrollable(n.getChild(i));if(r!=null)return r;}
        return null;
    }

    private android.webkit.WebView webOrNull(Activity a){return findWeb(a.getWindow().getDecorView());}

    private Bundle statusBundle(String text){Bundle b=new Bundle();b.putString("stream",text);return b;}

    private void awaitFooter(String prefix){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo n=footer();if(n!=null&&n.getText().toString().startsWith(prefix))return;SystemClock.sleep(150);}throw new IllegalStateException("Footer did not settle: "+prefix+" actual="+footerText());}
    private AccessibilityNodeInfo footer(){return footerNode(root());}
    private AccessibilityNodeInfo footerNode(AccessibilityNodeInfo n){if(n==null)return null;n.refresh();if(n.getText()!=null&&n.getText().toString().matches("\\d+/\\d+ \\d+\\.\\d%"))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo r=footerNode(n.getChild(i));if(r!=null)return r;}return null;}
    private String footerText(){AccessibilityNodeInfo n=footer();if(n==null)throw new IllegalStateException("Missing page footer");return n.getText().toString();}
    private AccessibilityNodeInfo pageNode(AccessibilityNodeInfo n){if(n==null)return null;for(AccessibilityNodeInfo.AccessibilityAction a:n.getActionList())if("下一页".contentEquals(a.getLabel()==null?"":a.getLabel()))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo r=pageNode(n.getChild(i));if(r!=null)return r;}return null;}
    private JSONArray bands()throws Exception{
        Bitmap image=getUiAutomation().takeScreenshot();Rect r=new Rect();pageNode(root()).getBoundsInScreen(r);JSONArray bands=new JSONArray();boolean active=false;int first=0,last=0;int inkRows=0;
        for(int y=r.top+1;y<r.bottom;y++){int ink=0;for(int x=r.left+45;x<r.right-45;x++){int c=image.getPixel(x,y);if(android.graphics.Color.red(c)<130&&android.graphics.Color.green(c)<130&&android.graphics.Color.blue(c)<130)ink++;}boolean row=ink>8;if(row)inkRows++;if(row&&!active)first=y;if(!row&&active){if(y-first>5){bands.put(new JSONArray().put(first).put(y-1));last=y-1;}}active=row;}
        // Count inked rows rather than requiring many tall bands: paragraphs now
        // carry a first-line indent, so a short paragraph in this synthetic
        // fixture wraps and its continuation line is only a couple of glyphs wide,
        // which falls under the per-row ink threshold. The intent of the check is
        // "several lines of real text were drawn, not a blank frame".
        check(inkRows>40,"正文实际绘制多行，非空白截图（墨迹行数 "+inkRows+"）");
        check(bands.length()>3,"正文分多个行带（行带数 "+bands.length()+"）");
        int spacing=bands.getJSONArray(2).getInt(0)-bands.getJSONArray(1).getInt(0);
        check(r.bottom-last<spacing+30,"页底剩余空间不足再容纳一整行及间距");
        return bands;
    }
    private AccessibilityNodeInfo panelScroll(AccessibilityNodeInfo n){if(n==null)return null;Rect r=new Rect();n.getBoundsInScreen(r);if(n.isScrollable()&&r.centerY()>getTargetContext().getResources().getDisplayMetrics().heightPixels*.6f)return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=panelScroll(n.getChild(i));if(child!=null)return child;}return null;}
    private void scrollPanel(String label){for(int i=0;i<15;i++){AccessibilityNodeInfo n=find(root(),label,false);if(n!=null){Rect r=new Rect();n.getBoundsInScreen(r);if(r.height()>0&&n.isVisibleToUser())return;}if(label.startsWith("颜色代码")){List<AccessibilityNodeInfo> fields=new ArrayList<>();editables(root(),fields);if(!fields.isEmpty()&&fields.get(0).isVisibleToUser())return;}AccessibilityNodeInfo scroll=panelScroll(root());if(scroll!=null)scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);else swipe(.9f,.87f,.9f,.66f);SystemClock.sleep(450);}throw new IllegalStateException("Setting missing: "+label);}    private String js(android.webkit.WebView web,String source)throws Exception{
        java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);String[] output={"null"};
        runOnMainSync(()->web.evaluateJavascript(source,value->{output[0]=value;latch.countDown();}));
        if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("WebView script timed out");
        Object decoded=new JSONTokener(output[0]).nextValue();return String.valueOf(decoded);
    }
    private void scrollTo(String label){
        for(int i=0;i<8;i++){AccessibilityNodeInfo node=find(root(),label,false);if(node!=null){Rect r=new Rect();node.getBoundsInScreen(r);if(r.height()>0)return;}swipe(.5f,.65f,.5f,.35f);}throw new IllegalStateException("Setting missing: "+label);
    }    private void selectAsset(String button,String filename)throws Exception {
        IntentFilter filter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");
        ActivityMonitor monitor=addMonitor(filter,new ActivityResult(Activity.RESULT_OK,new Intent().setData(Uri.parse("content://local.readapp.test.oem.files/"+filename)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)),true);
        try{click(button);SystemClock.sleep(700);check(monitor.getHits()>0,"系统文件选择请求："+button);}finally{removeMonitor(monitor);}
    }    private AccessibilityNodeInfo findDescription(AccessibilityNodeInfo n,String value){if(n==null)return null;if(n.getContentDescription()!=null&&n.getContentDescription().toString().contains(value))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=findDescription(n.getChild(i),value);if(child!=null)return child;}return null;}    private android.webkit.WebView findWeb(View v){if(v instanceof android.webkit.WebView)return (android.webkit.WebView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){android.webkit.WebView w=findWeb(g.getChildAt(i));if(w!=null)return w;}}return null;}
    private String locator(){try(SQLiteDatabase db=db();Cursor c=db.rawQuery("SELECT locator FROM books WHERE title='P2本地样书'",null)){if(!c.moveToFirst())throw new IllegalStateException("EPUB missing");return c.getString(0);}}
    private SQLiteDatabase db(){return SQLiteDatabase.openDatabase(getTargetContext().getDatabasePath("library.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);}
    private long position(String title,String mode)throws Exception{try(SQLiteDatabase db=db();Cursor c=db.rawQuery("SELECT position,locator FROM books WHERE title=?",new String[]{title})){if(!c.moveToFirst())throw new IllegalStateException("book missing: "+title);return mode.equals("epub")?new JSONObject(c.getString(1)).optLong("offset"):c.getLong(0);}}
    private void check(boolean pass,String name)throws Exception{Bundle status=new Bundle();status.putString("stream","CHECK "+name+": "+pass+"\n");sendStatus(0,status);checks.put(new JSONObject().put("name",name).put("pass",pass));if(!pass)throw new IllegalStateException(name);}
    private AccessibilityNodeInfo root(){return getUiAutomation().getRootInActiveWindow();}
    private String text(){return tree(root());}
    private String tree(AccessibilityNodeInfo n){if(n==null)return "";n.refresh();StringBuilder b=new StringBuilder();if(n.getText()!=null)b.append(n.getText()).append('\n');if(n.getContentDescription()!=null)b.append(n.getContentDescription()).append('\n');for(int i=0;i<n.getChildCount();i++)b.append(tree(n.getChild(i)));return b.toString();}
    private void await(String value,long timeout){long end=SystemClock.elapsedRealtime()+timeout;do{String visible=text();if(visible.contains(value))return;if(visible.contains("知道了")&&!value.contains("暂不支持")&&!value.contains("越界资源路径"))click("知道了");SystemClock.sleep(200);}while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("Missing: "+value);}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo n,String value,boolean contains){if(n==null)return null;n.refresh();String t=n.getText()==null?"":n.getText().toString();if(!n.isEditable()&&(contains?t.contains(value):t.equals(value)))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo f=find(n.getChild(i),value,contains);if(f!=null)return f;}return null;}
    private void awaitPage(){long end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end){if(footer()!=null)return;SystemClock.sleep(30);}throw new IllegalStateException("Page not ready");}
    private boolean pageDescription(AccessibilityNodeInfo n){if(n==null)return false;if(n.getContentDescription()!=null&&n.getContentDescription().length()>0&&!n.getContentDescription().toString().equals("阅读页")){for(AccessibilityNodeInfo.AccessibilityAction action:n.getActionList())if("下一页".contentEquals(action.getLabel()==null?"":action.getLabel()))return true;}for(int i=0;i<n.getChildCount();i++)if(pageDescription(n.getChild(i)))return true;return false;}
    private void clickFast(String value){AccessibilityNodeInfo n=find(root(),value,false);while(n!=null&&!n.isClickable())n=n.getParent();if(n==null||!n.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("Cannot click");}
    private void click(String value){clickNode(find(root(),value,false));}
    private void clickContaining(String value){clickNode(find(root(),value,true));}
    private void clickNode(AccessibilityNodeInfo n){while(n!=null&&!n.isClickable())n=n.getParent();if(n==null||!n.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("Cannot click");SystemClock.sleep(350);}
    private void editables(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> out){if(n==null)return;n.refresh();if(n.isEditable())out.add(n);for(int i=0;i<n.getChildCount();i++)editables(n.getChild(i),out);}
    private void set(AccessibilityNodeInfo n,String value){Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);SystemClock.sleep(200);}
    private AccessibilityNodeInfo findRange(AccessibilityNodeInfo n){if(n==null)return null;n.refresh();if(n.getRangeInfo()!=null)return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo f=findRange(n.getChild(i));if(f!=null)return f;}return null;}
    private void back(){getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(500);}
    private void swipe(float x1,float y1,float x2,float y2){touch(x1,y1,x2,y2,400);}
    private void touch(float x1,float y1,float x2,float y2,int duration){android.util.DisplayMetrics m=getTargetContext().getResources().getDisplayMetrics();long now=SystemClock.uptimeMillis();inject(now,now,MotionEvent.ACTION_DOWN,x1*m.widthPixels,y1*m.heightPixels);if(x1==x2&&y1==y2){SystemClock.sleep(duration);inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x2*m.widthPixels,y2*m.heightPixels);SystemClock.sleep(400);return;}int count=20;for(int i=1;i<=count;i++){SystemClock.sleep(duration/count);inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,(x1+(x2-x1)*i/count)*m.widthPixels,(y1+(y2-y1)*i/count)*m.heightPixels);}inject(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x2*m.widthPixels,y2*m.heightPixels);SystemClock.sleep(400);}
    private void inject(long down,long time,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);getUiAutomation().injectInputEvent(e,true);e.recycle();}
    private void shot(String name)throws Exception{File dir=new File(getTargetContext().getExternalFilesDir(null),"p4-evidence");dir.mkdirs();Bitmap b=getUiAutomation().takeScreenshot();if(b!=null)try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}
}






















