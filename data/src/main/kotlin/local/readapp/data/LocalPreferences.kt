package local.readapp.data
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import local.readapp.core.*
import java.io.IOException

private val Context.readerStore by preferencesDataStore("reader")
class LocalPreferences(context:Context):PreferencesRepository {
    private val store=context.applicationContext.readerStore
    private val font=intPreferencesKey("font_size"); private val theme=stringPreferencesKey("theme"); private val awake=booleanPreferencesKey("keep_awake")
    private val line=floatPreferencesKey("line_spacing");private val paragraph=intPreferencesKey("paragraph_spacing");private val margin=intPreferencesKey("page_margin");private val rule=stringPreferencesKey("chapter_rule")
    private val letterSpac=floatPreferencesKey("letter_spacing");private val justify=booleanPreferencesKey("justify")
    private val tap=booleanPreferencesKey("tap_turn");private val marks=booleanPreferencesKey("bookmarks_enabled");private val order=booleanPreferencesKey("navigation_first")
    override val preferences=store.data.catch { if(it is IOException)emit(emptyPreferences()) else throw it }.map {
        ReaderPreferences((it[font]?:20).coerceIn(14,32),it[theme]?.takeIf { value -> value in setOf("paper","light","dark","system") }?:"paper",it[awake]?:false,
            (it[line]?:1.7f).coerceIn(1.2f,2.4f),(it[paragraph]?:8).coerceIn(0,24),(it[margin]?:24).coerceIn(8,40),(it[letterSpac]?:0f).coerceIn(0f,0.3f),it[justify]?:true,it[rule]?.takeIf { value -> value in setOf("all","chinese","english","none") }?:"all",it[tap]?:true,it[marks]?:true,it[order]?:false, fontFile=it[stringPreferencesKey("font_file")]?:"",fontName=it[stringPreferencesKey("font_name")]?:"",backgroundFile=it[stringPreferencesKey("background_file")]?:"",dayBackground=it[stringPreferencesKey("day_bg")]?:"",dayText=it[stringPreferencesKey("day_text")]?:"",nightBackground=it[stringPreferencesKey("night_bg")]?:"",nightText=it[stringPreferencesKey("night_text")]?:"",nightImageDim=(it[floatPreferencesKey("night_dim")]?:0.65f).coerceIn(0f,0.95f),chapterUnits=it[stringPreferencesKey("chapter_units")]?:"章回节卷部篇",chapterSeparator=it[booleanPreferencesKey("chapter_separator")]?:false,chapterTitleLimit=(it[intPreferencesKey("chapter_title_limit")]?:100).coerceIn(6,100),excludedTitles=it[stringPreferencesKey("excluded_titles")]?:"")
    }
    override suspend fun update(preferences:ReaderPreferences) { store.edit {
                mapOf("font_file" to preferences.fontFile,"font_name" to preferences.fontName,"background_file" to preferences.backgroundFile,"day_bg" to preferences.dayBackground,"day_text" to preferences.dayText,"night_bg" to preferences.nightBackground,"night_text" to preferences.nightText,"chapter_units" to preferences.chapterUnits,"excluded_titles" to preferences.excludedTitles).forEach { (key,value)->it[stringPreferencesKey(key)]=value }
        it[floatPreferencesKey("night_dim")]=preferences.nightImageDim.coerceIn(0f,0.95f)
        it[booleanPreferencesKey("chapter_separator")]=preferences.chapterSeparator
        it[intPreferencesKey("chapter_title_limit")]=preferences.chapterTitleLimit.coerceIn(6,100)
        it[font]=preferences.fontSize.coerceIn(14,32); it[theme]=preferences.theme; it[awake]=preferences.keepScreenOn
        it[tap]=preferences.tapToTurn;it[marks]=preferences.bookmarksEnabled;it[order]=preferences.navigationFirst
        it[line]=preferences.lineSpacing.coerceIn(1.2f,2.4f);it[paragraph]=preferences.paragraphSpacing.coerceIn(0,24);it[margin]=preferences.pageMargin.coerceIn(8,40);it[letterSpac]=preferences.letterSpacing.coerceIn(0f,0.3f);it[justify]=preferences.justify;it[rule]=preferences.chapterRule
    } }
}

