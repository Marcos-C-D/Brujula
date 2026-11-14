package app.brujula.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.brujula.AccountItem
import app.brujula.app.Fmt
import app.brujula.model.AccountId
import app.brujula.model.Money

// ------------------------------------------------------------------ Cuentas

private sealed class Dialog {
    object Create : Dialog()
    data class Rename(val a: AccountItem) : Dialog()
    data class Income(val a: AccountItem) : Dialog()
    data class Delete(val a: AccountItem) : Dialog()
}

@Composable
fun AccountsScreen(c: UiController) {
    val accounts = c.data.value.accounts
    val dialog = remember { mutableStateOf<Dialog?>(null) }
    val selectedId = c.selected.value

    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
        item {
            Text("Cada cuenta es independiente: tiene su propio efectivo, cartera, historial y órdenes. No hay transferencias entre cuentas.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { dialog.value = Dialog.Create }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Crear cuenta") }
        }
        items(accounts) { a ->
            InfoCard {
                Text(a.name + if (a.archived) "  (archivada)" else "", style = MaterialTheme.typography.titleMedium)
                KeyValue("Patrimonio", Fmt.eur(a.netWorth) + if (!a.complete) " *" else "")
                KeyValue("Efectivo", Fmt.eur(a.cash))
                if (a.pendingOrders > 0) KeyValue("Órdenes pendientes", a.pendingOrders.toString())
                if (!a.complete) Text("* Falta el precio de algún activo.", style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!a.archived) {
                        TextButton(onClick = { c.select(a.id); c.sub.value = SubScreen.None; c.tab.value = Tab.HOME }) {
                            Text(if (a.id == selectedId) "● Seleccionada" else "Seleccionar")
                        }
                        TextButton(onClick = { dialog.value = Dialog.Rename(a) }) { Text("Renombrar") }
                        TextButton(onClick = { dialog.value = Dialog.Income(a) }) { Text("Ingreso") }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (a.archived) {
                        TextButton(onClick = { c.restore(a.id) }) { Text("Restaurar") }
                        TextButton(onClick = { dialog.value = Dialog.Delete(a) }) { Text("Eliminar definitivamente") }
                    } else {
                        TextButton(onClick = { c.archive(a.id) }) { Text("Archivar") }
                    }
                }
            }
        }
        item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
    }

    when (val dlg = dialog.value) {
        null -> Unit
        Dialog.Create -> CreateAccountDialog({ dialog.value = null }) { name, open, inc, day -> c.createAccount(name, open, inc, day); dialog.value = null }
        is Dialog.Rename -> TextDialog("Renombrar cuenta", "Nombre", dlg.a.name, { dialog.value = null }) { c.rename(dlg.a.id, it); dialog.value = null }
        is Dialog.Income -> IncomeDialog(dlg.a, { dialog.value = null }) { amount, day -> c.changeIncome(dlg.a.id, amount, day); dialog.value = null }
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = { dialog.value = null },
            title = { Text("Eliminar definitivamente") },
            text = { Text("Se borrarán la cuenta «${dlg.a.name}» y todo su historial. No se puede deshacer. Haz antes una copia de seguridad si quieres conservarla.") },
            confirmButton = { TextButton(onClick = { c.delete(dlg.a.id); dialog.value = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { dialog.value = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TextDialog(title: String, label: String, initial: String, dismiss: () -> Unit, confirm: (String) -> Unit) {
    val text = remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text.value, onValueChange = { text.value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { confirm(text.value) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun CreateAccountDialog(dismiss: () -> Unit, confirm: (String, Money, Money, Int) -> Unit) {
    val name = remember { mutableStateOf("") }
    val opening = remember { mutableStateOf("") }
    val income = remember { mutableStateOf("") }
    val day = remember { mutableStateOf("1") }
    val error = remember { mutableStateOf<String?>(null) }
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Nueva cuenta") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name.value, onValueChange = { name.value = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(value = opening.value, onValueChange = { opening.value = it }, label = { Text("Saldo inicial (€)") }, singleLine = true, keyboardOptions = decimal)
                OutlinedTextField(value = income.value, onValueChange = { income.value = it }, label = { Text("Ingreso mensual (€)") }, singleLine = true, keyboardOptions = decimal)
                OutlinedTextField(value = day.value, onValueChange = { day.value = it }, label = { Text("Día del ingreso (1-31)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                error.value?.let { Notice(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val o = Fmt.parseMoney(opening.value)
                val i = Fmt.parseMoney(income.value.ifBlank { "0" })
                val dd = day.value.trim().toIntOrNull()
                when {
                    name.value.isBlank() -> error.value = "Escribe un nombre"
                    o == null || o.isNegative || !o.isWholeCents -> error.value = "El saldo inicial debe ser un importe válido (máx. 2 decimales)"
                    i == null || i.isNegative || !i.isWholeCents -> error.value = "El ingreso mensual debe ser un importe válido (máx. 2 decimales)"
                    dd == null || dd !in 1..31 -> error.value = "El día debe estar entre 1 y 31"
                    else -> confirm(name.value, o, i, dd)
                }
            }) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun IncomeDialog(a: AccountItem, dismiss: () -> Unit, confirm: (Money, Int) -> Unit) {
    val amount = remember { mutableStateOf("") }
    val day = remember { mutableStateOf("1") }
    val error = remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Ingreso mensual de ${a.name}") },
        text = {
            Column {
                Text("El cambio se aplica desde el primer ingreso aún no abonado; los ya abonados no se modifican.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = amount.value, onValueChange = { amount.value = it }, label = { Text("Importe (€)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(value = day.value, onValueChange = { day.value = it }, label = { Text("Día (1-31)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                error.value?.let { Notice(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = Fmt.parseMoney(amount.value)
                val dd = day.value.trim().toIntOrNull()
                when {
                    m == null || m.isNegative || !m.isWholeCents -> error.value = "Importe no válido (máx. 2 decimales)"
                    dd == null || dd !in 1..31 -> error.value = "El día debe estar entre 1 y 31"
                    else -> confirm(m, dd)
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
    )
}

// ------------------------------------------------------------------ Más

@Composable
fun MoreScreen(c: UiController, context: Context) {
    val notificationsOn = remember { mutableStateOf(app.brujula.NotificationHelper.areEnabled(context)) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        notificationsOn.value = app.brujula.NotificationHelper.areEnabled(context)
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Gestión")
        OutlinedButton(onClick = { c.sub.value = SubScreen.Accounts }, modifier = Modifier.fillMaxWidth()) { Text("Cuentas") }
        OutlinedButton(onClick = { c.sub.value = SubScreen.Stats }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Estadísticas") }
        OutlinedButton(onClick = { c.sub.value = SubScreen.Tax }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Fiscalidad") }
        OutlinedButton(onClick = { c.sub.value = SubScreen.Backup }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Copias de seguridad") }
        OutlinedButton(onClick = { c.sub.value = SubScreen.TimeTravel }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Avance de tiempo") }
        SectionTitle("Notificaciones")
        Text(
            "Avisos de ingresos mensuales, órdenes ejecutadas, liquidadas, caducadas o rechazadas, dividendos, pagos del IRPF y fechas de impuestos próximas (7 días antes).",
            style = MaterialTheme.typography.bodySmall,
        )
        if (notificationsOn.value) {
            Text("✔ Notificaciones activadas", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        } else {
            Button(
                onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch("android.permission.POST_NOTIFICATIONS")
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (android.os.Build.VERSION.SDK_INT < 33) context.startActivity(intent)
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Activar notificaciones") }
            Text("Si Android no muestra el permiso, actívalas en Ajustes del sistema › Aplicaciones › Brújula › Notificaciones.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { c.refreshDataSummary(); c.sub.value = SubScreen.Diagnostics }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Diagnóstico de datos") }
        SectionTitle("Acerca de")
        Text(
            "Brújula es un simulador educativo de inversión. No ejecuta operaciones reales ni es asesoramiento financiero. " +
                "Los precios proceden de fuentes gratuitas con retraso, y los fondos se representan con un ETF proxy.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Versión ${app.brujula.BuildInfo.VERSION}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
    }
}

@Composable
fun DiagnosticsScreen(c: UiController, context: Context) {
    Column(modifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Datos guardados")
        Text(c.dataSummary.value, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { c.runPersistenceTest(context) }, enabled = !c.persistenceRunning.value, modifier = Modifier.padding(top = 16.dp)) {
            Text("Probar persistencia (base temporal)")
        }
        if (c.persistenceTest.value.isNotEmpty()) Text(c.persistenceTest.value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))

        SectionTitle("Precios y órdenes")
        Text("Última actualización correcta: ${formatInstant(c.data.value.lastMarketUpdate)}", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { c.updatePrices() }, enabled = !c.marketRunning.value, modifier = Modifier.padding(top = 8.dp)) {
            Text("Actualizar precios ahora")
        }
        if (c.marketReport.value.isNotEmpty()) Text(c.marketReport.value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
    }
}
