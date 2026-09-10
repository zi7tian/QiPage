package local.readapp.data
import local.readapp.core.Locator
import org.json.JSONObject

internal object LocatorCodec {
    fun encode(value:Locator)=JSONObject().put("v",1).put("format",value.format).put("offset",value.offset.coerceAtLeast(0)).put("href",value.href).put("anchor",value.anchor).put("progress",value.progression.takeIf { it.isFinite() }?.coerceIn(0.0,1.0)?:0.0).toString()
    fun decode(value:String,txtOffset:Long=0):Locator=runCatching {
        val json=JSONObject(value);require(json.optInt("v")==1)
        Locator(format=json.getString("format"),offset=json.optLong("offset").coerceAtLeast(0),href=json.optString("href"),anchor=json.optString("anchor"),progression=json.optDouble("progress",0.0).coerceIn(0.0,1.0))
    }.getOrDefault(Locator(offset=txtOffset))
}
