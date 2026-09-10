package local.readapp.epub
import org.junit.Assert.*
import org.junit.Test

class SanitizerTest {
    @Test fun tocParagraphBecomesHeadingAndBlankParagraphKeepsLocatorOffset(){
        val html=SafeEpubEngine.sanitize("<html><body><p id='c'>第一章</p><p> </p><p>正文</p></body></html>".toByteArray(),"a",setOf("a"),setOf("第一章"),setOf("c"))
        assertTrue(html.contains("<h2 id=\"book-c\">"));assertTrue(html.contains("data-read=\"4\""));assertFalse(html.contains("> </span>"))
    }
    @Test fun svgWrappedCoverKeepsRasterWithoutSvgScripts(){
        val input="""<html><body><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"><script>BAD</script><image xlink:href="cover.jpeg" onload="BAD"/><image href="https://remote.invalid/a.png"/></svg></body></html>"""
        val html=SafeEpubEngine.sanitize(input.toByteArray(),"cover.xhtml",setOf("cover.xhtml"))
        assertTrue(html.contains("<img src=\"https://reader.invalid/cover.jpeg\""))
        for(value in listOf("<svg","<script","BAD","onload","remote.invalid"))assertFalse(html.contains(value))
    }
    @Test fun keepsStableTextAnchorsAndInternalLinksButDropsExecutableContent(){
        val input="""<html><body><h1 id="start">标题</h1><p onclick="alert(1)" style="background:url(https://evil)">正常😀</p><script>BAD_SCRIPT</script><svg><script>BAD_SVG</script></svg><iframe src="https://evil"/><a href="b.xhtml#part">下一节</a><img src="https://evil/p.png"/><img src="local.png" onerror="alert(1)"/><style>@import 'https://evil';</style></body></html>"""
        val html=SafeEpubEngine.sanitize(input.toByteArray(),"OPS/a.xhtml",setOf("OPS/a.xhtml","OPS/b.xhtml"))
        assertTrue(html.contains("id=\"book-start\""));assertTrue(html.contains("data-read=\"2\""));assertTrue(html.contains("正常😀"))
        assertTrue(html.contains("https://reader.invalid/OPS/b.xhtml#book-part"));assertTrue(html.contains("https://reader.invalid/OPS/local.png"))
        for(forbidden in listOf("BAD_SCRIPT","BAD_SVG","onclick","onerror","https://evil","<style","<script","<iframe"))assertFalse(forbidden,html.contains(forbidden))
        assertEquals(html,SafeEpubEngine.sanitize(input.toByteArray(),"OPS/a.xhtml",setOf("OPS/a.xhtml","OPS/b.xhtml")))
    }
    @Test fun escapesTextAndRejectsEntityDeclarations(){
        val html=SafeEpubEngine.sanitize("<html><body>&lt;script&gt;&amp;</body></html>".toByteArray(),"a",setOf("a"))
        assertTrue(html.contains("&lt;script&gt;&amp;"))
        try{SafeEpubEngine.sanitize("<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///secret'>]><html><body>&x;</body></html>".toByteArray(),"a",setOf("a"));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun acceptsExternalDoctypeWithoutResolvingIt(){
        val html=SafeEpubEngine.sanitize("<!DOCTYPE html PUBLIC 'x' 'https://invalid/dtd'><html><body><p>A&nbsp;B</p></body></html>".toByteArray(),"a",setOf("a"))
        assertTrue(html.contains("A B"))
    }
}
