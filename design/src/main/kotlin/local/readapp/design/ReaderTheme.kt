package local.readapp.design
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

@Composable fun ReaderTheme(theme:String, content:@Composable ()->Unit) {
    val colors=when(if(theme=="system")if(isSystemInDarkTheme())"dark" else "light" else theme) {
        "dark" -> darkColorScheme(primary=Color(0xFFD4A373),secondary=Color(0xFFD4A373),background=Color(0xFF1E2426),surface=Color(0xFF1E2426),surfaceContainer=Color(0xFF293133),onSurface=Color(0xFFE5E1D7),onSurfaceVariant=Color(0xFFA6AAA5))
        else -> lightColorScheme(primary=Color(0xFF2A363B),secondary=Color(0xFFD4A373),primaryContainer=Color(0xFFE5E9E2),onPrimaryContainer=Color(0xFF2A363B),secondaryContainer=Color(0xFFF0E3D3),onSecondaryContainer=Color(0xFF61472F),background=Color(0xFFF8F6F0),surface=Color(0xFFF8F6F0),surfaceContainer=Color(0xFFF0EDE5),onSurface=Color(0xFF1E2022),onSurfaceVariant=Color(0xFF7C807B),outlineVariant=Color(0xFFE4E1D8))
    }
    MaterialTheme(colorScheme=colors,content=content)
}
