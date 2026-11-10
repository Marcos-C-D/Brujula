package app.brujula

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.brujula.data.SqliteBankStore
import app.brujula.engine.Bank
import app.brujula.engine.LedgerAuditor

class MainActivity : ComponentActivity() {

    private val datos = mutableStateOf("Leyendo datos guardados…")
    private val prueba = mutableStateOf("")
    private val ejecutando = mutableStateOf(false)
    private val mercado = mutableStateOf("")
    private val actualizando = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BrujulaTheme {
                PantallaInicial(
                    datos = datos.value,
                    prueba = prueba.value,
                    ejecutando = ejecutando.value,
                    mercado = mercado.value,
                    actualizando = actualizando.value,
                    alProbar = { lanzarPrueba() },
                    alActualizar = { actualizarPrecios() },
                )
            }
        }
        leerDatosGuardados()
        UpdateScheduler.scheduleNext(applicationContext) // próxima actualización a las 00:00 (Madrid)
        recuperarActualizacionPerdida()
    }

    /** Lee (solo lectura) la base de datos real y muestra un resumen verificado. */
    private fun leerDatosGuardados() {
        Thread {
            datos.value = try {
                val store = SqliteBankStore(applicationContext)
                try {
                    val cuentas = Bank.restore(store).all()
                    val movimientos = cuentas.sumOf { it.ledger.records.size }
                    val problemas = cuentas.flatMap { LedgerAuditor.audit(it.ledger.records) }
                    "Cuentas guardadas: ${cuentas.size} · movimientos: $movimientos\n" +
                        "Auditoría de datos: " + if (problemas.isEmpty()) "correcta" else "${problemas.size} problemas"
                } finally {
                    store.close()
                }
            } catch (e: Exception) {
                "Error al leer los datos guardados: ${e.message}"
            }
        }.start()
    }

    private fun lanzarPrueba() {
        if (ejecutando.value) return
        ejecutando.value = true
        prueba.value = "Ejecutando prueba…"
        Thread {
            val resultado = try {
                PruebaPersistencia.ejecutar(applicationContext, "brujula-prueba.db")
            } catch (e: Exception) {
                "✘ La prueba falló: ${e.message}"
            }
            prueba.value = resultado
            ejecutando.value = false
        }.start()
    }

    /** Botón manual: solo actualiza datos de mercado; no toca cuentas ni órdenes. */
    private fun actualizarPrecios() {
        if (actualizando.value) return
        actualizando.value = true
        mercado.value = "Actualizando precios… (puede tardar un minuto)"
        Thread {
            mercado.value = try {
                MarketModule.updateAndReport(applicationContext).joinToString("\n")
            } catch (e: Exception) {
                "✘ La actualización falló: ${e.message}"
            }
            actualizando.value = false
        }.start()
    }

    private fun recuperarActualizacionPerdida() {
        Thread {
            try {
                MarketModule.catchUpIfNeeded(applicationContext)
            } catch (e: Exception) {
                // sin conexión u otro fallo: se conserva lo guardado
            }
        }.start()
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
private fun PantallaInicial(
    datos: String,
    prueba: String,
    ejecutando: Boolean,
    mercado: String,
    actualizando: Boolean,
    alProbar: () -> Unit,
    alActualizar: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Brújula", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text("Simulador de inversión", style = MaterialTheme.typography.titleMedium)
            Text(
                "Fase 3 · órdenes y liquidaciones (v${BuildConfigInfo.VERSION})",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(datos, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall)

            Button(onClick = alProbar, enabled = !ejecutando, modifier = Modifier.padding(top = 24.dp)) {
                Text("Probar persistencia (base temporal)")
            }
            if (prueba.isNotEmpty()) {
                Text(prueba, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }

            Button(onClick = alActualizar, enabled = !actualizando, modifier = Modifier.padding(top = 24.dp)) {
                Text("Actualizar precios ahora (diagnóstico)")
            }
            if (mercado.isNotEmpty()) {
                Text(mercado, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }

            Text(
                "Esto es una simulación educativa. No es asesoramiento financiero.",
                modifier = Modifier.padding(top = 32.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private object BuildConfigInfo {
    const val VERSION = "0.4.0"
}
