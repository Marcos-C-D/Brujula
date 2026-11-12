package app.brujula.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Esmeralda = Color(0xFF2FBF8F)
val Rojo = Color(0xFFE5645A)

@Composable
fun BrujulaTheme(content: @Composable () -> Unit) {
    val colores = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Esmeralda, background = Color(0xFF0F1A17), surface = Color(0xFF14231F), onSurface = Color(0xFFE6F4EF))
    } else {
        lightColorScheme(primary = Color(0xFF127A59))
    }
    MaterialTheme(colorScheme = colores, content = content)
}
