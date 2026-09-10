package local.readapp.design
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

@Composable fun ReaderTheme(theme:String, content:@Composable ()->Unit) {
    val colors=when(if(theme=="system")if(isSystemInDarkTheme())"dark" else "light" else theme) {
        "dark" -> darkColorScheme(primary=Color(0xFFBED0B2),background=Color(0xFF171C19),surface=Color(0xFF171C19),surfaceContainer=Color(0xFF252D27),onSurface=Color(0xFFE3E8DE))
        "light" -> lightColorScheme(primary=Color(0xFF496342),background=Color(0xFFFCFDF9),surface=Color(0xFFFCFDF9),surfaceContainer=Color(0xFFF0F2EC))
        else -> lightColorScheme(primary=Color(0xFF53644B),background=Color(0xFFF8F4EA),surface=Color(0xFFF8F4EA),surfaceContainer=Color(0xFFEFE9DC),onSurface=Color(0xFF293129),onSurfaceVariant=Color(0xFF626A5D))
    }
    MaterialTheme(colorScheme=colors,content=content)
}
