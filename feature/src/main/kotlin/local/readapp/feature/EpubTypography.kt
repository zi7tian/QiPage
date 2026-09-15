package local.readapp.feature

/** Chromium on older Android versions does not implement hanging-punctuation.
 * Plan punctuation side-bearing compression from the actual selected font,
 * then leave glyph shaping, justification and selection to the browser.
 */
internal const val EPUB_PUNCTUATION_JS="""(function(){
var root=document.getElementById('pages');if(!root)return;
var old=root.querySelectorAll('.qp-punct');for(var i=0;i<old.length;i++){var e=old[i],p=e.parentNode;while(e.firstChild)p.insertBefore(e.firstChild,e);p.removeChild(e);p.normalize();}
if(getComputedStyle(root).textAlign!=='justify')return;
var open='（〔［｛〈《「『【“‘',close='，。！？：；、）〕］｝〉》」』】”’',canvas=document.createElement('canvas'),ctx=canvas.getContext('2d'),blocks=root.querySelectorAll('p,li,blockquote');
for(var b=0;b<blocks.length;b++){
 var block=blocks[b];if(block.querySelector('p,li,blockquote,img,table,br'))continue;
 var css=getComputedStyle(block),size=parseFloat(css.fontSize),width=block.clientWidth,spacing=parseFloat(css.letterSpacing)||0;
 if(!width)continue;ctx.font=css.fontWeight+' '+css.fontSize+' '+css.fontFamily;
 var walker=document.createTreeWalker(block,NodeFilter.SHOW_TEXT),nodes=[],chars=[],node;
 while(node=walker.nextNode()){nodes.push(node);var t=node.nodeValue;for(var j=0;j<t.length;){var cp=t.codePointAt(j),ch=String.fromCodePoint(cp);chars.push({node:node,at:j,ch:ch,w:ctx.measureText(ch).width+spacing,cut:0});j+=ch.length;}}
 var at=0,first=true;
 while(at<chars.length){
  var limit=width-(first?(parseFloat(css.textIndent)||0):0),end=at,used=0;
  while(end<chars.length&&used+chars[end].w<=limit){used+=chars[end].w;end++;}
  if(end===chars.length)break;if(end===at){at++;first=false;continue;}
  var stop=end+1;while(stop<chars.length&&close.indexOf(chars[stop].ch)>=0)stop++;
  var wanted=used,room=0;for(var j=at;j<stop;j++){var c=chars[j];if(j>=end)wanted+=c.w;if((open+close).indexOf(c.ch)>=0)room+=Math.max(0,(c.w-spacing)*.5);}
  var need=wanted-limit+.25;
  if(need>0&&need<=room){for(var j=at;j<stop;j++){var c=chars[j];if((open+close).indexOf(c.ch)>=0)c.cut=(c.w-spacing)*.5*need/room;}end=stop;}
  at=end;first=false;
 }
 // Only punctuation gets a new inline box; text and data-read offsets are intact.
 for(var n=0;n<nodes.length;n++){
  var src=nodes[n],hits=chars.filter(function(c){return c.node===src&&c.cut>0;}),text=src.nodeValue;if(!hits.length)continue;
  var fragment=document.createDocumentFragment(),last=0;
  for(var h=0;h<hits.length;h++){var c=hits[h];fragment.appendChild(document.createTextNode(text.slice(last,c.at)));var span=document.createElement('span');span.className='qp-punct';span.textContent=c.ch;span.style.letterSpacing=(spacing-c.cut)+'px';if(open.indexOf(c.ch)>=0){span.style.position='relative';span.style.left=(-c.cut)+'px';}fragment.appendChild(span);last=c.at+c.ch.length;}
  fragment.appendChild(document.createTextNode(text.slice(last)));src.parentNode.replaceChild(fragment,src);
 }
}
})()"""
