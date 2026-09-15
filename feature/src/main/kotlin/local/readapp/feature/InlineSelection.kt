package local.readapp.feature

import android.content.Context
import android.text.SpannableString
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.staticCompositionLocalOf
import local.readapp.core.Locator

internal data class MarkSelection(val text:String,val locator:Locator,val length:Int)
internal val LocalMarkSelection=staticCompositionLocalOf<(MarkSelection)->Unit>{{}}

internal class PaperWebView(context:Context):android.webkit.WebView(context){
    var selectionAction:((Int,()->Unit)->Unit)?=null
    var selectionClosed:(()->Unit)?=null
    override fun startActionMode(callback:ActionMode.Callback,type:Int):ActionMode? {
        val wrapped=object:ActionMode.Callback2(){
            override fun onGetContentRect(mode:ActionMode,view:View,outRect:android.graphics.Rect){if(callback is ActionMode.Callback2)callback.onGetContentRect(mode,view,outRect) else super.onGetContentRect(mode,view,outRect)}
            override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean{val ok=callback.onCreateActionMode(mode,menu);paperMenu(menu);return ok}
            override fun onPrepareActionMode(mode:ActionMode,menu:Menu):Boolean{callback.onPrepareActionMode(mode,menu);paperMenu(menu);return true}
            override fun onActionItemClicked(mode:ActionMode,item:MenuItem):Boolean{
                if(item.itemId in 9101..9103){selectionAction?.invoke(item.itemId){mode.finish()};return true}
                return callback.onActionItemClicked(mode,item)
            }
            override fun onDestroyActionMode(mode:ActionMode){callback.onDestroyActionMode(mode);selectionClosed?.invoke()}
        }
        return super.startActionMode(wrapped,type)
    }
}

/** Native selection handles sit exactly over the rendered TXT page. */
internal class SelectableTxtSurface(context:Context):FrameLayout(context){
    val cover=CoverPageView(context)
    private val text=TextView(context)
    private var page:TextPage?=null
    private var mode:ActionMode?=null
    var marked:((MarkSelection)->Unit)?=null
    var note:((SelectionPage)->Unit)?=null
    init {
        addView(cover,LayoutParams(-1,-1));addView(text,LayoutParams(-1,-1));text.visibility=GONE
        text.setTextIsSelectable(true);text.includeFontPadding=false;text.setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
        text.customSelectionActionModeCallback=object:ActionMode.Callback{
            override fun onCreateActionMode(action:ActionMode,menu:Menu):Boolean{mode=action;paperMenu(menu,true);return true}
            override fun onPrepareActionMode(action:ActionMode,menu:Menu):Boolean{paperMenu(menu,true);return true}
            override fun onActionItemClicked(action:ActionMode,item:MenuItem):Boolean{
                if(item.itemId !in 9101..9102)return false
                val f=page?:return false;val a=minOf(text.selectionStart,text.selectionEnd).coerceAtLeast(0);val b=maxOf(text.selectionStart,text.selectionEnd).coerceAtMost(text.length())
                if(b>a){val range=sourceSelection(f.offsets,a,b,f.start);val quote=text.text.substring(a,b);val locator=Locator(offset=range.start)
                    if(item.itemId==9101)marked?.invoke(MarkSelection(quote,locator,range.length))
                    else note?.invoke(SelectionPage(quote,locator,f.offsets.copyOfRange(a,b),true))
                }
                action.finish();return true
            }
            override fun onDestroyActionMode(action:ActionMode){mode=null;text.visibility=GONE;cover.visibility=VISIBLE}
        }
    }
    fun select(value:TextPage,margin:Int,vertical:Int,bg:Int,justify:Boolean,x:Float,y:Float){
        page=value
        text.text=SpannableString(value.layout.text)
        text.setTextColor(value.layout.paint.color);text.typeface=value.layout.paint.typeface
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX,value.layout.paint.textSize)
        text.letterSpacing=value.layout.paint.letterSpacing;text.breakStrategy=android.text.Layout.BREAK_STRATEGY_SIMPLE
        text.justificationMode=if(justify)android.text.Layout.JUSTIFICATION_MODE_INTER_WORD else android.text.Layout.JUSTIFICATION_MODE_NONE
        text.setPadding(margin,vertical,margin,vertical);text.setBackgroundColor(bg)
        text.visibility=VISIBLE;cover.visibility=INVISIBLE;text.requestFocus()
        fun begin(attempt:Int=0){
            if(text.width==0||text.height==0){if(attempt<10)text.postOnAnimation{begin(attempt+1)};return}
            val now=android.os.SystemClock.uptimeMillis();val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);down.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;text.dispatchTouchEvent(down);down.recycle()
            val offset=text.getOffsetForPosition(x,y).coerceIn(0,(text.length()-1).coerceAtLeast(0))
            if(text.length()>0)android.text.Selection.setSelection(text.text as android.text.Spannable,offset,offset+1)
            text.performLongClick(x,y)
            text.postDelayed({val up=MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0);text.dispatchTouchEvent(up);up.recycle()},100)
            text.postDelayed({if(mode==null){text.visibility=GONE;cover.visibility=VISIBLE}},1800)
        }
        text.postOnAnimation{begin()}
    }
    fun dispose(){mode?.finish();cover.abort()}
}

private fun paperMenu(menu:Menu,nativeText:Boolean=false){
    menu.clear()
    menu.add(0,9101,0,"高亮").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    menu.add(0,9102,1,"笔记").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    menu.add(0,if(nativeText)android.R.id.copy else 9103,2,"复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    if(nativeText)menu.add(0,android.R.id.selectAll,3,"全选")
}
