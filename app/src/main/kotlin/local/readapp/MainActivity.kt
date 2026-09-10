package local.readapp
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import local.readapp.data.LocalServices
import local.readapp.feature.IncomingFile
import local.readapp.feature.ReadApp
import local.readapp.txt.IndexedTxtEngine
import local.readapp.epub.SafeEpubEngine

class ReadApplication:Application() {
    val services by lazy { LocalServices(this,IndexedTxtEngine(),SafeEpubEngine()) }
}
class MainActivity:ComponentActivity() {
    private val incoming=Channel<IncomingFile>(Channel.UNLIMITED)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        consume(intent)
        val services=(application as ReadApplication).services
        setContent { ReadApp(services.books,services.preferences,remember { incoming.receiveAsFlow() }) }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); consume(intent) }
    private fun consume(value:Intent?) {
        if(value?.action==Intent.ACTION_VIEW && value.data!=null) {
            incoming.trySend(IncomingFile(value.data.toString(),value.flags))
            setIntent(Intent(this,MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        }
    }
}
