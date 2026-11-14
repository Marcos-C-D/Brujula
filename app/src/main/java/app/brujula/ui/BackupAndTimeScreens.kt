package app.brujula.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.brujula.app.Fmt

// ------------------------------------------------------------------ Copias de seguridad

@Composable
fun BackupScreen(c: UiController, context: Context) {
    val today = c.container.today()
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) c.exportBackup(context, uri)
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) c.exportCsv(context, uri)
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) c.readImport(context, uri)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Copia de seguridad completa (JSON)")
        Text(
            "Guarda todas tus cuentas (también las archivadas) con su configuración, planes, opciones fiscales y todos los movimientos. " +
                "Guárdala fuera del móvil (nube o ordenador): si desinstalas la app o pierdes el teléfono, es la única forma de recuperar tus datos.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { exportJson.launch("brujula-copia-$today.json") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Guardar copia de seguridad") }

        SectionTitle("Restaurar una copia")
        Text(
            "Se comprueba toda la copia (reproducción y auditoría de cada cuenta) antes de importar nada. Si una cuenta ya existe con el mismo " +
                "identificador o nombre, no se importa ninguna: tus datos actuales no se tocan.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("Importar copia de seguridad") }

        SectionTitle("Exportar movimientos (CSV)")
        Text(
            "Un movimiento por fila de la cuenta seleccionada, con el efectivo acumulado. Formato para Excel en español (separador «;» y coma decimal).",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { exportCsv.launch("brujula-movimientos-$today.csv") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Exportar CSV de la cuenta") }
        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }

    val pending = c.pendingImport.value
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { c.cancelImport() },
            title = { Text("¿Importar esta copia?") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("La copia es válida y contiene:", style = MaterialTheme.typography.bodyMedium)
                    pending.forEach { a ->
                        Text(
                            "• ${a.config.name}: ${a.events.size} movimientos" + if (a.config.archiveIntervals.lastOrNull()?.to == null && a.config.archiveIntervals.isNotEmpty()) " (archivada)" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Se añadirán a las que ya tienes; no se modifica ni se borra nada.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { c.confirmImport() }) { Text("Importar") } },
            dismissButton = { TextButton(onClick = { c.cancelImport() }) { Text("Cancelar") } },
        )
    }
}

// ------------------------------------------------------------------ Avance de tiempo

@Composable
fun TimeTravelScreen(c: UiController) {
    val tm = c.sandbox.value
    Column(modifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        if (tm == null) {
            SectionTitle("Avance de tiempo")
            Text(
                "Prueba cómo evolucionarían tus cuentas con el paso del tiempo (ingresos mensuales, planes periódicos, órdenes pendientes, liquidaciones, " +
                    "dividendos y calendario del IRPF) sin esperar meses.",
                style = MaterialTheme.typography.bodyMedium,
            )
            InfoCard {
                Text("Es una simulación aparte", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Trabaja sobre una COPIA de tus cuentas en memoria. No guarda nada, no cambia tus datos reales y se descarta al salir. " +
                        "Solo hay precios reales hasta el último día descargado: más allá, las órdenes esperan porque nunca se inventan precios.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { c.startSandbox() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Empezar simulación") }
            return@Column
        }
        val v = c.sandboxView.value
        Notice("MODO SIMULACIÓN · hoy virtual: ${Fmt.date(tm.today)}. No modifica tus datos reales.")
        val through = v?.dataThrough
        if (through != null && tm.today.isAfter(through)) {
            Text("Tus precios reales llegan hasta el ${Fmt.date(through)}: desde entonces las órdenes pendientes esperan.", style = MaterialTheme.typography.bodySmall)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("+1 día" to 1, "+1 semana" to 7, "+1 mes" to 30, "+1 año" to 365).forEach { (label, days) ->
                OutlinedButton(onClick = { c.advanceSandbox(days) }, enabled = !c.sandboxBusy.value) { Text(label, maxLines = 1) }
            }
        }
        if (c.sandboxBusy.value) Text("Procesando…", style = MaterialTheme.typography.bodySmall)
        if (v == null) {
            Text("No hay cuentas activas que simular.", modifier = Modifier.padding(top = 12.dp))
        } else {
            SectionTitle(v.accountName)
            InfoCard {
                KeyValue("Patrimonio", Fmt.eur(v.valuation.netWorth))
                KeyValue("Efectivo", Fmt.eur(v.valuation.cash), if (v.valuation.cash.isNegative) Rojo else androidx.compose.ui.graphics.Color.Unspecified)
                KeyValue("Disponible para operar", Fmt.eur(v.valuation.availableCash))
                KeyValue("Valor de las inversiones", Fmt.eur(v.valuation.investedValue))
                KeyValue("Órdenes pendientes", v.pendingOrders.toString())
                if (!v.valuation.complete) Notice("Falta el precio de algún activo en esa fecha.")
            }
            if (v.taxPostings.isNotEmpty()) {
                SectionTitle("IRPF simulado")
                v.taxPostings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (c.sandboxReport.value.isNotEmpty()) {
                SectionTitle("Lo ocurrido en el último avance")
                Text(c.sandboxReport.value, style = MaterialTheme.typography.bodySmall)
            }
            SectionTitle("Últimos movimientos")
            v.movements.forEach { mv ->
                Text("${Fmt.date(mv.date)} · ${mv.kind}: ${mv.description}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
        OutlinedButton(onClick = { c.stopSandbox() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp)) { Text("Salir y descartar la simulación") }
    }
}
