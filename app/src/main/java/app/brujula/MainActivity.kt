package app.brujula

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.brujula.model.Money

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BrujulaTheme { PantallaInicial() } }
    }
}

private val Esmeralda = Color(0xFF2FBF8F)

@Composable
private fun BrujulaTheme(content: @Composable () -> Unit) {
    val colores = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Esmeralda, background = Color(0xFF0F1A17), surface = Color(0xFF0F1A17))
    } else {
        lightColorScheme(primary = Color(0xFF127A59))
    }
    MaterialTheme(colorScheme = colores, content = content)
}

@Composable
private fun PantallaInicial() {
    // Comprobación mínima de que el módulo core-model está enlazado en el APK.
    val prueba = Money.of("0.10") + Money.of("0.20")

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Brújula", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text("Simulador de inversión", style = MaterialTheme.typography.titleMedium)
            Text(
                "Fase 1 · núcleo financiero (sin interfaz aún) (v${BuildConfigInfo.VERSION})",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Comprobación de módulos: 0,10 + 0,20 = $prueba",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Esto es una simulación educativa. No es asesoramiento financiero.",
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private object BuildConfigInfo {
    const val VERSION = "0.1.0"
}
