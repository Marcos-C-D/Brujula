package app.brujula.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.brujula.app.Fmt
import app.brujula.market.AssetCatalog
import app.brujula.market.AssetKind
import app.brujula.model.Money
import app.brujula.tax.PaymentMode
import app.brujula.tax.TaxSettings

// ------------------------------------------------------------------ Estadísticas

@Composable
fun StatsScreen(c: UiController) {
    val d = c.data.value
    val s = d.stats
    val v = d.valuation
    if (s == null || v == null) {
        Text("Crea una cuenta y haz alguna operación para ver estadísticas.", modifier = Modifier.padding(16.dp))
        return
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Rentabilidad")
        InfoCard {
            KeyValue("Patrimonio", Fmt.eurOrDash(s.netWorth))
            KeyValue("Aportado (saldo inicial + ingresos)", Fmt.eur(s.contributions))
            KeyValue("Rentabilidad total", Fmt.pctOrDash(s.totalReturnPct), signColor(s.totalReturnPct.sign()))
            KeyValue("Ponderada por tiempo", Fmt.pctOrDash(s.twrPct), signColor(s.twrPct.sign()))
            val annualized = s.annualizedPct
            KeyValue("Anualizada", if (annualized != null) Fmt.pct(annualized, true) else "— (necesita ≥ 30 días)", signColor(annualized.sign()))
            KeyValue("Días simulados", s.days.toString())
        }
        Text(
            "La rentabilidad total compara el patrimonio con lo aportado y no distingue cuándo se aportó. La ponderada por tiempo elimina el efecto de los ingresos mensuales y es la adecuada para comparar con un índice.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Resultados acumulados")
        InfoCard {
            KeyValue("Ganancias y pérdidas realizadas", Fmt.eur(s.realizedGain), signColor(s.realizedGain.signum.let { if (it > 0) true else if (it < 0) false else null }))
            KeyValue("Dividendos cobrados (netos)", Fmt.eur(s.dividendsNet))
            KeyValue("Impuestos pagados (IRPF)", Fmt.eur(s.taxesPaid))
        }

        SectionTitle("Comparación con un índice")
        val indices = AssetCatalog.DEFAULT.filter { it.kind == AssetKind.INDEX_FUND }.map { it.name to it.id }
        Selector("Índice de referencia", indices, d.benchmarkId) { c.selectBenchmark(it) }
        val b = s.benchmark
        if (b == null) {
            Text("Este índice aún no tiene datos: actualiza los precios.", style = MaterialTheme.typography.bodySmall)
        } else {
            InfoCard {
                KeyValue(b.name, Fmt.pctOrDash(b.benchmarkReturnPct), signColor(b.benchmarkReturnPct.sign()))
                KeyValue("Tu cuenta (ponderada por tiempo)", Fmt.pctOrDash(b.accountTwrPct), signColor(b.accountTwrPct.sign()))
                Text("Del ${Fmt.date(b.fromDate)} al ${Fmt.date(b.toDate)}. El índice es un ETF proxy sin comisiones de tu cuenta.", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionTitle("Evolución del patrimonio")
        LineChart(d.history.map { it.netWorth?.amount?.toDouble() })
        SectionTitle("Evolución del efectivo")
        LineChart(d.history.map { it.cash.amount.toDouble() }, color = androidx.compose.ui.graphics.Color(0xFF4F8FE0))
        if (d.history.size >= 2) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Fmt.date(d.history.first().date), style = MaterialTheme.typography.bodySmall)
                Text(Fmt.date(d.history.last().date), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (v.allocationByAsset.isNotEmpty()) {
            SectionTitle("Distribución por activos"); AllocationBars(v.allocationByAsset)
            SectionTitle("Distribución por categorías"); AllocationBars(v.allocationByCategory)
        }
        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
}

// ------------------------------------------------------------------ Fiscalidad

@Composable
fun TaxScreen(c: UiController) {
    val d = c.data.value
    val t = d.tax
    val dialog = remember { mutableStateOf(false) }
    if (t == null) {
        Text("Crea una cuenta para ver su fiscalidad simulada.", modifier = Modifier.padding(16.dp))
        return
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Button(onClick = { dialog.value = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Opciones fiscales") }
        t.notices.forEach { Notice(it) }

        SectionTitle("Próximas fechas")
        if (t.upcoming.isEmpty()) Text("No hay pagos ni devoluciones previstos.", style = MaterialTheme.typography.bodyMedium)
        t.upcoming.forEach { u ->
            InfoCard { Text("${Fmt.date(u.date)} · ${u.label}" + if (u.estimated) " (fecha estimada)" else "", style = MaterialTheme.typography.bodyMedium) }
        }

        SectionTitle("Ejercicios")
        if (t.years.isEmpty()) Text("Todavía no hay movimientos con efectos fiscales.", style = MaterialTheme.typography.bodyMedium)
        t.years.forEach { y ->
            val r = y.result
            InfoCard {
                Text("IRPF ${r.year}" + if (y.provisional) "  (provisional: el ejercicio no ha terminado)" else "", style = MaterialTheme.typography.titleMedium)
                KeyValue("Ganancias y pérdidas patrimoniales", Fmt.eur(r.gainsLossesBalance))
                if (r.integratedDeferred.isNegative) KeyValue("Pérdidas aplazadas que se integran", Fmt.eur(r.integratedDeferred))
                KeyValue("Dividendos (brutos)", Fmt.eur(r.dividendsGross))
                if (r.crossCompensation.isPositive) KeyValue("Pérdidas compensadas con dividendos (máx. 25 %)", Fmt.eur(r.crossCompensation))
                if (r.carriedUsed.isPositive) KeyValue("Pérdidas de años anteriores compensadas", Fmt.eur(r.carriedUsed))
                KeyValue("Base del ahorro", Fmt.eur(r.savingsBase))
                KeyValue("Cuota íntegra", Fmt.eur(r.grossTax))
                if (r.doubleTaxDeduction.isPositive) KeyValue("Deducción por doble imposición", Fmt.eur(-r.doubleTaxDeduction))
                KeyValue("Retenciones ya practicadas", Fmt.eur(-r.withholdings))
                KeyValue(
                    if (r.result.isNegative) "A devolver" else "A pagar", Fmt.eur(if (r.result.isNegative) -r.result else r.result),
                    if (r.result.isNegative) Esmeralda else Rojo,
                )
                r.carryForward.forEach { cf ->
                    Text("Pérdida de ${cf.originYear} por compensar: ${Fmt.eur(cf.remaining)} (hasta ${cf.originYear + 4})", style = MaterialTheme.typography.bodySmall)
                }
                Text(y.chargeDate.let { "Pago en la campaña: ${Fmt.date(it)}" } + if (y.campaignOfficial) "" else " (estimado)", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionTitle("Cómo funciona")
        Text(
            "Se simula el IRPF de la base del ahorro: FIFO, escala del ahorro, compensación de pérdidas (25 % con dividendos, arrastre de 4 años), " +
                "regla de recompra (2 meses en ETF, 1 año en fondos), retención del 19 % en dividendos y en reembolsos de fondos, y traspasos entre fondos sin tributar. " +
                "El impuesto se paga en la campaña de la renta del año siguiente y, si el efectivo no alcanza, queda en negativo. " +
                "Se asume que el mínimo personal y familiar se aplica a la base general. Es educativo: no sustituye a un asesor fiscal.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp),
        )
    }
    if (dialog.value) TaxSettingsDialog(c, t.settings, d.today.year) { dialog.value = false }
}

@Composable
private fun TaxSettingsDialog(c: UiController, current: TaxSettings, year: Int, dismiss: () -> Unit) {
    val payment = remember { mutableStateOf(current.payment) }
    val other = remember { mutableStateOf(current.otherSavingsBase[year]?.toString() ?: "") }
    val funds = remember { mutableStateOf(current.withholdOnFundRedemptions) }
    val dividends = remember { mutableStateOf(current.withholdOnDividends) }
    val crypto = remember { mutableStateOf(current.applyRepurchaseToCrypto) }
    val error = remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Opciones fiscales de la cuenta") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Selector("Pago del IRPF", listOf("Pago único (30 de junio)" to PaymentMode.SINGLE, "Fraccionado 60 % / 40 % (30 de junio y 5 de noviembre)" to PaymentMode.SPLIT_60_40), payment.value) { payment.value = it }
                OutlinedTextField(
                    value = other.value, onValueChange = { other.value = it }, label = { Text("Otras rentas del ahorro de $year (€)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text("Rentas del ahorro ajenas a la simulación que ya ocupan tramos de la escala.", style = MaterialTheme.typography.bodySmall)
                SwitchRow("Retención del 19 % en dividendos", dividends.value) { dividends.value = it }
                SwitchRow("Retención del 19 % al reembolsar fondos", funds.value) { funds.value = it }
                SwitchRow("Aplicar regla de recompra a Bitcoin", crypto.value) { crypto.value = it }
                error.value?.let { Notice(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val o = if (other.value.isBlank()) Money.ZERO else Fmt.parseMoney(other.value)
                if (o == null || o.isNegative || !o.isWholeCents) {
                    error.value = "Importe no válido (máx. 2 decimales)"
                } else {
                    val map = current.otherSavingsBase.toMutableMap()
                    if (o.isZero) map.remove(year) else map[year] = o
                    c.saveTaxSettings(
                        current.copy(
                            payment = payment.value, otherSavingsBase = map, withholdOnFundRedemptions = funds.value,
                            withholdOnDividends = dividends.value, applyRepurchaseToCrypto = crypto.value,
                        ),
                    )
                    dismiss()
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
