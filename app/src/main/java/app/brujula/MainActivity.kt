package app.brujula

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.brujula.ui.AppRoot
import app.brujula.ui.BrujulaTheme
import app.brujula.ui.UiController

class MainActivity : ComponentActivity() {

    private lateinit var controller: UiController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as BrujulaApp).container
        controller = UiController(container)
        setContent { BrujulaTheme { AppRoot(controller, applicationContext) } }
        UpdateScheduler.scheduleNext(applicationContext) // próxima actualización a las 00:00 (Madrid)
        container.submit {
            try { MarketModule.catchUpIfNeeded(container) } catch (e: Exception) { /* sin conexión: se conserva lo guardado */ }
        }
    }
}
